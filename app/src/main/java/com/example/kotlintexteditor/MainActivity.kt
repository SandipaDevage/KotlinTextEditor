package com.example.kotlintexteditor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.kotlintexteditor.ui.theme.KotlinTextEditorTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

// --------------------------- Config data ---------------------------
data class CommentConfig(val line: String?, val blockStart: String?, val blockEnd: String?)
data class SyntaxConfig(
    val keywords: List<String>,
    val types: List<String>,
    val comments: CommentConfig?,
    val strings: String?
)

// --------------------------- Load config safely ---------------------------
fun loadSyntaxConfigFromAssets(context: Context, fileName: String): SyntaxConfig? {
    return try {
        val input = context.assets.open(fileName)
        val json = BufferedReader(InputStreamReader(input)).use { it.readText() }
        val obj = JSONObject(json)

        val keywords = obj.optJSONArray("keywords")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
        val types = obj.optJSONArray("types")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
        val commentsObj = obj.optJSONObject("comments")
        val comments = commentsObj?.let {
            CommentConfig(
                line = it.optString("line", null),
                blockStart = it.optString("blockStart", null),
                blockEnd = it.optString("blockEnd", null)
            )
        }
        val strings = obj.optString("strings", null)
        SyntaxConfig(keywords, types, comments, strings)
    } catch (_: Exception) {
        null
    }
}

// --------------------------- Built-in Kotlin highlighting ---------------------------
fun highlightKotlinBuiltIn(code: String): AnnotatedString {
    val keywords = setOf(
        "fun","class","object","val","var","if","else","when","for","while","do","return",
        "null","true","false","in","is","interface","sealed","data","enum","try","catch",
        "finally","throw","super","this","as","typealias","package","import"
    )
    val types = setOf("Int","String","Float","Double","Boolean","Char","Long","Short","Any","Unit","List","Map","Set")

    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            when {
                // Block comment
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", startIndex = i + 2).let { if (it == -1) code.length else it + 2 }
                    pushStyle(SpanStyle(color = Color(0xFF6A9955))); append(code.substring(i, end)); pop(); i = end
                }
                // Line comment
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', startIndex = i).let { if (it == -1) code.length else it }
                    pushStyle(SpanStyle(color = Color(0xFF6A9955))); append(code.substring(i, end)); pop(); i = end
                }
                // String
                code[i] == '"' -> {
                    val start = i; i++
                    while (i < code.length && code[i] != '"') {
                        if (code[i] == '\\' && i + 1 < code.length) i++
                        i++
                    }
                    if (i < code.length) i++
                    pushStyle(SpanStyle(color = Color(0xFFD69D85))); append(code.substring(start, i)); pop()
                }
                // Numbers
                code[i].isDigit() -> {
                    val start = i
                    while (i < code.length && (code[i].isDigit() || code[i] == '.')) i++
                    pushStyle(SpanStyle(color = Color(0xFFB5CEA8))); append(code.substring(start, i)); pop()
                }
                // Identifiers
                code[i].isLetter() || code[i] == '_' -> {
                    val start = i
                    while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    val token = code.substring(start, i)
                    when {
                        keywords.contains(token) -> { pushStyle(SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.SemiBold)); append(token); pop() }
                        types.contains(token) -> { pushStyle(SpanStyle(color = Color(0xFF4EC9B0))); append(token); pop() }
                        else -> append(token)
                    }
                }
                else -> { append(code[i]); i++ }
            }
        }
    }
}

