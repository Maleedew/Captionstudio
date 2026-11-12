package com.techdew.captionstudio

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, PROCESSING, EDITOR, SETTINGS }

sealed interface JobState {
    data object Idle : JobState
    data class Running(val label: String, val progress: Float) : JobState
    data class Failed(val message: String, val needsKey: Boolean) : JobState
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data object Saving : ExportState
    data class Done(val file: File, val name: String, val savedToGallery: Boolean) : ExportState
    data class Failed(val message: String) : ExportState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application = app
    val prefs = Prefs(app)
    val keys = SecureKeys(app)
    val renderer = CaptionRenderer(app)
    private val store = ProjectStore(app)

    // ---------------- app state ----------------
    var screen by mutableStateOf(Screen.HOME)
    var video by mutableStateOf<VideoInfo?>(null)
    var captions by mutableStateOf<List<Caption>>(emptyList())
    var style by mutableStateOf(prefs.style)
    var jobState by mutableStateOf<JobState>(JobState.Idle)
    var exportState by mutableStateOf<ExportState>(ExportState.Idle)
    var toast by mutableStateOf<String?>(null)
    var saved by mutableStateOf<SavedProject?>(null)
    var dirty by mutableStateOf(false)

    // ---------------- settings (mirrors of Prefs so Compose reacts) ----------------
    var provider by mutableStateOf(prefs.provider)
    var langMode by mutableStateOf(prefs.langMode)
    var wordsPerCaption by mutableIntStateOf(prefs.wordsPerCaption)
    var exportShortSide by mutableIntStateOf(prefs.exportHeight)
    var geminiModelText by mutableStateOf(prefs.geminiModel)
    var groqModelText by mutableStateOf(prefs.groqModel)
    var keyVersion by mutableIntStateOf(0)
    var keyTest by mutableStateOf<String?>(null)
    var keyTestProvider by mutableStateOf<Provider?>(null)
    var keyTesting by mutableStateOf(false)

    private var settingsFrom = Screen.HOME
    private var rawSegs: List<Seg> = emptyList()
    private var procJob: Job? = null
    private var lastChunks: List<AudioChunk>? = null
    private val chunkResults = HashMap<Int, List<Seg>>()
    private var exporter: Exporter? = null
    private var exportJob: Job? = null
    private var projectSaveJob: Job? = null
    private var styleSaveJob: Job? = null

    init {
        saved = store.load()
    }

    // ---------------- navigation ----------------
    fun openSettings() {
        settingsFrom = screen
        screen = Screen.SETTINGS
    }

    fun back() {
        when (screen) {
            Screen.SETTINGS -> screen = settingsFrom
            Screen.PROCESSING -> {
                procJob?.cancel()
                jobState = JobState.Idle
                goHome()
            }
            Screen.EDITOR -> if (exportState is ExportState.Running) cancelExport() else goHome()
            Screen.HOME -> {}
        }
    }

    fun goHome() {
        procJob?.cancel()
        if (exportState is ExportState.Running) cancelExport()
        val v = video
        if (v != null && captions.isNotEmpty()) store.save(v, captions, rawSegs)
        saved = store.load()
        screen = Screen.HOME
    }

    fun resumeLast() {
        val s = saved ?: return
        val ok = try {
            val fd = ctx.contentResolver.openAssetFileDescriptor(s.video.uri, "r")
            fd?.close()
            fd != null
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            toast = "The original video can't be opened any more. Please import it again."
            return
        }
        video = s.video
        captions = s.captions.sortedBy { it.startMs }
        rawSegs = s.raw
        dirty = false
        exportState = ExportState.Idle
        screen = Screen.EDITOR
    }

    fun discardSaved() {
        store.clear()
        saved = null
    }

    // ---------------- settings ----------------
    fun hasKey(p: Provider): Boolean =
        keyVersion >= 0 && keys.has(if (p == Provider.GEMINI) SecureKeys.GEMINI else SecureKeys.GROQ)

    fun keyHint(p: Provider): String =
        if (keyVersion >= 0) keys.hint(if (p == Provider.GEMINI) SecureKeys.GEMINI else SecureKeys.GROQ) else ""

