package com.hamina.home.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.TrackGroup
import com.hamina.home.data.model.AspectRatio
import com.hamina.home.data.model.PlayerSettings
import com.hamina.home.ui.FocusSurface
import com.hamina.home.ui.HaminaColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** An audio or subtitle track the user can pick. */
class TrackOption(
    val label: String,
    val selected: Boolean,
    val group: TrackGroup,
    val trackIndex: Int
)

private val sizeSteps = listOf(0.6f, 0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)

// Distance of the subtitle baseline from the bottom edge, as stored: 1 - position.
private val positionSteps = listOf(0.97f, 0.94f, 0.9f, 0.85f, 0.8f, 0.7f, 0.6f)

private val speedSteps = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

private val colorSteps: List<Pair<String, Long>> = listOf(
    "White" to 0xFFFFFFFFL,
    "Yellow" to 0xFFFFEB3BL,
    "Cyan" to 0xFF4DD0E1L,
    "Green" to 0xFF81C784L,
    "Orange" to 0xFFFFB74DL,
    "Pink" to 0xFFF48FB1L
)

private val styleNames = listOf("Outline", "Shadow", "Dark box", "Plain")

private val aspectNames = mapOf(
    AspectRatio.FIT to "Fit",
    AspectRatio.FILL to "Fill (crop)",
    AspectRatio.STRETCH to "Stretch",
    AspectRatio.ORIGINAL to "Original"
)

private fun nearestIndex(steps: List<Float>, value: Float): Int {
    var best = 0
    for (i in steps.indices) {
        if (abs(steps[i] - value) < abs(steps[best] - value)) best = i
    }
    return best
}

private fun wrapIndex(index: Int, size: Int): Int = ((index % size) + size) % size

/**
 * Right-hand settings panel for the video player. Left/Right change the value of the focused
 * row (OK steps forward), Back or Menu closes it. Changes apply to the running video at once.
 */
