package io.github.yzjdev.filetransfer.transfer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

class TransferService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val devices = linkedMapOf<String, DiscoveredDevice>()
    private val stateMutex = Mutex()
    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()
    private val progressTrackers = ConcurrentHashMap<String, TransferProgressTracker>()
    private val receiveSessions = ConcurrentHashMap<String, ReceiveSession>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var serverSocket: ServerSocket? = null
    private var v2ServerSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var broadcastJob: Job? = null
    private var discoveryJob: Job? = null
    private var serverJob: Job? = null
    private var v2ServerJob: Job? = null
    private var progressPublishJob: Job? = null
    private var isEngineRunning = false
    private var lastTransferPersistAtElapsed = 0L

    private lateinit var localId: String
    private lateinit var localName: String

    inner class LocalBinder : Binder() {
        fun service(): TransferService = this@TransferService
    }

    override fun onCreate() {
        super.onCreate()
        localId = loadDeviceId()
        localName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "安卓设备" }
        _uiState.value = _uiState.value.copy(transfers = loadPersistedTransfers())
        createNotificationChannel()
        startEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine(stopService = true)
            return START_NOT_STICKY
        }
        if (!isEngineRunning) {
            startEngine()
        } else {
            promoteToForeground()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopEngine(stopService = false)
        super.onDestroy()
    }

    fun sendItems(target: DiscoveredDevice, items: List<ShareItem>) {
        if (!isEngineRunning) {
            updateMessage("传输服务未启动")
            return
        }
        if (items.isEmpty()) {
            updateMessage("没有可发送的文件")
            return
        }
        serviceScope.launch {
            sendBatch(target, items)
        }
    }

    fun toggleRunning() {
        if (isEngineRunning) {
            stopEngine(stopService = true)
        } else {
            startEngine()
        }
    }

    private fun startEngine() {
        if (isEngineRunning) return
        isEngineRunning = true
        promoteToForeground()
        acquireLocks()
        updateLocalState("传输服务已启动")
        startDiscovery()
        startTcpServer()
        startTcpV2Server()
        startProgressPublisher()
    }

    private fun stopEngine(stopService: Boolean) {
        if (!isEngineRunning && !stopService) return
        isEngineRunning = false
        broadcastJob?.cancel()
        discoveryJob?.cancel()
        serverJob?.cancel()
        v2ServerJob?.cancel()
        progressPublishJob?.cancel()
        broadcastJob = null
        discoveryJob = null
        serverJob = null
        v2ServerJob = null
        progressPublishJob = null
        discoverySocket?.close()
        serverSocket?.close()
        v2ServerSocket?.close()
        discoverySocket = null
        serverSocket = null
        v2ServerSocket = null
        progressTrackers.clear()
        receiveSessions.values.forEach { it.closeQuietly() }
        receiveSessions.clear()
        wakeLock?.releaseIfHeld()
        wifiLock?.releaseIfHeld()
        multicastLock?.releaseIfHeld()
        wakeLock = null
        wifiLock = null
        multicastLock = null
        devices.clear()
        _uiState.value = _uiState.value.copy(
            serviceRunning = false,
            discoveredDevices = emptyList(),
            lastMessage = "传输服务已停止",
        )
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        if (stopService) {
            stopSelf()
        }
    }

    private fun startDiscovery() {
        discoveryJob = serviceScope.launch {
            listenForDiscovery()
        }
        broadcastJob = serviceScope.launch {
            while (isActive) {
                broadcastPresence()
                expireStaleDevices()
                updateLocalState()
                delay(DISCOVERY_INTERVAL_MS)
            }
        }
    }

    private fun startTcpServer() {
        serverJob = serviceScope.launch {
            runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    receiveBufferSize = SOCKET_BUFFER_SIZE_BYTES
                    bind(InetSocketAddress(TCP_PORT))
                }.use { server ->
                    serverSocket = server
                    while (isActive) {
                        val socket = server.accept()
                        launch {
                            handleIncoming(socket)
                        }
                    }
                }
            }.onFailure { error ->
                if (isActive) updateMessage("接收服务异常：${error.safeMessage()}")
            }
        }
    }

    private fun startTcpV2Server() {
        v2ServerJob = serviceScope.launch {
            runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    receiveBufferSize = SOCKET_BUFFER_SIZE_BYTES
                    bind(InetSocketAddress(TCP_V2_PORT))
                }.use { server ->
                    v2ServerSocket = server
                    while (isActive) {
                        val socket = server.accept()
                        launch {
                            handleIncomingV2(socket)
                        }
                    }
                }
            }.onFailure { error ->
                if (isActive) updateMessage("协议二接收服务异常：${error.safeMessage()}")
            }
        }
    }

    private fun startProgressPublisher() {
        progressPublishJob = serviceScope.launch {
            while (isActive) {
                flushProgressUpdates()
                delay(PROGRESS_PUBLISH_INTERVAL_MS)
            }
        }
    }

    private suspend fun listenForDiscovery() {
        runCatching {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1_500
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
            discoverySocket = socket
            val buffer = ByteArray(2048)
            while (serviceScope.coroutineContext[Job]?.isActive == true) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                    parseDiscovery(payload, packet.address.hostAddress ?: continue)
                } catch (_: SocketTimeoutException) {
                    expireStaleDevices()
                }
            }
        }.onFailure { error ->
            if (serviceScope.coroutineContext[Job]?.isActive == true) {
                updateMessage("设备发现异常：${error.safeMessage()}")
            }
        }
    }

    private suspend fun parseDiscovery(payload: String, hostAddress: String) {
        if (!payload.startsWith(DISCOVERY_PREFIX)) return
        val parts = payload.split("|")
        if (parts.size < 5) return
        val id = parts[1]
        if (id == localId) return
        val name = URLDecoder.decode(parts[2], StandardCharsets.UTF_8.name())
        val port = parts[3].toIntOrNull() ?: TCP_PORT
        val device = DiscoveredDevice(
            deviceId = id,
            deviceName = name,
            ipAddress = hostAddress,
            tcpPort = port,
            lastSeenAt = System.currentTimeMillis(),
        )
        stateMutex.withLock {
            devices[id] = device
            publishDevices("发现设备：${device.deviceName}")
        }
    }

    private suspend fun broadcastPresence() {
        withContext(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val encodedName = URLEncoder.encode(localName, StandardCharsets.UTF_8.name())
                    val payload = "$DISCOVERY_PREFIX|$localId|$encodedName|$TCP_PORT|${System.currentTimeMillis()}"
                    val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                    val addresses = broadcastAddresses().ifEmpty { listOf(InetAddress.getByName("255.255.255.255")) }
                    addresses.forEach { address ->
                        socket.send(DatagramPacket(bytes, bytes.size, address, DISCOVERY_PORT))
                    }
                }
            }
        }
    }

    private suspend fun expireStaleDevices() {
        stateMutex.withLock {
            val now = System.currentTimeMillis()
            val before = devices.size
            devices.entries.removeAll { now - it.value.lastSeenAt > DEVICE_TTL_MS }
            if (devices.size != before) publishDevices()
        }
    }

    private suspend fun sendBatch(target: DiscoveredDevice, items: List<ShareItem>) {
        val preparedItems = items.map { item -> prepareItemForSend(item) }
        val transferId = UUID.randomUUID().toString()
        val totalBytes = preparedItems.sumOf { max(0L, it.item.sizeBytes) }
        val chunks = buildFileChunks(preparedItems)
        val requestedStreamCount = dataStreamCount(totalBytes, preparedItems.size)
        val streamPartitions = chunks.partitionForStreams(requestedStreamCount)
        val streamCount = streamPartitions.size
        addTransfer(
            TransferRecord(
                id = transferId,
                direction = TransferDirection.SEND,
                peerName = target.deviceName,
                peerAddress = target.ipAddress,
                itemNames = items.map { it.displayName },
                localPaths = items.map { it.filePath ?: it.uri?.toString().orEmpty() },
                bytesTotal = totalBytes,
                bytesTransferred = 0L,
                status = TransferStatus.RUNNING,
                startedAt = System.currentTimeMillis(),
                message = "正在发送",
            )
        )
        trackTransfer(transferId)

        runCatching {
            val v2Ready = runCatching {
                sendTransferManifest(target, transferId, preparedItems, totalBytes, chunks.size)
                true
            }.getOrElse { error ->
                when (error) {
                    is java.net.ConnectException,
                    is SocketTimeoutException -> false
                    else -> throw error
                }
            }
            if (v2Ready) {
                updateTransfer(transferId) { it.copy(message = "正在发送（协议二/$streamCount 路）") }
                if (streamPartitions.isNotEmpty()) {
                    val sentBytes = AtomicLong(0L)
                    coroutineScope {
                        streamPartitions
                            .map { assignedChunks ->
                                async(Dispatchers.IO) {
                                    if (assignedChunks.isNotEmpty()) {
                                        sendDataStream(target, transferId, assignedChunks, sentBytes, totalBytes)
                                    }
                                }
                            }
                            .awaitAll()
                    }
                }
            } else {
                updateTransfer(transferId) { it.copy(message = "正在发送（协议一兼容模式）") }
                sendBatchV1(target, preparedItems, totalBytes, transferId)
            }
        }.onSuccess {
            finishTransfer(transferId, TransferStatus.SUCCESS, "发送完成")
        }.onFailure { error ->
            finishTransfer(transferId, TransferStatus.FAILED, "发送失败：${error.safeMessage()}")
        }.also {
            preparedItems.forEach { prepared ->
                prepared.tempFile?.delete()
            }
        }
    }

    private suspend fun handleIncoming(socket: Socket) {
        socket.use { client ->
            client.configureForBulkTransfer()
            var activeTransferId: String? = null
            runCatching {
                DataInputStream(
                    BufferedInputStream(client.getInputStream(), STREAM_BUFFER_SIZE_BYTES)
                ).use { input ->
                    val magic = input.readUTF()
                    require(magic == PROTOCOL_MAGIC) { "协议不匹配" }
                    val senderId = input.readUTF()
                    val senderName = input.readUTF()
                    val totalBytes = input.readLong().coerceAtLeast(0L)
                    val count = input.readInt().coerceIn(0, 10_000)
                    val peerAddress = client.inetAddress.hostAddress.orEmpty()
                    val outputDir = receivedDirectory()
                    val transferId = UUID.randomUUID().toString()
                    activeTransferId = transferId
                    val itemNames = mutableListOf<String>()
                    val localPaths = mutableListOf<String>()
                    addTransfer(
                        TransferRecord(
                            id = transferId,
                            direction = TransferDirection.RECEIVE,
                            peerName = senderName.ifBlank { senderId },
                            peerAddress = peerAddress,
                            itemNames = itemNames,
                            localPaths = localPaths,
                            bytesTotal = totalBytes,
                            bytesTransferred = 0L,
                            status = TransferStatus.RUNNING,
                            startedAt = System.currentTimeMillis(),
                            message = "正在接收",
                        )
                    )
                    trackTransfer(transferId)

                    var received = 0L
                    repeat(count) {
                        val name = input.readUTF().safeFileName()
                        val size = input.readLong().coerceAtLeast(0L)
                        input.readUTF()
                        itemNames += name
                        updateTransfer(transferId) { it.copy(itemNames = itemNames.toList()) }
                        val target = outputDir.uniqueChild(name)
                        localPaths += target.absolutePath
                        val baseReceived = received
                        BufferedOutputStream(target.outputStream(), STREAM_BUFFER_SIZE_BYTES).use { output ->
                            received += copyExactWithProgress(input, output, size) { copied ->
                                recordTransferProgress(transferId, (baseReceived + copied).coerceAtMost(totalBytes))
                            }
                        }
                        updateTransfer(transferId) { it.copy(localPaths = localPaths.toList()) }
                    }
                    finishTransfer(transferId, TransferStatus.SUCCESS, "接收完成：${outputDir.absolutePath}")
                }
            }.onFailure { error ->
                activeTransferId?.let { transferId ->
                    finishTransfer(transferId, TransferStatus.FAILED, "接收失败：${error.safeMessage()}")
                } ?: updateMessage("接收失败：${error.safeMessage()}")
            }
        }
    }

    private fun handleIncomingV2(socket: Socket) {
        socket.use { client ->
            client.configureForBulkTransfer()
            runCatching {
                DataInputStream(
                    BufferedInputStream(client.getInputStream(), STREAM_BUFFER_SIZE_BYTES)
                ).use { input ->
                    when (input.readUTF()) {
                        PROTOCOL_MAGIC_V2_CONTROL -> handleIncomingV2Control(client, input)
                        PROTOCOL_MAGIC_V2_DATA -> handleIncomingV2Data(client, input)
                        else -> error("协议不匹配")
                    }
                }
            }.onFailure { error ->
                updateMessage("协议二接收失败：${error.safeMessage()}")
            }
        }
    }

    private fun handleIncomingV2Control(client: Socket, input: DataInputStream) {
        val senderId = input.readUTF()
        val senderName = input.readUTF()
        val sessionId = input.readUTF()
        val totalBytes = input.readLong().coerceAtLeast(0L)
        val totalChunkCount = input.readInt().coerceAtLeast(0)
        val count = input.readInt().coerceIn(0, 10_000)
        val peerAddress = client.inetAddress.hostAddress.orEmpty()
        val outputDir = receivedDirectory()
        val itemNames = mutableListOf<String>()
        val localPaths = mutableListOf<String>()
        val files = mutableListOf<ReceiveSessionFile>()
        repeat(count) {
            val name = input.readUTF().safeFileName()
            val size = input.readLong().coerceAtLeast(0L)
            input.readUTF()
            val target = outputDir.uniqueChild(name)
            itemNames += name
            localPaths += target.absolutePath
            files += ReceiveSessionFile(target = target, sizeBytes = size)
        }

        receiveSessions.remove(sessionId)?.closeQuietly()
        addTransfer(
            TransferRecord(
                id = sessionId,
                direction = TransferDirection.RECEIVE,
                peerName = senderName.ifBlank { senderId },
                peerAddress = peerAddress,
                itemNames = itemNames,
                localPaths = localPaths,
                bytesTotal = totalBytes,
                bytesTransferred = 0L,
                status = TransferStatus.RUNNING,
                startedAt = System.currentTimeMillis(),
                message = "正在接收",
            )
        )
        trackTransfer(sessionId)
        receiveSessions[sessionId] = ReceiveSession(
            sessionId = sessionId,
            outputDir = outputDir,
            files = files,
            expectedChunkCount = totalChunkCount,
        )

        DataOutputStream(
            BufferedOutputStream(client.getOutputStream(), STREAM_BUFFER_SIZE_BYTES)
        ).use { output ->
            output.writeUTF(PROTOCOL_ACK_OK)
            output.flush()
        }

        if (totalChunkCount == 0) {
            completeReceiveSession(sessionId)
        }
    }

    private fun handleIncomingV2Data(client: Socket, input: DataInputStream) {
        var activeSessionId: String? = null
        runCatching {
            val sessionId = input.readUTF()
            activeSessionId = sessionId
            val chunkCount = input.readInt().coerceAtLeast(0)
            val transferBuffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
            var receivedBytes = 0L
            val session = requireNotNull(receiveSessions[sessionId]) { "传输会话不存在" }
            repeat(chunkCount) {
                val fileIndex = input.readInt()
                val offset = input.readLong().coerceAtLeast(0L)
                val length = input.readInt().coerceAtLeast(0)
                receivedBytes += session.writeChunk(input, fileIndex, offset, length, transferBuffer)
            }
            DataOutputStream(
                BufferedOutputStream(client.getOutputStream(), STREAM_BUFFER_SIZE_BYTES)
            ).use { output ->
                output.writeUTF(PROTOCOL_ACK_OK)
                output.writeLong(receivedBytes)
                output.flush()
            }
        }.onFailure { error ->
            activeSessionId?.let { failReceiveSession(it, "协议二接收失败：${error.safeMessage()}") } ?: throw error
        }
    }

    private fun sendTransferManifest(
        target: DiscoveredDevice,
        sessionId: String,
        preparedItems: List<PreparedSendItem>,
        totalBytes: Long,
        chunkCount: Int,
    ) {
        Socket().use { socket ->
            socket.configureForBulkTransfer()
            socket.connect(InetSocketAddress(target.ipAddress, TCP_V2_PORT), CONNECT_TIMEOUT_MS)
            val input = DataInputStream(
                BufferedInputStream(socket.getInputStream(), STREAM_BUFFER_SIZE_BYTES)
            )
            val output = DataOutputStream(
                BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_SIZE_BYTES)
            )
            input.use { response ->
                output.use { request ->
                    request.writeUTF(PROTOCOL_MAGIC_V2_CONTROL)
                    request.writeUTF(localId)
                    request.writeUTF(localName)
                    request.writeUTF(sessionId)
                    request.writeLong(totalBytes)
                    request.writeInt(chunkCount)
                    request.writeInt(preparedItems.size)
                    preparedItems.forEach { prepared ->
                        val item = prepared.item
                        request.writeUTF(item.displayName)
                        request.writeLong(item.sizeBytes)
                        request.writeUTF(item.mimeType)
                    }
                    request.flush()
                    require(response.readUTF() == PROTOCOL_ACK_OK) { "接收端未确认传输会话" }
                }
            }
        }
    }

    private fun sendDataStream(
        target: DiscoveredDevice,
        sessionId: String,
        chunks: List<FileChunk>,
        sentBytes: AtomicLong,
        totalBytes: Long,
    ) {
        val reporter = ProgressReporter(
            expectedBytes = totalBytes,
            stepBytes = PROGRESS_STEP_BYTES,
            minReportIntervalMs = PROGRESS_MIN_REPORT_INTERVAL_MS,
            maxReportIntervalMs = PROGRESS_MAX_REPORT_INTERVAL_MS,
            onProgress = { copied -> recordTransferProgress(sessionId, copied.coerceAtMost(totalBytes)) },
        )
        Socket().use { socket ->
            socket.configureForBulkTransfer()
            socket.connect(InetSocketAddress(target.ipAddress, TCP_V2_PORT), CONNECT_TIMEOUT_MS)
            val input = DataInputStream(
                BufferedInputStream(socket.getInputStream(), STREAM_BUFFER_SIZE_BYTES)
            )
            val output = DataOutputStream(
                BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_SIZE_BYTES)
            )
            input.use { response ->
                output.use { request ->
                    request.writeUTF(PROTOCOL_MAGIC_V2_DATA)
                    request.writeUTF(sessionId)
                    request.writeInt(chunks.size)
                    var streamBytes = 0L
                    val openFiles = mutableMapOf<Int, RandomAccessFile>()
                    try {
                        chunks.forEach { chunk ->
                            request.writeInt(chunk.fileIndex)
                            request.writeLong(chunk.offset)
                            request.writeInt(chunk.length)
                            val file = openFiles.getOrPut(chunk.fileIndex) {
                                RandomAccessFile(chunk.filePath, "r")
                            }
                            copyRangeToStream(file, chunk.offset, chunk.length, request) { delta ->
                                val absolute = sentBytes.addAndGet(delta)
                                reporter.report(absolute)
                            }
                            streamBytes += chunk.length.toLong()
                        }
                        request.flush()
                        require(response.readUTF() == PROTOCOL_ACK_OK) { "协议二数据流未确认" }
                        val acknowledgedBytes = response.readLong().coerceAtLeast(0L)
                        require(acknowledgedBytes == streamBytes) { "协议二确认字节数不匹配" }
                    } finally {
                        openFiles.values.forEach { runCatching { it.close() } }
                    }
                }
            }
        }
    }

    private fun sendBatchV1(
        target: DiscoveredDevice,
        preparedItems: List<PreparedSendItem>,
        totalBytes: Long,
        transferId: String,
    ) {
        Socket().use { socket ->
            socket.configureForBulkTransfer()
            socket.connect(InetSocketAddress(target.ipAddress, target.tcpPort), CONNECT_TIMEOUT_MS)
            DataOutputStream(
                BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_SIZE_BYTES)
            ).use { output ->
                output.writeUTF(PROTOCOL_MAGIC_V1)
                output.writeUTF(localId)
                output.writeUTF(localName)
                output.writeLong(totalBytes)
                output.writeInt(preparedItems.size)
                var sent = 0L
                preparedItems.forEach { prepared ->
                    val item = prepared.item
                    val baseSent = sent
                    output.writeUTF(item.displayName)
                    output.writeLong(item.sizeBytes)
                    output.writeUTF(item.mimeType)
                    openInput(item).use { input ->
                        sent += copyWithProgress(input, output, item.sizeBytes) { copied ->
                            recordTransferProgress(transferId, (baseSent + copied).coerceAtMost(totalBytes))
                        }
                    }
                }
                output.flush()
            }
        }
    }

    private fun prepareItemForSend(item: ShareItem): PreparedSendItem {
        item.filePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                return PreparedSendItem(item.copy(sizeBytes = file.length().coerceAtLeast(0L)))
            }
        }
        item.uri?.let { uri ->
            val tempFile = File(cacheDir, "send-${UUID.randomUUID()}").apply { deleteOnExit() }
            requireNotNull(contentResolver.openInputStream(uri)) { "无法读取文件：${item.displayName}" }.use { input ->
                BufferedOutputStream(tempFile.outputStream(), STREAM_BUFFER_SIZE_BYTES).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE_BYTES)
                }
            }
            return PreparedSendItem(
                item.copy(filePath = tempFile.absolutePath, uri = null, sizeBytes = tempFile.length().coerceAtLeast(0L)),
                tempFile,
            )
        }
        return PreparedSendItem(item.copy(sizeBytes = item.sizeBytes.coerceAtLeast(0L)))
    }

    private fun openInput(item: ShareItem): InputStream {
        item.filePath?.let { return FileInputStream(File(it)) }
        val uri = requireNotNull(item.uri) { "文件地址为空" }
        return requireNotNull(contentResolver.openInputStream(uri)) { "无法打开文件：${item.displayName}" }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: DataOutputStream,
        expectedBytes: Long,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
        var copied = 0L
        val reporter = ProgressReporter(
            expectedBytes = expectedBytes,
            stepBytes = PROGRESS_STEP_BYTES,
            minReportIntervalMs = PROGRESS_MIN_REPORT_INTERVAL_MS,
            maxReportIntervalMs = PROGRESS_MAX_REPORT_INTERVAL_MS,
            onProgress = onProgress,
        )
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            reporter.report(copied)
        }
        return copied
    }

    private fun copyExactWithProgress(
        input: DataInputStream,
        output: BufferedOutputStream,
        expectedBytes: Long,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
        var remaining = expectedBytes
        var copied = 0L
        val reporter = ProgressReporter(
            expectedBytes = expectedBytes,
            stepBytes = PROGRESS_STEP_BYTES,
            minReportIntervalMs = PROGRESS_MIN_REPORT_INTERVAL_MS,
            maxReportIntervalMs = PROGRESS_MAX_REPORT_INTERVAL_MS,
            onProgress = onProgress,
        )
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IllegalStateException("连接提前断开")
            output.write(buffer, 0, read)
            copied += read
            remaining -= read
            reporter.report(copied)
        }
        return copied
    }

    private fun copyRangeToStream(
        file: RandomAccessFile,
        offset: Long,
        length: Int,
        output: DataOutputStream,
        onBytesWritten: (Long) -> Unit,
    ) {
        file.seek(offset)
        var remaining = length
        val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
        while (remaining > 0) {
            val read = file.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) throw IllegalStateException("文件读取提前结束")
            output.write(buffer, 0, read)
            remaining -= read
            onBytesWritten(read.toLong())
        }
    }

    private fun buildFileChunks(preparedItems: List<PreparedSendItem>): List<FileChunk> {
        val chunks = mutableListOf<FileChunk>()
        preparedItems.forEachIndexed { index, prepared ->
            val item = prepared.item
            val filePath = requireNotNull(item.filePath) { "协议二传输需要文件路径" }
            var offset = 0L
            val size = item.sizeBytes.coerceAtLeast(0L)
            while (offset < size) {
                val length = minOf(DATA_CHUNK_SIZE_BYTES.toLong(), size - offset).toInt()
                chunks += FileChunk(
                    fileIndex = index,
                    filePath = filePath,
                    offset = offset,
                    length = length,
                )
                offset += length
            }
        }
        return chunks
    }

    private fun dataStreamCount(totalBytes: Long, fileCount: Int): Int {
        if (fileCount <= 0) return 0
        val preferred = when {
            totalBytes >= 128L * 1024L * 1024L -> MAX_V2_STREAMS
            totalBytes >= 32L * 1024L * 1024L -> minOf(3, MAX_V2_STREAMS)
            else -> 1
        }
        return minOf(preferred, fileCount).coerceAtLeast(1)
    }

    private fun List<FileChunk>.partitionForStreams(streamCount: Int): List<List<FileChunk>> {
        if (streamCount <= 0) return emptyList()
        val buckets = List(streamCount) { mutableListOf<FileChunk>() }
        val bucketBytes = LongArray(streamCount)
        groupBy { it.fileIndex }
            .values
            .sortedByDescending { fileChunks -> fileChunks.sumOf { it.length.toLong() } }
            .forEach { fileChunks ->
                val bucketIndex = bucketBytes.indices.minByOrNull { bucketBytes[it] } ?: 0
                buckets[bucketIndex].addAll(fileChunks)
                bucketBytes[bucketIndex] += fileChunks.sumOf { it.length.toLong() }
            }
        return buckets.filter { it.isNotEmpty() }
    }

    private fun loadPersistedTransfers(): List<TransferRecord> {
        val persisted = getSharedPreferences(TRANSFER_PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_TRANSFER_RECORDS, null)
            ?: return emptyList()
        val now = System.currentTimeMillis()
        return runCatching {
            val array = JSONArray(persisted)
            val records = mutableListOf<TransferRecord>()
            for (index in 0 until minOf(array.length(), MAX_RECORDS)) {
                val item = array.optJSONObject(index) ?: continue
                decodeTransferRecord(item, now)?.let(records::add)
            }
            records
        }.getOrElse { emptyList() }
    }

    private fun persistTransfers(force: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastTransferPersistAtElapsed < TRANSFER_PERSIST_INTERVAL_MS) return
        lastTransferPersistAtElapsed = now
        val records = JSONArray()
        _uiState.value.transfers.take(MAX_RECORDS).forEach { record ->
            records.put(encodeTransferRecord(record))
        }
        getSharedPreferences(TRANSFER_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_TRANSFER_RECORDS, records.toString())
            .apply()
    }

    private fun completeReceiveSession(sessionId: String) {
        val session = receiveSessions.remove(sessionId) ?: return
        session.closeQuietly()
        finishTransfer(session.transferId, TransferStatus.SUCCESS, "接收完成：${session.outputDir.absolutePath}")
    }

    private fun failReceiveSession(sessionId: String, message: String) {
        val session = receiveSessions.remove(sessionId) ?: return
        session.closeQuietly()
        finishTransfer(session.transferId, TransferStatus.FAILED, message)
    }

    private fun addTransfer(record: TransferRecord) {
        val current = _uiState.value.transfers
        _uiState.value = _uiState.value.copy(
            transfers = (listOf(record) + current).take(MAX_RECORDS),
            lastMessage = record.message,
        )
        persistTransfers(force = true)
    }

    private fun trackTransfer(id: String) {
        progressTrackers[id] = TransferProgressTracker()
    }

    private fun recordTransferProgress(id: String, bytesTransferred: Long) {
        progressTrackers[id]?.record(bytesTransferred)
    }

    private fun flushProgressUpdates() {
        if (progressTrackers.isEmpty()) return
        val snapshots = mutableMapOf<String, TransferProgressSnapshot>()
        progressTrackers.forEach { (id, tracker) ->
            tracker.consume()?.let { snapshots[id] = it }
        }
        if (snapshots.isEmpty()) return
        val now = System.currentTimeMillis()
        val updated = _uiState.value.transfers.map { record ->
            val snapshot = snapshots[record.id] ?: return@map record
            val bytesTransferred = snapshot.bytesTransferred.coerceAtMost(record.bytesTotal)
            val updatedRecord = record.copy(
                bytesTransferred = bytesTransferred,
                currentBytesPerSecond = snapshot.bytesPerSecond,
                peakBytesPerSecond = max(record.peakBytesPerSecond, snapshot.peakBytesPerSecond),
                minBytesPerSecond = mergeMinBytesPerSecond(record.minBytesPerSecond, snapshot.minBytesPerSecond),
            )
            updatedRecord.copy(averageBytesPerSecondValue = updatedRecord.averageBytesPerSecond(now))
        }
        _uiState.value = _uiState.value.copy(transfers = updated)
        persistTransfers(force = false)
    }

    private fun updateTransfer(id: String, mapper: (TransferRecord) -> TransferRecord) {
        val updated = _uiState.value.transfers.map { if (it.id == id) mapper(it) else it }
        _uiState.value = _uiState.value.copy(transfers = updated)
        persistTransfers(force = true)
    }

    private fun finishTransfer(id: String, status: TransferStatus, message: String) {
        val finalSnapshot = progressTrackers.remove(id)?.finish()
        val finishedAt = System.currentTimeMillis()
        updateTransfer(id) {
            val finalBytesTransferred = when {
                    status == TransferStatus.SUCCESS -> it.bytesTotal
                    finalSnapshot != null -> finalSnapshot.bytesTransferred.coerceAtMost(it.bytesTotal)
                    else -> it.bytesTransferred
                }
            val finishedRecord = it.copy(
                bytesTransferred = finalBytesTransferred,
                currentBytesPerSecond = 0L,
                peakBytesPerSecond = max(it.peakBytesPerSecond, finalSnapshot?.peakBytesPerSecond ?: 0L),
                minBytesPerSecond = mergeMinBytesPerSecond(
                    it.minBytesPerSecond,
                    finalSnapshot?.minBytesPerSecond ?: 0L,
                ),
                status = status,
                finishedAt = finishedAt,
                message = message,
            )
            finishedRecord.copy(averageBytesPerSecondValue = finishedRecord.averageBytesPerSecond(finishedAt))
        }
        updateMessage(message)
    }

    private fun updateLocalState(message: String = _uiState.value.lastMessage) {
        _uiState.value = _uiState.value.copy(
            serviceRunning = true,
            localDevice = LocalDeviceInfo(
                deviceId = localId,
                deviceName = localName,
                ipAddresses = localIpAddresses(),
                tcpPort = TCP_PORT,
                discoveryPort = DISCOVERY_PORT,
            ),
            lastMessage = message,
        )
    }

    private fun publishDevices(message: String = _uiState.value.lastMessage) {
        _uiState.value = _uiState.value.copy(
            discoveredDevices = devices.values.sortedByDescending { it.lastSeenAt },
            lastMessage = message,
        )
    }

    private fun updateMessage(message: String) {
        _uiState.value = _uiState.value.copy(lastMessage = message)
    }

    private fun loadDeviceId(): String {
        val prefs = getSharedPreferences(TRANSFER_PREFS_NAME, MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val id = androidId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    private fun mergeMinBytesPerSecond(current: Long, sampled: Long): Long =
        when {
            sampled <= 0L -> current
            current <= 0L -> sampled
            else -> minOf(current, sampled)
        }

    private fun encodeTransferRecord(record: TransferRecord): JSONObject =
        JSONObject().apply {
            put("id", record.id)
            put("direction", record.direction.name)
            put("peerName", record.peerName)
            put("peerAddress", record.peerAddress)
            put("itemNames", JSONArray().apply { record.itemNames.forEach { put(it) } })
            put("localPaths", JSONArray().apply { record.localPaths.forEach { put(it) } })
            put("bytesTotal", record.bytesTotal)
            put("bytesTransferred", record.bytesTransferred)
            put("currentBytesPerSecond", record.currentBytesPerSecond)
            put("peakBytesPerSecond", record.peakBytesPerSecond)
            put("minBytesPerSecond", record.minBytesPerSecond)
            put("averageBytesPerSecondValue", record.averageBytesPerSecondValue)
            put("status", record.status.name)
            put("startedAt", record.startedAt)
            put("finishedAt", record.finishedAt ?: JSONObject.NULL)
            put("message", record.message)
        }

    private fun decodeTransferRecord(json: JSONObject, now: Long): TransferRecord? {
        val direction = runCatching { TransferDirection.valueOf(json.optString("direction")) }.getOrNull() ?: return null
        val status = runCatching { TransferStatus.valueOf(json.optString("status")) }.getOrNull() ?: return null
        val bytesTotal = json.optLong("bytesTotal").coerceAtLeast(0L)
        val bytesTransferred = json.optLong("bytesTransferred").let { persisted ->
            if (bytesTotal > 0L) {
                persisted.coerceIn(0L, bytesTotal)
            } else {
                persisted.coerceAtLeast(0L)
            }
        }
        val restoredStatus = if (status == TransferStatus.RUNNING) TransferStatus.FAILED else status
        val restoredFinishedAt = json.takeUnless { it.isNull("finishedAt") }
            ?.optLong("finishedAt")
            ?.takeIf { it > 0L }
            ?: if (restoredStatus == TransferStatus.RUNNING) null else now
        val restoredRecord = TransferRecord(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            direction = direction,
            peerName = json.optString("peerName"),
            peerAddress = json.optString("peerAddress"),
            itemNames = json.optJSONArray("itemNames").toStringList(),
            localPaths = json.optJSONArray("localPaths").toStringList(),
            bytesTotal = bytesTotal,
            bytesTransferred = bytesTransferred,
            currentBytesPerSecond = 0L,
            peakBytesPerSecond = json.optLong("peakBytesPerSecond").coerceAtLeast(0L),
            minBytesPerSecond = json.optLong("minBytesPerSecond").coerceAtLeast(0L),
            averageBytesPerSecondValue = json.optLong("averageBytesPerSecondValue").coerceAtLeast(0L),
            status = restoredStatus,
            startedAt = json.optLong("startedAt").takeIf { it > 0L } ?: now,
            finishedAt = if (status == TransferStatus.RUNNING) now else restoredFinishedAt,
            message = if (status == TransferStatus.RUNNING) {
                "服务重启后恢复记录：上次传输已中断"
            } else {
                json.optString("message")
            },
        )
        return restoredRecord.copy(
            averageBytesPerSecondValue = restoredRecord.averageBytesPerSecondValue
                .takeIf { it > 0L }
                ?: restoredRecord.averageBytesPerSecond(restoredRecord.finishedAt ?: now),
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:transfer").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "$packageName:transfer-high-perf",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = wifiManager.createMulticastLock("file-transfer-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "局域网传输", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("局域网极速传输运行中")
            .setContentText("正在发现设备并监听文件接收")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun receivedDirectory(): File =
        File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "received").apply { mkdirs() }

    private fun Socket.configureForBulkTransfer() {
        tcpNoDelay = false
        sendBufferSize = SOCKET_BUFFER_SIZE_BYTES
        receiveBufferSize = SOCKET_BUFFER_SIZE_BYTES
    }

    private class ProgressReporter(
        private val expectedBytes: Long,
        private val stepBytes: Long,
        private val minReportIntervalMs: Long,
        private val maxReportIntervalMs: Long,
        private val onProgress: (Long) -> Unit,
    ) {
        private var lastReportedBytes = 0L
        private var lastReportedAtMillis = SystemClock.elapsedRealtime()

        fun report(copied: Long) {
            if (copied <= lastReportedBytes) return
            val now = SystemClock.elapsedRealtime()
            val bytesDelta = copied - lastReportedBytes
            val timeDelta = now - lastReportedAtMillis
            val isFinal = expectedBytes > 0L && copied >= expectedBytes
            val shouldReport = isFinal ||
                (bytesDelta >= stepBytes && timeDelta >= minReportIntervalMs) ||
                timeDelta >= maxReportIntervalMs
            if (!shouldReport) return
            lastReportedBytes = copied
            lastReportedAtMillis = now
            onProgress(copied)
        }
    }

    private data class TransferProgressSnapshot(
        val bytesTransferred: Long,
        val bytesPerSecond: Long,
        val peakBytesPerSecond: Long,
        val minBytesPerSecond: Long,
    )

    private data class FileChunk(
        val fileIndex: Int,
        val filePath: String,
        val offset: Long,
        val length: Int,
    )

    private class TransferProgressTracker {
        private var latestBytesTransferred = 0L
        private var latestBytesPerSecond = 0L
        private var peakBytesPerSecond = 0L
        private var minBytesPerSecond = 0L
        private var lastSampleBytes = 0L
        private var lastSampleAtMillis = SystemClock.elapsedRealtime()
        private var dirty = false

        @Synchronized
        fun record(bytesTransferred: Long) {
            if (bytesTransferred < latestBytesTransferred) return
            val now = SystemClock.elapsedRealtime()
            val bytesDelta = bytesTransferred - lastSampleBytes
            val timeDelta = (now - lastSampleAtMillis).coerceAtLeast(1L)
            if (bytesDelta > 0L) {
                val sampleBytesPerSecond = (bytesDelta * 1000L / timeDelta).coerceAtLeast(0L)
                latestBytesPerSecond = if (latestBytesPerSecond == 0L) {
                    sampleBytesPerSecond
                } else {
                    ((latestBytesPerSecond * 3L) + sampleBytesPerSecond) / 4L
                }
                peakBytesPerSecond = max(peakBytesPerSecond, sampleBytesPerSecond)
                minBytesPerSecond = if (minBytesPerSecond == 0L) {
                    sampleBytesPerSecond
                } else {
                    minOf(minBytesPerSecond, sampleBytesPerSecond)
                }
                lastSampleBytes = bytesTransferred
                lastSampleAtMillis = now
            }
            latestBytesTransferred = bytesTransferred
            dirty = true
        }

        @Synchronized
        fun consume(): TransferProgressSnapshot? {
            if (!dirty) return null
            dirty = false
            return TransferProgressSnapshot(
                bytesTransferred = latestBytesTransferred,
                bytesPerSecond = latestBytesPerSecond,
                peakBytesPerSecond = peakBytesPerSecond,
                minBytesPerSecond = minBytesPerSecond,
            )
        }

        @Synchronized
        fun finish(): TransferProgressSnapshot =
            TransferProgressSnapshot(
                bytesTransferred = latestBytesTransferred,
                bytesPerSecond = latestBytesPerSecond,
                peakBytesPerSecond = peakBytesPerSecond,
                minBytesPerSecond = minBytesPerSecond,
            )
    }

    private class ReceiveSessionFile(
        val target: File,
        sizeBytes: Long,
    ) {
        private val access = RandomAccessFile(target, "rw").apply {
            setLength(sizeBytes.coerceAtLeast(0L))
        }
        private val writeLock = Any()

        fun writeFrom(input: DataInputStream, offset: Long, length: Int, buffer: ByteArray): Long =
            synchronized(writeLock) {
                var remaining = length
                var copied = 0L
                access.seek(offset)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) throw IllegalStateException("连接提前断开")
                    access.write(buffer, 0, read)
                    copied += read
                    remaining -= read
                }
                copied
            }

        fun closeQuietly() {
            runCatching { access.close() }
        }
    }

    private inner class ReceiveSession(
        val sessionId: String,
        val outputDir: File,
        private val files: List<ReceiveSessionFile>,
        expectedChunkCount: Int,
    ) {
        val transferId: String = sessionId
        private val remainingChunks = AtomicInteger(expectedChunkCount)
        private val receivedBytes = AtomicLong(0L)
        private val closed = AtomicBoolean(false)

        fun writeChunk(
            input: DataInputStream,
            fileIndex: Int,
            offset: Long,
            length: Int,
            transferBuffer: ByteArray,
        ): Long {
            val file = files.getOrNull(fileIndex) ?: error("文件索引越界")
            val copied = file.writeFrom(input, offset, length, transferBuffer)
            val total = receivedBytes.addAndGet(copied)
            recordTransferProgress(transferId, total)
            if (remainingChunks.decrementAndGet() == 0) {
                completeReceiveSession(sessionId)
            }
            return copied
        }

        fun closeQuietly() {
            if (!closed.compareAndSet(false, true)) return
            files.forEach { it.closeQuietly() }
        }
    }

    companion object {
        const val TCP_PORT = 39457
        private const val TCP_V2_PORT = 39459
        const val DISCOVERY_PORT = 39458
        private const val DISCOVERY_PREFIX = "FT_DISCOVERY_V1"
        private const val PROTOCOL_MAGIC = "FILE_TRANSFER_V1"
        private const val PROTOCOL_MAGIC_V1 = "FILE_TRANSFER_V1"
        private const val PROTOCOL_MAGIC_V2_CONTROL = "FILE_TRANSFER_V2_CONTROL"
        private const val PROTOCOL_MAGIC_V2_DATA = "FILE_TRANSFER_V2_DATA"
        private const val PROTOCOL_ACK_OK = "OK"
        private const val CHANNEL_ID = "transfer_service"
        private const val NOTIFICATION_ID = 1207
        private const val DISCOVERY_INTERVAL_MS = 3_000L
        private const val DEVICE_TTL_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val PROGRESS_STEP_BYTES = 4L * 1024L * 1024L
        private const val PROGRESS_MIN_REPORT_INTERVAL_MS = 150L
        private const val PROGRESS_MAX_REPORT_INTERVAL_MS = 750L
        private const val PROGRESS_PUBLISH_INTERVAL_MS = 400L
        private const val TRANSFER_PERSIST_INTERVAL_MS = 1_500L
        private const val MAX_RECORDS = 50
        private const val COPY_BUFFER_SIZE_BYTES = 1024 * 1024
        private const val STREAM_BUFFER_SIZE_BYTES = 1024 * 1024
        private const val SOCKET_BUFFER_SIZE_BYTES = 1024 * 1024
        private const val DATA_CHUNK_SIZE_BYTES = 4 * 1024 * 1024
        private const val MAX_V2_STREAMS = 4
        private const val TRANSFER_PREFS_NAME = "transfer"
        private const val KEY_TRANSFER_RECORDS = "transfer_records"
        const val ACTION_STOP = "io.github.yzjdev.filetransfer.STOP"

        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

private fun PowerManager.WakeLock.releaseIfHeld() {
    if (isHeld) release()
}

private fun WifiManager.WifiLock.releaseIfHeld() {
    if (isHeld) release()
}

private fun WifiManager.MulticastLock.releaseIfHeld() {
    if (isHeld) release()
}

private fun Throwable.safeMessage(): String = message ?: javaClass.simpleName

private fun String.safeFileName(): String =
    replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "received_file" }

private fun File.uniqueChild(name: String): File {
    val cleanName = name.safeFileName()
    val base = cleanName.substringBeforeLast('.', cleanName)
    val extension = cleanName.substringAfterLast('.', "")
    var candidate = File(this, cleanName)
    var index = 1
    while (candidate.exists()) {
        val suffix = if (extension.isBlank()) " ($index)" else " ($index).$extension"
        candidate = File(this, base + suffix)
        index++
    }
    return candidate
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val values = mutableListOf<String>()
    for (index in 0 until length()) {
        val value = optString(index)
        if (value.isNotBlank()) values += value
    }
    return values
}

private fun localIpAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .map { it.hostAddress ?: "" }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

private fun broadcastAddresses(): List<InetAddress> =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { networkInterface -> networkInterface.interfaceAddresses.asSequence() }
        .mapNotNull { it.broadcast }
        .distinctBy { it.hostAddress }
        .toList()

private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
    while (hasMoreElements()) {
        yield(nextElement())
    }
}

private data class PreparedSendItem(
    val item: ShareItem,
    val tempFile: File? = null,
)
