package com.carson.androidtuxterminal

/** Native pseudo-terminal bridge backed by Android's bionic forkpty(). */
class NativePty {
    companion object {
        init { System.loadLibrary("android_tux_pty") }
    }

    private var handle: Long = 0

    @Synchronized
    fun start(cwd: String, columns: Int, rows: Int) {
        close()
        handle = nativeStart(cwd, columns, rows)
        if (handle == 0L) error("Unable to create PTY")
    }

    fun read(): ByteArray? {
        val h = synchronized(this) { handle }
        if (h == 0L) return null
        return nativeRead(h)
    }

    @Synchronized
    fun write(data: ByteArray) {
        if (handle != 0L) nativeWrite(handle, data)
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    @Synchronized
    fun resize(columns: Int, rows: Int) {
        if (handle != 0L) nativeResize(handle, columns, rows)
    }

    @Synchronized
    fun sendCtrlC() {
        if (handle != 0L) nativeWrite(handle, byteArrayOf(0x03))
    }

    @Synchronized
    fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeStart(cwd: String, columns: Int, rows: Int): Long
    private external fun nativeRead(handle: Long): ByteArray?
    private external fun nativeWrite(handle: Long, data: ByteArray): Int
    private external fun nativeResize(handle: Long, columns: Int, rows: Int): Int
    private external fun nativeClose(handle: Long)
}
