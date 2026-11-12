package com.techdew.captionstudio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techdew.captionstudio.AppViewModel
import com.techdew.captionstudio.CaptionText
import com.techdew.captionstudio.JobState
import com.techdew.captionstudio.LangMode
import com.techdew.captionstudio.Provider
import kotlin.math.roundToInt

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // no browser installed
    }
}

// ------------------------------------------------------------------ Home

@Composable
fun HomeScreen(vm: AppViewModel, onImport: () -> Unit) {
    val keyReady = vm.hasKey(vm.provider)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Caption Studio", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Sinhala + English auto captions", fontSize = 14.sp, color = Pal.SmokeDim)
            }
            RoundIconButton(onClick = { vm.openSettings() }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Pal.Smoke)
            }
        }

        Panel {
            Text("Add captions to a video", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Pick a video (up to 15 minutes). The audio is read on your phone, turned into captions with a free API, " +
                    "and the captions are burned into a new MP4.",
                fontSize = 14.sp,
                color = Pal.SmokeDim
            )
            PrimaryButton("Choose video", onClick = onImport, modifier = Modifier.fillMaxWidth())
        }

        if (!keyReady) {
            Panel(accent = true) {
                Text("One-time setup", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Add your own free API key (takes a minute). It is stored encrypted on this phone only.",
                    fontSize = 14.sp,
                    color = Pal.SmokeDim
                )
                GhostButton("Open settings", onClick = { vm.openSettings() }, modifier = Modifier.fillMaxWidth())
            }
        }

        vm.saved?.let { s ->
            Panel {
                Text("Continue last project", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    s.video.name + " · " + s.captions.size + " captions · " +
                        CaptionText.formatTime(s.video.durationMs).substringBefore('.'),
                    fontSize = 14.sp,
                    color = Pal.SmokeDim
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Continue", onClick = { vm.resumeLast() }, modifier = Modifier.weight(1f))
                    GhostButton("Discard", onClick = { vm.discardSaved() })
                }
            }
        }

        Panel {
            Text("How it works", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "1  Import a video\n2  Captions are generated (Sinhala + English mixed)\n" +
                    "3  Fix words, change font, colours, shadow, size, animation\n4  Export MP4 and share",
                fontSize = 14.sp,
                color = Pal.SmokeDim,
                lineHeight = 22.sp
            )
        }
    }
}

// ------------------------------------------------------------------ Processing

