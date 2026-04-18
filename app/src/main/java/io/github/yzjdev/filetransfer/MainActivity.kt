package io.github.yzjdev.filetransfer

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yzjdev.filetransfer.transfer.DiscoveredDevice
import io.github.yzjdev.filetransfer.transfer.InstalledApk
import io.github.yzjdev.filetransfer.transfer.ShareItem
import io.github.yzjdev.filetransfer.transfer.TransferDirection
import io.github.yzjdev.filetransfer.transfer.TransferRecord
import io.github.yzjdev.filetransfer.transfer.TransferService
import io.github.yzjdev.filetransfer.transfer.TransferStatus
import io.github.yzjdev.filetransfer.transfer.TransferUiState
import io.github.yzjdev.filetransfer.transfer.averageBytesPerSecond
import io.github.yzjdev.filetransfer.transfer.durationMillis
import io.github.yzjdev.filetransfer.transfer.loadInstalledApks
import io.github.yzjdev.filetransfer.transfer.toReadableDuration
import io.github.yzjdev.filetransfer.transfer.toReadableSize
import io.github.yzjdev.filetransfer.ui.theme.FileTransferTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TransferService.start(this)
        setContent {
            FileTransferTheme {
                FileTransferApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileTransferApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val serviceHolder = rememberBoundTransferService(context)
    val service = serviceHolder.service
    val uiState by service?.uiState?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(TransferUiState()) }
    val prefs = remember(context) { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    val sortedDevices = uiState.discoveredDevices.sortedWith(
        compareBy<DiscoveredDevice> { it.deviceName.lowercase() }.thenBy { it.ipAddress }
    )

    var showIp by rememberSaveable { mutableStateOf(prefs.getBoolean(KEY_SHOW_IP, true)) }
    var showApkSheet by rememberSaveable { mutableStateOf(false) }
    var apkSearch by rememberSaveable { mutableStateOf("") }
    var currentPage by rememberSaveable { mutableStateOf(MainPage.HOME) }
    var pendingApkTarget by remember { mutableStateOf<PendingApkTarget?>(null) }
    val selectedDeviceIds = remember { mutableStateListOf<String>() }
    val selectedFiles = remember { mutableStateListOf<ShareItem>() }
    val selectedApks = remember { mutableStateListOf<InstalledApk>() }
    val installedApksState = produceState<List<InstalledApk>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadInstalledApks(context.packageManager) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val freshItems = uris.mapNotNull { uri ->
            context.contentResolver.takeIfPersistablePermission(uri)
            uri.toShareItem(context.contentResolver)
        }
        selectedFiles.addOrReplaceAllBy(freshItems) { it.sourceLabel }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showApkSheet) {
        val installedApks = installedApksState.value
        val filteredApks = installedApks
            ?.filter {
                apkSearch.isBlank() ||
                    it.label.contains(apkSearch, ignoreCase = true) ||
                    it.packageName.contains(apkSearch, ignoreCase = true)
            }
            .orEmpty()
        ModalBottomSheet(onDismissRequest = { showApkSheet = false }) {
            if (installedApks == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = apkSearch,
                                onValueChange = { apkSearch = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("搜索 APK") },
                                placeholder = { Text("应用名或包名") },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        selectedApks.addAllMissingBy(filteredApks) { it.packageName }
                                    },
                                    enabled = filteredApks.isNotEmpty(),
                                ) {
                                    Text("全选")
                                }
                                OutlinedButton(
                                    onClick = {
                                        selectedApks.removeAllBy(filteredApks) { it.packageName }
                                    },
                                    enabled = filteredApks.isNotEmpty(),
                                ) {
                                    Text("清空筛选")
                                }
                            }
                        }
                    }
                    items(filteredApks, key = { it.packageName }) { apk ->
                        val selected = selectedApks.any { it.packageName == apk.packageName }
                        DenseCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                selectedApks.toggleBy(apk) { it.packageName }
                            },
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(packageName = apk.packageName)
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(apk.label, fontWeight = FontWeight.SemiBold)
                                    Text(apk.packageName, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "版本 ${apk.versionName.ifBlank { "未知" }} · ${apk.sizeBytes.toReadableSize()}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        if (selected) "已加入发送队列" else "点击加入发送队列",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }

    pendingApkTarget?.let { target ->
        val apkPath = target.path
        if (apkPath != null) {
            AlertDialog(
                onDismissRequest = { pendingApkTarget = null },
                title = { Text("\u6253\u5f00 APK") },
                text = { Text(target.label) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            context.installApkTarget(apkPath)
                            pendingApkTarget = null
                        },
                    ) {
                        Text("\u5b89\u88c5")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                context.openTransferTarget(apkPath)
                                pendingApkTarget = null
                            },
                        ) {
                            Text("\u9009\u62e9\u6253\u5f00")
                        }
                        TextButton(onClick = { pendingApkTarget = null }) {
                            Text("\u53d6\u6d88")
                        }
                    }
                },
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentPage == MainPage.HOME,
                    onClick = { currentPage = MainPage.HOME },
                    icon = {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_menu_share),
                            contentDescription = "传输",
                        )
                    },
                    label = { Text("传输") },
                )
                NavigationBarItem(
                    selected = currentPage == MainPage.RECORDS,
                    onClick = { currentPage = MainPage.RECORDS },
                    icon = {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_menu_recent_history),
                            contentDescription = "记录",
                        )
                    },
                    label = { Text("记录") },
                )
            }
        },
    ) { padding ->
        when (currentPage) {
            MainPage.HOME -> HomePage(
                padding = padding,
                uiState = uiState,
                sortedDevices = sortedDevices,
                showIp = showIp,
                onToggleIp = {
                    showIp = it
                    prefs.edit().putBoolean(KEY_SHOW_IP, it).apply()
                },
                onToggleService = {
                    service?.toggleRunning() ?: if (uiState.serviceRunning) {
                        TransferService.stop(context)
                    } else {
                        TransferService.start(context)
                    }
                },
                selectedFiles = selectedFiles.toList(),
                selectedApks = selectedApks.toList(),
                onPickFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
                onPickApks = { showApkSheet = true },
                onClearFiles = { selectedFiles.clear() },
                onClearApks = { selectedApks.clear() },
                selectedDeviceIds = selectedDeviceIds.toList(),
                onToggleDeviceSelection = { device ->
                    selectedDeviceIds.toggleBy(device.deviceId) { it }
                },
                onSelectAllDevices = {
                    val onlineDeviceIds = sortedDevices.map { it.deviceId }
                    val allOnlineSelected = onlineDeviceIds.isNotEmpty() && onlineDeviceIds.all { it in selectedDeviceIds }
                    selectedDeviceIds.clear()
                    if (!allOnlineSelected) {
                        selectedDeviceIds.addAll(onlineDeviceIds)
                    }
                },
                onSend = { device ->
                    service?.sendItems(device, buildBatch(selectedFiles, selectedApks))
                },
                onSendSelected = {
                    val batch = buildBatch(selectedFiles, selectedApks)
                    sortedDevices
                        .filter { it.deviceId in selectedDeviceIds }
                        .forEach { device -> service?.sendItems(device, batch) }
                },
            )
            MainPage.RECORDS -> RecordsPage(
                padding = padding,
                transfers = uiState.transfers,
                packageName = context.packageName,
                showIp = showIp,
                onRecordItemClick = { record, itemIndex ->
                    val target = record.openTargetAt(itemIndex) ?: return@RecordsPage
                    if (target.label.endsWith(".apk", ignoreCase = true)) {
                        pendingApkTarget = target
                    } else {
                        context.openTransferTarget(target.path)
                    }
                },
            )
        }
    }
}

