package com.jarvis.app

import android.app.Application

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        configureAppCheck(this, intent = null)
    }
}
