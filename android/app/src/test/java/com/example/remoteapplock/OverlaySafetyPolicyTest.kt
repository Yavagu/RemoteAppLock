package com.example.remoteapplock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySafetyPolicyTest {
    private fun policy(pkg: String, label: String = "Example", excluded: Set<String> = emptySet()) =
        OverlaySafetyPolicy(mapOf(pkg to label), excluded)

    @Test fun `common payment apps cannot be covered even when remotely locked`() {
        for (pkg in listOf(
            "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
            "in.org.npci.upiapp", "com.google.android.apps.walletnfcrel", "com.dreamplug.androidapp",
            "in.amazon.mShop.android.shopping", "com.whatsapp"
        )) {
            assertFalse(pkg, policy(pkg).canCover(pkg, "MainActivity", true))
        }
    }

    @Test fun `new UPI handlers discovered on the device are also excluded`() {
        val pkg = "in.example.newpayments"
        assertFalse(policy(pkg, excluded = setOf(pkg)).canCover(pkg, "Home", true))
    }

    @Test fun `bank identifiers and labels cover providers outside the fixed list`() {
        assertFalse(policy("com.hdfc.mobile").canCover("com.hdfc.mobile", "Home", true))
        assertFalse(policy("com.sbi.lotusintouch").canCover("com.sbi.lotusintouch", "Home", true))
        assertFalse(policy("com.example.mobile", "Example Bank Mobile").canCover("com.example.mobile", "Home", true))
    }

    @Test fun `system surfaces and OEM resolved launchers are excluded`() {
        for (pkg in listOf("android", "com.android.systemui", "com.android.settings",
            "com.google.android.permissioncontroller", "com.miui.securitycenter", "com.google.android.gms")) {
            assertFalse(pkg, policy(pkg).canCover(pkg, "MainActivity", true))
        }
        val launcher = "vendor.custom.launcher"
        assertFalse(policy(launcher, excluded = setOf(launcher)).canCover(launcher, "HomeActivity", true))
    }

    @Test fun `embedded payment and credential activities are excluded inside an otherwise lockable app`() {
        val pkg = "com.example.shopping"
        val policy = policy(pkg)
        assertTrue(policy.canCover(pkg, "com.example.Home", true))
        for (activity in listOf("org.npci.upiapp.PayActivity", "com.example.UpiPinActivity",
            "com.example.PaymentActivity", "com.example.CheckoutActivity", "com.example.BiometricActivity",
            "com.android.internal.app.ResolverActivity", "com.example.CredentialActivity")) {
            assertFalse(activity, policy.canCover(pkg, activity, true))
        }
        assertTrue(policy.canCover(pkg, "com.example.Home", true))
    }

    @Test fun `unknown foreground or missing activity information never produces an overlay`() {
        val policy = policy("com.example.video")
        assertFalse(policy.canCover(null, "Home", true))
        assertFalse(policy.canCover("com.unlisted.app", "Home", true))
        assertFalse(policy.canCover("com.example.video", null, true))
        assertFalse(policy.canCover("com.example.video", "", true))
    }

    @Test fun `ordinary locked apps remain eligible after visiting a payment app`() {
        val video = "com.google.android.youtube"
        val payment = "com.phonepe.app"
        val policy = OverlaySafetyPolicy(mapOf(video to "YouTube", payment to "PhonePe"), emptySet())
        assertTrue(policy.canCover(video, "PlayerActivity", true))
        assertFalse(policy.canCover(payment, "HomeActivity", true))
        assertTrue(policy.canCover(video, "PlayerActivity", true))
        assertFalse(policy.canCover(video, "PlayerActivity", false))
    }
}
