package com.jarvis.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.domain.model.ScreenConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.jarvisDataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_config_v1")

class ConfigStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private val KEY_SCREEN = stringPreferencesKey("screen_config_json")
        private val KEY_BRAIN = stringPreferencesKey("brain_config_json")
        private val KEY_BOOKMARK_TEXT = stringPreferencesKey("reading_text")
        private val KEY_BOOKMARK_PAGE = stringPreferencesKey("reading_page")
    }

    val screenConfig: Flow<ScreenConfig> = context.jarvisDataStore.data.map { prefs ->
        prefs[KEY_SCREEN]?.let { raw ->
            try {
                json.decodeFromString<ScreenConfig>(raw)
            } catch (_: Exception) {
                ScreenConfig()
            }
        } ?: ScreenConfig()
    }

    val brainConfig: Flow<BrainConfig> = context.jarvisDataStore.data.map { prefs ->
        prefs[KEY_BRAIN]?.let { raw ->
            try {
                json.decodeFromString<BrainConfig>(raw)
            } catch (_: Exception) {
                BrainConfig()
            }
        } ?: BrainConfig()
    }

    suspend fun getScreenConfigOnce(): ScreenConfig = screenConfig.first()
    suspend fun getBrainConfigOnce(): BrainConfig = brainConfig.first()

    suspend fun updateScreenConfig(transform: (ScreenConfig) -> ScreenConfig) {
        context.jarvisDataStore.edit { prefs ->
            val current = prefs[KEY_SCREEN]?.let {
                try {
                    json.decodeFromString<ScreenConfig>(it)
                } catch (_: Exception) {
                    ScreenConfig()
                }
            } ?: ScreenConfig()
            prefs[KEY_SCREEN] = json.encodeToString(transform(current))
        }
    }

    suspend fun updateBrainConfig(transform: (BrainConfig) -> BrainConfig) {
        context.jarvisDataStore.edit { prefs ->
            val current = prefs[KEY_BRAIN]?.let {
                try {
                    json.decodeFromString<BrainConfig>(it)
                } catch (_: Exception) {
                    BrainConfig()
                }
            } ?: BrainConfig()
            prefs[KEY_BRAIN] = json.encodeToString(transform(current))
        }
    }

    suspend fun saveReadingSession(text: String, pageIndex: Int) {
        context.jarvisDataStore.edit { prefs ->
            prefs[KEY_BOOKMARK_TEXT] = text
            prefs[KEY_BOOKMARK_PAGE] = pageIndex.toString()
        }
    }

    suspend fun loadReadingSession(): Pair<String, Int> {
        val prefs = context.jarvisDataStore.data.first()
        val text = prefs[KEY_BOOKMARK_TEXT].orEmpty()
        val page = prefs[KEY_BOOKMARK_PAGE]?.toIntOrNull() ?: 0
        return text to page
    }
}
