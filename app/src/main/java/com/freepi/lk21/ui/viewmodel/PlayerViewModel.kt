@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.freepi.lk21.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.freepi.lk21.core.userMessage
import com.freepi.lk21.data.MovieRepository
import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.ResolvedStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class VideoQuality(val label: String, val height: Int)

data class SubtitleOption(val id: String, val label: String)

data class PlayerUiState(
    val loadingDetail: Boolean = true,
    val detail: MovieDetail? = null,
    val sourceIndex: Int = 0,
    val resolving: Boolean = false,
    val resolved: ResolvedStream? = null,
    val error: String? = null,
    val buffering: Boolean = true,
    val isPlaying: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val qualities: List<VideoQuality> = emptyList(),
    val selectedQualityHeight: Int? = null,
    val subtitleOptions: List<SubtitleOption> = emptyList(),
    val selectedSubtitleId: String? = null,
    val controlsVisible: Boolean = true,
)

class PlayerViewModel(
    context: Context,
    private val repository: MovieRepository,
    okHttpClient: OkHttpClient,
    private val appScope: CoroutineScope,
    private val slug: String,
    initialSourceIndex: Int,
) : ViewModel() {

    private val appContext = context.applicationContext

    private val httpFactory = OkHttpDataSource.Factory(okHttpClient)
        .setUserAgent(com.freepi.lk21.data.remote.Lk21Client.USER_AGENT)

    private val mediaSourceFactory = DefaultMediaSourceFactory(
        DefaultDataSource.Factory(appContext, httpFactory)
    )

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(mediaSourceFactory)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private val _state = MutableStateFlow(PlayerUiState(sourceIndex = initialSourceIndex))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var resolveJob: Job? = null
    private var resumePositionMs: Long = 0L
    private var resumeApplied = false
    private var textGroups: List<Tracks.Group> = emptyList()
    private var lastSaveAt = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val buffering = playbackState == Player.STATE_BUFFERING
                _state.value = _state.value.copy(
                    buffering = buffering,
                    ended = playbackState == Player.STATE_ENDED,
                )
                if (playbackState == Player.STATE_READY) {
                    val duration = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
                    if (!resumeApplied) {
                        resumeApplied = true
                        if (resumePositionMs > 10_000 && duration > 0 &&
                            resumePositionMs < duration - 10_000
                        ) {
                            player.seekTo(resumePositionMs)
                        }
                    }
                    _state.value = _state.value.copy(
                        durationMs = duration,
                        positionMs = player.currentPosition,
                    )
                }
                if (playbackState == Player.STATE_ENDED) {
                    saveProgress(force = true)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (!isPlaying) saveProgress()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(
                    buffering = false,
                    error = "Playback failed: ${error.errorCodeName}. Try another source.",
                )
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateTracks(tracks)
            }
        })

        startPositionTicker()
        load(initialSourceIndex)
    }

    private fun load(index: Int) {
        _state.value = _state.value.copy(loadingDetail = true, error = null)
        viewModelScope.launch {
            try {
                val detail = repository.detail(slug)
                val progress = repository.getProgress(detail.slug)
                resumePositionMs = progress?.takeIf { !it.completed }?.positionMs ?: 0L
                _state.value = _state.value.copy(
                    loadingDetail = false,
                    detail = detail,
                    durationMs = detail.durationMs ?: 0L,
                )
                if (detail.sources.isEmpty()) {
                    _state.value = _state.value.copy(
                        error = "No streaming providers were listed for this title.",
                        buffering = false,
                    )
                } else {
                    resolveAndPlay(index.coerceIn(0, detail.sources.lastIndex))
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loadingDetail = false,
                    buffering = false,
                    error = t.userMessage(),
                )
            }
        }
    }

    private fun resolveAndPlay(index: Int) {
        val detail = _state.value.detail ?: return
        if (index !in detail.sources.indices) return
        resolveJob?.cancel()
        resumeApplied = false
        player.stop()
        player.clearMediaItems()
        _state.value = _state.value.copy(
            sourceIndex = index,
            resolving = true,
            buffering = true,
            error = null,
            resolved = null,
            selectedQualityHeight = null,
            qualities = emptyList(),
            subtitleOptions = emptyList(),
            selectedSubtitleId = null,
        )
        resolveJob = viewModelScope.launch {
            try {
                val source = detail.sources[index]
                val resolved = repository.resolve(detail, source)
                if (!isActive) return@launch
                _state.value = _state.value.copy(resolving = false, resolved = resolved)
                prepare(resolved)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    resolving = false,
                    buffering = false,
                    error = t.userMessage(),
                )
            }
        }
    }

    private fun prepare(resolved: ResolvedStream) {
        val subtitles = resolved.subtitles.distinctBy { it.url }.mapIndexed { index, track ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setMimeType(
                    if (track.url.contains(".srt", ignoreCase = true)) MimeTypes.APPLICATION_SUBRIP
                    else MimeTypes.TEXT_VTT
                )
                .setLanguage(track.language ?: "und")
                .setLabel(track.label)
                .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(resolved.url)
            .setMimeType(resolved.mimeType)
            .setSubtitleConfigurations(subtitles)
            .build()

        httpFactory.setDefaultRequestProperties(resolved.headers)
        val mediaSource = mediaSourceFactory.createMediaSource(item)
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        _state.value = _state.value.copy(
            buffering = true,
            positionMs = resumePositionMs,
        )
    }

    private fun updateTracks(tracks: Tracks) {
        val qualities = LinkedHashMap<Int, VideoQuality>()
        val texts = ArrayList<SubtitleOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val height = format.height
                    if (height > 0) {
                        qualities[height] = VideoQuality("${height}p", height)
                    }
                }
            }
        }
        textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        textGroups.forEachIndexed { index, group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label
                    ?: format.language
                    ?: "Subtitle ${index + 1}"
                texts.add(SubtitleOption("text-$index", label))
            }
        }
        _state.value = _state.value.copy(
            qualities = qualities.values.sortedByDescending { it.height },
            subtitleOptions = texts.distinctBy { it.id },
        )
    }

    private fun startPositionTicker() {
        viewModelScope.launch {
            var saveTick = 0
            while (isActive) {
                val state = _state.value
                if (state.resolved != null) {
                    val position = player.currentPosition.coerceAtLeast(0)
                    val duration = player.duration.takeIf { it > 0 } ?: state.durationMs
                    _state.value = state.copy(
                        positionMs = position,
                        durationMs = duration,
                        isPlaying = player.isPlaying,
                        buffering = player.playbackState == Player.STATE_BUFFERING,
                    )
                    saveTick++
                    if (saveTick >= 4 && player.isPlaying) { // every ~2s
                        saveTick = 0
                        saveProgress()
                    }
                }
                delay(500)
            }
        }
    }

    fun saveProgress(force: Boolean = false) {
        val detail = _state.value.detail ?: return
        val position = player.currentPosition
        val duration = if (player.duration > 0) player.duration else _state.value.durationMs
        if (duration <= 0) return
        if (!force && position < 2_000) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveAt < 1_500) return
        lastSaveAt = now
        val safePosition = if (player.playbackState == Player.STATE_ENDED) duration else position
        appScope.launch {
            runCatching { repository.saveProgress(detail, safePosition, duration) }
        }
    }

    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceIn(0, player.duration.coerceAtLeast(0)))
    }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
        seekTo((player.currentPosition + deltaMs).coerceIn(0, duration.coerceAtLeast(0)))
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    fun selectSource(index: Int) {
        if (index == _state.value.sourceIndex && _state.value.resolved != null) return
        resolveAndPlay(index)
    }

    fun retry() = resolveAndPlay(_state.value.sourceIndex)

    fun selectQuality(height: Int?) {
        val builder = player.trackSelectionParameters.buildUpon()
        builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        builder.setMinVideoSize(0, 0)
        builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (height != null) {
            builder.setMaxVideoSize(Int.MAX_VALUE, height)
        }
        player.trackSelectionParameters = builder.build()
        _state.value = _state.value.copy(selectedQualityHeight = height)
    }

    fun selectSubtitle(id: String?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (id == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            val index = id.removePrefix("text-").toIntOrNull()
            val group = index?.let { textGroups.getOrNull(it) }
            if (group != null) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            }
        }
        player.trackSelectionParameters = builder.build()
        _state.value = _state.value.copy(selectedSubtitleId = id)
    }

    fun setControlsVisible(visible: Boolean) {
        _state.value = _state.value.copy(controlsVisible = visible)
    }

    override fun onCleared() {
        saveProgress(force = true)
        resolveJob?.cancel()
        player.release()
        super.onCleared()
    }
}