@Composable
private fun HomePage(
    padding: androidx.compose.foundation.layout.PaddingValues,
    uiState: TransferUiState,
    sortedDevices: List<DiscoveredDevice>,
    showIp: Boolean,
    onToggleIp: (Boolean) -> Unit,
    onToggleService: () -> Unit,
    selectedFiles: List<ShareItem>,
    selectedApks: List<InstalledApk>,
    onPickFiles: () -> Unit,
    onPickApks: () -> Unit,
    onClearFiles: () -> Unit,
    onClearApks: () -> Unit,
    selectedDeviceIds: List<String>,
    onToggleDeviceSelection: (DiscoveredDevice) -> Unit,
    onSelectAllDevices: () -> Unit,
    onSend: (DiscoveredDevice) -> Unit,
    onSendSelected: () -> Unit,
) {
    val itemCount = selectedFiles.size + selectedApks.size
    val totalBatchSize = selectedFiles.sumOf { it.sizeBytes } + selectedApks.sumOf { it.sizeBytes }
    val selectedAvailableCount = sortedDevices.count { it.deviceId in selectedDeviceIds }
    val allOnlineDevicesSelected = sortedDevices.isNotEmpty() && sortedDevices.all { it.deviceId in selectedDeviceIds }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("局域网极速传输", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            StatusCard(
                uiState = uiState,
                showIp = showIp,
                onToggleIp = onToggleIp,
                onToggleService = onToggleService,
            )
        }
        item {
            SelectionCard(
                selectedFiles = selectedFiles,
                selectedApks = selectedApks,
                onPickFiles = onPickFiles,
                onPickApks = onPickApks,
                onClearFiles = onClearFiles,
                onClearApks = onClearApks,
            )
        }
        if (sortedDevices.isNotEmpty()) {
            item {
                DenseCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "\u5df2\u9009\u8bbe\u5907 $selectedAvailableCount \u4e2a",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = onSelectAllDevices,
                                enabled = sortedDevices.isNotEmpty(),
                            ) {
                                Text(if (allOnlineDevicesSelected) "\u53d6\u6d88\u5168\u9009" else "\u5168\u9009")
                            }
                            val sendSelectedEnabled = itemCount > 0 && selectedAvailableCount > 0
                            IconButton(
                                onClick = onSendSelected,
                                enabled = sendSelectedEnabled,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = "\u53d1\u9001\u5230\u5df2\u9009\u8bbe\u5907",
                                    tint = if (sendSelectedEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("设备")
        }
        if (sortedDevices.isEmpty()) {
            item {
                EmptyCard("暂无设备，确保双方在同一局域网且服务已运行。")
            }
        } else {
            items(sortedDevices, key = { it.deviceId }) { device ->
                DeviceCard(
                    device = device,
                    showIp = showIp,
                    selected = device.deviceId in selectedDeviceIds,
                    totalBatchSize = totalBatchSize,
                    itemCount = itemCount,
                    onToggleSelected = { onToggleDeviceSelection(device) },
                    onSend = { onSend(device) },
                )
            }
        }
        item { Spacer(Modifier.height(14.dp)) }
    }
}

@Composable
private fun RecordsPage(
    padding: androidx.compose.foundation.layout.PaddingValues,
    transfers: List<TransferRecord>,
    packageName: String,
    showIp: Boolean,
    onRecordItemClick: (TransferRecord, Int) -> Unit,
) {
    val nowMillis = rememberTickingNowMillis(transfers.any { it.status == TransferStatus.RUNNING })
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("传输记录", style = MaterialTheme.typography.headlineSmall)
        }
        if (transfers.isEmpty()) {
            item {
                EmptyCard("暂无记录。接收目录：Android/data/$packageName/files/Download/received")
            }
        } else {
            items(transfers, key = { it.id }) { record ->
                TransferRecordCard(
                    record = record,
                    nowMillis = nowMillis,
                    showIp = showIp,
                    onItemClick = { index -> onRecordItemClick(record, index) },
                )
            }
        }
        item { Spacer(Modifier.height(14.dp)) }
    }
}

