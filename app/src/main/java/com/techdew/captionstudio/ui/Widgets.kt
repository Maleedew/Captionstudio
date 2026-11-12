package com.techdew.captionstudio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Rounded surface that groups related controls. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Pal.Surface)
            .border(1.dp, if (accent) Pal.Electric else Pal.Outline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Pal.Electric,
            contentColor = Pal.Black,
            disabledContainerColor = Pal.Surface2,
            disabledContentColor = Pal.SmokeDim
        )
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Pal.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Pal.Smoke, disabledContentColor = Pal.SmokeDim)
    ) {
        Text(text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    }
}

/** Compact rounded action used inside caption rows. */
@Composable
fun MiniButton(text: String, onClick: () -> Unit, danger: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Pal.Surface2)
            .border(1.dp, if (danger) Pal.Danger else Pal.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 12.sp, color = if (danger) Pal.Danger else Pal.Smoke, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun RoundIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.Surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Pal.Electric else Pal.Surface2)
            .border(1.dp, if (selected) Pal.Electric else Pal.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Pal.Black else Pal.Smoke,
            fontFamily = fontFamily
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Pal.Electric, letterSpacing = 1.sp)
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, color = Pal.SmokeDim)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Pal.Electric,
                activeTrackColor = Pal.Electric,
                inactiveTrackColor = Pal.Outline
            )
        )
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Pal.Black,
                checkedTrackColor = Pal.Electric,
                uncheckedThumbColor = Pal.SmokeDim,
                uncheckedTrackColor = Pal.Surface2,
                uncheckedBorderColor = Pal.Outline
            )
        )
    }
}

private val swatchColors: List<Int> = listOf(
    0xFFF2F4F7, 0xFFFFFFFF, 0xFF00B7FF, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A,
    0xFFFF2D95, 0xFFBF5AF2, 0xFF30D158, 0xFF64D2FF, 0xFF07080A, 0xFF000000
).map { it.toInt() }

private fun parseHex(t: String): Int? {
    val h = t.trim().removePrefix("#")
    if (h.length != 6) return null
    val v = h.toLongOrNull(16) ?: return null
    return (0xFF000000L or v).toInt()
}

/** A row of colour swatches plus a "#" button for any custom hex colour. */
@Composable
fun ColorRow(label: String, color: Int, onChange: (Int) -> Unit) {
    var dialog by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.width(76.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (c in swatchColors) {
                val selected = (c or 0xFF000000.toInt()) == (color or 0xFF000000.toInt())
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(if (selected) 3.dp else 1.dp, if (selected) Pal.Electric else Pal.Outline, CircleShape)
                        .clickable { onChange(c) }
                )
            }
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Pal.Surface2)
                    .border(1.dp, Pal.Outline, CircleShape)
                    .clickable { dialog = true },
                contentAlignment = Alignment.Center
            ) {
                Text("#", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (dialog) {
        var text by remember { mutableStateOf("%06X".format(color and 0xFFFFFF)) }
        AlertDialog(
            onDismissRequest = { dialog = false },
            confirmButton = {
                TextButton(onClick = {
                    parseHex(text)?.let { onChange(it) }
                    dialog = false
                }) { Text("Apply", color = Pal.Electric) }
            },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancel") } },
            title = { Text("Custom colour") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isLetterOrDigit() || ch == '#' }.take(7).uppercase() },
                    singleLine = true,
                    label = { Text("Hex (RRGGBB)") }
                )
            },
            containerColor = Pal.Surface2,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

/** Play triangle from the icon set, or two bars when playing. */
@Composable
fun PlayPauseIcon(playing: Boolean, tint: Color = Pal.Black) {
    if (!playing) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = tint, modifier = Modifier.size(26.dp))
    } else {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val bar = Size(w * 0.24f, h * 0.78f)
            val r = CornerRadius(w * 0.07f)
            drawRoundRect(tint, topLeft = Offset(w * 0.14f, h * 0.11f), size = bar, cornerRadius = r)
            drawRoundRect(tint, topLeft = Offset(w * 0.62f, h * 0.11f), size = bar, cornerRadius = r)
        }
    }
}
