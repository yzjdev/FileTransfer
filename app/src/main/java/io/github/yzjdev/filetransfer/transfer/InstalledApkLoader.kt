package io.github.yzjdev.filetransfer.transfer

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

fun loadInstalledApks(packageManager: PackageManager): List<InstalledApk> {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
    }

    return activities
        .mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
            val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystemApp || isUpdatedSystemApp) return@mapNotNull null
            val apkFile = File(appInfo.publicSourceDir ?: return@mapNotNull null)
            if (!apkFile.exists()) return@mapNotNull null
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    appInfo.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(appInfo.packageName, 0)
            }
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
}
