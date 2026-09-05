package com.carson.androidtuxterminal

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private val pty = NativePty()
    private val io = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private var cwd = "/"
    private var stopping = false

    private val pkgCatalogUrl = "https://raw.githubusercontent.com/carjam120443-netizen/Android-tux-terminal/main/pkg/resources/packages.json"
    private val githubApiBase = "https://api.github.com/repos/"
    private val fdroidIndexUrl = "https://f-droid.org/repo/index-v2.json"
    private val promptMarker = "__TUX_PROMPT__"
    private val prompt2Marker = "__TUX_PROMPT2__"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(12, 12, 12, 8) }
        output = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(14f); typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.TOP
            text = "Android Tux Terminal 0.8.0\nNative PTY • /system/bin/sh • interactive terminal session\nCtrl+C • terminal resize • persistent shell state\nType 'help' for built-in commands.\n\n"
        }
        scroll = ScrollView(this).apply { addView(output); isFillViewport = true }
        input = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setTextSize(14f); typeface = android.graphics.Typeface.MONOSPACE
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_GO; setBackgroundColor(Color.TRANSPARENT); setPadding(0, 10, 0, 4)
            setOnEditorActionListener { _, _, _ -> submitCommand(); true }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_C) { pty.sendCtrlC(); true }
                else when (keyCode) { KeyEvent.KEYCODE_DPAD_UP -> { previousCommand(); true }; KeyEvent.KEYCODE_DPAD_DOWN -> { nextCommand(); true }; else -> false }
            }
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(input, LinearLayout.LayoutParams(-1, -2)); setContentView(root)
        updatePrompt()
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val width = output.width.coerceAtLeast(1); val height = output.height.coerceAtLeast(1)
                val cw = output.paint.measureText("M").coerceAtLeast(1f); val lh = output.lineHeight.coerceAtLeast(1)
                pty.resize((width / cw).toInt().coerceIn(40, 240), (height / lh).coerceIn(8, 100))
            }
        })
        startPty(); input.requestFocus()
    }

    private fun startPty() {
        io.execute {
            try {
                pty.start(cwd, 80, 24)
                pty.write("export TERM=xterm-256color; export COLORTERM=truecolor; PS1='$promptMarker'; PS2='$prompt2Marker'\n")
                val buffer = StringBuilder()
                while (!stopping) {
                    val bytes = pty.read() ?: break
                    buffer.append(bytes.toString(Charsets.UTF_8))
                    drainPtyBuffer(buffer)
                }
            } catch (e: Exception) { if (!stopping) append("\nPTY error: ${e.message}\n") }
        }
    }

    private fun drainPtyBuffer(buffer: StringBuilder) {
        while (true) {
            val p1 = buffer.indexOf(promptMarker); val p2 = buffer.indexOf(prompt2Marker)
            val marker = when { p1 >= 0 && p2 >= 0 -> minOf(p1, p2); p1 >= 0 -> p1; p2 >= 0 -> p2; else -> -1 }
            if (marker < 0) {
                if (buffer.length > 8192) { val keep = 1024; appendAnimated(buffer.substring(0, buffer.length - keep)); buffer.delete(0, buffer.length - keep) }
                return
            }
            if (marker > 0) appendAnimated(buffer.substring(0, marker))
            val markerLength = if (marker == p1) promptMarker.length else prompt2Marker.length
            buffer.delete(0, marker + markerLength)
            runOnUiThread { updatePrompt() }
        }
    }

    private fun submitCommand() {
        val command = input.text.toString(); if (command.isBlank()) return
        history += command; historyIndex = history.size; input.text.clear(); append("$cwd $ $command\n")
        when {
            command == "help" -> append(helpText())
            command == "clear" -> output.text = ""
            command == "history" -> append(history.mapIndexed { i, v -> "${i + 1}  $v\n" }.joinToString())
            command == "exit" -> append("exit: close the app to end the PTY session.\n")
            command == "pkg" || command == "pkg help" -> append(pkgHelp())
            command == "pkg sources" || command == "pkg-get sources" -> append("Catalog: $pkgCatalogUrl\nSources: direct HTTPS APK, GitHub Releases, F-Droid.\n")
            command == "pkg update" || command == "pkg-get update" -> fetchCatalog(false)
            command == "pkg list" || command == "pkg-get list" -> fetchCatalog(true)
            command.startsWith("pkg search ") -> searchPackages(command.removePrefix("pkg search ").trim())
            command.startsWith("pkg-get search ") -> searchPackages(command.removePrefix("pkg-get search ").trim())
            command.startsWith("pkg install ") -> installPackage(command.removePrefix("pkg install ").trim())
            command.startsWith("pkg-get install ") -> installPackage(command.removePrefix("pkg-get install ").trim())
            command.startsWith("pkg-get upgrade ") -> installPackage(command.removePrefix("pkg-get upgrade ").trim())
            command == "pkg storage" || command == "pkg-get storage" -> showStorage()
            else -> pty.write(command + "\n")
        }
    }

    private fun helpText() = "Android Tux Terminal 0.8.0:\n  help                     show this help\n  clear                    clear terminal output\n  history                  command history\n  exit                     leave the app shell\n  Ctrl+C                   send SIGINT through the PTY\n  All other commands       native interactive /system/bin/sh\n\n" + pkgHelp()
    private fun pkgHelp() = "Android Tux pkg 0.8.0:\n  pkg update               refresh package catalog\n  pkg list                 list catalog packages\n  pkg search <query>       search packages\n  pkg install <name|url>   install APK\n  pkg sources              show APK sources\n  pkg storage              show Android storage\npkg-get is an alias for the same APK package manager.\n"

    private fun fetchCatalog(listAfter: Boolean) { io.execute { try { val json = downloadText(pkgCatalogUrl); runOnUiThread { if (listAfter) showCatalog(json) else append("pkg: catalog updated.\n") } } catch (e: Exception) { append("pkg: catalog error: ${e.message}\n") } } }
    private fun showCatalog(json: String) { try { val a = JSONArray(json); append(buildString { append("Available packages:\n"); for (i in 0 until a.length()) { val p = a.getJSONObject(i); append("  ${p.optString("name")} - ${p.optString("description")}\n") } }) } catch (e: Exception) { append("pkg: invalid catalog: ${e.message}\n") } }
    private fun searchPackages(query: String) {
        if (query.isBlank()) { append("Usage: pkg search <query>\n"); return }
        io.execute { try {
            val a = JSONArray(downloadText(pkgCatalogUrl)); val q = query.lowercase(); val hits = mutableListOf<String>()
            for (i in 0 until a.length()) { val p = a.getJSONObject(i); val text = "${p.optString("name")} ${p.optString("package")} ${p.optString("description")} ${p.optJSONArray("aliases")}".lowercase(); if (q in text) hits += "  ${p.optString("name")} [${p.optString("package")}] - ${p.optString("description")}" }
            runOnUiThread { append(if (hits.isEmpty()) "pkg: no matches for '$query'.\n" else hits.joinToString("\n", "Search results:\n", "\n")) }
        } catch (e: Exception) { append("pkg: search failed: ${e.message}\n") } }
    }

    private fun installPackage(target: String) {
        if (target.isBlank()) { append("Usage: pkg install <name|https-url>\n"); return }
        io.execute { try {
            val url = if (target.startsWith("https://", true)) target else resolvePackageUrl(target) ?: error("package '$target' was not found")
            append("pkg: downloading APK...\n"); val uri = downloadApk(url); runOnUiThread { append("pkg: APK saved. Launching Android Package Installer...\n"); launchInstaller(uri) }
        } catch (e: Exception) { append("pkg: install failed: ${e.message}\n") } }
    }

    private fun resolvePackageUrl(target: String): String? {
        val a = JSONArray(downloadText(pkgCatalogUrl)); val q = target.lowercase(); val matches = mutableListOf<org.json.JSONObject>()
        for (i in 0 until a.length()) { val p = a.getJSONObject(i); val names = mutableListOf(p.optString("name"), p.optString("package")); p.optJSONArray("aliases")?.let { x -> for (j in 0 until x.length()) names += x.optString(j) }; if (names.any { it.equals(target, true) }) return resolveEntry(p); if (names.any { it.lowercase().contains(q) }) matches += p }
        return if (matches.size == 1) resolveEntry(matches[0]) else null
    }
    private fun resolveEntry(p: org.json.JSONObject): String? { val direct = p.optString("url"); if (direct.startsWith("https://", true)) return direct; if (p.optString("source").equals("github", true) || p.optString("repo").isNotBlank()) return findGithubApk(p.optString("repo")); if (p.optString("source").equals("fdroid", true)) return findFdroidApk(p.optString("package")); return null }
    private fun findGithubApk(repo: String): String? { if (!repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) return null; val a = org.json.JSONObject(downloadText(githubApiBase + repo + "/releases/latest")).optJSONArray("assets") ?: return null; for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optString("name").endsWith(".apk", true)) return x.optString("browser_download_url") }; return null }
    private fun findFdroidApk(id: String): String? { if (id.isBlank()) return null; val p = org.json.JSONObject(downloadText(fdroidIndexUrl)).optJSONObject("packages")?.optJSONObject(id) ?: return null; val versions = p.optJSONObject("versions") ?: return null; var best: org.json.JSONObject? = null; var bestCode = Long.MIN_VALUE; val keys = versions.keys(); while (keys.hasNext()) { val v = versions.getJSONObject(keys.next()); val code = v.optJSONObject("manifest")?.optLong("versionCode", Long.MIN_VALUE) ?: Long.MIN_VALUE; if (code > bestCode) { bestCode = code; best = v } }; val name = best?.optJSONObject("file")?.optString("name") ?: return null; return if (name.isBlank()) null else "https://f-droid.org/repo/$name" }
    private fun downloadText(urlString: String): String { val c = openHttpsConnection(urlString); return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() } }
    private fun downloadApk(urlString: String): Uri {
        val c = openHttpsConnection(urlString); val name = "Android-Tux-${System.currentTimeMillis()}.apk"; if (c.contentLengthLong > 100L * 1024L * 1024L) { c.disconnect(); error("APK is larger than 100 MB") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive"); put(MediaStore.Downloads.IS_PENDING, 1) }; val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("cannot create Downloads entry"); try { contentResolver.openOutputStream(uri)?.use { out -> c.inputStream.use { it.copyTo(out) } } ?: error("cannot open Downloads entry"); values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); contentResolver.update(uri, values, null, null); return uri } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e } finally { c.disconnect() }
        }
        val dir = getExternalFilesDir("Download") ?: cacheDir; dir.mkdirs(); val file = File(dir, name); try { c.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out) } }; return FileProvider.getUriForFile(this, "$packageName.fileprovider", file) } finally { c.disconnect() }
    }
    private fun openHttpsConnection(value: String): HttpURLConnection { var current = value; repeat(5) { val u = URL(current); require(u.protocol.equals("https", true)) { "only HTTPS URLs are allowed" }; val c = (u.openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false; setRequestProperty("User-Agent", "Android-Tux-Terminal-pkg/0.8") }; c.connect(); when (c.responseCode) { in 200..299 -> return c; in 300..399 -> { val location = c.getHeaderField("Location") ?: error("redirect missing Location"); c.disconnect(); current = URL(u, location).toString() }; else -> { val code = c.responseCode; c.disconnect(); error("HTTP $code") } } }; error("too many HTTPS redirects") }
    private fun launchInstaller(uri: Uri) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) { append("pkg: allow this app to install unknown apps, then run the command again.\n"); startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))); return }; try { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } catch (e: Exception) { append("pkg: package installer unavailable: ${e.message}\n") } }
    private fun showStorage() { append("Android storage:\n  App files: ${getExternalFilesDir(null)?.absolutePath ?: "unavailable"}\n  APKs: Android Downloads\n  Installer: system Package Installer\n") }

    private fun previousCommand() { if (history.isNotEmpty()) { historyIndex = (historyIndex - 1).coerceAtLeast(0); input.setText(history[historyIndex]); input.setSelection(input.length()) } }
    private fun nextCommand() { if (history.isNotEmpty()) { historyIndex = (historyIndex + 1).coerceAtMost(history.size); input.setText(if (historyIndex == history.size) "" else history[historyIndex]); input.setSelection(input.length()) } }
    private fun updatePrompt() { input.hint = "$cwd $ " }
    private fun appendAnimated(text: String) { if (text.isEmpty()) return; val clean = text.replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), ""); if (clean.length > 240) { append(clean); return }; runOnUiThread { var i = 0; fun tick() { if (i >= clean.length) return; output.append(clean[i].toString()); i++; scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }; output.postDelayed(::tick, 4L) }; tick() } }
    private fun append(text: String) { runOnUiThread { output.append(text); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } } }

    override fun onDestroy() { stopping = true; io.shutdownNow(); pty.close(); super.onDestroy() }
}