    fun saveKey(p: Provider, value: String) {
        if (value.isBlank()) {
            toast = "Paste your API key first."
            return
        }
        keys.set(if (p == Provider.GEMINI) SecureKeys.GEMINI else SecureKeys.GROQ, value)
        if (p == Provider.GEMINI) prefs.geminiModelAuto = ""
        keyVersion++
        keyTest = null
        if (value.isNotBlank() && !keys.persistent) {
            toast = "Secure storage isn't available on this phone — the key is kept only until the app closes."
        }
    }

    fun removeKey(p: Provider) {
        keys.remove(if (p == Provider.GEMINI) SecureKeys.GEMINI else SecureKeys.GROQ)
        if (p == Provider.GEMINI) prefs.geminiModelAuto = ""
        keyVersion++
        keyTest = null
    }

    fun testKey(p: Provider) {
        val key = keys.get(if (p == Provider.GEMINI) SecureKeys.GEMINI else SecureKeys.GROQ)
        if (key.isNullOrEmpty()) {
            keyTestProvider = p
            keyTest = "Paste a key and tap Save first."
            return
        }
        keyTesting = true
        keyTest = null
        keyTestProvider = p
        viewModelScope.launch {
            keyTest = try {
                when (p) {
                    Provider.GEMINI -> {
                        val m = GeminiApi.pick(key, emptySet())
                        prefs.geminiModelAuto = m
                        "Key works ✓  Model: $m"
                    }
                    Provider.GROQ -> KeyTester.groq(key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                friendly(e)
            }
            keyTesting = false
        }
    }

    fun selectProvider(p: Provider) {
        provider = p
        prefs.provider = p
    }

    fun setLang(l: LangMode) {
        langMode = l
        prefs.langMode = l
    }

    fun setWords(n: Int) {
        wordsPerCaption = n.coerceIn(1, 12)
        prefs.wordsPerCaption = wordsPerCaption
    }

    fun setGeminiModel(text: String) {
        geminiModelText = text
        prefs.geminiModel = text
        prefs.geminiModelAuto = ""
    }

    fun setGroqModel(text: String) {
        groqModelText = text
        prefs.groqModel = text
    }

    fun selectExportQuality(v: Int) {
        exportShortSide = v
        prefs.exportHeight = v
    }

    // ---------------- style ----------------
    fun updateStyle(block: (CaptionStyle) -> CaptionStyle) {
        style = block(style)
        styleSaveJob?.cancel()
        styleSaveJob = viewModelScope.launch {
            delay(400)
            prefs.style = style
        }
    }

    // ---------------- import + transcription ----------------
    fun onVideoPicked(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // not every provider allows persisting access; fine
        }
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { VideoUtil.read(ctx, uri) }
            when {
                info == null -> toast = "Couldn't read this video file."
                info.durationMs <= 0L -> toast = "This video has no readable duration."
                info.durationMs > VideoUtil.MAX_VIDEO_MS ->
                    toast = "This video is ${CaptionText.formatTime(info.durationMs).substringBefore('.')} long. " +
                        "Version 1 supports videos up to 15:00."
                else -> {
                    video = info
                    captions = emptyList()
                    rawSegs = emptyList()
                    dirty = false
                    lastChunks = null
                    chunkResults.clear()
                    exportState = ExportState.Idle
                    startProcessing()
                }
            }
        }
    }

