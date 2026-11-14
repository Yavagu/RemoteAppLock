package com.example.remoteapplock

import java.util.Locale

/** Payment and system exclusions take precedence over every remote lock. */
class OverlaySafetyPolicy(
    private val launchableApps: Map<String, String>,
    private val sensitivePackages: Set<String>
) {
    fun canCover(pkg: String?, activity: String?, locked: Boolean): Boolean {
        if (!locked || pkg == null || activity.isNullOrBlank()) return false
        val label = launchableApps[pkg] ?: return false // Unknown/system-only surfaces fail open.
        return pkg !in sensitivePackages && !isSensitivePackage(pkg, label) && !isSensitiveActivity(activity)
    }

    companion object {
        // Fallbacks work even if package visibility or UPI intent discovery is incomplete.
        private val paymentPackages = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay India
            "com.google.android.apps.walletnfcrel", // Google Wallet
            "com.phonepe.app", "com.phonepe.app.business",
            "net.one97.paytm", "com.paytm.business",
            "in.org.npci.upiapp", // BHIM
            "com.dreamplug.androidapp", // CRED
            "in.amazon.mshop.android.shopping", // Includes Amazon Pay (normalized to lower case)
            "com.mobikwik_new", "com.freecharge.android", "com.whatsapp", "com.whatsapp.w4b"
        )
        private val systemPackages = setOf(
            "android", "com.android.systemui", "com.android.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.miui.packageinstaller", "com.miui.securitycenter",
            "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.android.credentialmanager", "com.google.android.gms", "com.android.vending"
        )
        private val sensitivePackageWords = Regex(
            "(^|[._])(bank[a-z]*|[a-z]*bank|banking|upi|npci|hdfc[a-z]*|icici[a-z]*|sbi|yono[a-z]*|kotak[a-z]*|axis[a-z]*|idfc[a-z]*|bob[a-z]*|pnb[a-z]*|canara[a-z]*|unionbank[a-z]*|indusind[a-z]*|yesbank[a-z]*)([._]|$)"
        )
        private val sensitiveLabelWords = Regex(
            "\\b(bank[a-z]*|banking|upi|bhim|phonepe|paytm|gpay|google pay|google wallet|amazon pay|hdfc|icici|sbi|yono|kotak|axis|idfc|bob world|pnb|canara|indusind|yes bank|cred|mobikwik|freecharge)\\b"
        )
        private val sensitiveActivityWords = listOf(
            "npci", "upipin", "upiactivity", "upipayment", "upitransaction", ".upi.",
            "payment", "checkout", "billing", "banking", "bankaccount", "razorpay", "cashfree",
            "permission", "grantpermission", "credential", "biometric", "keyguard", "confirmdevicecredential",
            "resolveractivity", "chooseractivity", "recentsactivity", "fallbackrecents", "emergencydialer"
        )

        fun isSensitivePackage(pkg: String, label: String): Boolean {
            val normalized = pkg.lowercase(Locale.ROOT)
            return normalized in paymentPackages || normalized in systemPackages ||
                sensitivePackageWords.containsMatchIn(normalized) ||
                sensitiveLabelWords.containsMatchIn(label.lowercase(Locale.ROOT))
        }

        fun isSensitiveActivity(activity: String): Boolean {
            val normalized = activity.lowercase(Locale.ROOT)
            return sensitiveActivityWords.any { it in normalized }
        }
    }
}
