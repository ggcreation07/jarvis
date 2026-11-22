package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

internal object AppCheckVariantProvider {
    private const val TAG = "AppCheckInit"
    private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

    val kind: AppCheckProviderKind = AppCheckProviderKind.RECAPTCHA_ENTERPRISE

    fun createFactory(context: Context): AppCheckProviderFactory? {
        val resId = context.resources.getIdentifier(
            RECAPTCHA_SITE_KEY_RES,
            "string",
            context.packageName
        )
        if (resId == 0) {
            Log.e(TAG, "recaptcha_site_key resource not found; production App Check provider not installed.")
            return null
        }
        val siteKey = context.getString(resId).trim()
        if (siteKey.isEmpty()) {
            Log.e(TAG, "recaptcha_site_key resource is blank; production App Check provider not installed.")
            return null
        }
        return RecaptchaAppCheckProviderFactory.getInstance(siteKey)
    }

    /**
     * Release builds must completely ignore FIREBASE_APPCHECK_DEBUG_TOKEN intent extras.
     */
    @Suppress("UNUSED_PARAMETER")
    fun applyDebugTokenFromIntent(context: Context, intent: Intent?): Boolean = false
}
