package com.carson.androidtuxterminal

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView

    private var cwd = "/"
    private val history = mutableListOf<String>()
    private var historyIndex = 0

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
            text = "Android Tux Terminal 0.2.0\n" +
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
                "  help       show this help\n" +
                "  clear      clear terminal output\n" +
                "  cd <dir>   change directory\n" +
                "  pwd        print working directory\n" +
                "  history    show command history\n" +
                "  exit       keep the app shell open\n\n" +
                "Any other command is executed by Android /system/bin/sh.\n"
            )
            cmd == "clear" -> output.text = ""
            cmd == "history" -> append(history.mapIndexed { i, value -> "${i + 1}  $value\n" }.joinToString())
            cmd == "pwd" -> append("$cwd\n")
            cmd == "exit" -> append("Android Tux Terminal: exit is disabled in the app shell.\n")
            cmd == "cd" || cmd.startsWith("cd ") -> changeDirectory(cmd.removePrefix("cd").trim())
            else -> executeShell(cmd)
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
