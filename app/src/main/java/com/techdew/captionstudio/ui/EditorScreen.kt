package com.techdew.captionstudio.ui

import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.techdew.captionstudio.AppViewModel
import com.techdew.captionstudio.CaptionText
import com.techdew.captionstudio.ExportState
import com.techdew.captionstudio.VideoInfo
import kotlinx.coroutines.delay

/** Lets the caption list talk to the preview player without recomposing anything. */
class PreviewController {
    var player: ExoPlayer? = null

    fun seekTo(ms: Long) {
        player?.seekTo(ms)
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }
}

@Composable
fun EditorScreen(vm: AppViewModel, onSaveAs: (String) -> Unit) {
    val video = vm.video ?: return
    var tab by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    val position = remember { mutableLongStateOf(0L) }
    val controller = remember { PreviewController() }
    val busy = vm.exportState is ExportState.Running || vm.exportState is ExportState.Saving

    LaunchedEffect(busy) { if (busy) tab = 2 }

    Column(Modifier.fillMaxSize()) {
        EditorHeader(vm, video)
        if (busy) {
            // The preview player is released here on purpose: low-end phones can't run two video decoders at once.
            ExportProgress(vm)
        } else {
            val maxPreview = (LocalConfiguration.current.screenHeightDp * 0.34f).dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                PreviewPlayer(vm, video, controller, position, maxPreview) { playing = it }
            }
            PlayerControls(position, video.durationMs, playing, controller)
            TabBar(tab) { tab = it }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (tab) {
                    0 -> CaptionsTab(vm, position) { ms ->
                        position.longValue = ms
                        controller.seekTo(ms)
                    }
                    1 -> StyleTab(vm)
                    else -> ExportTab(vm, onSaveAs)
                }
            }
        }
    }
}

@Composable
private fun EditorHeader(vm: AppViewModel, video: VideoInfo) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundIconButton(onClick = { vm.back() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Pal.Smoke)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(video.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                vm.captions.size.toString() + " captions · " + CaptionText.formatTime(video.durationMs).substringBefore('.'),
                fontSize = 12.sp,
                color = Pal.SmokeDim
            )
        }
        RoundIconButton(onClick = { vm.openSettings() }) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Pal.Smoke)
        }
    }
}

@Composable
private fun PreviewPlayer(
    vm: AppViewModel,
    video: VideoInfo,
    controller: PreviewController,
    position: MutableLongState,
    maxHeight: Dp,
    onPlaying: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val player = remember(video.uri) { ExoPlayer.Builder(context).build() }

    DisposableEffect(player) {
        player.setMediaItem(MediaItem.fromUri(video.uri))
        player.prepare()
        player.playWhenReady = false
        controller.player = player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaying(isPlaying)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            controller.player = null
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            position.longValue = player.currentPosition
            delay(40)
        }
    }

    Box(
        Modifier
            .heightIn(max = maxHeight)
            .aspectRatio(video.aspect)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            modifier = Modifier.fillMaxSize()
        )
        // Live caption overlay: the very same renderer that is used for the exported video.
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // drag = move the caption up/down, pinch = resize
                    detectTransformGestures { _, pan, zoom, _ ->
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        vm.updateStyle { s ->
                            s.copy(
                                posY = (s.posY + pan.y / h).coerceIn(0.05f, 0.95f),
                                size = (s.size * zoom).coerceIn(0.02f, 0.12f)
                            )
                        }
                    }
                }
        ) {
            val t = position.longValue
            val idx = CaptionText.activeIndex(vm.captions, t)
            if (idx >= 0) {
                val c = vm.captions[idx]
                val st = vm.style
                drawIntoCanvas { canvas ->
                    vm.renderer.draw(canvas.nativeCanvas, size.width.toInt(), size.height.toInt(), c.text, st, t - c.startMs)
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(position: MutableLongState, durationMs: Long, playing: Boolean, controller: PreviewController) {
    val pos = position.longValue
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Pal.Electric)
                .clickable { controller.togglePlay() },
            contentAlignment = Alignment.Center
        ) {
            PlayPauseIcon(playing)
        }
        Text(
            CaptionText.formatTime(pos).substringBefore('.'),
            fontSize = 12.sp,
            color = Pal.SmokeDim,
            modifier = Modifier
                .padding(start = 10.dp)
                .width(42.dp)
        )
        Slider(
            value = pos.coerceIn(0L, durationMs).toFloat(),
            onValueChange = { v ->
                position.longValue = v.toLong()
                controller.seekTo(v.toLong())
            },
            valueRange = 0f..maxOf(1f, durationMs.toFloat()),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Pal.Electric,
                activeTrackColor = Pal.Electric,
                inactiveTrackColor = Pal.Outline
            )
        )
        Text(
            CaptionText.formatTime(durationMs).substringBefore('.'),
            fontSize = 12.sp,
            color = Pal.SmokeDim,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val names = listOf("Captions", "Style", "Export")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Pal.Surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        names.forEachIndexed { i, n ->
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (i == selected) Pal.Electric else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    n,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (i == selected) Pal.Black else Pal.SmokeDim
                )
            }
        }
    }
}

@Composable
private fun ExportProgress(vm: AppViewModel) {
    val s = vm.exportState
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Panel(accent = true) {
            Text(
                if (s is ExportState.Saving) "Saving to your gallery…" else "Exporting video",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            val p = ((s as? ExportState.Running)?.progress ?: 1f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = Pal.Electric,
                trackColor = Pal.Outline
            )
            Text((p * 100).toInt().toString() + "%", fontSize = 15.sp)
            Text(
                "The phone's own video encoder is burning the captions in. A long video can take several minutes on " +
                    "a low-end phone. Keep the app open — the screen stays on.",
                fontSize = 13.sp,
                color = Pal.SmokeDim
            )
            if (s is ExportState.Running) {
                GhostButton("Cancel", onClick = { vm.cancelExport() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