@Composable
private fun StatusCard(
    uiState: TransferUiState,
    showIp: Boolean,
    onToggleIp: (Boolean) -> Unit,
    onToggleService: () -> Unit,
) {
    DenseCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("服务", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (uiState.serviceRunning) "运行中" else "未连接",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onToggleService) {
                    Text(if (uiState.serviceRunning) "停止" else "启动")
                }
            }
            MetaLine("设备", uiState.localDevice.deviceName)
            MetaLine("端口", "${uiState.localDevice.tcpPort} / ${uiState.localDevice.discoveryPort}")
            MetaLine(
                label = "地址",
                value = if (showIp) {
                    uiState.localDevice.ipAddresses.joinToString().ifBlank { "未获取到局域网 IPv4" }
                } else {
                    if (uiState.localDevice.ipAddresses.isEmpty()) "未获取到局域网 IPv4" else "********"
                },
                trailing = {
                    Icon(
                        imageVector = if (showIp) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (showIp) "隐藏 IP" else "显示 IP",
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onToggleIp(!showIp) },
                    )
                },
            )
        }
    }
}

@Composable
private fun SelectionCard(
    selectedFiles: List<ShareItem>,
    selectedApks: List<InstalledApk>,
    onPickFiles: () -> Unit,
    onPickApks: () -> Unit,
    onClearFiles: () -> Unit,
    onClearApks: () -> Unit,
) {
    DenseCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("待发送", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onPickFiles) { Text("选文件") }
                OutlinedButton(onClick = onPickApks) { Text("选 APK") }
            }
            if (selectedFiles.isNotEmpty()) {
                Text(
                    "文件 ${selectedFiles.size} 个 · ${selectedFiles.sumOf { it.sizeBytes }.toReadableSize()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                CompactChipFlow(labels = selectedFiles.take(6).map { it.displayName })
                TextButton(onClick = onClearFiles) { Text("清空文件") }
            }
            if (selectedApks.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "APK ${selectedApks.size} 个 · ${selectedApks.sumOf { it.sizeBytes }.toReadableSize()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectedApkRow(items = selectedApks)
                TextButton(onClick = onClearApks) { Text("清空 APK") }
            }
            if (selectedFiles.isEmpty() && selectedApks.isEmpty()) {
                Text("先选择文件或 APK，再对目标设备发送。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    showIp: Boolean,
    selected: Boolean,
    itemCount: Int,
    totalBatchSize: Long,
    onToggleSelected: () -> Unit,
    onSend: () -> Unit,
) {
    DenseCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(device.deviceName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (showIp) "${device.ipAddress}:${device.tcpPort}" else "********",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${((System.currentTimeMillis() - device.lastSeenAt) / 1000L).coerceAtLeast(0)} 秒前发现",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onToggleSelected) {
                        Text(if (selected) "\u5df2\u9009" else "\u9009\u62e9")
                    }
                    Button(onClick = onSend, enabled = itemCount > 0) {
                        Text(if (itemCount > 0) "\u53d1\u9001" else "\u5f85\u9009")
                    }
                }
            }
            if (itemCount > 0) {
                Text("$itemCount 项 · ${totalBatchSize.toReadableSize()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TransferRecordCard(
    record: TransferRecord,
    nowMillis: Long,
    showIp: Boolean,
    onItemClick: ((Int) -> Unit)? = null,
) {
    val progress = when {
        record.bytesTotal <= 0L -> 0f
        else -> (record.bytesTransferred.toFloat() / record.bytesTotal.toFloat()).coerceIn(0f, 1f)
    }
    val direction = if (record.direction == TransferDirection.SEND) "发送" else "接收"
    val statusText = when (record.status) {
        TransferStatus.RUNNING -> "进行中"
        TransferStatus.SUCCESS -> "成功"
        TransferStatus.FAILED -> "失败"
    }
    DenseCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$direction · $statusText", fontWeight = FontWeight.SemiBold)
                Text("${record.itemNames.size} 项", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${record.peerName} (${if (showIp) record.peerAddress else "********"})",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${record.bytesTransferred.toReadableSize()} / ${record.bytesTotal.toReadableSize()}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "\u901f\u7387 ${record.averageBytesPerSecond(nowMillis).toReadableSize()}/s \u00b7 \u65f6\u957f ${record.durationMillis(nowMillis).toReadableDuration()}",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(record.message, style = MaterialTheme.typography.bodySmall)
            if (record.itemNames.isNotEmpty()) {
                TransferRecordItems(record = record, onItemClick = onItemClick)
            }
        }
    }
}

@Composable
private fun TransferRecordItems(
    record: TransferRecord,
    onItemClick: ((Int) -> Unit)?,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(record.itemNames, key = { index, name -> "$index:$name" }) { index, name ->
            val clickable = onItemClick != null && record.openTargetAt(index) != null
            AssistChip(
                onClick = { if (clickable) onItemClick?.invoke(index) },
                enabled = clickable,
                shape = RoundedCornerShape(18.dp),
                leadingIcon = {
                    Icon(
                        imageVector = fileIconForName(name),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = { Text(name, style = MaterialTheme.typography.bodySmall) },
            )
        }
    }
}

private fun fileIconForName(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "apk" -> Icons.Filled.Android
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> Icons.Filled.Image
        "mp4", "mkv", "avi", "mov", "webm" -> Icons.Filled.Movie
        "mp3", "wav", "flac", "aac", "ogg" -> Icons.Filled.Audiotrack
        else -> Icons.Filled.InsertDriveFile
    }
}

@Composable
private fun EmptyCard(text: String) {
    DenseCard {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DenseCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick == null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                content = content,
            )
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 2.dp, bottom = 1.dp),
    )
}