// --------------------------- Generic highlighting from config (safe) ---------------------------
fun highlightWithConfig(code: String, cfg: SyntaxConfig?): AnnotatedString {
    if (cfg == null) return AnnotatedString(code)
    return buildAnnotatedString {
        append(code)

        // Keywords
        for (kw in cfg.keywords) {
            try {
                val rx = Regex("\\b${Regex.escape(kw)}\\b")
                rx.findAll(code).forEach { m ->
                    addStyle(SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
                }
            } catch (_: Exception) { }
        }
        // Types
        for (tp in cfg.types) {
            try {
                val rx = Regex("\\b${Regex.escape(tp)}\\b")
                rx.findAll(code).forEach { m ->
                    addStyle(SpanStyle(color = Color(0xFF4EC9B0)), m.range.first, m.range.last + 1)
                }
            } catch (_: Exception) { }
        }
        // Comments
        try {
            cfg.comments?.line?.let { line ->
                val rx = Regex(Regex.escape(line) + ".*")
                rx.findAll(code).forEach { m -> addStyle(SpanStyle(color = Color(0xFF6A9955)), m.range.first, m.range.last + 1) }
            }
            if (cfg.comments?.blockStart != null && cfg.comments.blockEnd != null) {
                val rx = Regex(
                    Regex.escape(cfg.comments.blockStart) + ".*?" + Regex.escape(cfg.comments.blockEnd),
                    RegexOption.DOT_MATCHES_ALL
                )
                rx.findAll(code).forEach { m -> addStyle(SpanStyle(color = Color(0xFF6A9955)), m.range.first, m.range.last + 1) }
            }
        } catch (_: Exception) { }
        // Strings
        cfg.strings?.let { quote ->
            try {
                val rx = Regex(Regex.escape(quote) + ".*?" + Regex.escape(quote))
                rx.findAll(code).forEach { m -> addStyle(SpanStyle(color = Color(0xFFD69D85)), m.range.first, m.range.last + 1) }
            } catch (_: Exception) { }
        }
    }
}

// --------------------------- Search overlay (highlight matches) ---------------------------
fun overlaySearchHighlights(
    base: AnnotatedString,
    fullText: String,
    findText: String,
    caseSensitive: Boolean,
    wholeWord: Boolean
): AnnotatedString {
    if (findText.isBlank()) return base
    val pattern = if (wholeWord) "\\b${Regex.escape(findText)}\\b" else Regex.escape(findText)
    val regex = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)

    return buildAnnotatedString {
        append(base)
        regex.findAll(fullText).forEach { m ->
            addStyle(SpanStyle(background = Color.Yellow, color = Color.Black), m.range.first, m.range.last + 1)
        }
    }
}

// --------------------------- Error overlay (line highlighting) ---------------------------
fun overlayErrorHighlights(
    base: AnnotatedString,
    fullText: String,
    errorLines: Set<Int>
): AnnotatedString {
    if (errorLines.isEmpty()) return base
    val lineStartIdx = mutableListOf(0)
    fullText.forEachIndexed { idx, c -> if (c == '\n') lineStartIdx.add(idx + 1) }

    return buildAnnotatedString {
        append(base)
        errorLines.forEach { lineNo1 ->
            val ln = lineNo1.coerceAtLeast(1)
            val start = lineStartIdx.getOrNull(ln - 1) ?: return@forEach
            val end = lineStartIdx.getOrNull(ln) ?: fullText.length
            addStyle(
                SpanStyle(background = Color(0xFFFFE5E5), color = Color.Unspecified),
                start, end
            )
        }
    }
}

// --------------------------- ADB-bridge compile client ---------------------------
// Expected desktop bridge:
//   - adb reverse tcp:8177 tcp:8177
//   - GET  /health   -> 200 OK
//   - POST /compile  -> streams text lines and ends with a JSON line: {"ok":true,"jar":"/path"} or {"ok":false,"message":"..."}

private const val BRIDGE_HOST = "127.0.0.1"
private const val BRIDGE_PORT = 8177

sealed class CompileState {
    data object Idle : CompileState()
    data object Connecting : CompileState()
    data object Compiling : CompileState()
    data class Success(val artifactPath: String?) : CompileState()
    data class Failure(val reason: String) : CompileState()
}

