package com.techdew.captionstudio.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.techdew.captionstudio.AppViewModel
import com.techdew.captionstudio.ExportState
import java.io.File

private fun shareVideo(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share video"))
    } catch (e: Exception) {
        // nothing can handle the share intent
    }
}

@Composable
fun ExportTab(vm: AppViewModel, onSaveAs: (String) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Panel {
            SectionTitle("Quality")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("480p · fastest", vm.exportShortSide == 480, onClick = { vm.selectExportQuality(480) })
                Chip("720p · recommended", vm.exportShortSide == 720, onClick = { vm.selectExportQuality(720) })
                Chip("Original", vm.exportShortSide == 0, onClick = { vm.selectExportQuality(0) })
            }
            Text(
                "Lower quality exports faster and needs less memory. If a long video fails on an older phone, pick 480p.",
                fontSize = 13.sp,
                color = Pal.SmokeDim
            )
        }

        when (val s = vm.exportState) {
            is ExportState.Done -> Panel(accent = true) {
                Text("Your video is ready ✓", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (s.savedToGallery) {
                        "Saved in Movies/CaptionStudio — open your gallery to see it."
                    } else {
                        "Not saved to the gallery yet. Tap “Save to device”."
                    },
                    fontSize = 14.sp,
                    color = Pal.SmokeDim
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Share", onClick = { shareVideo(context, s.file) }, modifier = Modifier.weight(1f))
                    if (!s.savedToGallery) {
                        GhostButton("Save to device", onClick = { onSaveAs(s.name) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            is ExportState.Failed -> Panel {
                Text("Export failed", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Pal.Danger)
                Text(s.message, fontSize = 14.sp)
            }
            else -> {}
        }

        PrimaryButton(
            if (vm.exportState is ExportState.Done) "Export again" else "Export MP4",
            onClick = { vm.startExport() },
            modifier = Modifier.fillMaxWidth(),
            enabled = vm.captions.isNotEmpty()
        )
        Text(
            "Captions are burned into the picture, so they show up wherever you upload the video.",
            fontSize = 12.sp,
            color = Pal.SmokeDim
        )
    }
}