    fun startProcessing() {
        val v = video ?: return
        procJob?.cancel()
        procJob = viewModelScope.launch {
            screen = Screen.PROCESSING
            jobState = JobState.Running("Reading audio…", 0f)
            try {
                var chunks = lastChunks
                if (chunks == null || chunks.any { !it.file.exists() }) {
                    chunkResults.clear()
                    chunks = AudioExtractor(ctx).extract(v.uri, File(ctx.cacheDir, "work")) { p ->
                        jobState = JobState.Running("Reading audio…", p * 0.2f)
                    }
                    lastChunks = chunks
                }
                val n = chunks.size
                val excluded = HashSet<String>()
                var rotations = 0
                var asr: Asr = makeAsr(excluded)

                for ((i, ch) in chunks.withIndex()) {
                    if (chunkResults.containsKey(i)) continue
                    val base = 0.2f + 0.75f * i / n
                    while (true) {
                        jobState = JobState.Running("Transcribing part ${i + 1} of $n…", base)
                        try {
                            val segs = asr.transcribe(ch.file, ch.durationMs) { status ->
                                jobState = JobState.Running(status, base)
                            }
                            chunkResults[i] = CaptionText.sanitizeChunk(segs, ch.durationMs, ch.startMs)
                            break
                        } catch (e: ModelUnavailableException) {
                            if (prefs.geminiModel.isNotBlank() || ++rotations > 3) throw e
                            excluded.add(e.model)
                            prefs.geminiModelAuto = ""
                            asr = makeAsr(excluded)
                        } catch (e: QuotaException) {
                            val canRotate = e.daily && prefs.provider == Provider.GEMINI && prefs.geminiModel.isBlank()
                            if (!canRotate || ++rotations > 3) throw e
                            (asr as? GeminiAsr)?.let { excluded.add(it.model) }
                            prefs.geminiModelAuto = ""
                            asr = makeAsr(excluded)
                        }
                    }
                }

                val all = chunkResults.entries.sortedBy { it.key }.flatMap { it.value }
                rawSegs = all
                captions = CaptionText.toCaptions(all, v.durationMs, wordsPerCaption)
                dirty = false
                withContext(Dispatchers.IO) { store.save(v, captions, rawSegs) }
                File(ctx.cacheDir, "work").deleteRecursively()
                lastChunks = null
                chunkResults.clear()
                jobState = JobState.Idle
                if (captions.isEmpty()) toast = "No speech was found. You can add captions by hand."
                screen = Screen.EDITOR
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                jobState = JobState.Failed(friendly(e), e is ApiKeyException)
            }
        }
    }

    private suspend fun makeAsr(excluded: Set<String>): Asr = when (prefs.provider) {
        Provider.GEMINI -> {
            val key = keys.get(SecureKeys.GEMINI)
                ?: throw ApiKeyException("Add your free Gemini API key in Settings first.")
            val manual = prefs.geminiModel
            val cached = prefs.geminiModelAuto
            val model = when {
                manual.isNotBlank() -> manual
                cached.isNotBlank() && cached !in excluded -> cached
                else -> GeminiApi.pick(key, excluded).also { prefs.geminiModelAuto = it }
            }
            GeminiAsr(key, model, prefs.langMode)
        }
        Provider.GROQ -> {
            val key = keys.get(SecureKeys.GROQ)
                ?: throw ApiKeyException("Add your free Groq API key in Settings first.")
            GroqAsr(key, prefs.groqModel, prefs.langMode)
        }
    }

    // ---------------- caption editing ----------------
    private fun edit(block: (List<Caption>) -> List<Caption>) {
        captions = block(captions).sortedBy { it.startMs }
        dirty = true
        scheduleProjectSave()
    }

    private fun scheduleProjectSave() {
        val v = video ?: return
        projectSaveJob?.cancel()
        projectSaveJob = viewModelScope.launch {
            delay(800)
            val caps = captions
            val raw = rawSegs
            withContext(Dispatchers.IO) { store.save(v, caps, raw) }
        }
    }

    fun updateText(id: Long, text: String) = edit { l -> l.map { if (it.id == id) it.copy(text = text) else it } }

    fun setStart(id: Long, ms: Long) = edit { l ->
        l.map { if (it.id == id) it.copy(startMs = ms.coerceIn(0L, maxOf(0L, it.endMs - 100L))) else it }
    }

    fun setEnd(id: Long, ms: Long) = edit { l ->
        val dur = video?.durationMs ?: Long.MAX_VALUE
        l.map {
            if (it.id == id) it.copy(endMs = ms.coerceIn(it.startMs + 100L, maxOf(it.startMs + 100L, dur))) else it
        }
    }

    fun nudgeStart(c: Caption, deltaMs: Long) = setStart(c.id, c.startMs + deltaMs)

    fun nudgeEnd(c: Caption, deltaMs: Long) = setEnd(c.id, c.endMs + deltaMs)

    fun deleteCaption(id: Long) = edit { l -> l.filter { it.id != id } }

