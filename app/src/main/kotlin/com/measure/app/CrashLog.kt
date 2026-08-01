package com.measure.app

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the last uncaught exception to a file so it can be read back on the next
 * launch.
 *
 * Builds reach the test device by sideloading an APK from a browser, which means the
 * usual answer — attach `adb logcat` — is not available to whoever is holding the phone.
 * Without this, a crash is indistinguishable from the app quietly closing: Android
 * returns to whatever was behind it and there is nothing left to look at.
 *
 * Deliberately a file rather than an in-memory field. The process is dying; anything not
 * written to disk before the handler returns is gone.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(appContext, thread, error)
            } catch (ignored: Throwable) {
                // The process is already going down. There is nothing useful to do if
                // even recording the reason fails, and throwing here would replace the
                // real crash with a less interesting one.
            }
            // Always chain: the system handler is what actually terminates the process,
            // and what puts the trace in logcat for anyone who does have a cable.
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val file = file(context)
        return if (file.exists()) file.readText().ifBlank { null } else null
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        file(context).writeText(
            buildString {
                appendLine("Crash at $stamp on thread '${thread.name}'")
                appendLine("${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")
                appendLine()
                append(trace)
            },
        )
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}

/** Exists solely to install [CrashLog] before any activity can run. */
class MeasureApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
