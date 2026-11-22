package com.jarvis.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.jarvis.app.platform.JarvisVoiceInteractionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase4dAppCheckRobolectricTest {

    @Test
    fun `custom JarvisApplication initializes App Check at startup before VoiceInteractionService`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue("Manifest must instantiate JarvisApplication", app is JarvisApplication)

        val status = AppCheckManager.currentStatus()
        assertTrue("App Check must initialize during Application.onCreate()", status.initialized)
        assertTrue("FirebaseApp must be initialized", FirebaseApp.getApps(app).isNotEmpty())
        assertTrue("At least one install should occur on startup", status.installCount >= 1)

        if (BuildConfig.DEBUG) {
            assertEquals(AppCheckProviderKind.DEBUG, status.providerKind)
            assertTrue(
                status.factoryClassName.orEmpty().endsWith("DebugAppCheckProviderFactory")
            )
        } else {
            assertEquals(AppCheckProviderKind.RECAPTCHA_ENTERPRISE, status.providerKind)
            assertTrue(
                status.factoryClassName.orEmpty().endsWith("RecaptchaAppCheckProviderFactory")
            )
        }

        // Cold-starting VoiceInteractionService without MainActivity uses the already-initialized App Check
        val installCountBeforeService = status.installCount
        val serviceController = Robolectric.buildService(JarvisVoiceInteractionService::class.java)
        serviceController.create()
        try {
            val afterService = AppCheckManager.currentStatus()
            assertTrue(afterService.initialized)
            assertEquals(installCountBeforeService, afterService.installCount)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun `configureAppCheck prevents repeated initialization and enforces debug-only intent token handling`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val debugPrefsName = "com.google.firebase.appcheck.debug.store.$persistenceKey"
        val debugSecretKey = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
        app.getSharedPreferences(debugPrefsName, Context.MODE_PRIVATE).edit().clear().commit()

        AppCheckManager.resetForTest()

        val initial = configureAppCheck(app, intent = null)
        assertTrue(initial.initialized)
        assertEquals(1, initial.installCount)
        assertFalse(initial.updatedDebugTokenFromIntent)

        // Repeated calls without a new debug token must not reinstall provider
        val repeatNoExtra = configureAppCheck(app, intent = Intent(Intent.ACTION_MAIN))
        assertEquals(1, repeatNoExtra.installCount)
        assertEquals(initial.factoryClassName, repeatNoExtra.factoryClassName)

        // Launch intent carrying a debug token extra
        val injectedIntent = Intent(Intent.ACTION_MAIN).apply {
            putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", "00000000-1111-2222-3333-444444444444")
        }
        val afterIntent = configureAppCheck(app, injectedIntent)

        if (BuildConfig.DEBUG) {
            assertEquals(AppCheckProviderKind.DEBUG, afterIntent.providerKind)
            assertTrue(afterIntent.updatedDebugTokenFromIntent)
            assertEquals(2, afterIntent.installCount)
            val saved = app.getSharedPreferences(debugPrefsName, Context.MODE_PRIVATE)
                .getString(debugSecretKey, null)
            assertEquals("00000000-1111-2222-3333-444444444444", saved)

            // Same token repeated must NOT cause another reinstall
            val sameTokenRepeat = configureAppCheck(app, injectedIntent)
            assertFalse(sameTokenRepeat.updatedDebugTokenFromIntent)
            assertEquals(2, sameTokenRepeat.installCount)

            // Debug provider class must resolve only in debug variant
            assertNotNull(
                Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
            )
        } else {
            assertEquals(AppCheckProviderKind.RECAPTCHA_ENTERPRISE, afterIntent.providerKind)
            assertFalse("Release must ignore debug token intent extra", afterIntent.updatedDebugTokenFromIntent)
            assertEquals("Release must not reinstall on intent extra", 1, afterIntent.installCount)
            val saved = app.getSharedPreferences(debugPrefsName, Context.MODE_PRIVATE)
                .getString(debugSecretKey, null)
            assertNull("Release must never persist debug secret from Intent", saved)

            val debugClassPresent = runCatching {
                Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
            }.isSuccess
            assertFalse("Debug App Check provider must not be on release classpath", debugClassPresent)
        }
    }
}
