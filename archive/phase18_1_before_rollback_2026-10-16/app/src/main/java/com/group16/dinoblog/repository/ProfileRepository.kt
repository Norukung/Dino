package com.group16.dinoblog.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.group16.dinoblog.model.DisplayNames
import com.group16.dinoblog.model.RelayPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

enum class NearbyConnectionMethod { AUTO, WIFI_DIRECT, BLUETOOTH }

data class UserProfile(val displayName: String, val imagePath: String?, val onboardingCompleted: Boolean,
    val imageRevision: Long, val relayEnabled: Boolean, val nearbyDiscoverableEnabled: Boolean,
    val nearbyPromptShown: Boolean, val nearbyConnectionMethod: NearbyConnectionMethod = NearbyConnectionMethod.AUTO,
    val showNearbyDisplayName: Boolean = true,
    val defaultRelayPolicy: RelayPolicy = RelayPolicy.ALLOW_RELAY)

/** App-only profile. DeviceIdentity remains the immutable routing identity. */
class ProfileRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("chat_preferences", Context.MODE_PRIVATE)
    private val imageFile = File(context.filesDir, "profile.jpg")
    private var imageRevision = 0L
    private val mutableProfile = MutableStateFlow(read())
    val profile = mutableProfile.asStateFlow()
    val displayName: String get() = mutableProfile.value.displayName
    val relayEnabled: Boolean get() = mutableProfile.value.relayEnabled

    private fun read() = UserProfile(
        DisplayNames.normalize(preferences.getString("display_name", null)) ?: DisplayNames.FALLBACK,
        imageFile.absolutePath.takeIf { imageFile.exists() },
        preferences.getBoolean("onboarding_completed", false),
        imageRevision,
        preferences.getBoolean("relay_enabled", true),
        preferences.getBoolean("nearby_discoverable_enabled", false),
        preferences.getBoolean("nearby_prompt_shown", false),
        runCatching { NearbyConnectionMethod.valueOf(preferences.getString("nearby_connection_method", "AUTO") ?: "AUTO") }
            .getOrDefault(NearbyConnectionMethod.AUTO),
        preferences.getBoolean("show_nearby_display_name", true),
        runCatching { RelayPolicy.valueOf(preferences.getString("default_relay_policy", "ALLOW_RELAY") ?: "ALLOW_RELAY") }
            .getOrDefault(RelayPolicy.ALLOW_RELAY),
    )

    fun setDisplayName(raw: String): Boolean {
        val name = DisplayNames.normalize(raw) ?: return false
        if (!preferences.edit().putString("display_name", name).commit()) return false
        mutableProfile.value = read()
        return true
    }

    fun completeOnboarding(name: String?, relayEnabled: Boolean = true): Boolean {
        val selected = if (name.isNullOrBlank()) DisplayNames.FALLBACK else DisplayNames.normalize(name) ?: return false
        if (!preferences.edit().putString("display_name", selected).putBoolean("onboarding_completed", true)
                .putBoolean("relay_enabled", relayEnabled).commit()) return false
        mutableProfile.value = read()
        return true
    }

    fun setRelayEnabled(enabled: Boolean): Boolean {
        if (!preferences.edit().putBoolean("relay_enabled", enabled).commit()) return false
        mutableProfile.value = read()
        return true
    }

    fun setDefaultRelayPolicy(policy: RelayPolicy): Boolean {
        if (!preferences.edit().putString("default_relay_policy", policy.name).commit()) return false
        mutableProfile.value = read()
        return true
    }

    fun setNearbyDiscoverableEnabled(enabled: Boolean): Boolean {
        if (!preferences.edit().putBoolean("nearby_discoverable_enabled", enabled).commit()) return false
        mutableProfile.value = read()
        return true
    }

    fun setShowNearbyDisplayName(enabled: Boolean): Boolean {
        if (!preferences.edit().putBoolean("show_nearby_display_name", enabled).commit()) return false
        mutableProfile.value = read()
        return true
    }

    fun markNearbyPromptShown(): Boolean {
        if (!preferences.edit().putBoolean("nearby_prompt_shown", true).commit()) return false
        mutableProfile.value = read()
        return true
    }

    fun setNearbyConnectionMethod(method: NearbyConnectionMethod): Boolean {
        if (!preferences.edit().putString("nearby_connection_method", method.name).commit()) return false
        mutableProfile.value = read()
        return true
    }

    fun saveImage(uri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val size = info.size
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(maxOf(1, maxOf(size.width, size.height) / 512))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512) }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("Cannot open image")
            val orientation = resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                }
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        }
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, minOf(side, 512), minOf(side, 512), true)
        val temp = File(context.filesDir, "profile.tmp")
        for (quality in listOf(82, 68, 52)) {
            FileOutputStream(temp).use { output ->
                require(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output))
            }
            if (temp.length() <= 1_048_576) break
        }
        require(temp.length() in 1..1_048_576)
        require(temp.renameTo(imageFile) || temp.copyTo(imageFile, overwrite = true).let { temp.delete(); true })
        if (scaled !== square) scaled.recycle()
        if (square !== bitmap) square.recycle()
        bitmap.recycle()
        imageRevision++
        mutableProfile.value = read()
        true
    }.getOrDefault(false)

    fun removeImage() {
        imageFile.delete()
        imageRevision++
        mutableProfile.value = read()
    }
}
