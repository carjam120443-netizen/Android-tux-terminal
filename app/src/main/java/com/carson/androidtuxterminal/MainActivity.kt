package com.carson.androidtuxterminal

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView

    private var cwd = "/"
    private val history = mutableListOf<String>()
    private var historyIndex = 0

    private val pkgCatalogUrl =
        "https://raw.githubusercontent.com/carjam120443-netizen/Android-tux-terminal/main/pkg/resources/packages.json"

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
            text = "Android Tux Terminal 0.3.0\n" +
                    "Android shell • Linux-style terminal\n" +
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

            setOnEditorActionListener { _, _, _ ->
                runCommand(text.toString())
                true
            }

            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        showPreviousCommand()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        showNextCommand()
                        true
                    }
                    else -> false
                }
            }
        }

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        updatePrompt()
        input.requestFocus()
    }

    private fun runCommand(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return

        history.add(cmd)
        historyIndex = history.size
        input.text.clear()
        append("\n$cwd $ $cmd\n")

        when {
            cmd == "help" -> append(
                "Built-ins:\n" +
                "  help              show this help\n" +
                "  clear             clear terminal output\n" +
                "  cd <dir>          change directory\n" +
                "  pwd               print working directory\n" +
                "  history           show command history\n" +
                "  pkg help          show package manager help\n" +
                "  exit              keep the app shell open\n\n" +
                "Any other command is executed by Android /system/bin/sh.\n"
            )
            cmd == "clear" -> output.text = ""
            cmd == "history" -> append(history.mapIndexed { i, value -> "${i + 1}  $value\n" }.joinToString())
            cmd == "pwd" -> append("$cwd\n")
            cmd == "exit" -> append("Android Tux Terminal: exit is disabled in the app shell.\n")
            cmd == "cd" || cmd.startsWith("cd ") -> changeDirectory(cmd.removePrefix("cd").trim())
            cmd == "pkg" || cmd == "pkg help" -> append(pkgHelp())
            cmd == "pkg sources" -> append("Package catalog:\n$pkgCatalogUrl\n\nOnly HTTPS APK downloads are accepted.\n")
            cmd == "pkg update" || cmd == "pkg list" -> fetchCatalog(cmd == "pkg list")
            cmd.startsWith("pkg install ") -> installPackage(cmd.removePrefix("pkg install ").trim())
            else -> executeShell(cmd)
        }
    }

    private fun pkgHelp(): String =
        "Android Tux pkg:\n" +
        "  pkg help                 show package manager help\n" +
        "  pkg sources              show configured catalog\n" +
        "  pkg update               download the latest package catalog\n" +
        "  pkg list                 list packages in the catalog\n" +
        "  pkg install <name>       install a catalog package\n" +
        "  pkg install <https-url>  download an APK from the web\n\n" +
        "APK installs are handed to Android's package installer.\n"

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
            val text = buildString {
                append("Available packages:\n")
                for (i in 0 until packages.length()) {
                    val item = packages.getJSONObject(i)
                    append("  ${item.optString("name")} - ${item.optString("description")}\n")
                }
            }
            append(text)
        } catch (e: Exception) {
            append("pkg: invalid catalog: ${e.message}\n")
        }
    }

    private fun installPackage(target: String) {
        if (target.isBlank()) {
            append("Usage: pkg install <package-name|https-url>\n")
            return
        }

        Thread {
            try {
                val url = if (target.startsWith("https://", ignoreCase = true)) {
                    target
                } else {
                    resolvePackageUrl(target)
                        ?: throw IllegalArgumentException("package '$target' was not found in the catalog")
                }
                runOnUiThread { append("pkg: downloading $url\n") }
                val apk = downloadApk(url)
                runOnUiThread {
                    append("pkg: downloaded ${apk.name}\n")
                    launchInstaller(apk)
                }
            } catch (e: Exception) {
                runOnUiThread { append("pkg: install failed: ${e.message}\n") }
            }
        }.start()
    }

    private fun resolvePackageUrl(name: String): String? {
        val packages = JSONArray(downloadText(pkgCatalogUrl))
        for (i in 0 until packages.length()) {
            val item = packages.getJSONObject(i)
            if (item.optString("name").equals(name, ignoreCase = true)) {
                return item.optString("url").takeIf { it.startsWith("https://", ignoreCase = true) }
            }
        }
        return null
    }

    private fun downloadText(urlString: String): String {
        val connection = openHttpsConnection(urlString)
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun downloadApk(urlString: String): File {
        val connection = openHttpsConnection(urlString)
        val contentLength = connection.contentLengthLong
        if (contentLength > 100L * 1024L * 1024L) {
            connection.disconnect()
            throw IllegalArgumentException("APK is larger than 100 MB")
        }

        val dir = File(cacheDir, "pkg").apply { mkdirs() }
        val apk = File(dir, "download-${System.currentTimeMillis()}.apk")
        connection.inputStream.use { input ->
            apk.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        connection.disconnect()
        if (apk.length() == 0L) {
            apk.delete()
            throw IllegalArgumentException("downloaded APK is empty")
        }
        return apk
    }

    private fun openHttpsConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw IllegalArgumentException("only HTTPS URLs are allowed")
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Android-Tux-Terminal-pkg/0.3")
        }
        connection.connect()
        if (!connection.url.protocol.equals("https", ignoreCase = true)) {
            connection.disconnect()
            throw IllegalArgumentException("redirected to a non-HTTPS URL")
        }
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw IllegalArgumentException("HTTP $code")
        }
        return connection
    }

    private fun launchInstaller(apk: File) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            append("pkg: allow Android Tux Terminal to install unknown apps, then run the command again.\n")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
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

    private fun changeDirectory(target: String) {
        val destination = if (target.isBlank() || target == "~") "/" else target
        executeShell("cd ${shellQuote(destination)} && pwd") { result ->
            val newPath = result.trim().lineSequence().lastOrNull()?.trim()
            if (!newPath.isNullOrBlank() && newPath.startsWith("/")) {
                cwd = newPath
                updatePrompt()
            }
            append(result)
        }
    }

    private fun executeShell(command: String, callback: ((String) -> Unit)? = null) {
        Thread {
            try {
                val fullCommand = "cd ${shellQuote(cwd)} && $command"
                val process = ProcessBuilder("/system/bin/sh", "-c", fullCommand)
                    .redirectErrorStream(true)
                    .start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val result = buildString {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        append(line).append('\n')
                    }
                }
                process.waitFor()
                runOnUiThread {
                    if (callback != null) callback(result) else append(result)
                }
            } catch (e: Exception) {
                runOnUiThread { append("Error: ${e.message}\n") }
            }
        }.start()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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

    private fun updatePrompt() {
        input.hint = "$cwd $ "
    }

    private fun append(text: String) {
        output.append(text)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