@Composable
fun PlayerMenu(
    settings: PlayerSettings,
    textTracks: List<TrackOption>,
    textDisabled: Boolean,
    audioTracks: List<TrackOption>,
    speed: Float,
    onSelectText: (TrackOption?) -> Unit,
    onSelectAudio: (TrackOption) -> Unit,
    onSettings: (PlayerSettings) -> Unit,
    onSpeed: (Float) -> Unit
) {
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        try {
            firstRow.requestFocus()
        } catch (_: Exception) {
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(440.dp)
                .fillMaxHeight()
                .background(Color(0xEE0A0E14))
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(
                text = "Player settings",
                color = HaminaColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "◀ ▶ change    OK next    Back close",
                color = HaminaColors.TextSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            SubtitlePreview(settings)
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // --- subtitle track ---
                val textChoices: List<TrackOption?> = listOf<TrackOption?>(null) + textTracks
                val currentText = if (textDisabled) {
                    0
                } else {
                    val selectedIndex = textTracks.indexOfFirst { it.selected }
                    if (selectedIndex >= 0) selectedIndex + 1 else 0
                }
                MenuRow(
                    label = "Subtitles",
                    value = textChoices[currentText]?.label
                        ?: if (textTracks.isEmpty()) "None available" else "Off",
                    requester = firstRow,
                    onPrev = { onSelectText(textChoices[wrapIndex(currentText - 1, textChoices.size)]) },
                    onNext = { onSelectText(textChoices[wrapIndex(currentText + 1, textChoices.size)]) }
                )

                // --- subtitle look ---
                val sizeIndex = nearestIndex(sizeSteps, settings.subtitleSize)
                MenuRow(
                    label = "Size",
                    value = "${(sizeSteps[sizeIndex] * 100).roundToInt()}%",
                    onPrev = {
                        onSettings(settings.copy(subtitleSize = sizeSteps[wrapIndex(sizeIndex - 1, sizeSteps.size)]))
                    },
                    onNext = {
                        onSettings(settings.copy(subtitleSize = sizeSteps[wrapIndex(sizeIndex + 1, sizeSteps.size)]))
                    }
                )

                val colorIndex = colorSteps.indexOfFirst { it.second == settings.subtitleColor }
                    .let { if (it < 0) 0 else it }
                MenuRow(
                    label = "Color",
                    value = colorSteps[colorIndex].first,
                    swatch = Color(colorSteps[colorIndex].second.toInt()),
                    onPrev = {
                        onSettings(
                            settings.copy(subtitleColor = colorSteps[wrapIndex(colorIndex - 1, colorSteps.size)].second)
                        )
                    },
                    onNext = {
                        onSettings(
                            settings.copy(subtitleColor = colorSteps[wrapIndex(colorIndex + 1, colorSteps.size)].second)
                        )
                    }
                )

                val styleIndex = settings.subtitleStyle.coerceIn(0, styleNames.size - 1)
                MenuRow(
                    label = "Style",
                    value = styleNames[styleIndex],
                    onPrev = { onSettings(settings.copy(subtitleStyle = wrapIndex(styleIndex - 1, styleNames.size))) },
                    onNext = { onSettings(settings.copy(subtitleStyle = wrapIndex(styleIndex + 1, styleNames.size))) }
                )

                val positionIndex = nearestIndex(positionSteps, settings.subtitlePosition)
                MenuRow(
                    label = "Height",
                    value = "${((1f - positionSteps[positionIndex]) * 100).roundToInt()}% from bottom",
                    onPrev = {
                        onSettings(
                            settings.copy(subtitlePosition = positionSteps[wrapIndex(positionIndex - 1, positionSteps.size)])
                        )
                    },
                    onNext = {
                        onSettings(
                            settings.copy(subtitlePosition = positionSteps[wrapIndex(positionIndex + 1, positionSteps.size)])
                        )
                    }
                )

                // --- audio track ---
                val audioIndex = audioTracks.indexOfFirst { it.selected }.let { if (it < 0) 0 else it }
                MenuRow(
                    label = "Audio",
                    value = audioTracks.getOrNull(audioIndex)?.label ?: "Default",
                    onPrev = {
                        if (audioTracks.size > 1) {
                            onSelectAudio(audioTracks[wrapIndex(audioIndex - 1, audioTracks.size)])
                        }
                    },
                    onNext = {
                        if (audioTracks.size > 1) {
                            onSelectAudio(audioTracks[wrapIndex(audioIndex + 1, audioTracks.size)])
                        }
                    }
                )

                // --- playback ---
                val speedIndex = nearestIndex(speedSteps, speed)
                MenuRow(
                    label = "Speed",
                    value = "${speedSteps[speedIndex]}×",
                    onPrev = { onSpeed(speedSteps[wrapIndex(speedIndex - 1, speedSteps.size)]) },
                    onNext = { onSpeed(speedSteps[wrapIndex(speedIndex + 1, speedSteps.size)]) }
                )

                val aspects = AspectRatio.values().toList()
                val aspectIndex = aspects.indexOf(settings.aspectRatio).let { if (it < 0) 0 else it }
                MenuRow(
                    label = "Picture",
                    value = aspectNames[aspects[aspectIndex]] ?: aspects[aspectIndex].name,
                    onPrev = {
                        onSettings(settings.copy(aspectRatio = aspects[wrapIndex(aspectIndex - 1, aspects.size)]))
                    },
                    onNext = {
                        onSettings(settings.copy(aspectRatio = aspects[wrapIndex(aspectIndex + 1, aspects.size)]))
                    }
                )
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    value: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    requester: FocusRequester? = null,
    swatch: Color? = null
) {
    FocusSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        focusRequester = requester,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        onLeftKey = onPrev,
        onRightKey = onNext,
        onClick = onNext
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = HaminaColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.width(92.dp)
            )
            Icon(
                imageVector = Icons.Rounded.ChevronLeft,
                contentDescription = null,
                tint = if (focused) HaminaColors.Accent else HaminaColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (swatch != null) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(swatch)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = value,
                    color = HaminaColors.TextPrimary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = if (focused) HaminaColors.Accent else HaminaColors.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Shows how the subtitles will look with the current size, color and style. */
@Composable
private fun SubtitlePreview(settings: PlayerSettings) {
    val color = Color(settings.subtitleColor.toInt())
    val boxed = settings.subtitleStyle == 2
    val shadow = if (settings.subtitleStyle == 3 || boxed) {
        null
    } else {
        Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 4f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2B3340)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Sample subtitle  نمونه زیرنویس",
            color = color,
            fontSize = (16f * settings.subtitleSize).coerceAtMost(30f).sp,
            style = TextStyle(shadow = shadow),
            maxLines = 1,
            modifier = if (boxed) {
                Modifier
                    .background(Color(0xB0000000))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            } else {
                Modifier
            }
        )
    }
}
