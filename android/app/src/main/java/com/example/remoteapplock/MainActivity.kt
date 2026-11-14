package com.example.remoteapplock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private enum class SetupStep { USAGE, OVERLAY, NOTIFICATIONS, BACKGROUND, COMPLETE }

    private lateinit var store: LocalStore
    private val busy = AtomicBoolean(false)
    private val backgroundColor = Color.rgb(248, 246, 243)
    private val ink = Color.rgb(38, 32, 33)
    private val muted = Color.rgb(111, 101, 98)
    private val accent = Color.rgb(218, 91, 105)
    private val softPink = Color.rgb(252, 235, 238)
    private val success = Color.rgb(45, 139, 91)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalStore(this)
        renderSetup()
        syncSilently()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) {
            renderSetup()
            if (ProtectionPermissions.canMonitor(this)) startProtectionKeeper()
            else stopService(Intent(this, ProtectionKeeperService::class.java))
            syncSilently()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST) renderSetup()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        includeFontPadding = false
    }

    private fun action(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        background = rounded(accent, 15)
        setOnClickListener { click() }
        stateListAnimator = null
    }

    private fun currentStep(): SetupStep = when {
        !ProtectionPermissions.hasUsageAccess(this) -> SetupStep.USAGE
        !Settings.canDrawOverlays(this) -> SetupStep.OVERLAY
        needsNotificationPermission() -> SetupStep.NOTIFICATIONS
        !ignoresBatteryOptimizations() -> SetupStep.BACKGROUND
        else -> SetupStep.COMPLETE
    }

    private fun renderSetup() {
        val outer = ScrollView(this).apply {
            setBackgroundColor(backgroundColor)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(34), dp(24), dp(32))
        }
        outer.addView(root)
        setContentView(outer)

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.carrotlover_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.WHITE, 25)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(8).toFloat()
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(26) })

        val hello = text("Hi 👋", 36f, ink, true).apply { gravity = Gravity.CENTER }
        root.addView(hello)
        root.addView(text("Let’s get CarrotLover ready for you.", 16f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(30))
        })

        val step = currentStep()
        if (step == SetupStep.COMPLETE) {
            renderComplete(root)
        } else {
            renderPermission(root, step)
        }
    }

    private fun renderPermission(root: LinearLayout, step: SetupStep) {
        val number = when (step) {
            SetupStep.USAGE -> 1
            SetupStep.OVERLAY -> 2
            SetupStep.NOTIFICATIONS -> 3
            SetupStep.BACKGROUND -> 4
            SetupStep.COMPLETE -> 4
        }
        val progress = text("STEP $number OF 4", 12f, accent, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .12f
            background = rounded(softPink, 50)
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        root.addView(progress, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(27), dp(24), dp(24))
            background = rounded(Color.WHITE, 24)
            elevation = dp(3).toFloat()
        }
        root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2))

        val icon: String
        val title: String
        val body: String
        val button: String
        val click: () -> Unit
        when (step) {
            SetupStep.USAGE -> {
                icon = "✨"
                title = "Detect selected apps"
                body = "Usage Access lets CarrotLover identify which app is open so it can apply your dashboard’s locks. It does not read the text on your screen. Accessibility is no longer needed."
                button = "Allow Usage Access"
                click = { openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
            SetupStep.OVERLAY -> {
                icon = "🔒"
                title = "Show your focus screen"
                body = "Allow display over other apps to show a sliding access-code screen when a locked app opens. The screen is removed when you unlock or leave that app."
                button = "Allow display over other apps"
                click = { openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            }
            SetupStep.NOTIFICATIONS -> {
                icon = "💬"
                title = "Allow notifications"
                body = "A quiet ongoing notification shows that monitoring is running. CarrotLover does not need access to read other apps’ notifications."
                button = "Allow notifications"
                click = { requestNotificationPermission() }
            }
            SetupStep.BACKGROUND -> {
                icon = "🌱"
                title = "Keep it running"
                body = "Allow unrestricted battery use to help monitoring continue in the background. Your phone may also have separate Autostart and background pop-up settings."
                button = "Allow background operation"
                click = { requestBatteryExemption() }
            }
            SetupStep.COMPLETE -> return
        }

        card.addView(text(icon, 34f).apply { gravity = Gravity.CENTER })
        card.addView(text(title, 23f, ink, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(12))
        })
        card.addView(text(body, 14f, muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.18f)
        })
        card.addView(action(button, click), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(25)
        })
    }

    private fun renderComplete(root: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(31), dp(26), dp(31))
            background = rounded(Color.WHITE, 24)
            elevation = dp(3).toFloat()
        }
        root.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2))
        card.addView(text("✓", 36f, success, true).apply { gravity = Gravity.CENTER })
        card.addView(text("Permissions are ready", 24f, ink, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(10))
        })
        val status = if (store.isConfigured()) {
            "Open an app locked on your dashboard to check the focus screen. Remote commands sync about every 15 seconds while online."
        } else {
            "This build is not connected to a dashboard. Configure the Worker URL and device token, then rebuild."
        }
        card.addView(text(status, 14f, muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.18f)
        })
        card.addView(text("Payment protection: known banking apps, detected UPI apps, Home, Settings and sensitive system screens are excluded from locking, even if selected on your dashboard. Returning to another locked app brings its focus screen back.", 14f, muted).apply {
            setPadding(0, dp(18), 0, 0)
        })
        card.addView(text("On Xiaomi / Redmi", 18f, ink, true).apply { setPadding(0, dp(24), 0, dp(10)) })
        card.addView(text("In App info → Other permissions, enable ‘Open new windows while running in the background’ (or ‘Display pop-up windows while running in the background’). Enable Autostart and set battery use to No restrictions. Names vary by phone; these settings cannot be checked automatically.", 14f, muted))
        card.addView(action("Open app settings") { openAppSettings() }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(18) })
        card.addView(action("Open Autostart settings") { openAutostartSettings() }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) })
        card.addView(text("Updating from the old version? Accessibility is no longer used. If CarrotLover Protection is still listed in Accessibility settings, leave it off.", 13f, muted).apply { setPadding(0, dp(20), 0, 0) })
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: RuntimeException) { openAppSettings() }
    }

    private fun openAutostartSettings() {
        try {
            startActivity(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        } catch (_: RuntimeException) {
            openAppSettings()
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        } else {
            renderSetup()
        }
    }

    private fun ignoresBatteryOptimizations(): Boolean {
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        return manager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(direct)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun startProtectionKeeper() {
        try {
            ProtectionKeeperService.start(this)
        } catch (_: RuntimeException) {
            Toast.makeText(this, "Could not start protection. Check background permissions and reopen the app.", Toast.LENGTH_LONG).show()
        }
    }

    private fun syncSilently() {
        if (!store.isConfigured() || !busy.compareAndSet(false, true)) return
        Thread {
            try {
                RemoteApi.uploadApps(store.baseUrl(), store.deviceToken(), AppScanner.getLaunchableApps(this))
                val state = RemoteApi.state(store.baseUrl(), store.deviceToken())
                // Serialize lock changes with monitoring and OTP completion on the main thread.
                runOnUiThread { store.replaceRemoteLocks(state) }
            } catch (_: Exception) {
                // The service preserves the last downloaded lock state while offline.
            } finally {
                busy.set(false)
            }
        }.start()
    }

    companion object {
        private const val NOTIFICATION_REQUEST = 2402
    }
}
