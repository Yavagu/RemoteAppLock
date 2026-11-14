package com.example.remoteapplock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ProtectionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!ProtectionPermissions.canMonitor(context)) return
        try {
            ProtectionKeeperService.start(context)
        } catch (e: RuntimeException) {
            // OEM background restrictions may require the user to reopen the app.
            Log.w("CarrotLover", "Reopen the app to resume protection", e)
        }
    }
}
