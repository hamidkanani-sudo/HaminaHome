package com.hamina.home.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.hamina.home.data.local.LocalStorage
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.playback.PlaybackRepository
import com.hamina.home.player.PhotoSession
import com.hamina.home.player.PhotoViewerActivity
import com.hamina.home.player.PlayerActivity

/**
 * Entry point of Hamina Home. All state lives in [BrowserViewModel]; the screens are in Screens.kt.
 */
class MainActivity : ComponentActivity() {

    private val vm: BrowserViewModel by viewModels()

    // Android 10 and older: the usual runtime permission for shared storage.
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshVolumes() }

    // USB drives and SD cards being plugged in or removed
    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            vm.refreshVolumes()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HaminaRoot(
                vm = vm,
                onOpenItem = { item, index -> openItem(item, index) },
                onPlayRecent = { recent -> playRecent(recent) },
                onOpenVolume = { server, index -> openVolume(server, index) }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(this, storageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(storageReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPlayback()
    }

    // ---------- storage access ----------

    private fun openVolume(server: SmbServer, index: Int) {
        if (LocalStorage.hasAccess(this)) {
            vm.openServer(server, index, BrowserViewModel.SECTION_STORAGE)
        } else {
            requestStorageAccess()
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            // "All files access" is needed to browse, copy and delete files on USB drives.
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                    // Many TVs have no settings page for it. It can be granted over ADB.
                    vm.showMessage(
                        "Storage access is needed to browse this storage.\n\n" +
                            "This TV has no settings page for it. Connect with ADB and run:\n" +
                            "adb shell appops set $packageName MANAGE_EXTERNAL_STORAGE allow\n\n" +
                            "Then open this storage again."
                    )
                }
            }
        } else {
            storagePermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    // ---------- opening things ----------

    private fun openItem(item: MediaItem, index: Int) {
        val server = vm.currentServer ?: return
        when {
            item.isDirectory -> vm.openFolder(item, index)
            item.isVideo -> startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_SERVER_ID, server.id)
                    putExtra(PlayerActivity.EXTRA_PATH, item.path)
                    putExtra(PlayerActivity.EXTRA_NAME, item.name)
                    putExtra(PlayerActivity.EXTRA_MEDIA_ID, item.id)
                }
            )
            item.isImage -> {
                // The viewer pages through all images of this folder (in the order shown).
                val images = vm.items.filter { it.isImage }
                PhotoSession.serverId = server.id
                PhotoSession.items = images
                startActivity(
                    Intent(this, PhotoViewerActivity::class.java).apply {
                        putExtra(PhotoViewerActivity.EXTRA_SERVER_ID, server.id)
                        putExtra(PhotoViewerActivity.EXTRA_PATH, item.path)
                        putExtra(PhotoViewerActivity.EXTRA_NAME, item.name)
                        putExtra(
                            PhotoViewerActivity.EXTRA_INDEX,
                            images.indexOfFirst { it.id == item.id }
                        )
                    }
                )
            }
            else -> Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playRecent(recent: PlaybackRepository.RecentPlay) {
        val known = recent.serverId.startsWith(LocalStorage.ID_PREFIX) ||
            vm.servers.any { it.id == recent.serverId }
        if (!known) {
            Toast.makeText(this, "This server was removed", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_SERVER_ID, recent.serverId)
                putExtra(PlayerActivity.EXTRA_PATH, recent.path)
                putExtra(PlayerActivity.EXTRA_NAME, recent.name)
                putExtra(PlayerActivity.EXTRA_MEDIA_ID, recent.id)
            }
        )
    }
}
