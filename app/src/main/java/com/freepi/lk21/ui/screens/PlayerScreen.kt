@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.freepi.lk21.ui.screens

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.freepi.lk21.ui.theme.Primary
import com.freepi.lk21.ui.theme.TextSecondary
import com.freepi.lk21.ui.util.findActivity
import com.freepi.lk21.ui.viewmodel.PlayerUiState
import com.freepi.lk21.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var landscape by remember { mutableStateOf(true) }
    var showSpeed by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }

    BackHandler {
        viewModel.saveProgress(force = true)
        onBack()
    }

    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(landscape) {
        activity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    // Auto-hide the controls during playback.
    LaunchedEffect(state.controlsVisible, state.isPlaying) {
        if (state.controlsVisible && state.isPlaying) {
            delay(3_500)
            viewModel.setControlsVisible(false)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { viewModel.setControlsVisible(!state.controlsVisible) }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { view -> view.player = viewModel.player },
            modifier = Modifier.fillMaxSize(),
        )

        // Transparent tap layer so controls toggle reliably over the surface.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { viewModel.setControlsVisible(!state.controlsVisible) }
        )

        if (state.resolving || state.buffering || state.loadingDetail) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Primary)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            state.loadingDetail -> "Loading title…"
                            state.resolving -> "Resolving ${state.detail?.sources?.getOrNull(state.sourceIndex)?.provider ?: "source"}…"
                            else -> "Buffering…"
                        },
                        color = Color.White,
                    )
                }
            }
        }

        if (state.error != null && !state.resolving) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000))
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Can't play this source",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.error.orEmpty(),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionPill(text = "Retry", icon = Icons.Filled.Refresh) {
                            viewModel.retry()
                        }
                        if ((state.detail?.sources?.size ?: 0) > 1) {
                            ActionPill(text = "Sources", icon = Icons.Filled.SwapHoriz) {
                                showSources = true
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top gradient and title row.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xDD000000), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        viewModel.saveProgress(force = true)
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.detail?.title ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.resolved?.provider
                                ?.ifBlank { state.detail?.sources?.getOrNull(state.sourceIndex)?.provider }
                                ?: "Native player",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // Center playback buttons.
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LargeRoundButton(Icons.Filled.Replay10, "Back 10 seconds") {
                        viewModel.seekBy(-10_000)
                    }
                    LargeRoundButton(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        size = 68.dp,
                        background = Primary,
                    ) { viewModel.togglePlay() }
                    LargeRoundButton(Icons.Filled.Forward10, "Forward 10 seconds") {
                        viewModel.seekBy(10_000)
                    }
                }

                // Bottom controls.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xE6000000))
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    var scrubbing by remember { mutableStateOf(false) }
                    var scrubPosition by remember { mutableLongStateOf(0L) }
                    val duration = state.durationMs.coerceAtLeast(1L)
                    val sliderValue = if (scrubbing) scrubPosition else state.positionMs
                    Slider(
                        value = sliderValue.coerceIn(0, duration).toFloat(),
                        onValueChange = {
                            scrubbing = true
                            scrubPosition = it.toLong()
                        },
                        onValueChangeFinished = {
                            viewModel.seekTo(scrubPosition)
                            scrubbing = false
                        },
                        valueRange = 0f..duration.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Primary,
                            activeTrackColor = Primary,
                            inactiveTrackColor = Color(0x55FFFFFF),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${formatPlayerTime(sliderValue)} / ${formatPlayerTime(state.durationMs)}",
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        ControlIcon(
                            icon = Icons.Filled.Speed,
                            label = "${state.speed}x",
                        ) { showSpeed = true }
                        ControlIcon(
                            icon = Icons.Filled.HighQuality,
                            label = state.selectedQualityHeight?.let { "${it}p" } ?: "Auto",
                        ) { showQuality = true }
                        ControlIcon(
                            icon = Icons.Filled.Subtitles,
                            label = if (state.selectedSubtitleId == null) "CC" else "CC on",
                        ) { showSubtitles = true }
                        ControlIcon(
                            icon = Icons.Filled.SwapHoriz,
                            label = "Source",
                        ) { showSources = true }
                        IconButton(onClick = { landscape = !landscape }) {
                            Icon(
                                if (landscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = "Toggle fullscreen",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSpeed) {
        OptionDialog(
            title = "Playback speed",
            options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { it to "${it}x" },
            selected = state.speed,
            onSelect = {
                viewModel.setSpeed(it)
                showSpeed = false
            },
            onDismiss = { showSpeed = false },
        )
    }

    if (showQuality) {
        val options = listOf<Pair<Int?, String>>(null to "Auto") +
            state.qualities.map { it.height to it.label }
        OptionDialog(
            title = "Video quality",
            options = options,
            selected = state.selectedQualityHeight,
            onSelect = {
                viewModel.selectQuality(it)
                showQuality = false
            },
            onDismiss = { showQuality = false },
        )
    }

    if (showSubtitles) {
        val options = listOf<Pair<String?, String>>(null to "Off") +
            state.subtitleOptions.map { it.id to it.label }
        OptionDialog(
            title = "Subtitles",
            options = options,
            selected = state.selectedSubtitleId,
            onSelect = {
                viewModel.selectSubtitle(it)
                showSubtitles = false
            },
            onDismiss = { showSubtitles = false },
        )
    }

    if (showSources) {
        val sources = state.detail?.sources.orEmpty()
        AlertDialog(
            onDismissRequest = { showSources = false },
            title = { Text("Choose a source") },
            text = {
                Column {
                    sources.forEachIndexed { index, source ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectSource(index)
                                    showSources = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                source.provider,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (index == state.sourceIndex) FontWeight.Bold else FontWeight.Normal,
                                color = if (index == state.sourceIndex) Primary else MaterialTheme.colorScheme.onSurface,
                            )
                            val qualities = source.qualities.ifEmpty { listOfNotNull(source.maxQuality) }
                            Text(
                                qualities.joinToString(" · ") { "${it}p" },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSources = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun LargeRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    background: Color = Color(0x99000000),
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .background(background, RoundedCornerShape(50))
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun ControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}

@Composable
private fun ActionPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .background(Primary, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun <T> OptionDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            color = if (value == selected) Primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatPlayerTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
