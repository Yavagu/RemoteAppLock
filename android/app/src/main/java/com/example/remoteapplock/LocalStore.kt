package com.example.remoteapplock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores the latest remote lock state for offline enforcement. */
class LocalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("remote_app_lock", Context.MODE_PRIVATE)

    data class Lock(val packageName: String, val expiresAt: Long)

    fun baseUrl(): String = BuildConfig.WORKER_URL
    fun deviceToken(): String = BuildConfig.DEVICE_TOKEN
    fun isConfigured(): Boolean = baseUrl().startsWith("https://") && deviceToken().length >= 32

    @Synchronized fun replaceRemoteLocks(json: JSONArray) {
        // Prevent an older in-flight HTTP poll from re-locking a just-verified app.
        // A future admin re-lock increments lockVersion and is still accepted.
        val ignoredVersions = ignoredVersions()
        val filtered = JSONArray()
        for (i in 0 until json.length()) {
            val item = json.optJSONObject(i) ?: continue
            val pkg = item.optString("packageName")
            if (item.optLong("lockVersion", 0L) > ignoredVersions.optLong(pkg, -1L)) {
                filtered.put(item)
            }
        }
        prefs.edit().putString("remoteLocks", filtered.toString()).apply()
    }

    fun lockVersion(pkg: String): Long? {
        val all = remoteLocks()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optString("packageName") == pkg) return item.optLong("lockVersion", 0L)
        }
        return null
    }

    @Synchronized fun clearRemoteLock(pkg: String, verifiedVersion: Long) {
        val all = remoteLocks()
        val result = JSONArray()
        val ignored = ignoredVersions()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optString("packageName") != pkg || item.optLong("lockVersion", 0L) > verifiedVersion) {
                result.put(item)
            }
        }
        // Remember the verified version even if a concurrent poll already removed it.
        ignored.put(pkg, maxOf(ignored.optLong(pkg, -1L), verifiedVersion))
        prefs.edit()
            .putString("remoteLocks", result.toString())
            .putString("ignoredVersions", ignored.toString())
            .apply()
    }

    private fun ignoredVersions(): JSONObject = try {
        JSONObject(prefs.getString("ignoredVersions", "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun remoteLocks(): JSONArray = try {
        JSONArray(prefs.getString("remoteLocks", "[]") ?: "[]")
    } catch (_: Exception) { JSONArray() }

    fun isRemoteLocked(pkg: String): Boolean {
        val all = remoteLocks()
        val now = System.currentTimeMillis()
        for (i in 0 until all.length()) {
            val lock = all.optJSONObject(i) ?: continue
            if (lock.optString("packageName") == pkg && lock.optLong("expiresAt") > now) return true
        }
        return false
    }

    fun isLocked(pkg: String): Boolean = isRemoteLocked(pkg)
}
