package com.jasongzy.medialab

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioPlayer(
    file: File,
    active: Boolean,
    initialPosition: Long,
    rememberPosition: (Long) -> Unit,
) {
    val context = LocalContext.current
    var player by remember(file) { mutableStateOf<ExoPlayer?>(null) }
    var position by remember(file) { mutableLongStateOf(initialPosition) }
    var duration by remember(file) { mutableLongStateOf(0) }
    var playing by remember(file) { mutableStateOf(false) }
    var buffering by remember(file) { mutableStateOf(true) }
    var showBuffering by remember(file) { mutableStateOf(false) }
    var seekable by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var dragging by remember(file) { mutableStateOf<Float?>(null) }

    if (active)
        LifecycleResumeEffect(file) {
            val current = ExoPlayer.Builder(context.applicationContext).build()
            val listener =
                object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (player.playbackState == Player.STATE_ENDED && player.playWhenReady)
                            player.pause()
                        duration = player.duration.coerceAtLeast(0)
                        position = player.currentPosition.coerceAtLeast(0)
                        playing =
                            player.playWhenReady &&
                                player.playbackState != Player.STATE_ENDED &&
                                player.playerError == null
                        buffering = player.playbackState == Player.STATE_BUFFERING
                        seekable = player.isCurrentMediaItemSeekable
                        error = player.playerError?.errorCodeName
                    }
                }
            current.addListener(listener)
            current.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            current.setHandleAudioBecomingNoisy(true)
            current.setMediaItem(
                androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)),
                position,
            )
            current.prepare()
            player = current
            onPauseOrDispose {
                position = current.currentPosition.coerceAtLeast(0)
                rememberPosition(position)
                current.removeListener(listener)
                current.release()
                player = null
                playing = false
                dragging = null
            }
        }
    LaunchedEffect(file, buffering, active) {
        showBuffering = false
        if (buffering && active) {
            delay(350)
            showBuffering = true
        }
    }
    LaunchedEffect(player, playing) {
        val current = player ?: return@LaunchedEffect
        while (isActive && playing) {
            position = current.currentPosition.coerceAtLeast(0)
            delay(200)
        }
    }

    val elapsed = playbackTime(dragging?.toLong() ?: position)
    val total = if (duration > 0) playbackTime(duration) else stringResource(R.string.unknown_time)
    val seekLabel = stringResource(R.string.playback_position)
    val seekDescription = stringResource(R.string.playback_time, elapsed, total)
    val range = 0f..duration.coerceAtLeast(1).toFloat()
    val slider = remember(file, duration) { SliderState(0f, trackRange = range) }
    slider.value = (dragging ?: position.toFloat()).coerceIn(range)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolButton(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) R.string.pause else R.string.play,
                    enabled = player != null && error == null,
                ) {
                    player?.let { current ->
                        if (playing) current.pause()
                        else {
                            if (current.playbackState == Player.STATE_ENDED) current.seekTo(0)
                            current.play()
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Slider(
                        state = slider,
                        onValueChange = {
                            dragging = it
                            slider.value = it
                        },
                        onValueChangeFinished = {
                            dragging?.let {
                                position = it.toLong().coerceIn(0, duration)
                                player?.seekTo(position)
                            }
                            dragging = null
                        },
                        enabled = player != null && seekable && duration > 0 && error == null,
                        modifier =
                            Modifier.fillMaxWidth().semantics {
                                contentDescription = seekLabel
                                stateDescription = seekDescription
                            },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(elapsed, style = MaterialTheme.typography.labelSmall)
                        Text(total, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showBuffering) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }
            error?.let {
                Text(
                    stringResource(R.string.playback_error, it),
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600)
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
