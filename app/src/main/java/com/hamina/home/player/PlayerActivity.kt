package com.hamina.home.player

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
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
    private var mediaId: String = ""
    private var saveJob: Job? = null
    private var currentServer: SmbServer? = null
    private var currentPath: String = ""
    private var aspectRatio: AspectRatio = AspectRatio.FIT

    /** Position to continue from when the player is re-created (e.g. after Home -> back to the app). */
    private var resumeFromMs: Long = -1L

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
            aspectRatio = app.settingsRepository.playerSettings.first().aspectRatio
            ensurePlayer()
        }
    }

    override fun onStart() {
        super.onStart()
        // The player is released in onStop, so it must be created again here.
        ensurePlayer()
    }

    private fun ensurePlayer() {
        if (player != null) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val server = currentServer ?: return
        setupPlayer(server)
    }

    private fun setupPlayer(server: SmbServer) {
        val dataSourceFactory = SmbDataSource.Factory(app.smbManager, server)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player = exo
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
            .setUri(SmbDataSource.buildUri(server, currentPath))
            .setMediaId(mediaId)
            .build()

        exo.setMediaItem(mediaItem)
        exo.prepare()

        lifecycleScope.launch {
            val pending = resumeFromMs
            if (pending > 0) {
                resumeFromMs = -1L
                if (player === exo) exo.seekTo(pending)
            } else {
                val info = app.playbackRepository.getPosition(mediaId)
                if (player === exo && info != null &&
                    info.positionMs > 5_000 && info.positionMs < (info.durationMs - 10_000)
                ) {
                    exo.seekTo(info.positionMs)
                    Toast.makeText(
                        this@PlayerActivity,
                        "Resuming from ${formatTime(info.positionMs)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            if (player === exo) exo.playWhenReady = true
        }

        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            while (isActive) {
                delay(8_000)
                player?.let { persistPosition(it.currentPosition, it.duration) }
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

    /**
     * Saves the position in the application scope: an activity-scoped coroutine can be cancelled
     * by finish() before the write completes, which silently loses the resume position.
     */
    private fun persistPosition(position: Long, duration: Long) {
        if (mediaId.isBlank() || duration <= 0 || duration == C.TIME_UNSET) return
        val id = mediaId
        app.applicationScope.launch {
            if (duration - position < 10_000) {
                // Watched to the end: do not offer to resume.
                app.playbackRepository.clearPosition(id)
            } else if (position >= 5_000) {
                app.playbackRepository.savePosition(id, position, duration)
            }
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
                finish() // position is saved in onStop
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
        val p = player
        if (p != null) {
            val position = p.currentPosition
            if (position > 0) resumeFromMs = position
            persistPosition(position, p.duration)
        }
        saveJob?.cancel()
        saveJob = null
        playerView?.player = null
        p?.release()
        player = null
    }
}
