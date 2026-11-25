package com.hamina.home.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.hamina.home.data.model.AspectRatio
import com.hamina.home.data.model.PlayerSettings
import com.hamina.home.data.model.SortMode
import com.hamina.home.data.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val SUBTITLE_SIZE = floatPreferencesKey("subtitle_size")
        val SUBTITLE_COLOR = longPreferencesKey("subtitle_color")
        val SUBTITLE_POSITION = floatPreferencesKey("subtitle_position")
        val SUBTITLE_OFFSET = longPreferencesKey("subtitle_offset")
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
    }

    val viewMode: Flow<ViewMode> = dataStore.data.map { prefs ->
        when (prefs[Keys.VIEW_MODE]) {
            "GRID" -> ViewMode.GRID
            else -> ViewMode.LIST
        }
    }

    val sortMode: Flow<SortMode> = dataStore.data.map { prefs ->
        try {
            SortMode.valueOf(prefs[Keys.SORT_MODE] ?: SortMode.NAME_ASC.name)
        } catch (_: Exception) {
            SortMode.NAME_ASC
        }
    }

    val playerSettings: Flow<PlayerSettings> = dataStore.data.map { prefs ->
        PlayerSettings(
            subtitleSize = prefs[Keys.SUBTITLE_SIZE] ?: 1.0f,
            subtitleColor = prefs[Keys.SUBTITLE_COLOR] ?: 0xFFFFFFFF,
            subtitlePosition = prefs[Keys.SUBTITLE_POSITION] ?: 0.9f,
            subtitleOffsetMs = prefs[Keys.SUBTITLE_OFFSET] ?: 0L,
            aspectRatio = try {
                AspectRatio.valueOf(prefs[Keys.ASPECT_RATIO] ?: AspectRatio.FIT.name)
            } catch (_: Exception) {
                AspectRatio.FIT
            }
        )
    }

    suspend fun setViewMode(mode: ViewMode) {
        dataStore.edit { it[Keys.VIEW_MODE] = mode.name }
    }

    suspend fun setSortMode(mode: SortMode) {
        dataStore.edit { it[Keys.SORT_MODE] = mode.name }
    }

    suspend fun updatePlayerSettings(settings: PlayerSettings) {
        dataStore.edit {
            it[Keys.SUBTITLE_SIZE] = settings.subtitleSize
            it[Keys.SUBTITLE_COLOR] = settings.subtitleColor
            it[Keys.SUBTITLE_POSITION] = settings.subtitlePosition
            it[Keys.SUBTITLE_OFFSET] = settings.subtitleOffsetMs
            it[Keys.ASPECT_RATIO] = settings.aspectRatio.name
        }
    }
}
