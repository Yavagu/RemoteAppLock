package com.example.remoteapplock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors

/** Usage Access detects apps; a normal application overlay supplies the OTP gate. */
class ProtectionKeeperService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val tracker = ForegroundAppTracker()
    private lateinit var store: LocalStore
    private lateinit var overlay: FocusLockOverlay
    private var safetyPolicy: OverlaySafetyPolicy? = null
    private var safetyRefreshAt = 0L
    private var running = false
    private var lastQuery = 0L
    private var lastPermissionCheck = 0L
    private var notificationStatus = "Monitoring selected apps"

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) overlay.hide()
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Do not use cached classifications after installation/update changes.
            safetyPolicy = null
            safetyRefreshAt = 0L
            overlay.hide()
        }
    }

    private fun canCover(pkg: String): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            tracker.packageName == pkg &&
            safetyPolicy?.canCover(pkg, tracker.activityName, store.isLocked(pkg)) == true

    private val monitorTask = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            try {
                if (now < lastPermissionCheck || now - lastPermissionCheck >= 2_000L) {
                    lastPermissionCheck = now
                    if (!ProtectionPermissions.canMonitor(this@ProtectionKeeperService)) {
                        stopSelf()
                        return
                    }
                }
                val interactive = getSystemService(PowerManager::class.java).isInteractive
                val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
                if (!interactive || locked) {
                    overlay.hide()
                } else {
                    if (safetyPolicy == null || now >= safetyRefreshAt || now < safetyRefreshAt - 300_000L) {
                        // Failed discovery leaves the policy empty, so nothing is covered.
                        safetyPolicy = null
                        safetyPolicy = SensitiveAppDiscovery.load(this@ProtectionKeeperService)
                        safetyRefreshAt = now + 300_000L
                    }
                    val foregroundAvailable = readForeground(now)
                    val pkg = tracker.packageName
                    if (foregroundAvailable && pkg != null && canCover(pkg)) {
                        overlay.show(pkg)
                    } else {
                        overlay.hide()
                    }
                    updateStatus("Monitoring selected apps")
                }
            } catch (e: RuntimeException) {
                overlay.hide()
                updateStatus("Protection needs attention — open CarrotLover")
                Log.w("CarrotLover", "Could not check foreground app or display lock", e)
            } finally {
                if (running) handler.postDelayed(this, if (getSystemService(PowerManager::class.java).isInteractive) 500L else 2_000L)
            }
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            if (!store.isConfigured()) {
                handler.postDelayed(this, 15_000L)
                return
            }
            network.execute {
                val state = try {
                    RemoteApi.state(store.baseUrl(), store.deviceToken())
                } catch (_: Exception) { null }
                handler.post {
                    if (!running) return@post
                    if (state != null) store.replaceRemoteLocks(state)
                    // Schedule after completion so slow requests never create a backlog.
                    handler.postDelayed(this, 15_000L)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readForeground(now: Long): Boolean {
        if (lastQuery == 0L || now < lastQuery) {
            tracker.reset()
            lastQuery = now - 86_400_000L
        }
        val events = getSystemService(UsageStatsManager::class.java).queryEvents(lastQuery - 2_000L, now) ?: return false
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val transition = when (event.eventType) {
                // These constants also work on API 26–28; renamed ACTIVITY_* on API 29.
                UsageEvents.Event.MOVE_TO_FOREGROUND -> ForegroundAppTracker.Transition.RESUMED
                UsageEvents.Event.MOVE_TO_BACKGROUND -> ForegroundAppTracker.Transition.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundAppTracker.Transition.STOPPED
                else -> continue
            }
            if (tracker.record(pkg, event.className, transition, event.timeStamp)) {
                // Process departures even if Recents -> target occurred within this same batch.
                // Only the final eligible foreground is shown after the complete batch is read.
                val covered = overlay.packageName
                if (covered != null && !canCover(covered)) overlay.hide()
            }
        }
        lastQuery = now
        return true
    }

    private fun updateStatus(status: String) {
        if (notificationStatus == status) return
        notificationStatus = status
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    companion object {
        private const val CHANNEL_ID = "remote_app_lock_protection"
        private const val NOTIFICATION_ID = 2401

        fun start(context: Context) {
            val intent = Intent(context, ProtectionKeeperService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = LocalStore(this)
        overlay = FocusLockOverlay(this, store, ::canCover)
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(packageReceiver, packageFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
            registerReceiver(packageReceiver, packageFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        safetyRefreshAt = 0L
        if (!ProtectionPermissions.canMonitor(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            handler.post(monitorTask)
            handler.post(pollTask)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        overlay.close()
        unregisterReceiver(screenReceiver)
        unregisterReceiver(packageReceiver)
        network.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CarrotLover background protection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps CarrotLover protection active in the background"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification_lock)
            .setContentTitle("CarrotLover focus protection")
            .setContentText(notificationStatus)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
