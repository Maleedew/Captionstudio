package com.techdew.captionstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techdew.captionstudio.AppViewModel
import com.techdew.captionstudio.Caption
import com.techdew.captionstudio.CaptionText
import kotlin.math.roundToInt

@Composable
fun CaptionsTab(vm: AppViewModel, position: MutableLongState, onSeek: (Long) -> Unit) {
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var confirmResplit by remember { mutableStateOf(false) }
    // Only changes a few times per second, so the list is not recomposed on every playback tick.
    val activeIdx by remember { derivedStateOf { CaptionText.activeIndex(vm.captions, position.longValue) } }
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Panel {
                SectionTitle("Caption length")
                LabeledSlider(
                    label = "Words per caption",
                    valueText = vm.wordsPerCaption.toString(),
                    value = vm.wordsPerCaption.toFloat(),
                    onChange = { vm.setWords(it.roundToInt()) },
                    range = 1f..12f,
                    steps = 10
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniButton("Re-split captions", onClick = { if (vm.dirty) confirmResplit = true else vm.resplit() })
                    MiniButton("+ Add at playhead", onClick = { selectedId = vm.addCaptionAt(position.longValue) })
                }
                Text(
                    "Tap a caption's time to jump there. Edit the words directly; tap “Edit timing” for fine control.",
                    fontSize = 12.sp,
                    color = Pal.SmokeDim
                )
            }
        }
        if (vm.captions.isEmpty()) {
            item {
                Text(
                    "No captions yet. Tap “+ Add at playhead” to write one by hand.",
                    fontSize = 14.sp,
                    color = Pal.SmokeDim,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        itemsIndexed(vm.captions, key = { _, c -> c.id }) { index, c ->
            CaptionRow(
                vm = vm,
                c = c,
                active = index == activeIdx,
                selected = selectedId == c.id,
                position = position,
                onSelect = { selectedId = if (selectedId == c.id) null else c.id },
                onSeek = onSeek
            )
        }
    }

    if (confirmResplit) {
        AlertDialog(
            onDismissRequest = { confirmResplit = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmResplit = false
                    vm.resplit()
                }) { Text("Re-split", color = Pal.Electric) }
            },
            dismissButton = { TextButton(onClick = { confirmResplit = false }) { Text("Cancel") } },
            title = { Text("Re-split captions?") },
            text = { Text("This rebuilds the captions from the original transcript and discards the edits you made by hand.") },
            containerColor = Pal.Surface2,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun CaptionRow(
    vm: AppViewModel,
    c: Caption,
    active: Boolean,
    selected: Boolean,
    position: MutableLongState,
    onSelect: () -> Unit,
    onSeek: (Long) -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (active) Pal.Electric.copy(alpha = 0.14f) else Pal.Surface)
            .border(1.dp, if (selected) Pal.Electric else Pal.Outline, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                CaptionText.formatTime(c.startMs) + "  →  " + CaptionText.formatTime(c.endMs),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Pal.Electric,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSeek(c.startMs) }
            )
            Text(
                if (selected) "Close" else "Edit timing",
                fontSize = 12.sp,
                color = Pal.SmokeDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect() }
                    .padding(6.dp)
            )
        }
        OutlinedTextField(
            value = c.text,
            onValueChange = { vm.updateText(c.id, it) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 16.sp),
            shape = RoundedCornerShape(16.dp),
            minLines = 1,
            maxLines = 4
        )
        if (selected) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniButton("Start −0.1s", onClick = { vm.nudgeStart(c, -100L) })
                MiniButton("Start +0.1s", onClick = { vm.nudgeStart(c, 100L) })
                MiniButton("End −0.1s", onClick = { vm.nudgeEnd(c, -100L) })
                MiniButton("End +0.1s", onClick = { vm.nudgeEnd(c, 100L) })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniButton("Start = playhead", onClick = { vm.setStart(c.id, position.longValue) })
                MiniButton("End = playhead", onClick = { vm.setEnd(c.id, position.longValue) })
                MiniButton("Split", onClick = { vm.splitCaption(c.id) })
                MiniButton("Merge next", onClick = { vm.mergeWithNext(c.id) })
                MiniButton("Delete", onClick = { vm.deleteCaption(c.id) }, danger = true)
            }
        }
    }
}
