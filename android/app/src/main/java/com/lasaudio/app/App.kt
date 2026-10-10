package com.lasaudio.app

import android.app.Application
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to a file under the app's external files dir so a crash can be
 * diagnosed without adb, and shows a toast so the user knows where to look.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("LANAudio", "crash on " + thread.name, throwable)
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val header = buildString {
                    append("time=").append(stamp).append('\n')
                    append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append("android=").append(Build.VERSION.RELEASE).append(" (api ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("app=1.0.0\n")
                    append("state=").append(AppState.state).append('\n')
                    append("servers=").append(AppState.servers.size).append('\n')
                    append("jitterMode=").append(AppState.jitterMode).append('\n')
                    append("autoReconnect=").append(AppState.autoReconnect).append('\n')
                    append("---\n")
                }
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "crash-$stamp.txt").writeText(header + sw.toString())
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