@Composable
fun ProcessingScreen(vm: AppViewModel) {
    val state = vm.jobState
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Panel(accent = state is JobState.Failed) {
            when (state) {
                is JobState.Running -> {
                    Text("Creating captions", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(vm.video?.name ?: "", fontSize = 13.sp, color = Pal.SmokeDim, maxLines = 1)
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = Pal.Electric,
                        trackColor = Pal.Outline
                    )
                    Text(state.label, fontSize = 15.sp)
                    Text(
                        "Keep the app open. The screen stays on while this runs.",
                        fontSize = 13.sp,
                        color = Pal.SmokeDim
                    )
                    GhostButton("Cancel", onClick = { vm.back() }, modifier = Modifier.fillMaxWidth())
                }
                is JobState.Failed -> {
                    Text("Couldn't finish", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.Danger)
                    Text(state.message, fontSize = 15.sp)
                    if (state.needsKey) {
                        PrimaryButton("Open settings", onClick = { vm.openSettings() }, modifier = Modifier.fillMaxWidth())
                    }
                    PrimaryButton("Try again", onClick = { vm.startProcessing() }, modifier = Modifier.fillMaxWidth())
                    GhostButton("Back to start", onClick = { vm.goHome() }, modifier = Modifier.fillMaxWidth())
                }
                JobState.Idle -> {
                    Text("Preparing…", fontSize = 18.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
fun SettingsScreen(vm: AppViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Pal.Smoke)
            }
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        }

        Panel {
            SectionTitle("Speech-to-text provider")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Google Gemini", vm.provider == Provider.GEMINI, onClick = { vm.selectProvider(Provider.GEMINI) })
                Chip("Groq Whisper", vm.provider == Provider.GROQ, onClick = { vm.selectProvider(Provider.GROQ) })
            }
            Text(
                if (vm.provider == Provider.GEMINI) {
                    "Best choice for Sinhala + English mixed speech. Free tier, no card needed."
                } else {
                    "Very fast with precise timing, but weaker on Sinhala. Free tier, no card needed."
                },
                fontSize = 13.sp,
                color = Pal.SmokeDim
            )
        }

        KeyPanel(vm, Provider.GEMINI, "Gemini API key", "https://aistudio.google.com/app/apikey")
        KeyPanel(vm, Provider.GROQ, "Groq API key", "https://console.groq.com/keys")

        Panel {
            SectionTitle("Language & caption length")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Sinhala + English mix", vm.langMode == LangMode.MIXED, onClick = { vm.setLang(LangMode.MIXED) })
                Chip("English", vm.langMode == LangMode.ENGLISH, onClick = { vm.setLang(LangMode.ENGLISH) })
                Chip("Sinhala", vm.langMode == LangMode.SINHALA, onClick = { vm.setLang(LangMode.SINHALA) })
            }
            LabeledSlider(
                label = "Words per caption",
                valueText = vm.wordsPerCaption.toString(),
                value = vm.wordsPerCaption.toFloat(),
                onChange = { vm.setWords(it.roundToInt()) },
                range = 1f..12f,
                steps = 10
            )
            Text("1–3 gives fast CapCut-style captions, 5–7 gives subtitle lines.", fontSize = 13.sp, color = Pal.SmokeDim)
        }

        Panel {
            SectionTitle("Advanced")
            OutlinedTextField(
                value = vm.geminiModelText,
                onValueChange = { vm.setGeminiModel(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Gemini model (empty = automatic)") },
                shape = RoundedCornerShape(16.dp)
            )
            OutlinedTextField(
                value = vm.groqModelText,
                onValueChange = { vm.setGroqModel(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Groq model") },
                shape = RoundedCornerShape(16.dp)
            )
            Text(
                "Google renames and retires Gemini models often. Leave the Gemini field empty and the app finds a " +
                    "working free model for your key by itself.",
                fontSize = 13.sp,
                color = Pal.SmokeDim
            )
        }

        Panel {
            SectionTitle("Privacy & security")
            Text(
                "• API keys are encrypted with the Android Keystore and are never part of the APK.\n" +
                    "• Only the audio track is uploaded, to the provider you picked, for transcription.\n" +
                    "• Free tiers: Google may use submitted content to improve its products, so avoid confidential recordings.\n" +
                    "• This app has no server of its own and collects nothing.",
                fontSize = 13.sp,
                color = Pal.SmokeDim,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun KeyPanel(vm: AppViewModel, p: Provider, title: String, helpUrl: String) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val has = vm.hasKey(p)
    Panel(accent = vm.provider == p) {
        SectionTitle(title)
        Text(
            if (has) "Saved securely · " + vm.keyHint(p) else "Not set yet",
            fontSize = 14.sp,
            color = if (has) Pal.Electric else Pal.SmokeDim
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (has) "Paste a new key to replace it" else "Paste your key here") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(16.dp)
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniButton("Save", onClick = {
                vm.saveKey(p, input)
                if (input.isNotBlank()) input = ""
            })
            MiniButton("Test", onClick = { vm.testKey(p) })
            if (has) MiniButton("Remove", onClick = { vm.removeKey(p) }, danger = true)
            MiniButton("Get free key ↗", onClick = { openUrl(context, helpUrl) })
        }
        if (vm.keyTestProvider == p) {
            if (vm.keyTesting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Pal.Electric,
                    trackColor = Pal.Outline
                )
            }
            vm.keyTest?.let { Text(it, fontSize = 13.sp) }
        }
    }
}