    fun splitCaption(id: Long) = edit { l -> l.flatMap { if (it.id == id) CaptionText.splitCaption(it) else listOf(it) } }

    fun mergeWithNext(id: Long) = edit { l ->
        val i = l.indexOfFirst { it.id == id }
        if (i < 0 || i + 1 >= l.size) {
            l
        } else {
            val m = l.toMutableList()
            m[i] = CaptionText.merge(l[i], l[i + 1])
            m.removeAt(i + 1)
            m
        }
    }

    /** Adds an empty-ish caption at [ms] and returns its id. */
    fun addCaptionAt(ms: Long): Long {
        val dur = video?.durationMs ?: (ms + 2000L)
        val start = ms.coerceIn(0L, maxOf(0L, dur - 300L))
        val nextStart = captions.firstOrNull { it.startMs > start }?.startMs ?: dur
        val end = minOf(start + 2000L, nextStart).coerceAtLeast(start + 300L)
        val c = Caption(Ids.next(), start, end, "New caption")
        edit { it + c }
        return c.id
    }

    /** Rebuilds captions from the raw transcript with the current words-per-caption value (discards manual edits). */
    fun resplit() {
        val v = video ?: return
        if (rawSegs.isEmpty()) {
            toast = "Nothing to re-split yet."
            return
        }
        captions = CaptionText.toCaptions(rawSegs, v.durationMs, wordsPerCaption)
        dirty = false
        scheduleProjectSave()
    }

    // ---------------- export ----------------
    fun startExport() {
        val v = video ?: return
        if (captions.isEmpty()) {
            toast = "Generate or add captions first."
            return
        }
        if (exportState is ExportState.Running || exportState is ExportState.Saving) return
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val outFile = File(dir, "captioned_${System.currentTimeMillis()}.mp4")
        val ex = Exporter(ctx, renderer)
        exporter = ex
        exportState = ExportState.Running(0f)
        try {
            ex.start(
                video = v,
                captions = captions,
                style = style,
                shortSide = exportShortSide,
                outFile = outFile,
                onDone = { finishExport(outFile) },
                onError = { msg ->
                    exportJob?.cancel()
                    exporter = null
                    exportState = ExportState.Failed(msg)
                }
            )
        } catch (e: Throwable) {
            exporter = null
            exportState = ExportState.Failed(friendly(e))
            return
        }
        exportJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                val p = ex.progress()
                if (p != null && exportState is ExportState.Running) exportState = ExportState.Running(p)
            }
        }
    }

    private fun finishExport(file: File) {
        exportJob?.cancel()
        exporter = null
        val name = "CaptionStudio_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        exportState = ExportState.Saving
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { MediaSaver.saveToMovies(ctx, file, name) }
            exportState = ExportState.Done(file, name, uri != null)
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exporter?.cancel()
        exporter = null
        exportState = ExportState.Idle
    }

    /** Android 8-9: the user picked where to save with the system dialog. */
    fun saveExportTo(dest: Uri) {
        val d = exportState as? ExportState.Done ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { MediaSaver.copyTo(ctx, d.file, dest) }
            toast = if (ok) "Saved." else "Couldn't save the file."
            if (ok) exportState = d.copy(savedToGallery = true)
        }
    }

    // ---------------- helpers ----------------
    private fun friendly(e: Throwable): String = when (e) {
        is ApiKeyException -> e.message ?: "There is a problem with the API key."
        is QuotaException -> (e.message ?: "The free limit is reached.") +
            if (e.daily) " Try again tomorrow, or switch provider/model in Settings." else ""
        is ModelUnavailableException ->
            "The Gemini model \"${e.model}\" isn't available. Clear the model name in Settings to pick one automatically."
        is NoAudioException -> e.message ?: "This video has no audio."
        is AudioDecodeException -> e.message ?: "Couldn't read the audio."
        is AsrException -> e.message ?: "Transcription failed."
        is IOException -> "Network problem — check your internet connection and try again."
        is OutOfMemoryError -> "The phone ran out of memory. Close other apps and try again."
        else -> "Something went wrong (" + e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "") + ")."
    }

    override fun onCleared() {
        exporter?.cancel()
        super.onCleared()
    }
}
