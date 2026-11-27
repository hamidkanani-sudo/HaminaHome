package com.hamina.home.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.hamina.home.data.model.MediaItem
import com.hamina.home.data.playback.PlaybackRepository
import com.hamina.home.player.PhotoSession
import com.hamina.home.player.PhotoViewerActivity
import com.hamina.home.player.PlayerActivity

/**
 * Entry point of Hamina Home. All state lives in [BrowserViewModel]; the screens are in Screens.kt.
 */
class MainActivity : ComponentActivity() {

    private val vm: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HaminaRoot(
                vm = vm,
                onOpenItem = { item, index -> openItem(item, index) },
                onPlayRecent = { recent -> playRecent(recent) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPlayback()
    }

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
        if (vm.servers.none { it.id == recent.serverId }) {
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
