package com.example.remoteapplock

import android.content.Context
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
import android.view.KeyEvent
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** A normal application overlay: no Accessibility access or background activity launch needed. */
class FocusLockOverlay(
    private val context: Context,
    private val store: LocalStore,
    private val canCover: (String) -> Boolean
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val verification = Executors.newSingleThreadExecutor()
    private var view: FrameLayout? = null
    private var closed = false
    var packageName: String? = null
        private set

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun show(pkg: String) {
        // Last guard at the window boundary: callers cannot override payment/system exclusions.
        if (!canCover(pkg)) {
            hide()
            return
        }
        if (closed || !store.isLocked(pkg) || (packageName == pkg && view != null)) return
        hide()
        val ink = Color.rgb(38, 32, 33)
        val muted = Color.rgb(111, 101, 98)
        val accent = Color.rgb(218, 91, 105)
        val background = Color.rgb(248, 246, 243)
        fun text(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
            this.text = value
            textSize = size
            setTextColor(if (bold) ink else muted)
            gravity = Gravity.CENTER
            if (bold) setTypeface(null, Typeface.BOLD)
        }
        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) goHome()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            // The full-size root consumes touches even while its content slides into place.
            setBackgroundColor(background)
            isClickable = true
            isFocusableInTouchMode = true
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(background)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(32))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val appName = AppScanner.getLaunchableApps(context).firstOrNull { it.packageName == pkg }?.name ?: pkg
        content.addView(text("Stay in focus", 32f, true))
        content.addView(text("$appName is locked", 21f, true).apply {
            setPadding(0, dp(16), 0, dp(12))
        })
        content.addView(text("Take a breath. Return to your day, or enter the 6-digit access code to continue.", 16f).apply {
            setPadding(0, 0, 0, dp(28))
        })
        val input = EditText(context).apply {
            hint = "6-digit access code"
            textSize = 22f
            setTextColor(ink)
            setHintTextColor(muted)
            setSingleLine(true)
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(6))
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            this.background = rounded(Color.WHITE)
        }
        content.addView(input, LinearLayout.LayoutParams(-1, dp(60)))
        val feedback = text("", 14f).apply { setPadding(0, dp(12), 0, dp(12)) }
        content.addView(feedback)
        val submit = Button(context).apply {
            text = "Unlock app"
            isAllCaps = false
            setTextColor(Color.WHITE)
            this.background = rounded(accent)
        }
        submit.setOnClickListener {
            val entered = input.text.toString().trim()
            if (entered.length != 6 || !entered.all { it in '0'..'9' }) {
                feedback.text = "Please enter all 6 digits"
                return@setOnClickListener
            }
            if (!store.isConfigured()) {
                feedback.text = "This app build is not configured"
                return@setOnClickListener
            }
            val verifiedVersion = store.lockVersion(pkg) ?: return@setOnClickListener
            submit.isEnabled = false
            feedback.text = "Checking code…"
            verification.execute {
                val accepted = try {
                    RemoteApi.verify(store.baseUrl(), store.deviceToken(), pkg, entered)
                } catch (_: Exception) { null }
                handler.post {
                    // Apply state even if the user switched apps during verification.
                    if (accepted == true) store.clearRemoteLock(pkg, verifiedVersion)
                    if (view !== root || closed) return@post
                    submit.isEnabled = true
                    when (accepted) {
                        true -> if (!store.isLocked(pkg)) hide() else {
                            feedback.text = "A new lock was applied. Request its code on your dashboard."
                        }
                        false -> feedback.text = "Code not accepted. Check or renew it on your dashboard."
                        null -> feedback.text = "Could not connect. Check your internet and try again."
                    }
                }
            }
        }
        content.addView(submit, LinearLayout.LayoutParams(-1, dp(56)))
        val home = Button(context).apply {
            text = "Back to focus"
            isAllCaps = false
            setTextColor(ink)
            this.background = rounded(Color.rgb(239, 228, 225))
            setOnClickListener { goHome() }
        }
        content.addView(home, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) })
        content.addView(text("CarrotLover • Your private app lock", 12f).apply {
            setPadding(0, dp(24), 0, 0)
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "CarrotLover Focus Lock"
        }
        if (!canCover(pkg)) return
        windows.addView(root, params)
        view = root
        packageName = pkg
        root.requestFocus()
        scroll.post {
            if (view !== root) return@post
            scroll.translationY = root.height.toFloat()
            scroll.animate().translationY(0f).setDuration(280L).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun goHome() {
        try {
            context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            // Keep the lock attached until Usage Access observes the departure.
        } catch (_: RuntimeException) {
            android.widget.Toast.makeText(context, "Use your phone’s Home button or gesture", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun hide() {
        val previous = view
        view = null
        packageName = null
        previous?.let {
            try { windows.removeViewImmediate(it) } catch (_: IllegalArgumentException) { }
        }
    }

    fun close() {
        closed = true
        hide()
        verification.shutdownNow()
    }
}
