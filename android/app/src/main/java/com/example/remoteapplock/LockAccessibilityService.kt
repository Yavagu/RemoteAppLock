package com.example.remoteapplock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort launcher-app blocking using an Accessibility overlay.
 * Not a device-owner security boundary; users can turn off the service.
 */
class LockAccessibilityService : AccessibilityService() {
    private lateinit var store: LocalStore
    private lateinit var windows: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val polling = AtomicBoolean(false)
    private var overlay: LinearLayout? = null
    private var overlayPackage: String? = null
    private var lastForeground: String? = null
    private var running = false
    private var launchablePackages: Set<String> = emptySet()
    private var homePackages: Set<String> = emptySet()

    /**
     * Accessibility events normally enforce immediately. This watchdog covers
     * missed events and service/process recovery by checking only the active
     * window's package name every five seconds.
     */
    private val enforcementTask = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val currentPackage = rootInActiveWindow?.let { root ->
                    try { root.packageName?.toString() } finally { root.recycle() }
                }
                if (!currentPackage.isNullOrBlank() &&
                    currentPackage != packageName &&
                    currentPackage != "android" &&
                    currentPackage != "com.android.systemui") {
                    lastForeground = currentPackage
                    if (store.isLocked(currentPackage)) {
                        showOverlay(currentPackage)
                    } else if (overlay != null && overlayPackage?.let { !store.isLocked(it) } == true) {
                        hideOverlay()
                    }
                }
            } catch (_: Exception) {
                // Event-driven enforcement remains active if a window cannot be queried.
            } finally {
                if (running) handler.postDelayed(this, 5_000L)
            }
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            if (store.isConfigured() && polling.compareAndSet(false, true)) {
                Thread {
                    try {
                        val state = RemoteApi.state(store.baseUrl(), store.deviceToken())
                        store.replaceRemoteLocks(state)
                        handler.post {
                            val pkg = overlayPackage
                            if (pkg != null && !store.isLocked(pkg)) hideOverlay()
                            val foreground = lastForeground
                            if (foreground != null && store.isLocked(foreground)) showOverlay(foreground)
                        }
                    } catch (_: Exception) {
                        // Preserve the previously downloaded lock list when offline.
                    } finally { polling.set(false) }
                }.start()
            }
            // 15-second network polling is an MVP compromise. FCM is a future upgrade.
            handler.postDelayed(this, 15_000L)
        }
    }

    @Suppress("DEPRECATION")
    override fun onServiceConnected() {
        super.onServiceConnected()
        try { ProtectionKeeperService.start(this) } catch (_: Exception) { }
        store = LocalStore(this)
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        launchablePackages = AppScanner.getLaunchableApps(this).map { it.packageName }.toSet()
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homePackages = packageManager.queryIntentActivities(homeIntent, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        running = true
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(enforcementTask)
        handler.post(pollTask)
        handler.post(enforcementTask)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::store.isInitialized || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui" ||
            pkg.contains("inputmethod", ignoreCase = true) || pkg.contains("keyboard", ignoreCase = true)) return
        // Ignore soft-keyboard and system popups: otherwise the OTP screen can
        // disappear as soon as the user starts entering a code.
        if (pkg !in launchablePackages && pkg !in homePackages && !store.isLocked(pkg)) return
        val pendingPackage = overlayPackage
        if (pendingPackage != null && overlay != null) {
            if (!store.isLocked(pendingPackage)) hideOverlay()
            // Keep the authentication screen visible after the target app is
            // moved to Home; do not dismiss it because the launcher appeared.
            return
        }
        lastForeground = pkg
        if (store.isLocked(pkg)) showOverlay(pkg)
        else if (overlay != null) hideOverlay()
    }

    override fun onInterrupt() { hideOverlay() }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep polling when the launcher activity is removed from Recents.
        if (running) {
            handler.removeCallbacks(pollTask)
            handler.removeCallbacks(enforcementTask)
            handler.post(pollTask)
            handler.post(enforcementTask)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(enforcementTask)
        hideOverlay()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int = 14): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        gravity = Gravity.CENTER_HORIZONTAL
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun action(label: String, color: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE)
        background = bg(color)
        setOnClickListener { onClick() }
    }

    private fun showOverlay(pkg: String) {
        if (!::windows.isInitialized || !store.isLocked(pkg)) return
        if (overlayPackage == pkg && overlay != null) return
        hideOverlay()
        overlayPackage = pkg

        val white = Color.WHITE
        val muted = Color.rgb(170, 185, 208)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20))
            setBackgroundColor(Color.rgb(19, 25, 39))
        }
        val appName = AppScanner.getLaunchableApps(this).firstOrNull { it.packageName == pkg }?.name ?: pkg
        root.addView(text("🔒", 50f, white), LinearLayout.LayoutParams(-1, dp(75)))
        root.addView(text("$appName is paused", 27f, white, true))
        val message = text("The app was restarted. Enter the 6-digit access code to continue.", 15f, muted)
        message.setPadding(0, dp(12), 0, dp(28))
        root.addView(message)

        val input = EditText(this).apply {
            hint = "6-digit OTP"
            textSize = 25f
            setTextColor(Color.rgb(20, 27, 41))
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            background = bg(Color.WHITE, 10)
        }
        root.addView(input, LinearLayout.LayoutParams(-1, dp(60)))
        val feedback = text("", 13f, muted)
        feedback.setPadding(0, dp(10), 0, dp(12))
        root.addView(feedback)
        val submit = action("Unlock app", Color.rgb(74, 94, 225)) { }
        // Replace the button listener after creation, so it can disable itself while verifying.
        submit.setOnClickListener {
            val entered = input.text.toString().trim()
            if (entered.length != 6) { feedback.text = "Please enter all 6 digits"; return@setOnClickListener }
            if (!store.isConfigured()) { feedback.text = "This app build is not configured"; return@setOnClickListener }
            submit.isEnabled = false
            feedback.text = "Checking code..."
            Thread {
                val result = try {
                    if (RemoteApi.verify(store.baseUrl(), store.deviceToken(), pkg, entered)) {
                        store.clearRemoteLock(pkg)
                        null
                    } else "Verification was not accepted"
                } catch (e: Exception) { e.message ?: "Network unavailable. Try again online." }
                handler.post {
                    if (overlayPackage != pkg) return@post
                    submit.isEnabled = true
                    if (result == null) {
                        if (!store.isLocked(pkg)) {
                            hideOverlay()
                        } else feedback.text = "Another lock is still active"
                    } else feedback.text = result
                }
            }.start()
        }
        root.addView(submit, LinearLayout.LayoutParams(-1, dp(54)))
        val home = action("Go to home screen", Color.rgb(65, 74, 96)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            lastForeground = null
            hideOverlay()
        }
        val homeLayout = LinearLayout.LayoutParams(-1, dp(54))
        homeLayout.topMargin = dp(14)
        root.addView(home, homeLayout)
        val subtitle = text("Protection is active while the app is locked.", 12f, muted)
        subtitle.setPadding(0, dp(20), 0, 0)
        root.addView(subtitle)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        try {
            windows.addView(root, params)
            overlay = root
            // Attach the blocking UI first, then reset/relaunch the target
            // behind it. Its fresh activity cannot be used until OTP succeeds.
            handler.post { freshRelaunchBehindOverlay(pkg) }
        } catch (_: Exception) {
            overlayPackage = null
            overlay = null
        }
    }

    private fun hideOverlay() {
        overlay?.let { try { windows.removeView(it) } catch (_: Exception) {} }
        overlay = null
        overlayPackage = null
    }

    private fun moveTargetToBackground() {
        if (performGlobalAction(GLOBAL_ACTION_HOME)) return
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) { }
    }

    private fun freshRelaunchBehindOverlay(pkg: String) {
        if (overlayPackage != pkg || overlay == null || !store.isLocked(pkg)) return
        try {
            val launch = packageManager.getLaunchIntentForPackage(pkg) ?: run {
                moveTargetToBackground()
                return
            }
            // Clear only the target's activity task and immediately recreate its
            // launcher activity under the OTP overlay. The process, receivers,
            // services, and notification-capable background work are not stopped.
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(launch)
        } catch (_: Exception) {
            moveTargetToBackground()
        }
    }
}
