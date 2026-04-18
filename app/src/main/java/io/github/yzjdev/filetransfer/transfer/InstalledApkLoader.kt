package io.github.yzjdev.filetransfer.transfer

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

fun loadInstalledApks(packageManager: PackageManager): List<InstalledApk> =
    packageManager.loadInstalledPackageInfos()
        .asSequence()
        .mapNotNull { packageInfo ->
            val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
            if (!appInfo.shouldIncludeInTransferList()) return@mapNotNull null
            val apkFile = File(appInfo.publicSourceDir ?: return@mapNotNull null)
            if (!apkFile.exists() || !apkFile.isFile) return@mapNotNull null
            InstalledApk(
                packageName = appInfo.packageName,
                label = packageManager.getApplicationLabel(appInfo).toString(),
                versionName = packageInfo.versionName.orEmpty(),
                apkPath = apkFile.absolutePath,
                sizeBytes = apkFile.length(),
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
        .toList()

private fun PackageManager.loadInstalledPackageInfos(): List<PackageInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getInstalledPackages(0)
    }

private fun ApplicationInfo.shouldIncludeInTransferList(): Boolean {
    if (packageName.isBlank()) return false
    val isPureSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
        (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
    if (isPureSystemApp) return false
    return true
}
