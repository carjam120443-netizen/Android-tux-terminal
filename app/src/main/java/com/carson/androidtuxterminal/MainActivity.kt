package com.carson.androidtuxterminal

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
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
            text = "Android Tux Terminal 0.1.0\n" +
                    "Android shell • Linux-style terminal\n" +
                    "Type 'help' for built-in commands.\n\n"
            isFocusable = false
        }

        scroll = ScrollView(this).apply {
            addView(output)
            isFillViewport = true
        }

        input = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setHint("command")
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
        }

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        input.requestFocus()
    }

    private fun runCommand(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        input.text.clear()
        append("\n$ $cmd\n")

        when (cmd) {
            "help" -> append("Built-ins: help, clear, pwd, whoami, uname, exit\n")
            "clear" -> output.text = ""
            "exit" -> append("Android Tux Terminal: exit is disabled in the app shell.\n")
            else -> executeShell(cmd)
        }
    }

    private fun executeShell(command: String) {
        Thread {
            try {
                val process = ProcessBuilder("/system/bin/sh", "-c", command)
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
                runOnUiThread { append(result) }
            } catch (e: Exception) {
                runOnUiThread { append("Error: ${e.message}\n") }
            }
        }.start()
    }

    private fun append(text: String) {
        output.append(text)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
