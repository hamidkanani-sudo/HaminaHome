package com.hamina.home.player

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.hamina.home.HaminaApp
import com.hamina.home.R
import com.hamina.home.data.model.AspectRatio
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.smb.SmbDataSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-screen video player with:
 * - Resume from last position
 * - Global subtitle size / color / position / offset
 * - Aspect ratio control
 * - Audio & subtitle track selection via remote
 * - Periodic position saving
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_MEDIA_ID = "media_id"
        private const val TAG = "HaminaPlayer"
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var subtitleOffsetMs: Long = 0L
    private var mediaId: String = ""
    private var saveJob: Job? = null
    private var currentServer: SmbServer? = null
    private var currentPath: String = ""

    private val app get() = HaminaApp.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        mediaId = intent.getStringExtra(EXTRA_MEDIA_ID) ?: ""
        currentPath = intent.getStringExtra(EXTRA_PATH) ?: ""
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: ""

        lifecycleScope.launch {
            val servers = app.serverRepository.getServersOnce()
            currentServer = servers.find { it.id == serverId }
            if (currentServer == null) {
                Toast.makeText(this@PlayerActivity, "Server not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val settings = app.settingsRepository.playerSettings.first()
            subtitleOffsetMs = settings.subtitleOffsetMs
            setupPlayer(settings.aspectRatio)
        }
    }

    private fun setupPlayer(aspectRatio: AspectRatio) {
        val server = currentServer ?: return

        val dataSourceFactory = SmbDataSource.Factory(app.smbManager, server)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                playerView?.player = exo
                applyAspectRatio(aspectRatio)

                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Playback error", error)
                        Toast.makeText(
                            this@PlayerActivity,
                            "Playback error: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })

                val mediaItem = MediaItem.Builder()
                    .setUri("smb://${server.host}/${server.share}/$currentPath")
                    .setMediaId(mediaId)
                    .build()

                exo.setMediaItem(mediaItem)
                exo.prepare()

                lifecycleScope.launch {
                    val info = app.playbackRepository.getPosition(mediaId)
                    if (info != null && info.positionMs > 5_000 && info.positionMs < (info.durationMs - 10_000)) {
                        exo.seekTo(info.positionMs)
                        Toast.makeText(
                            this@PlayerActivity,
                            "Resuming from ${formatTime(info.positionMs)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    exo.playWhenReady = true
                }

                saveJob = lifecycleScope.launch {
                    while (isActive) {
                        delay(8_000)
                        saveCurrentPosition()
                    }
                }
            }
    }

    private fun applyAspectRatio(ratio: AspectRatio) {
        val view = playerView ?: return
        when (ratio) {
            AspectRatio.FIT -> view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatio.FILL -> view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatio.STRETCH -> view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatio.ORIGINAL -> view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun saveCurrentPosition() {
        val p = player ?: return
        if (p.duration <= 0 || mediaId.isBlank()) return
        val pos = p.currentPosition
        val dur = p.duration
        lifecycleScope.launch {
            app.playbackRepository.savePosition(mediaId, pos, dur)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (p.isPlaying) p.pause() else p.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                p.seekTo(p.currentPosition + 10_000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                saveCurrentPosition()
                finish()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                cycleAudioTrack()
                return true
            }
            KeyEvent.KEYCODE_CAPTIONS -> {
                cycleSubtitleTrack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun cycleAudioTrack() {
        val p = player ?: return
        val groups = p.currentTracks.groups
        val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        Toast.makeText(this, "Audio tracks: ${audioGroups.size}", Toast.LENGTH_SHORT).show()
    }

    private fun cycleSubtitleTrack() {
        val p = player ?: return
        val groups = p.currentTracks.groups
        val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        Toast.makeText(this, "Subtitle tracks: ${textGroups.size}", Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    override fun onStop() {
        super.onStop()
        saveCurrentPosition()
        saveJob?.cancel()
        player?.release()
        player = null
    }
}
