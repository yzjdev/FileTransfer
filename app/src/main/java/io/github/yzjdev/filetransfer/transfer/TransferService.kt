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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlin.math.max

class TransferService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val devices = linkedMapOf<String, DiscoveredDevice>()
    private val stateMutex = Mutex()
    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var broadcastJob: Job? = null
    private var discoveryJob: Job? = null
    private var serverJob: Job? = null
    private var isEngineRunning = false

    private lateinit var localId: String
    private lateinit var localName: String

    inner class LocalBinder : Binder() {
        fun service(): TransferService = this@TransferService
    }

    override fun onCreate() {
        super.onCreate()
        localId = loadDeviceId()
        localName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android Device" }
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
    }

    private fun stopEngine(stopService: Boolean) {
        if (!isEngineRunning && !stopService) return
        isEngineRunning = false
        broadcastJob?.cancel()
        discoveryJob?.cancel()
        serverJob?.cancel()
        broadcastJob = null
        discoveryJob = null
        serverJob = null
        discoverySocket?.close()
        serverSocket?.close()
        discoverySocket = null
        serverSocket = null
        wakeLock?.releaseIfHeld()
        multicastLock?.releaseIfHeld()
        wakeLock = null
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
                ServerSocket(TCP_PORT).use { server ->
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

        runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(target.ipAddress, target.tcpPort), CONNECT_TIMEOUT_MS)
                DataOutputStream(BufferedOutputStream(socket.getOutputStream())).use { output ->
                    output.writeUTF(PROTOCOL_MAGIC)
                    output.writeUTF(localId)
                    output.writeUTF(localName)
                    output.writeLong(totalBytes)
                    output.writeInt(preparedItems.size)
                    var sent = 0L
                    preparedItems.forEach { prepared ->
                        val item = prepared.item
                        output.writeUTF(item.displayName)
                        output.writeLong(item.sizeBytes)
                        output.writeUTF(item.mimeType)
                        openInput(item).use { input ->
                            sent += copyWithProgress(input, output, item.sizeBytes) { copied ->
                                updateTransfer(transferId) {
                                    it.copy(bytesTransferred = (sent + copied).coerceAtMost(totalBytes))
                                }
                            }
                        }
                    }
                    output.flush()
                }
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
            client.tcpNoDelay = true
            runCatching {
                DataInputStream(BufferedInputStream(client.getInputStream())).use { input ->
                    val magic = input.readUTF()
                    require(magic == PROTOCOL_MAGIC) { "协议不匹配" }
                    val senderId = input.readUTF()
                    val senderName = input.readUTF()
                    val totalBytes = input.readLong().coerceAtLeast(0L)
                    val count = input.readInt().coerceIn(0, 10_000)
                    val peerAddress = client.inetAddress.hostAddress.orEmpty()
                    val outputDir = receivedDirectory()
                    val transferId = UUID.randomUUID().toString()
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

                    var received = 0L
                    repeat(count) {
                        val name = input.readUTF().safeFileName()
                        val size = input.readLong().coerceAtLeast(0L)
                        input.readUTF()
                        itemNames += name
                        updateTransfer(transferId) { it.copy(itemNames = itemNames.toList()) }
                        val target = outputDir.uniqueChild(name)
                        localPaths += target.absolutePath
                        BufferedOutputStream(target.outputStream()).use { output ->
                            received += copyExactWithProgress(input, output, size) { copied ->
                                updateTransfer(transferId) {
                                    it.copy(
                                        bytesTransferred = (received + copied).coerceAtMost(totalBytes),
                                        localPaths = localPaths.toList(),
                                    )
                                }
                            }
                        }
                    }
                    finishTransfer(transferId, TransferStatus.SUCCESS, "接收完成：${outputDir.absolutePath}")
                }
            }.onFailure { error ->
                updateMessage("接收失败：${error.safeMessage()}")
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
            runCatching {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    if (descriptor.length >= 0L) {
                        return PreparedSendItem(item.copy(sizeBytes = descriptor.length))
                    }
                }
            }
            val tempFile = File(cacheDir, "send-${UUID.randomUUID()}").apply { deleteOnExit() }
            requireNotNull(contentResolver.openInputStream(uri)) { "???????${item.displayName}" }.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
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

    private suspend fun copyWithProgress(
        input: InputStream,
        output: DataOutputStream,
        expectedBytes: Long,
        onProgress: suspend (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        var lastReport = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            if (copied - lastReport >= PROGRESS_STEP_BYTES || copied == expectedBytes) {
                lastReport = copied
                onProgress(copied)
            }
        }
        return copied
    }

    private suspend fun copyExactWithProgress(
        input: DataInputStream,
        output: BufferedOutputStream,
        expectedBytes: Long,
        onProgress: suspend (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = expectedBytes
        var copied = 0L
        var lastReport = 0L
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IllegalStateException("连接提前断开")
            output.write(buffer, 0, read)
            copied += read
            remaining -= read
            if (copied - lastReport >= PROGRESS_STEP_BYTES || copied == expectedBytes) {
                lastReport = copied
                onProgress(copied)
            }
        }
        return copied
    }

    private fun addTransfer(record: TransferRecord) {
        val current = _uiState.value.transfers
        _uiState.value = _uiState.value.copy(
            transfers = (listOf(record) + current).take(MAX_RECORDS),
            lastMessage = record.message,
        )
    }

    private fun updateTransfer(id: String, mapper: (TransferRecord) -> TransferRecord) {
        val updated = _uiState.value.transfers.map { if (it.id == id) mapper(it) else it }
        _uiState.value = _uiState.value.copy(transfers = updated)
    }

    private fun finishTransfer(id: String, status: TransferStatus, message: String) {
        updateTransfer(id) {
            it.copy(
                bytesTransferred = if (status == TransferStatus.SUCCESS) it.bytesTotal else it.bytesTransferred,
                status = status,
                finishedAt = System.currentTimeMillis(),
                message = message,
            )
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
        val prefs = getSharedPreferences("transfer", MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val id = androidId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:transfer").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
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

    companion object {
        const val TCP_PORT = 39457
        const val DISCOVERY_PORT = 39458
        private const val DISCOVERY_PREFIX = "FT_DISCOVERY_V1"
        private const val PROTOCOL_MAGIC = "FILE_TRANSFER_V1"
        private const val CHANNEL_ID = "transfer_service"
        private const val NOTIFICATION_ID = 1207
        private const val DISCOVERY_INTERVAL_MS = 3_000L
        private const val DEVICE_TTL_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val PROGRESS_STEP_BYTES = 256L * 1024L
        private const val MAX_RECORDS = 50
        private const val DEFAULT_BUFFER_SIZE = 256 * 1024
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
