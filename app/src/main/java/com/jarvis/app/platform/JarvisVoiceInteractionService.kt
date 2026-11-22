package com.jarvis.app.platform

import android.service.voice.VoiceInteractionService

class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        JarvisAssistantRuntime.getInstance(applicationContext)
    }

    override fun onShutdown() {
        super.onShutdown()
    }
}
