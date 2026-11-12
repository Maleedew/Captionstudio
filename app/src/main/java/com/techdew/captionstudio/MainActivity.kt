package com.techdew.captionstudio

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.techdew.captionstudio.ui.CaptionTheme
import com.techdew.captionstudio.ui.EditorScreen
import com.techdew.captionstudio.ui.HomeScreen
import com.techdew.captionstudio.ui.Pal
import com.techdew.captionstudio.ui.ProcessingScreen
import com.techdew.captionstudio.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CaptionTheme {
                AppRoot(vm)
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    val context = LocalContext.current

    // Video import uses the system file picker: no storage permission is needed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onVideoPicked(uri)
    }
    // Fallback "Save as" dialog (Android 8-9, or if the gallery save failed).
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) vm.saveExportTo(uri)
    }

    val message = vm.toast
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            vm.toast = null
        }
    }

    val busy = vm.screen == Screen.PROCESSING ||
        vm.exportState is ExportState.Running ||
        vm.exportState is ExportState.Saving
    KeepScreenOn(busy)

    BackHandler(enabled = vm.screen != Screen.HOME) { vm.back() }

    Surface(modifier = Modifier.fillMaxSize(), color = Pal.Black, contentColor = Pal.Smoke) {
        when (vm.screen) {
            Screen.HOME -> HomeScreen(vm, onImport = {
                if (vm.hasKey(vm.provider)) {
                    picker.launch(arrayOf("video/*"))
                } else {
                    vm.toast = "Add your free API key first."
                    vm.openSettings()
                }
            })
            Screen.PROCESSING -> ProcessingScreen(vm)
            Screen.EDITOR -> EditorScreen(vm, onSaveAs = { name -> saveAs.launch(name) })
            Screen.SETTINGS -> SettingsScreen(vm)
        }
    }
}

/** Keeps the screen awake while audio is being processed or the video is being exported. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(on) {
        if (on) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (on) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
