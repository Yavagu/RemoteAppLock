package com.example.remoteapplock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/** Lists only user-launchable apps; no broad QUERY_ALL_PACKAGES permission needed. */
object AppScanner {
    data class InstalledApp(val packageName: String, val name: String)

    @Suppress("DEPRECATION")
    fun getLaunchableApps(context: Context): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        val results = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
        return results.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            InstalledApp(pkg, info.loadLabel(pm)?.toString()?.take(100) ?: pkg)
        }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
    }

    fun asJson(apps: List<InstalledApp>): JSONArray = JSONArray().also { out ->
        apps.forEach { app -> out.put(JSONObject().put("packageName", app.packageName).put("name", app.name)) }
    }
}