@Composable
private fun MetaLine(
    label: String,
    value: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
private fun CompactChipFlow(labels: List<String>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(labels, key = { index, label -> "$index:$label" }) { _, label ->
            AssistChip(
                onClick = {},
                label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            )
        }
    }
}

@Composable
private fun SelectedApkRow(items: List<InstalledApk>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items, key = { it.packageName }) { apk ->
            DenseCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(packageName = apk.packageName)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            apk.label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                        Text(
                            apk.versionName.ifBlank { apk.packageName },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberTickingNowMillis(enabled: Boolean): Long {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            nowMillis = System.currentTimeMillis()
            return@LaunchedEffect
        }
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return nowMillis
}

@Composable
private fun AppIcon(packageName: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val icon = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }
    AndroidView(
        factory = { android.widget.ImageView(it) },
        modifier = Modifier.size(22.dp),
        update = { view ->
            view.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            view.setImageDrawable(icon ?: context.defaultActivityIcon())
        },
    )
}

@Composable
private fun rememberBoundTransferService(context: Context): BoundServiceHolder {
    var service by remember { mutableStateOf<TransferService?>(null) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? TransferService.LocalBinder)?.service()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val intent = Intent(context, TransferService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            runCatching { context.unbindService(connection) }
        }
    }

    return BoundServiceHolder(service)
}

