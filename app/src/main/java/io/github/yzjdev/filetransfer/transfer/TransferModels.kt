package io.github.yzjdev.filetransfer.transfer

import android.net.Uri
import java.text.DecimalFormat

data class LocalDeviceInfo(
    val deviceId: String = "",
    val deviceName: String = "",
    val ipAddresses: List<String> = emptyList(),
    val tcpPort: Int = 39457,
    val discoveryPort: Int = 39458,
)

data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String,
    val tcpPort: Int,
    val lastSeenAt: Long,
)

data class ShareItem(
    val id: String,
    val displayName: String,
    val uri: Uri? = null,
    val filePath: String? = null,
    val sizeBytes: Long,
    val mimeType: String,
    val sourceLabel: String,
)

data class InstalledApk(
    val packageName: String,
    val label: String,
    val versionName: String,
    val apkPath: String,
    val sizeBytes: Long,
)

enum class TransferDirection {
    SEND,
    RECEIVE,
}

enum class TransferStatus {
    RUNNING,
    SUCCESS,
    FAILED,
}

data class TransferRecord(
    val id: String,
    val direction: TransferDirection,
    val peerName: String,
    val peerAddress: String,
    val itemNames: List<String>,
    val localPaths: List<String> = emptyList(),
    val bytesTotal: Long,
    val bytesTransferred: Long,
    val currentBytesPerSecond: Long = 0L,
    val peakBytesPerSecond: Long = 0L,
    val minBytesPerSecond: Long = 0L,
    val averageBytesPerSecondValue: Long = 0L,
    val status: TransferStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val message: String = "",
)

data class TransferUiState(
    val serviceRunning: Boolean = false,
    val localDevice: LocalDeviceInfo = LocalDeviceInfo(),
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val transfers: List<TransferRecord> = emptyList(),
    val lastMessage: String = "",
)

fun Long.toReadableSize(): String {
    if (this <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = this.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return "${DecimalFormat("#,##0.#").format(value)} ${units[index]}"
}

fun Long.toReadableDuration(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

fun TransferRecord.durationMillis(now: Long = System.currentTimeMillis()): Long =
    ((finishedAt ?: now) - startedAt).coerceAtLeast(0L)

fun TransferRecord.averageBytesPerSecond(now: Long = System.currentTimeMillis()): Long {
    val durationSeconds = durationMillis(now) / 1000.0
    if (durationSeconds <= 0.0) return 0L
    return (bytesTransferred / durationSeconds).toLong().coerceAtLeast(0L)
}
