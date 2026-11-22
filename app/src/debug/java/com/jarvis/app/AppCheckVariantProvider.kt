package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

internal object AppCheckVariantProvider {
    private const val TAG = "AppCheckInit"
    private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
    private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
    private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

    val kind: AppCheckProviderKind = AppCheckProviderKind.DEBUG

    fun createFactory(context: Context): AppCheckProviderFactory? =
        DebugAppCheckProviderFactory.getInstance()

    /**
     * Debug builds only: synchronously persists a launch-intent debug token before
     * DebugAppCheckProviderFactory creates or refreshes its provider. Never logs the token.
     */
    fun applyDebugTokenFromIntent(context: Context, intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG) return false
        val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)?.takeIf { it.isNotBlank() }
            ?: return false
        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val prefs = context.getSharedPreferences(
            "$DEBUG_STORE_PREFIX$persistenceKey",
            Context.MODE_PRIVATE
        )
        val existing = prefs.getString(DEBUG_SECRET_KEY, null)
        if (existing == debugToken) {
            return false
        }
        prefs.edit()
            .putString(DEBUG_SECRET_KEY, debugToken)
            .commit()
        Log.d(TAG, "App Check debug secret configured for debug build.")
        return true
    }
}