private data class BoundServiceHolder(
    val service: TransferService?,
)

private data class PendingApkTarget(
    val label: String,
    val path: String,
)

private const val KEY_SHOW_IP = "show_ip"

private enum class MainPage {
    HOME,
    RECORDS,
}

private fun buildBatch(files: List<ShareItem>, apks: List<InstalledApk>): List<ShareItem> =
    files + apks.map { apk ->
        ShareItem(
            id = "apk:${apk.packageName}",
            displayName = "${apk.label}-${apk.versionName.ifBlank { "base" }}.apk",
            filePath = apk.apkPath,
            sizeBytes = apk.sizeBytes,
            mimeType = "application/vnd.android.package-archive",
            sourceLabel = apk.packageName,
        )
    }

private fun Uri.toShareItem(contentResolver: ContentResolver): ShareItem? {
    val info = contentResolver.queryOpenable(this) ?: return null
    return ShareItem(
        id = UUID.randomUUID().toString(),
        displayName = info.first,
        uri = this,
        sizeBytes = info.second,
        mimeType = contentResolver.getType(this).orEmpty().ifBlank { "application/octet-stream" },
        sourceLabel = toString(),
    )
}

private fun ContentResolver.queryOpenable(uri: Uri): Pair<String, Long>? {
    val cursor = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?: return null
    cursor.useColumn { displayNameIndex, sizeIndex ->
        if (!moveToFirst()) return null
        val name = getString(displayNameIndex).orEmpty().ifBlank {
            File(uri.path.orEmpty()).name.ifBlank { "shared_file" }
        }
        val size = if (isNull(sizeIndex)) 0L else getLong(sizeIndex)
        return name to size
    }
}

