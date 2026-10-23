package com.group16.dinoblog

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.repository.ProfileRepository
import com.group16.dinoblog.repository.NearbyConnectionMethod
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase16NearbyPreferenceTest {
    @Test fun nearbyNameVisibilityDefaultsOnAndPersistsIndependentlyOfDiscoverability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("chat_preferences", Context.MODE_PRIVATE)
        val previousName = if (prefs.contains("show_nearby_display_name"))
            prefs.getBoolean("show_nearby_display_name", true) else null
        val previousDiscovery = if (prefs.contains("nearby_discoverable_enabled"))
            prefs.getBoolean("nearby_discoverable_enabled", false) else null
        val deviceId = com.group16.dinoblog.util.DeviceIdentity(context).id
        try {
            prefs.edit().remove("show_nearby_display_name").remove("nearby_discoverable_enabled").commit()
            val initial = ProfileRepository(context).profile.value
            assertTrue(initial.showNearbyDisplayName)
            assertFalse(initial.nearbyDiscoverableEnabled)
            assertTrue(ProfileRepository(context).setShowNearbyDisplayName(false))
            assertFalse(ProfileRepository(context).profile.value.showNearbyDisplayName)
            assertFalse(ProfileRepository(context).profile.value.nearbyDiscoverableEnabled)
            assertEquals(deviceId, com.group16.dinoblog.util.DeviceIdentity(context).id)
        } finally {
            val editor = prefs.edit()
            if (previousName == null) editor.remove("show_nearby_display_name")
            else editor.putBoolean("show_nearby_display_name", previousName)
            if (previousDiscovery == null) editor.remove("nearby_discoverable_enabled")
            else editor.putBoolean("nearby_discoverable_enabled", previousDiscovery)
            editor.commit()
        }
    }

    @Test fun nearbyTransportPreferencePersistsWithoutChangingIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("chat_preferences", Context.MODE_PRIVATE)
        val previous = prefs.getString("nearby_connection_method", null)
        val deviceId = com.group16.dinoblog.util.DeviceIdentity(context).id
        try {
            prefs.edit().remove("nearby_connection_method").commit()
            assertEquals(NearbyConnectionMethod.AUTO, ProfileRepository(context).profile.value.nearbyConnectionMethod)
            assertTrue(ProfileRepository(context).setNearbyConnectionMethod(NearbyConnectionMethod.BLUETOOTH))
            assertEquals(NearbyConnectionMethod.BLUETOOTH, ProfileRepository(context).profile.value.nearbyConnectionMethod)
            assertEquals(deviceId, com.group16.dinoblog.util.DeviceIdentity(context).id)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove("nearby_connection_method") else editor.putString("nearby_connection_method", previous)
            editor.commit()
        }
    }
    @Test fun discoverabilityIsOptInAndPersistsWithoutChangingDeviceIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("chat_preferences", Context.MODE_PRIVATE)
        val previous = if (prefs.contains("nearby_discoverable_enabled")) prefs.getBoolean("nearby_discoverable_enabled", false) else null
        val deviceId = com.group16.dinoblog.util.DeviceIdentity(context).id
        try {
            prefs.edit().remove("nearby_discoverable_enabled").commit()
            assertFalse(ProfileRepository(context).profile.value.nearbyDiscoverableEnabled)
            assertTrue(ProfileRepository(context).setNearbyDiscoverableEnabled(true))
            assertTrue(ProfileRepository(context).profile.value.nearbyDiscoverableEnabled)
            assertEquals(deviceId, com.group16.dinoblog.util.DeviceIdentity(context).id)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove("nearby_discoverable_enabled") else editor.putBoolean("nearby_discoverable_enabled", previous)
            editor.commit()
        }
    }
}
