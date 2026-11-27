package com.hamina.home

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.hamina.home.data.cache.CacheManager
import com.hamina.home.data.playback.PlaybackRepository
import com.hamina.home.data.settings.ServerRepository
import com.hamina.home.data.settings.SettingsRepository
import com.hamina.home.data.smb.SmbManager
import com.hamina.home.data.thumb.ThumbnailManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hamina_settings")

class HaminaApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var smbManager: SmbManager
        private set

    lateinit var cacheManager: CacheManager
        private set

    lateinit var thumbnailManager: ThumbnailManager
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var serverRepository: ServerRepository
        private set

    lateinit var playbackRepository: PlaybackRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        cacheManager = CacheManager(this)
        settingsRepository = SettingsRepository(dataStore)
        serverRepository = ServerRepository(dataStore)
        playbackRepository = PlaybackRepository(dataStore)
        smbManager = SmbManager(this, cacheManager)
        thumbnailManager = ThumbnailManager(this, smbManager)

        // Encrypt any plain-text SMB passwords saved by older versions.
        applicationScope.launch { serverRepository.migratePasswordsIfNeeded() }

        // Keep the thumbnail cache within its size limit.
        applicationScope.launch(Dispatchers.IO) { thumbnailManager.trimDiskCache() }
    }

    companion object {
        lateinit var instance: HaminaApp
            private set
    }
}
