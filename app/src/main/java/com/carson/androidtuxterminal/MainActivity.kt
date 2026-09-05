package com.carson.androidtuxterminal

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView

    private var cwd = "/"
    private val history = mutableListOf<String>()
    private var historyIndex = 0

    private var shellProcess: Process? = null
    private var shellWriter: OutputStreamWriter? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shellLock = Any()
    private val shellMarker = "__ANDROID_TUX_TERMINAL_COMMAND_DONE__"

    private val pkgCatalogUrl =
        "https://raw.githubusercontent.com/carjam120443-netizen/Android-tux-terminal/main/pkg/resources/packages.json"
    private val githubApiBase = "https://api.github.com/repos/"
    private val fdroidIndexUrl = "https://f-droid.org/repo/index-v2.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(12, 12, 12, 8)
        }

        output = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(14f)
            typeface = android.graphics.Typeface.MONOSPACE
            text = "Android Tux Terminal 0.7.0\n" +
                    "Persistent Android /system/bin/sh shell\n" +
                    "Animated terminal output • real Android storage + package installation\n" +
                    "Type 'help' for built-in commands.\n\n"
            isFocusable = false
            gravity = Gravity.TOP
        }

        scroll = ScrollView(this).apply {
            addView(output)
            isFillViewport = true
        }

        input = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setHint("$cwd $ ")
            setTextSize(14f)
            typeface = android.graphics.Typeface.MONOSPACE
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 10, 0, 4)
            setOnEditorActionListener { _, _, _ -> runCommand(text.toString()); true }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { showPreviousCommand(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { showNextCommand(); true }
                    else -> false
                }
            }
        }

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        updatePrompt()
        startShell()
        input.requestFocus()
    }

    override fun onDestroy() {
        stopShell()
        super.onDestroy()
    }

    private fun startShell() {
        Thread {
            try {
                val process = ProcessBuilder("/system/bin/sh")
                    .redirectErrorStream(true)
                    .start()
                synchronized(shellLock) {
                    shellProcess = process
                    shellWriter = OutputStreamWriter(process.outputStream)
                }

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == shellMarker) continue
                    appendAnimated(line + "\n")
                }

                synchronized(shellLock) {
                    shellProcess = null
                    shellWriter = null
                }
                runOnUiThread { append("\nAndroid shell process ended. Restarting shell...\n") }
                if (!isFinishing) startShell()
            } catch (e: Exception) {
                runOnUiThread { append("Shell error: ${e.message}\n") }
            }
        }.start()
    }

    private fun stopShell() {
        synchronized(shellLock) {
            try { shellWriter?.close() } catch (_: Exception) { }
            shellWriter = null
            shellProcess?.destroy()
            shellProcess = null
        }
    }

    private fun sendToShell(command: String, callback: ((String) -> Unit)? = null) {
        Thread {
            try {
                val writer = synchronized(shellLock) { shellWriter }
                    ?: throw IllegalStateException("shell is not ready")
                val captured = StringBuilder()
                synchronized(shellLock) {
                    writer.write(command)
                    writer.write("\nprintf '%s\\n' '$shellMarker'\n")
                    writer.flush()
                }

                val result = waitForMarker(captured)
                runOnUiThread {
                    if (callback != null) callback(result) else appendAnimated(result)
                }
            } catch (e: Exception) {
                runOnUiThread { append("Error: ${e.message}\n") }
            }
        }.start()
    }

    private fun waitForMarker(captured: StringBuilder): String {
        // The persistent reader appends terminal output asynchronously. Commands that
        // need a result use a shell-side marker and a temporary polling buffer below.
        // This method is replaced by executeShell's synchronized command queue.
        return captured.toString()
    }

    private fun runCommand(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        history.add(cmd)
        historyIndex = history.size
        input.text.clear()
        append("\n$cwd $ $cmd\n")
        when {
            cmd == "help" -> append(pkgHelp() + "\nOther commands are executed by the persistent Android /system/bin/sh session.\n")
            cmd == "clear" -> output.text = ""
            cmd == "history" -> append(history.mapIndexed { i, value -> "${i + 1}  $value\n" }.joinToString())
            cmd == "pwd" -> append("$cwd\n")
            cmd == "exit" -> append("Android Tux Terminal: exit is disabled in the app shell.\n")
            cmd == "cd" || cmd.startsWith("cd ") -> changeDirectory(cmd.removePrefix("cd").trim())
            cmd == "pkg" || cmd == "pkg help" -> append(pkgHelp())
            cmd == "pkg sources" -> append("Package catalog:\n$pkgCatalogUrl\n\nSources: direct HTTPS APK, GitHub Releases, and F-Droid.\n")
            cmd == "pkg update" || cmd == "pkg list" -> fetchCatalog(cmd == "pkg list")
            cmd.startsWith("pkg search ") -> searchPackages(cmd.removePrefix("pkg search ").trim())
            cmd.startsWith("pkg install ") -> installPackage(cmd.removePrefix("pkg install ").trim())
            cmd == "pkg storage" -> showStorage()
            cmd == "pkg-get" || cmd == "pkg-get help" -> append(pkgGetHelp())
            cmd == "pkg-get update" || cmd == "pkg-get list" -> fetchCatalog(cmd == "pkg-get list")
            cmd == "pkg-get sources" || cmd == "pkg-get source" -> append("Package catalog:\n$pkgCatalogUrl\n\nSources: direct HTTPS APK, GitHub Releases, and F-Droid.\n")
            cmd.startsWith("pkg-get search ") -> searchPackages(cmd.removePrefix("pkg-get search ").trim())
            cmd.startsWith("pkg-get install ") -> installPackage(cmd.removePrefix("pkg-get install ").trim())
            cmd.startsWith("pkg-get upgrade ") -> installPackage(cmd.removePrefix("pkg-get upgrade ").trim())
            cmd == "pkg-get storage" -> showStorage()
            cmd == "pkg-get upgrade" -> append("pkg-get: no package specified. Use 'pkg-get upgrade <name>' to reinstall the latest catalog APK.\n")
            else -> executeShell(cmd)
        }
    }

    private fun pkgHelp(): String =
        "Android Tux pkg 0.7.0:\n" +
        "  pkg help                 show package manager help\n" +
        "  pkg sources              show configured sources\n" +
        "  pkg update               download the latest package catalog\n" +
        "  pkg list                 list catalog packages\n" +
        "  pkg search <query>       search name, ID, alias, or description\n" +
        "  pkg install <name>       install a catalog package\n" +
        "  pkg install <https-url>  install any HTTPS APK URL\n" +
        "  pkg storage              show Android app/download storage\n\n" +
        "pkg-get is the apt-get-style alias for the same APK package manager.\n" +
        "Catalog entries support: url, source=github, repo, or source=fdroid.\n" +
        "Future apps can be added without changing the APK by updating packages.json.\n"

    private fun pkgGetHelp(): String =
        "Android Tux pkg-get 0.7.0 — apt-get-style APK manager:\n" +
        "  pkg-get update             refresh the APK package catalog\n" +
        "  pkg-get list               list available APK packages\n" +
        "  pkg-get search <query>     search available packages\n" +
        "  pkg-get install <name>     install a catalog APK\n" +
        "  pkg-get upgrade <name>     reinstall the latest catalog APK\n" +
        "  pkg-get sources            show APK sources\n" +
        "  pkg-get storage            show Android storage integration\n\n" +
        "pkg-get uses the same real Android Package Installer and Downloads storage as pkg.\n"

    private fun fetchCatalog(listAfterFetch: Boolean) {
        Thread {
            try {
                val json = downloadText(pkgCatalogUrl)
                runOnUiThread {
                    if (listAfterFetch) showCatalog(json)
                    else append("pkg: package catalog updated from GitHub.\n")
                }
            } catch (e: Exception) {
                runOnUiThread { append("pkg: catalog error: ${e.message}\n") }
            }
        }.start()
    }

    private fun showCatalog(json: String) {
        try {
            val packages = JSONArray(json)
            if (packages.length() == 0) {
                append("pkg: catalog is empty.\n")
                return
            }
            append(buildString {
                append("Available packages:\n")
                for (i in 0 until packages.length()) {
                    val item = packages.getJSONObject(i)
                    append("  ${item.optString("name")} - ${item.optString("description")}\n")
                }
            })
        } catch (e: Exception) {
            append("pkg: invalid catalog: ${e.message}\n")
        }
    }

    private fun searchPackages(query: String) {
        if (query.isBlank()) {
            append("Usage: pkg search <name or keyword>\n")
            return
        }
        Thread {
            try {
                val packages = JSONArray(downloadText(pkgCatalogUrl))
                val needle = query.lowercase()
                val matches = mutableListOf<String>()
                for (i in 0 until packages.length()) {
                    val item = packages.getJSONObject(i)
                    val name = item.optString("name")
                    val id = item.optString("package")
                    val description = item.optString("description")
                    val aliases = item.optJSONArray("aliases")
                    val aliasText = buildString {
                        if (aliases != null) {
                            for (j in 0 until aliases.length()) append(" ").append(aliases.optString(j))
                        }
                    }
                    if ("$name $id $description $aliasText".lowercase().contains(needle)) {
                        matches += "  $name" + if (id.isNotBlank()) " [$id]" else "" +
                                if (description.isNotBlank()) " - $description" else ""
                    }
                }
                runOnUiThread {
                    if (matches.isEmpty()) append("pkg: no packages found for '$query'.\n")
                    else append("Search results for '$query':\n${matches.joinToString("\n")}\n")
                }
            } catch (e: Exception) {
                runOnUiThread { append("pkg: search failed: ${e.message}\n") }
            }
        }.start()
    }

    private fun installPackage(target: String) {
        if (target.isBlank()) {
            append("Usage: pkg install <package-name|package-id|https-url>\n")
            return
        }
        Thread {
            try {
                val url = if (target.startsWith("https://", true)) {
                    target
                } else {
                    resolvePackageUrl(target)
                        ?: throw IllegalArgumentException("package '$target' was not found; try 'pkg search $target'")
                }
                runOnUiThread { append("pkg: resolving APK source...\n") }
                val downloaded = downloadApkToRealStorage(url)
                runOnUiThread {
                    append("pkg: APK saved to Android Downloads/storage.\n")
                    launchInstaller(downloaded)
                }
            } catch (e: Exception) {
                runOnUiThread { append("pkg: install failed: ${e.message}\n") }
            }
        }.start()
    }

    private fun resolvePackageUrl(target: String): String? {
        val packages = JSONArray(downloadText(pkgCatalogUrl))
        val needle = target.trim().lowercase()
        val partial = mutableListOf<String>()
        for (i in 0 until packages.length()) {
            val item = packages.getJSONObject(i)
            val name = item.optString("name")
            val id = item.optString("package")
            val aliases = item.optJSONArray("aliases")
            val names = mutableListOf(name, id)
            if (aliases != null) for (j in 0 until aliases.length()) names += aliases.optString(j)
            val exact = names.any { it.equals(target, true) }
            if (exact) return resolveEntrySource(item)
            if (names.any { it.lowercase().contains(needle) }) partial += item.toString()
        }
        return if (partial.size == 1) resolveEntrySource(org.json.JSONObject(partial[0])) else null
    }

    private fun resolveEntrySource(item: org.json.JSONObject): String? {
        val direct = item.optString("url")
        if (direct.startsWith("https://", true)) return direct
        val source = item.optString("source").lowercase()
        if (source == "github" || item.optString("repo").isNotBlank()) {
            val repo = item.optString("repo")
            if (repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) return findGithubApk(repo)
        }
        if (source == "fdroid") return findFdroidApk(item.optString("package"))
        return null
    }

    private fun findGithubApk(repo: String): String? {
        val json = downloadText(githubApiBase + repo + "/releases/latest")
        val assets = org.json.JSONObject(json).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", true) && url.startsWith("https://", true)) return url
        }
        return null
    }

    private fun findFdroidApk(packageId: String): String? {
        if (packageId.isBlank()) return null
        val root = org.json.JSONObject(downloadText(fdroidIndexUrl))
        val packageObject = root.optJSONObject("packages")?.optJSONObject(packageId) ?: return null
        val versions = packageObject.optJSONObject("versions") ?: return null
        val keys = versions.keys()
        var newest: org.json.JSONObject? = null
        var newestCode = Long.MIN_VALUE
        while (keys.hasNext()) {
            val key = keys.next()
            val version = versions.optJSONObject(key) ?: continue
            val manifest = version.optJSONObject("manifest")
            val versionCode = manifest?.optLong("versionCode", Long.MIN_VALUE) ?: Long.MIN_VALUE
            val realCode = if (versionCode != Long.MIN_VALUE) versionCode else key.toLongOrNull() ?: Long.MIN_VALUE
            if (realCode > newestCode) {
                newestCode = realCode
                newest = version
            }
        }
        val file = newest?.optJSONObject("file") ?: return null
        val name = file.optString("name")
        return if (name.isNotBlank()) "https://f-droid.org/repo/$name" else null
    }

    private fun downloadText(urlString: String): String {
        val connection = openHttpsConnection(urlString)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadApkToRealStorage(urlString: String): Uri {
        val connection = openHttpsConnection(urlString)
        val contentLength = connection.contentLengthLong
        if (contentLength > 100L * 1024L * 1024L) {
            connection.disconnect()
            throw IllegalArgumentException("APK is larger than 100 MB")
        }
        val fileName = "Android-Tux-${System.currentTimeMillis()}.apk"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create Downloads entry")
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    connection.inputStream.use { it.copyTo(out) }
                } ?: throw IllegalStateException("could not open Downloads entry")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                connection.disconnect()
                return uri
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                connection.disconnect()
                throw e
            }
        }

        val dir = getExternalFilesDir("Download") ?: cacheDir
        dir.mkdirs()
        val file = File(dir, fileName)
        try {
            connection.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
        } finally {
            connection.disconnect()
        }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun openHttpsConnection(urlString: String): HttpURLConnection {
        var currentUrl = urlString
        repeat(5) {
            val url = URL(currentUrl)
            if (!url.protocol.equals("https", true)) {
                throw IllegalArgumentException("only HTTPS URLs are allowed")
            }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Android-Tux-Terminal-pkg/0.7")
                setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, */*")
            }
            connection.connect()
            when (connection.responseCode) {
                in 200..299 -> return connection
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) throw IllegalArgumentException("HTTPS redirect missing Location header")
                    currentUrl = URL(url, location).toString()
                }
                else -> {
                    val code = connection.responseCode
                    connection.disconnect()
                    throw IllegalArgumentException("HTTP $code")
                }
            }
        }
        throw IllegalArgumentException("too many HTTPS redirects")
    }

    private fun launchInstaller(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            append("pkg: allow Android Tux Terminal to install unknown apps, then run the command again.\n")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            append("pkg: Android package installer unavailable: ${e.message}\n")
        }
    }

    private fun showStorage() {
        val external = getExternalFilesDir(null)?.absolutePath ?: "unavailable"
        val downloads = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "MediaStore Downloads (public Android storage)"
        } else {
            "$external/Download"
        }
        append("Android storage integration:\n" +
                "  App files: $external\n" +
                "  APK downloads: $downloads\n" +
                "  Installer: Android system Package Installer\n")
    }

    private fun changeDirectory(target: String) {
        val destination = if (target.isBlank() || target == "~") "/" else target
        executeShell("cd ${shellQuote(destination)}") { result ->
            if (result.isBlank()) {
                cwd = destination
                updatePrompt()
            }
        }
    }

    private fun executeShell(command: String, callback: ((String) -> Unit)? = null) {
        Thread {
            try {
                val writer = synchronized(shellLock) { shellWriter }
                    ?: throw IllegalStateException("shell is not ready yet")
                val escapedCommand = command.replace("'", "'\\''")
                synchronized(shellLock) {
                    writer.write("$command\n")
                    writer.write("printf '%s\\n' '$shellMarker'\n")
                    writer.flush()
                }
                // The persistent shell owns cwd/environment now. Keep the UI prompt in sync
                // for normal cd commands while preserving arbitrary shell commands.
                if (command.trim().startsWith("cd ")) {
                    val destination = command.trim().removePrefix("cd ").trim()
                    runOnUiThread {
                        cwd = destination.removeSurrounding("'")
                        updatePrompt()
                    }
                }
                if (callback != null) runOnUiThread { callback("") }
            } catch (e: Exception) {
                runOnUiThread { append("Error: ${e.message}\n") }
            }
        }.start()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun appendAnimated(text: String) {
        if (text.isEmpty()) return
        mainHandler.post {
            if (text.length > 500) {
                append(text)
                return@post
            }
            var index = 0
            val tick = object : Runnable {
                override fun run() {
                    if (index >= text.length) return
                    val end = (index + 2).coerceAtMost(text.length)
                    output.append(text.substring(index, end))
                    index = end
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    if (index < text.length) mainHandler.postDelayed(this, 8L)
                }
            }
            mainHandler.post(tick)
        }
    }

    private fun showPreviousCommand() {
        if (history.isEmpty()) return
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        input.setText(history[historyIndex])
        input.setSelection(input.text.length)
    }

    private fun showNextCommand() {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + 1).coerceAtMost(history.size)
        input.setText(if (historyIndex == history.size) "" else history[historyIndex])
        input.setSelection(input.text.length)
    }

    private fun updatePrompt() { input.hint = "$cwd $ " }

    private fun append(text: String) {
        output.append(text)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