private inline fun <T> Cursor.useColumn(block: Cursor.(Int, Int) -> T): T {
    use {
        val displayNameIndex = getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = getColumnIndexOrThrow(OpenableColumns.SIZE)
        return block(displayNameIndex, sizeIndex)
    }
}

private fun ContentResolver.takeIfPersistablePermission(uri: Uri) {
    runCatching {
        takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun <T, K> MutableList<T>.toggleBy(item: T, keySelector: (T) -> K) {
    val key = keySelector(item)
    val index = indexOfFirst { keySelector(it) == key }
    if (index >= 0) {
        removeAt(index)
    } else {
        add(item)
    }
}

private fun <T, K> MutableList<T>.addOrReplaceAllBy(items: List<T>, keySelector: (T) -> K) {
    items.forEach { item ->
        val key = keySelector(item)
        val index = indexOfFirst { keySelector(it) == key }
        if (index >= 0) {
            set(index, item)
        } else {
            add(item)
        }
    }
}

private fun <T, K> MutableList<T>.addAllMissingBy(items: List<T>, keySelector: (T) -> K) {
    items.forEach { item ->
        val key = keySelector(item)
        if (none { keySelector(it) == key }) {
            add(item)
        }
    }
}

private fun <T, K> MutableList<T>.removeAllBy(items: List<T>, keySelector: (T) -> K) {
    val keys = items.map(keySelector).toSet()
    removeAll { keySelector(it) in keys }
}

private fun Context.defaultActivityIcon(): Drawable = packageManager.defaultActivityIcon

private fun TransferRecord.installableApkPath(): String? {
    if (direction != TransferDirection.RECEIVE || status != TransferStatus.SUCCESS) return null
    return localPaths.firstOrNull { it.endsWith(".apk", ignoreCase = true) }
}

private fun TransferRecord.openableFilePath(): String? {
    if (direction != TransferDirection.RECEIVE || status != TransferStatus.SUCCESS) return null
    return localPaths.firstOrNull()
}

private fun TransferRecord.openTargetAt(index: Int): PendingApkTarget? {
    if (status != TransferStatus.SUCCESS) return null
    val path = localPaths.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return null
    val label = itemNames.getOrNull(index).orEmpty().ifBlank {
        path.substringAfterLast('/').ifBlank { path }
    }
    return PendingApkTarget(label = label, path = path)
}

private fun Context.installApk(file: File) {
    if (!file.exists()) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        val permissionIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(permissionIntent)
        return
    }
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

private fun Context.installApkTarget(path: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        val permissionIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(permissionIntent)
        return
    }
    val uri = path.toTransferUri(this) ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

private fun Context.openTransferTarget(path: String) {
    val uri = path.toTransferUri(this) ?: return
    val mimeType = contentResolver.getType(uri)
        ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase())
        ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(Intent.createChooser(intent, "\u9009\u62e9\u5e94\u7528").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun String.toTransferUri(context: Context): Uri? {
    if (startsWith("content://", ignoreCase = true)) return Uri.parse(this)
    val file = File(this)
    if (!file.exists()) return null
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun Context.openFileWithApp(file: File) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val mimeType = contentResolver.getType(uri)
        ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
        ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(Intent.createChooser(intent, "选择应用").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
