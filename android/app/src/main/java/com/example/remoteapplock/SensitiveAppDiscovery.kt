package com.example.remoteapplock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

object SensitiveAppDiscovery {
    @Suppress("DEPRECATION")
    fun load(context: Context): OverlaySafetyPolicy {
        val pm = context.packageManager
        val apps = AppScanner.getLaunchableApps(context).associate { it.packageName to it.name }
        val excluded = mutableSetOf(context.packageName)
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay")),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            Intent(Settings.ACTION_SETTINGS),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(Intent.ACTION_INSTALL_PACKAGE, Uri.parse("content://remoteapplock.invalid/package.apk"))
        )
        for (intent in intents) {
            pm.queryIntentActivities(intent, 0).forEach { info ->
                info.activityInfo?.packageName?.let(excluded::add)
            }
        }
        context.getSystemService(InputMethodManager::class.java).inputMethodList.forEach {
            excluded.add(it.packageName)
        }
        return OverlaySafetyPolicy(apps, excluded)
    }
}
