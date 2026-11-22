package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck

private const val TAG = "AppCheckInit"

enum class AppCheckProviderKind {
    NONE,
    DEBUG,
    RECAPTCHA_ENTERPRISE
}

data class AppCheckConfigurationStatus(
    val initialized: Boolean,
    val providerKind: AppCheckProviderKind,
    val factoryClassName: String?,
    val updatedDebugTokenFromIntent: Boolean,
    val installCount: Int
)

object AppCheckManager {
    private val initLock = Any()

    @Volatile
    private var installedFactory: AppCheckProviderFactory? = null

    @Volatile
    private var installCount: Int = 0

    @Volatile
    private var lastStatus: AppCheckConfigurationStatus = AppCheckConfigurationStatus(
        initialized = false,
        providerKind = AppCheckProviderKind.NONE,
        factoryClassName = null,
        updatedDebugTokenFromIntent = false,
        installCount = 0
    )

    fun currentStatus(): AppCheckConfigurationStatus = lastStatus

    internal fun resetForTest() {
        synchronized(initLock) {
            installedFactory = null
            installCount = 0
            lastStatus = AppCheckConfigurationStatus(
                initialized = false,
                providerKind = AppCheckProviderKind.NONE,
                factoryClassName = null,
                updatedDebugTokenFromIntent = false,
                installCount = 0
            )
        }
    }

    fun configure(context: Context, intent: Intent?): AppCheckConfigurationStatus {
        val appContext = context.applicationContext ?: context
        synchronized(initLock) {
            return try {
                if (FirebaseApp.getApps(appContext).isEmpty()) {
                    FirebaseApp.initializeApp(appContext)
                }

                val updatedDebugToken = if (BuildConfig.DEBUG) {
                    AppCheckVariantProvider.applyDebugTokenFromIntent(appContext, intent)
                } else {
                    false
                }

                val existingFactory = installedFactory
                if (existingFactory != null && !updatedDebugToken) {
                    val status = lastStatus.copy(updatedDebugTokenFromIntent = false)
                    lastStatus = status
                    return status
                }

                val factory = AppCheckVariantProvider.createFactory(appContext)
                if (factory == null) {
                    return lastStatus
                }

                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory)
                installedFactory = factory
                installCount += 1
                val status = AppCheckConfigurationStatus(
                    initialized = true,
                    providerKind = AppCheckVariantProvider.kind,
                    factoryClassName = factory::class.java.name,
                    updatedDebugTokenFromIntent = updatedDebugToken,
                    installCount = installCount
                )
                lastStatus = status
                Log.d(TAG, "Firebase App Check provider active: ${status.providerKind}")
                status
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Firebase App Check", e)
                lastStatus
            }
        }
    }
}

fun configureAppCheck(context: Context, intent: Intent?): AppCheckConfigurationStatus =
    AppCheckManager.configure(context, intent)