suspend fun checkBridge(): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val url = URL("http://$BRIDGE_HOST:$BRIDGE_PORT/health")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2000
            readTimeout = 2000
        }
        if (conn.responseCode == 200) {
            conn.inputStream.close()
            Result.success(Unit)
        } else {
            Result.failure(Exception("HTTP ${conn.responseCode}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Posts the source to /compile and streams lines as they arrive.
 * Expected server behavior:
 *   - chunked text/plain or text/event-stream lines, e.g.
 *       "Compiling...\n"
 *       "/tmp/Temp.kt:12:5: error: ...\n"
 *   - final JSON line: {"ok":true,"jar":"/tmp/Out.jar"} OR {"ok":false,"message":"..."}
 */
suspend fun streamCompile(
    filename: String,
    code: String,
    onLine: suspend (String) -> Unit   // ✅ suspend added here
): Result<Boolean> = withContext(Dispatchers.IO) {
    val url = URL("http://$BRIDGE_HOST:$BRIDGE_PORT/compile")
    val conn = (url.openConnection() as HttpURLConnection)
    return@withContext try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setChunkedStreamingMode(0)
        val body = JSONObject()
            .put("filename", filename)
            .put("code", code)
            .toString()
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            var line: String?
            var ok = false
            while (true) {
                line = reader.readLine() ?: break
                onLine(line!!)   // ✅ now safe to call with `withContext` inside
                if (line!!.startsWith("{") && line!!.endsWith("}")) {
                    try {
                        val obj = JSONObject(line)
                        ok = obj.optBoolean("ok", false)
                    } catch (_: Exception) {}
                }
            }
            Result.success(ok)
        }
    } catch (e: Exception) {
        val errText = try {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: e.message.orEmpty()
        } catch (_: Exception) { e.message.orEmpty() }
        Result.failure(Exception(errText.ifBlank { e.message.orEmpty() }))
    } finally {
        conn.disconnect()
    }
}


// --------------------------- Main UI ---------------------------
class MainActivity : ComponentActivity() {

    private val draftFileName = "autosave_draft.txt"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KotlinTextEditorTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TextEditorScreen(this, draftFileName)
                }
            }
        }
    }
}

