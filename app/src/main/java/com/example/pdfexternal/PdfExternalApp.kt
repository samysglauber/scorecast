package com.example.pdfexternal

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfExternalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        val savedMode = prefs.getInt(MainActivity.KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)

        installCrashLogger()
    }

    /**
     * Grava qualquer crash (exceção não tratada) num arquivo de texto legível em
     * getExternalFilesDir, para diagnóstico sem precisar de Android Studio/adb.
     * Local típico: /storage/emulated/0/Android/data/com.example.pdfexternal/files/crash_log.txt
     */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                val dir = getExternalFilesDir(null) ?: filesDir
                val logFile = File(dir, "crash_log.txt")
                logFile.appendText("\n\n===== CRASH em $timestamp (thread: ${thread.name}) =====\n$sw")
            } catch (loggingError: Exception) {
                loggingError.printStackTrace()
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
