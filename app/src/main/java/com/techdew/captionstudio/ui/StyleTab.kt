package com.techdew.captionstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techdew.captionstudio.Anim
import com.techdew.captionstudio.AppViewModel
import com.techdew.captionstudio.CaptionStyle
import com.techdew.captionstudio.Fonts
import com.techdew.captionstudio.Presets
import kotlin.math.roundToInt

private fun pct(v: Float): String = (v * 100f).roundToInt().toString() + "%"

@Composable
fun StyleTab(vm: AppViewModel) {
    val s = vm.style
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Panel {
            SectionTitle("Quick looks")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in Presets.names) {
                    Chip(name, selected = false, onClick = { vm.updateStyle { Presets.apply(name, it) } })
                }
            }
            Text(
                "Tip: drag the caption on the video to move it, pinch to resize.",
                fontSize = 12.sp,
                color = Pal.SmokeDim
            )
        }

        Panel {
            SectionTitle("Font")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (spec in Fonts.all) {
                    val family = remember(spec.id) {
                        FontFamily(vm.renderer.typeface(CaptionStyle(fontId = spec.id, bold = false)))
                    }
                    FontChip(spec.label, s.fontId == spec.id, family) { vm.updateStyle { it.copy(fontId = spec.id) } }
                }
            }
            LabeledSlider(
                label = "Size",
                valueText = "%.1f%%".format(s.size * 100f),
                value = s.size,
                onChange = { v -> vm.updateStyle { it.copy(size = v) } },
                range = 0.02f..0.10f
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Bold", s.bold, onClick = { vm.updateStyle { it.copy(bold = !it.bold) } })
                Chip("Italic", s.italic, onClick = { vm.updateStyle { it.copy(italic = !it.italic) } })
                Chip("UPPERCASE", s.uppercase, onClick = { vm.updateStyle { it.copy(uppercase = !it.uppercase) } })
            }
        }

        Panel {
            SectionTitle("Text colour")
            ColorRow("Colour", s.textColor) { c -> vm.updateStyle { it.copy(textColor = c) } }
        }

        Panel {
            SectionTitle("Outline")
            ToggleRow("Outline around letters", s.strokeOn) { on -> vm.updateStyle { it.copy(strokeOn = on) } }
            if (s.strokeOn) {
                ColorRow("Colour", s.strokeColor) { c -> vm.updateStyle { it.copy(strokeColor = c) } }
                LabeledSlider(
                    label = "Thickness",
                    valueText = pct(s.strokeWidth),
                    value = s.strokeWidth,
                    onChange = { v -> vm.updateStyle { it.copy(strokeWidth = v) } },
                    range = 0.02f..0.40f
                )
            }
        }

        Panel {
            SectionTitle("Shadow / glow")
            ToggleRow("Shadow", s.shadowOn) { on -> vm.updateStyle { it.copy(shadowOn = on) } }
            if (s.shadowOn) {
                ColorRow("Colour", s.shadowColor) { c -> vm.updateStyle { it.copy(shadowColor = c) } }
                LabeledSlider(
                    label = "Softness",
                    valueText = pct(s.shadowBlur),
                    value = s.shadowBlur,
                    onChange = { v -> vm.updateStyle { it.copy(shadowBlur = v) } },
                    range = 0f..0.5f
                )
                LabeledSlider(
                    label = "Distance",
                    valueText = pct(s.shadowOffset),
                    value = s.shadowOffset,
                    onChange = { v -> vm.updateStyle { it.copy(shadowOffset = v) } },
                    range = 0f..0.4f
                )
            }
        }

        Panel {
            SectionTitle("Background box")
            ToggleRow("Box behind the text", s.bgOn) { on -> vm.updateStyle { it.copy(bgOn = on) } }
            if (s.bgOn) {
                ColorRow("Colour", s.bgColor) { c -> vm.updateStyle { it.copy(bgColor = c) } }
                LabeledSlider(
                    label = "Opacity",
                    valueText = pct(s.bgAlpha),
                    value = s.bgAlpha,
                    onChange = { v -> vm.updateStyle { it.copy(bgAlpha = v) } },
                    range = 0f..1f
                )
                LabeledSlider(
                    label = "Rounded corners",
                    valueText = pct(s.bgRadius),
                    value = s.bgRadius,
                    onChange = { v -> vm.updateStyle { it.copy(bgRadius = v) } },
                    range = 0f..0.8f
                )
            }
        }

        Panel {
            SectionTitle("Position")
            LabeledSlider(
                label = "Vertical position",
                valueText = pct(s.posY),
                value = s.posY,
                onChange = { v -> vm.updateStyle { it.copy(posY = v) } },
                range = 0.08f..0.95f
            )
            LabeledSlider(
                label = "Text width",
                valueText = pct(s.maxWidth),
                value = s.maxWidth,
                onChange = { v -> vm.updateStyle { it.copy(maxWidth = v) } },
                range = 0.5f..0.98f
            )
        }

        Panel {
            SectionTitle("Zoom animation")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("None", s.anim == Anim.NONE, onClick = { vm.updateStyle { it.copy(anim = Anim.NONE) } })
                Chip("Pop / zoom-in", s.anim == Anim.POP, onClick = { vm.updateStyle { it.copy(anim = Anim.POP) } })
                Chip("Fade", s.anim == Anim.FADE, onClick = { vm.updateStyle { it.copy(anim = Anim.FADE) } })
            }
            if (s.anim == Anim.POP) {
                LabeledSlider(
                    label = "Zoom amount",
                    valueText = pct(s.animStrength),
                    value = s.animStrength,
                    onChange = { v -> vm.updateStyle { it.copy(animStrength = v) } },
                    range = 0f..0.6f
                )
            }
        }

        GhostButton("Reset style", onClick = { vm.updateStyle { CaptionStyle() } }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FontChip(label: String, selected: Boolean, family: FontFamily, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Pal.Electric else Pal.Surface2)
            .border(1.dp, if (selected) Pal.Electric else Pal.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("අම Aa", fontFamily = family, fontSize = 20.sp, color = if (selected) Pal.Black else Pal.Smoke)
        Text(label, fontSize = 10.sp, color = if (selected) Pal.Black else Pal.SmokeDim)
    }
}
