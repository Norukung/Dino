package com.group16.dinoblog.online

import android.content.Context
import android.content.pm.ApplicationInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.group16.dinoblog.R
import com.group16.dinoblog.experiment.ExperimentManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class OnlineApi(private val context: Context, private val metrics: ExperimentManager? = null) {
    private val preferences = context.getSharedPreferences("online_transport", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val debug: Boolean get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val baseUrl: String get() = (if (debug) preferences.getString("debug_url", null) else null)
        ?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.dinoblog_server_base_url).trim().trimEnd('/')
    private fun preferenceKey(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(baseUrl.toByteArray())
        return name + ":" + digest.take(8).joinToString("") { "%02x".format(it) }
    }
    val token: String? get() = preferences.getString(preferenceKey("token"), null)?.let { encoded -> runCatching {
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, cryptoKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }.getOrNull() }
    var cursor: Long
        get() = preferences.getLong(preferenceKey("cursor"), 0L)
        set(value) { check(preferences.edit().putLong(preferenceKey("cursor"), value).commit()) }

    fun setDebugUrl(value: String) {
        require(debug)
        val url = value.trim().trimEnd('/')
        require(url.isEmpty() || url.startsWith("https://") || url.startsWith("http://"))
        preferences.edit().putString("debug_url", url).apply()
    }

    fun storeToken(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cryptoKey())
        val encoded = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()))
        check(preferences.edit().putString(preferenceKey("token"), encoded).commit())
    }

    private fun cryptoKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry("dinoblog-online-token", null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("dinoblog-online-token",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    fun request(method: String, path: String, payload: ByteArray? = null, contentType: String? = null,
        headers: Map<String, String> = emptyMap(), authenticated: Boolean = true): ByteArray {
        val base = baseUrl
        require(base.isNotEmpty()) { "Server URL is not configured" }
        require(debug || base.startsWith("https://")) { "HTTPS required" }
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 15_000
            useCaches = false
            if (authenticated) setRequestProperty("Authorization", "Bearer ${token ?: error("Not registered")}")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            metrics?.activeId()?.let { setRequestProperty("X-DinoBlog-Experiment-ID", it) }
            if (payload != null) {
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
            }
        }
        return try {
            val mediaPayload = path.startsWith("/api/v1/media/") &&
                (method == "PUT" || method == "GET")
            val logicalPayload = mediaPayload || path == "/api/v1/messages"
            if (payload != null) connection.outputStream.use { it.write(payload) }.also {
                runCatching { metrics?.bytes("SENT", "ONLINE", mediaPayload, payload.size.toLong(), logicalPayload) }
            }
            val code = connection.responseCode
            val bytes = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            runCatching { metrics?.bytes("RECEIVED", "ONLINE", mediaPayload && code in 200..299,
                bytes.size.toLong(), mediaPayload && code in 200..299) }
            if (code !in 200..299) throw OnlineHttpException(code, runCatching {
                json.parseToJsonElement(bytes.decodeToString()).jsonObject["error"].toString().trim('"')
            }.getOrDefault("HTTP_$code"))
            bytes
        } finally { connection.disconnect() }
    }

    fun json(method: String, path: String, payload: String? = null, authenticated: Boolean = true): JsonObject =
        json.parseToJsonElement(request(method, path, payload?.toByteArray(Charsets.UTF_8),
            if (payload == null) null else "application/json", authenticated = authenticated).decodeToString()).jsonObject

    fun jsonBytes(method: String, path: String, payload: ByteArray, contentType: String): JsonObject =
        json.parseToJsonElement(request(method, path, payload, contentType).decodeToString()).jsonObject
}

internal class OnlineHttpException(val status: Int, val code: String) : Exception(code)
