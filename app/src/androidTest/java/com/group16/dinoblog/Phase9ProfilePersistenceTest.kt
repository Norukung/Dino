package com.group16.dinoblog

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.repository.ProfileRepository
import com.group16.dinoblog.util.DeviceIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase9ProfilePersistenceTest {
    @Test fun relayChoiceSurvivesRepositoryRecreationAndCanBeReenabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = ProfileRepository(context)
        assertTrue(profile.setRelayEnabled(false))
        assertFalse(ProfileRepository(context).relayEnabled)
        assertTrue(ProfileRepository(context).setRelayEnabled(true))
        assertTrue(ProfileRepository(context).relayEnabled)
    }
    @Test fun displayNameAndOnboardingSurviveRepositoryRecreationWithoutChangingDeviceId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = DeviceIdentity(context).id
        val profile = ProfileRepository(context)
        assertTrue(profile.completeOnboarding(" Nate "))
        assertTrue(profile.setDisplayName("พี่ วิลเลียม"))
        val reopened = ProfileRepository(context)
        assertEquals("พี่ วิลเลียม", reopened.profile.value.displayName)
        assertTrue(reopened.profile.value.onboardingCompleted)
        assertEquals(id, DeviceIdentity(context).id)
    }
}
