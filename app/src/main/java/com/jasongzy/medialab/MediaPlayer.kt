package com.jasongzy.medialab

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPlayer(
    file: File,
    active: Boolean,
    initialPosition: Long,
    modifier: Modifier = Modifier,
    video: Boolean = false,
    muted: Boolean = false,
    paused: Boolean = false,
    videoContent: (@Composable ColumnScope.(Player?, Boolean, Boolean) -> Unit)? = null,
    hasVideoPlaceholder: Boolean = false,
    onInteraction: () -> Unit = {},
    seekPosition: Long? = null,
    onSeekHandled: () -> Unit = {},
    channelCount: Int = 0,
    auditionChannels: Set<Int> = emptySet(),
    rememberPosition: (Long) -> Unit,
) {
    val context = LocalContext.current
    val savePosition by key(file) { rememberUpdatedState(rememberPosition) }
    val currentMuted by rememberUpdatedState(muted)
    val currentAudition by rememberUpdatedState(auditionChannels)
    var resumeAudition by remember(file) { mutableStateOf(false) }
    var player by remember(file) { mutableStateOf<ExoPlayer?>(null) }
    var play by remember(file) { mutableStateOf<(() -> Unit)?>(null) }
    var updateMute by remember(file) { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var position by remember(file) { mutableLongStateOf(initialPosition) }
    var duration by remember(file) { mutableLongStateOf(0) }
    var playing by remember(file) { mutableStateOf(false) }
    var buffering by remember(file) { mutableStateOf(true) }
    var firstFrame by remember(file) { mutableStateOf(false) }
    var showBuffering by remember(file) { mutableStateOf(false) }
    var seekable by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var dragging by remember(file) { mutableStateOf<Float?>(null) }

    if (active)
        LifecycleResumeEffect(file, auditionChannels) {
            firstFrame = false
            buffering = true
            error = null
            val renderers = object : DefaultRenderersFactory(context.applicationContext) {
                override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean): AudioSink? {
                    val sink = if (auditionChannels.isEmpty())
                        super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams) ?: return null
                    else DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(channelAudition(channelCount, auditionChannels))).build()
                    return PlaybackAudioSink(sink)
                }
            }
            val current =
                ExoPlayer.Builder(context.applicationContext, renderers)
                    // Keep the visible playhead current even when audio is fully buffered.
                    .experimentalSetDynamicSchedulingEnabled(false)
                    .build()
            val audio = context.getSystemService(AudioManager::class.java)
            val attributes =
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
            var focused = false
            val focus =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes.platformAudioAttributes)
                    .setOnAudioFocusChangeListener { change ->
                        if (focused && change < 0) current.pause()
                    }
                    .build()
            fun releaseFocus() {
                if (focused) {
                    focused = false
                    audio.abandonAudioFocusRequest(focus)
                }
            }
            fun requestFocus(): Boolean {
                if (!focused)
                    focused =
                        audio.requestAudioFocus(focus) ==
                            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (!focused)
                    Toast.makeText(
                            context,
                            R.string.playback_focus_unavailable,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                return focused
            }
            val listener =
                object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        firstFrame = true
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (
                            player.playbackState == Player.STATE_ENDED &&
                                player.playWhenReady &&
                                !current.isScrubbingModeEnabled
                        )
                            player.pause()
                        if (!player.playWhenReady || player.playerError != null) releaseFocus()
                        duration = player.duration.coerceAtLeast(0)
                        if (dragging == null) {
                            position = player.currentPosition.coerceAtLeast(0)
                            savePosition(position)
                        }
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
            current.setAudioAttributes(attributes, false)
            current.volume = if (currentMuted) 0f else 1f
            current.setHandleAudioBecomingNoisy(true)
            current.setMediaItem(
                androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)),
                position,
            )
            player = current
            play = {
                if (current.playbackState == Player.STATE_ENDED) current.seekTo(0)
                if (currentMuted || requestFocus()) current.play()
            }
            updateMute = { mute ->
                if (mute) {
                    current.volume = 0f
                    releaseFocus()
                } else {
                    if (current.playWhenReady && !requestFocus()) current.pause()
                    current.volume = 1f
                }
            }
            if (resumeAudition && !paused) play?.invoke()
            resumeAudition = false
            onPauseOrDispose {
                resumeAudition = current.playWhenReady && currentAudition != auditionChannels
                position = current.currentPosition.coerceAtLeast(0)
                savePosition(position)
                current.removeListener(listener)
                releaseFocus()
                current.release()
                player = null
                play = null
                updateMute = null
                playing = false
                firstFrame = false
                dragging = null
            }
        }
    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        // Let the video output attach before decoding the paused first frame.
        if (video) withFrameNanos { }
        current.prepare()
    }
    LaunchedEffect(player, muted) { updateMute?.invoke(muted) }
    LaunchedEffect(player, paused) { if (paused) player?.pause() }
    LaunchedEffect(player, seekPosition) {
        val current = player ?: return@LaunchedEffect
        val target = seekPosition ?: return@LaunchedEffect
        current.pause()
        current.seekTo(target)
        position = target
        savePosition(target)
        onSeekHandled()
    }
    LaunchedEffect(file, buffering, active) {
        showBuffering = false
        if (buffering && active) {
            delay(350)
            showBuffering = true
        }
    }
    LaunchedEffect(player, playing, dragging != null) {
        val current = player ?: return@LaunchedEffect
        var lastSaved = 0L
        while (isActive && playing && dragging == null) {
            withFrameNanos { time ->
                position = current.currentPosition.coerceAtLeast(0)
                if (time - lastSaved >= 200_000_000L) {
                    savePosition(position)
                    lastSaved = time
                }
            }
        }
    }

    val elapsed = playbackTime(dragging?.toLong() ?: position)
    val total =
        if (duration > 0) playbackTime(duration) else stringResource(R.string.unknown_time)
    val seekLabel = stringResource(R.string.playback_position)
    val seekDescription = stringResource(R.string.playback_time, elapsed, total)
    val range = 0f..duration.coerceAtLeast(1).toFloat()
    val slider = remember(file, duration) { SliderState(0f, trackRange = range) }
    slider.value = (dragging ?: position.toFloat()).coerceIn(range)
    val hasPausedFrame = hasVideoPlaceholder && !playing && dragging == null
    val loadingVideo = active && error == null && !hasPausedFrame && (!firstFrame || (buffering && showBuffering))
    Column(
        modifier,
        verticalArrangement =
            Arrangement.spacedBy(if (video && videoContent == null) 12.dp else 0.dp),
    ) {
        if (videoContent != null) videoContent(player, loadingVideo, firstFrame)
        else if (video)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 240.dp).aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { androidx.media3.ui.PlayerView(it).apply { useController = false } },
                    update = { it.player = player },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
                if (loadingVideo) CanvasLoading()
            }
        Surface(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = if (video) 4.dp else 8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = if (video) 0.dp else 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                        if (playing) R.string.pause else R.string.play,
                        enabled = player != null && error == null && !paused,
                    ) {
                        onInteraction()
                        if (playing) player?.pause() else play?.invoke()
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Slider(
                            state = slider,
                            onValueChange = {
                                onInteraction()
                                dragging = it
                                slider.value = it
                                savePosition(it.toLong().coerceIn(0, duration))
                                if (video)
                                    player?.let { current ->
                                        current.setScrubbingModeEnabled(true)
                                        current.seekTo(it.toLong().coerceIn(0, duration))
                                    }
                            },
                            onValueChangeFinished = {
                                dragging?.let {
                                    position = it.toLong().coerceIn(0, duration)
                                    player?.seekTo(position)
                                    savePosition(position)
                                }
                                if (video) player?.setScrubbingModeEnabled(false)
                                dragging = null
                            },
                            enabled =
                                player != null && seekable && duration > 0 && error == null && !paused,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .then(if (video) Modifier.height(32.dp) else Modifier)
                                    .semantics {
                                        contentDescription = seekLabel
                                        stateDescription = seekDescription
                                    },
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(elapsed, style = MaterialTheme.typography.labelSmall)
                            if (auditionChannels.isNotEmpty()) {
                                val channels = auditionChannels.sorted().map { audioChannelLabel(it, channelCount) }
                                Text(
                                    stringResource(R.string.audio_audition_status, channels.joinToString(" · ")),
                                    Modifier.weight(1f).padding(horizontal = 8.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(total, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!video && showBuffering) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
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
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun channelAudition(count: Int, selected: Set<Int>) = ChannelMixingAudioProcessor().apply {
    require(count > 0 && selected.isNotEmpty() && selected.all { it in 0 until count })
    putChannelMixingMatrix(ChannelMixingMatrix(count, 1,
        FloatArray(count) { if (it in selected) 1f / selected.size else 0f }))
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    @Suppress("DEPRECATION")
    override fun configure(config: AudioSink.AudioSinkConfig) {
        val format = config.format
        // Android requires MONO rather than a lone FRONT_CENTER from WAVE_FORMAT_EXTENSIBLE.
        val output = if (format.channelCount == 1 && format.channelMask == android.media.AudioFormat.CHANNEL_OUT_FRONT_CENTER)
            AudioSink.AudioSinkConfig.Builder(
                format.buildUpon().setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build(),
            )
                .setPreferredBufferSizeOverride(config.preferredBufferSizeOverride)
                .setOutputChannelMapping(config.outputChannelMapping)
                .setTimeline(config.timeline)
                .setMediaPeriodId(config.mediaPeriodId)
                .build()
        else config
        super.configure(output)
    }
}

internal fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600)
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
