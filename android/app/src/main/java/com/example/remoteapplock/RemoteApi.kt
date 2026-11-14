package com.example.remoteapplock

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Blocking network calls: invoke only from background threads. */
object RemoteApi {
    private fun call(url: String, path: String, token: String? = null, body: JSONObject? = null): JSONObject {
        require(url.startsWith("https://")) { "A deployed HTTPS Worker URL is required" }
        val conn = (URL(url.trimEnd('/') + path).openConnection() as HttpURLConnection)
        conn.requestMethod = if (body == null) "GET" else "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val parsed = JSONObject(response)
            if (conn.responseCode !in 200..299) throw IllegalStateException(parsed.optString("error", "HTTP ${conn.responseCode}"))
            return parsed
        } finally { conn.disconnect() }
    }

    fun uploadApps(url: String, token: String, apps: List<AppScanner.InstalledApp>): Int {
        // Keep each request below D1's free-tier per-invocation query ceiling.
        var uploaded = 0
        for (chunk in apps.chunked(30)) {
            uploaded += call(url, "/api/device/apps", token,
                JSONObject().put("apps", AppScanner.asJson(chunk))).getInt("uploaded")
        }
        return uploaded
    }

    fun state(url: String, token: String): JSONArray = call(url, "/api/device/state", token).getJSONArray("locks")

    fun verify(url: String, token: String, pkg: String, otp: String): Boolean =
        call(url, "/api/device/verify", token, JSONObject().put("packageName", pkg).put("otp", otp)).optBoolean("unlocked", false)
}