@Composable
fun StatusChip(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(label, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(context: Context, draftFileName: String) {
    // Text + file state
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    var currentFileUri by remember { mutableStateOf<Uri?>(null) }

    // Undo / Redo
    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }

    // Clipboard
    val clipboard = LocalClipboardManager.current

    // Language & config
    var language by remember { mutableStateOf("Kotlin (built-in)") }
    val config by remember(language) {
        mutableStateOf(
            when (language) {
                "Kotlin (built-in)" -> null // use built-in
                "Python (assets/python.json)" -> loadSyntaxConfigFromAssets(context, "python.json")
                "Java (assets/java.json)" -> loadSyntaxConfigFromAssets(context, "java.json")
                else -> null
            }
        )
    }

    // Search & Replace state
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }

    // Compile/bridge state
    var compileState by remember { mutableStateOf<CompileState>(CompileState.Idle) }
    val compileLog = remember { mutableStateListOf<String>() }
    val errorLines = remember { mutableStateListOf<Int>() }
    var connected by remember { mutableStateOf(false) }
    var lastArtifact by remember { mutableStateOf<String?>(null) }

    // Scroll
    val scroll = rememberScrollState()
    val logScroll = rememberScrollState()

    // Auto-load draft
    LaunchedEffect(Unit) {
        val draft = File(context.filesDir, draftFileName)
        if (draft.exists()) textState = TextFieldValue(draft.readText())
    }

    // Auto-save (debounced)
    LaunchedEffect(textState.text) {
        delay(1200)
        File(context.filesDir, draftFileName).writeText(textState.text)
    }

    // Undo/Redo helpers
    fun onTextChanged(newVal: TextFieldValue) {
        undoStack.add(textState.text)
        redoStack.clear()
        textState = newVal
    }
    fun undo() {
        if (undoStack.isNotEmpty()) {
            val last = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(textState.text)
            textState = TextFieldValue(last)
        }
    }
    fun redo() {
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(textState.text)
            textState = TextFieldValue(next)
        }
    }


    // Find & Replace Row + checkboxes here...

    Spacer(Modifier.height(8.dp))

    // 🔹 Buttons row (Find/Replace + Undo/Redo together)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            if (findText.isNotEmpty()) {
                val pattern = if (wholeWord) "\\b${Regex.escape(findText)}\\b" else Regex.escape(findText)
                val rx = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                val m = rx.find(
                    textState.text,
                    startIndex = textState.selection.end.coerceAtMost(textState.text.length)
                ) ?: rx.find(textState.text)
                m?.let { match ->
                    textState = textState.copy(selection = TextRange(match.range.first, match.range.last + 1))
                }
            }
        }) { Text("Find Next") }

        Button(onClick = {
            val sel = textState.selection
            if (!sel.collapsed && sel.start in 0..textState.text.length && sel.end in 0..textState.text.length) {
                val selected = textState.text.substring(sel.start, sel.end)
                val matches = if (findText.isEmpty()) false else {
                    val pattern = if (wholeWord) "\\b${Regex.escape(findText)}\\b" else Regex.escape(findText)
                    val rx = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                    rx.matches(selected)
                }
                if (matches) {
                    val new = textState.text.substring(0, sel.start) +
                            replaceText +
                            textState.text.substring(sel.end)
                    textState = textState.copy(
                        text = new,
                        selection = TextRange(sel.start, sel.start + replaceText.length)
                    )
                }
            }
        }) { Text("Replace") }

        Button(onClick = {
            if (findText.isNotEmpty()) {
                val pattern = if (wholeWord) "\\b${Regex.escape(findText)}\\b" else Regex.escape(findText)
                val rx = if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                val newText = rx.replace(textState.text, replaceText)
                textState = textState.copy(text = newText, selection = TextRange(0, 0))
            }
        }) { Text("Replace All") }

        Spacer(Modifier.width(16.dp))

        // 🔹 Undo / Redo (moved here!)
        Button(onClick = { undo() }, enabled = undoStack.isNotEmpty()) { Text("Undo") }
        Button(onClick = { redo() }, enabled = redoStack.isNotEmpty()) { Text("Redo") }
    }





    // File pickers
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r ->
                onTextChanged(TextFieldValue(r.readText()))
                currentFileUri = it
            }
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { w -> w.write(textState.text) }
            currentFileUri = it
        }
    }

    // Coroutine scope
    val scope = rememberCoroutineScope()

    // Bridge connect action
    fun connectBridge(scope: CoroutineScope) {
        scope.launch {
            compileState = CompileState.Connecting
            val res = checkBridge()
            connected = res.isSuccess
            compileState = if (connected) CompileState.Idle
            else CompileState.Failure("Bridge not reachable at http://$BRIDGE_HOST:$BRIDGE_PORT (check adb reverse)")
        }
    }

    // Start compile action
    fun startCompile(scope: CoroutineScope) {
        if (!connected) {
            compileState = CompileState.Failure("Not connected to compiler bridge")
            return
        }
        compileLog.clear()
        errorLines.clear()
        lastArtifact = null
        compileState = CompileState.Compiling

        scope.launch {
            val filename = when (language) {
                "Kotlin (built-in)" -> "Main.kt"
                "Python (assets/python.json)" -> "script.py"
                "Java (assets/java.json)" -> "Main.java"
                else -> "Main.kt"
            }
            val result = streamCompile(filename, textState.text) { line ->
                withContext(Dispatchers.Main) {
                    compileLog.add(line)

                    // Parse kotlinc-style errors: ".../File.kt:12:5: error: message"
                    val m = Regex(""".*?:(\d+):(\d+):\s+error:""").find(line)
                    if (m != null) {
                        val lineNo = m.groupValues[1].toIntOrNull()
                        if (lineNo != null) errorLines.add(lineNo)
                    }

                    // Parse final JSON line for artifact path
                    if (line.startsWith("{") && line.endsWith("}")) {
                        try {
                            val obj = JSONObject(line)
                            if (obj.optBoolean("ok", false)) {
                                lastArtifact = obj.optString("jar", null)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            withContext(Dispatchers.Main) {
                compileState = if (result.isSuccess && result.getOrNull() == true) {
                    CompileState.Success(lastArtifact)
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Compilation failed"
                    CompileState.Failure(msg)
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp)
    ) {
        // Title + Language picker + Status
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Kotlin Text Editor", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (compileState) {
                    is CompileState.Compiling -> StatusChip("Compiling...", Color(0xFF1976D2))
                    is CompileState.Success -> StatusChip("Success", Color(0xFF2E7D32))
                    is CompileState.Failure -> StatusChip("Failure", Color(0xFFC62828))
                    is CompileState.Connecting -> StatusChip("Connecting...", Color(0xFF6A1B9A))
                    else -> { if (connected) StatusChip("Connected", Color(0xFF2E7D32)) else StatusChip("Disconnected", Color(0xFF9E9E9E)) }
                }
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(language) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("Kotlin (built-in)", "Python (assets/python.json)", "Java (assets/java.json)")
                            .forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang) },
                                    onClick = { language = lang; expanded = false }
                                )
                            }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // File + bridge controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openLauncher.launch(arrayOf("text/plain", "text/x-kotlin", "text/x-java-source")) }) { Text("Open") }

            Button(onClick = {
                if (currentFileUri != null) {
                    context.contentResolver.openOutputStream(currentFileUri!!)?.bufferedWriter()?.use { it.write(textState.text) }
                } else {
                    val suggested = when (language) {
                        "Kotlin (built-in)" -> "untitled.kt"
                        "Python (assets/python.json)" -> "untitled.py"
                        "Java (assets/java.json)" -> "Untitled.java"
                        else -> "untitled.txt"
                    }
                    saveLauncher.launch(suggested)
                }
            }) { Text("Save") }

            Button(onClick = {
                onTextChanged(TextFieldValue(""))
                currentFileUri = null
            }) { Text("New") }

            Spacer(Modifier.width(12.dp))

            OutlinedButton(onClick = { connectBridge(scope) }) { Text("Connect (ADB)") }

            Button(
                enabled = connected && (compileState !is CompileState.Compiling),
                onClick = { startCompile(scope) }
            ) { Text("Compile") }
        }

        Spacer(Modifier.height(12.dp))

        // Find & Replace


        Spacer(Modifier.height(12.dp))

        // Editor with highlighting + overlays (search + error lines)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .padding(8.dp)
        ) {
            // 1) base syntax highlight
            val baseHighlighted = if (language == "Kotlin (built-in)") {
                highlightKotlinBuiltIn(textState.text)
            } else {
                highlightWithConfig(textState.text, config)
            }
            // 2) overlay search highlights
            val searchOverlay = overlaySearchHighlights(
                baseHighlighted,
                textState.text,
                findText,
                caseSensitive,
                wholeWord
            )
            // 3) overlay error line highlights
            val fullHighlight = overlayErrorHighlights(searchOverlay, textState.text, errorLines.toSet())

            // Render highlighted text behind
            Text(
                text = fullHighlight,
                style = LocalTextStyle.current.copy(color = Color.Black, fontFamily = FontFamily.Monospace)
            )

            // Editable transparent layer on top
            BasicTextField(
                value = textState,
                onValueChange = { onTextChanged(it) },
                textStyle = LocalTextStyle.current.copy(color = Color.Transparent, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Color.Black),
                modifier = Modifier.matchParentSize()
            )
        }

        // Compilation results / log
        Spacer(Modifier.height(12.dp))
        Text("Compilation Output", style = MaterialTheme.typography.titleMedium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(Color(0xFFF5F5F5))
                .padding(8.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(logScroll)
            ) {
                if (compileLog.isEmpty()) {
                    Text("No output yet.", color = Color(0xFF616161), fontFamily = FontFamily.Monospace)
                } else {
                    compileLog.forEach { line ->
                        val color = if (line.contains("error", ignoreCase = true)) Color(0xFFC62828) else Color.Unspecified
                        Text(line, fontFamily = FontFamily.Monospace, color = color)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        when (val st = compileState) {
            is CompileState.Success -> {
                val jar = st.artifactPath ?: "(artifact path not provided)"
                Text("✅ Compilation succeeded.\nArtifact: $jar", color = Color(0xFF2E7D32))
            }
            is CompileState.Failure -> {
                Text("❌ Compilation failed.\n${
                    st.reason.ifBlank { "See log above for details." }
                }", color = Color(0xFFC62828))
            }
            is CompileState.Compiling -> Text("⏳ Compiling…", color = Color(0xFF1976D2))
            is CompileState.Connecting -> Text("🔌 Connecting to bridge…", color = Color(0xFF6A1B9A))
            else -> {}
        }

        // Edit controls
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { undo() }) { Text("Undo") }
            Button(onClick = { redo() }) { Text("Redo") }
            Button(onClick = { clipboard.setText(AnnotatedString(textState.text)) }) { Text("Copy") }
            Button(onClick = {
                clipboard.getText()?.let { onTextChanged(TextFieldValue(it.text)) }
            }) { Text("Paste") }
            Button(onClick = {
                clipboard.setText(AnnotatedString(textState.text))
                onTextChanged(TextFieldValue(""))
            }) { Text("Cut") }
        }

        Spacer(Modifier.height(12.dp))

        // Counts (Characters, Words, Lines)
        val charCount = textState.text.length
        val wordCount = textState.text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
        val lineCount = if (textState.text.isEmpty()) 0 else textState.text.lines().size
        Text("Characters: $charCount    Words: $wordCount    Lines: $lineCount", style = MaterialTheme.typography.bodyMedium)
    }
}
