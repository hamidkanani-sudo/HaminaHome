package com.hamina.home.player

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.hamina.home.HaminaApp
import com.hamina.home.R
import com.hamina.home.data.model.AspectRatio
import com.hamina.home.data.model.PlayerSettings
import com.hamina.home.data.model.SmbServer
import com.hamina.home.data.smb.SmbDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Full-screen video player with:
 * - Resume from last position
 * - Settings panel (Up or Menu): subtitle and audio track, subtitle size / color / style /
 *   height, playback speed and picture mode. Subtitle and picture settings are remembered.
 * - Subtitle files next to the video (.srt, .vtt, .ass), including Windows-1256 Persian files
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
    private var menuView: ComposeView? = null
    private var mediaId: String = ""
    private var saveJob: Job? = null
    private var currentServer: SmbServer? = null
    private var currentPath: String = ""
    private var playName: String = ""

    // State read by the settings panel
    private var settings by mutableStateOf(PlayerSettings())
    private var menuVisible by mutableStateOf(false)
    private var textTracks by mutableStateOf<List<TrackOption>>(emptyList())
    private var audioTracks by mutableStateOf<List<TrackOption>>(emptyList())
    private var textDisabled by mutableStateOf(true)
    private var speed by mutableFloatStateOf(1f)

    /** Position to continue from when the player is re-created (e.g. after Home -> back to the app). */
    private var resumeFromMs: Long = -1L

    private val app get() = HaminaApp.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        menuView = findViewById<ComposeView>(R.id.player_menu).also { view ->
            view.setContent {
                if (menuVisible) {
                    PlayerMenu(
                        settings = settings,
                        textTracks = textTracks,
                        textDisabled = textDisabled,
                        audioTracks = audioTracks,
                        speed = speed,
                        onSelectText = { selectText(it) },
                        onSelectAudio = { selectAudio(it) },
                        onSettings = { updateSettings(it) },
                        onSpeed = { setSpeed(it) }
                    )
                }
            }
        }

        mediaId = intent.getStringExtra(EXTRA_MEDIA_ID) ?: ""
        currentPath = intent.getStringExtra(EXTRA_PATH) ?: ""
        playName = intent.getStringExtra(EXTRA_NAME) ?: ""
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: ""

        lifecycleScope.launch {
            val servers = app.serverRepository.getServersOnce()
            currentServer = servers.find { it.id == serverId }
            if (currentServer == null) {
                Toast.makeText(this@PlayerActivity, "Server not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            settings = app.settingsRepository.playerSettings.first()
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
        // DefaultDataSource handles the converted subtitle files (file://); smb:// goes to our source.
        val dataSourceFactory = DefaultDataSource.Factory(
            this,
            SmbDataSource.Factory(app.smbManager, server)
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player = exo
        playerView?.player = exo
        applyAspectRatio(settings.aspectRatio)
        applySubtitleStyle()
        exo.setPlaybackSpeed(speed)

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error", error)
                Toast.makeText(
                    this@PlayerActivity,
                    "Playback error: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onTracksChanged(tracks: Tracks) {
                refreshTracks()
            }
        })

        lifecycleScope.launch {
            // Subtitle files next to the video (the folder listing is cached, so this is quick).
            val sidecars = withContext(Dispatchers.IO) {
                SubtitleSupport.findSidecars(this@PlayerActivity, server, currentPath)
            }
            if (player !== exo) return@launch

            val mediaItem = MediaItem.Builder()
                .setUri(SmbDataSource.buildUri(server, currentPath))
                .setMediaId(mediaId)
                .setSubtitleConfigurations(sidecars)
                .build()
            exo.setMediaItem(mediaItem)
            exo.prepare()

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

    // ---------- settings panel ----------

    private fun openMenu() {
        if (player == null) return
        refreshTracks()
        menuVisible = true
        menuView?.visibility = View.VISIBLE
    }

    private fun closeMenu() {
        menuVisible = false
        menuView?.visibility = View.GONE
    }

    private fun updateSettings(new: PlayerSettings) {
        settings = new
        applySubtitleStyle()
        applyAspectRatio(new.aspectRatio)
        app.applicationScope.launch { app.settingsRepository.updatePlayerSettings(new) }
    }

    private fun setSpeed(value: Float) {
        speed = value
        player?.setPlaybackSpeed(value)
    }

    private fun selectText(option: TrackOption?) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(option.group, option.trackIndex))
        }
        p.trackSelectionParameters = builder.build()
    }

    private fun selectAudio(option: TrackOption) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group, option.trackIndex))
            .build()
    }

    private fun refreshTracks() {
        val p = player ?: return
        val text = ArrayList<TrackOption>()
        val audio = ArrayList<TrackOption>()
        for (group in p.currentTracks.groups) {
            val isText = group.type == C.TRACK_TYPE_TEXT
            val isAudio = group.type == C.TRACK_TYPE_AUDIO
            if (!isText && !isAudio) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val list = if (isText) text else audio
                val label = trackLabel(group.getTrackFormat(i), list.size + 1, isAudio)
                list.add(TrackOption(label, group.isTrackSelected(i), group.mediaTrackGroup, i))
            }
        }
        textTracks = text
        audioTracks = audio
        textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    }

    private fun trackLabel(format: Format, number: Int, isAudio: Boolean): String {
        val language = format.language
            ?.takeIf { it != "und" && it.isNotBlank() }
            ?.let { Locale.forLanguageTag(it).displayLanguage }
            ?.takeIf { it.isNotBlank() }
        val base = format.label?.takeIf { it.isNotBlank() }
            ?: language
            ?: (if (isAudio) "Audio $number" else "Subtitle $number")

        val details = mutableListOf<String>()
        if (isAudio) {
            when (format.channelCount) {
                1 -> details.add("Mono")
                2 -> details.add("Stereo")
                6 -> details.add("5.1")
                8 -> details.add("7.1")
                else -> if (format.channelCount > 0) details.add("${format.channelCount}ch")
            }
            format.sampleMimeType?.substringAfter('/')?.uppercase()?.let { details.add(it) }
        }
        // Add the language when the label is a custom text that does not mention it.
        val shown = if (language != null && !base.contains(language, ignoreCase = true) &&
            format.label != null
        ) {
            "$base ($language)"
        } else {
            base
        }
        return if (details.isEmpty()) shown else "$shown • ${details.joinToString(" ")}"
    }

    private fun applySubtitleStyle() {
        val view = playerView?.subtitleView ?: return
        val s = settings
        val foreground = s.subtitleColor.toInt()
        val background: Int
        val edgeType: Int
        when (s.subtitleStyle) {
            1 -> {
                background = Color.TRANSPARENT
                edgeType = CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            }
            2 -> {
                background = 0xB0000000.toInt()
                edgeType = CaptionStyleCompat.EDGE_TYPE_NONE
            }
            3 -> {
                background = Color.TRANSPARENT
                edgeType = CaptionStyleCompat.EDGE_TYPE_NONE
            }
            else -> {
                background = Color.TRANSPARENT
                edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE
            }
        }
        view.setStyle(
            CaptionStyleCompat(foreground, background, Color.TRANSPARENT, edgeType, Color.BLACK, null)
        )
        // Our size and color should win over styles embedded in .ass / .vtt files.
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.subtitleSize)
        view.setBottomPaddingFraction((1f - s.subtitlePosition).coerceIn(0.02f, 0.5f))
    }

    private fun applyAspectRatio(ratio: AspectRatio) {
        val view = playerView ?: return
        when (ratio) {
            AspectRatio.FIT -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatio.FILL -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatio.STRETCH -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatio.ORIGINAL -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    /**
     * Saves the position in the application scope: an activity-scoped coroutine can be cancelled
     * by finish() before the write completes, which silently loses the resume position.
     */
    private fun persistPosition(position: Long, duration: Long) {
        if (mediaId.isBlank() || duration <= 0 || duration == C.TIME_UNSET) return
        val id = mediaId
        val serverId = currentServer?.id.orEmpty()
        val path = currentPath
        val name = playName
        app.applicationScope.launch {
            if (duration - position < 10_000) {
                // Watched to the end: do not offer to resume.
                app.playbackRepository.clearPosition(id)
            } else if (position >= 5_000) {
                app.playbackRepository.savePosition(id, position, duration, serverId, path, name)
            }
        }
    }

    // ---------- keys ----------

    /** Opens the panel before the PlayerView can use the key for its own controller. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!menuVisible && player != null &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_CAPTIONS,
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
                KeyEvent.KEYCODE_SETTINGS -> {
                    openMenu()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (menuVisible) {
            // The panel handles its own keys; anything else must not seek the video behind it.
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) closeMenu()
            return true
        }
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
        }
        return super.onKeyDown(keyCode, event)
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
        closeMenu()
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
