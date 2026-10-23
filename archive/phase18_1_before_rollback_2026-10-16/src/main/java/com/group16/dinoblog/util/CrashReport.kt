package com.group16.dinoblog.util

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log

/** Keeps one local fatal stack trace so a device without Logcat can report a startup crash. */
object CrashReport {
    private const val PREFERENCES = "crash_report"
    private const val KEY = "last_fatal"
    private const val MAX_CHARS = 16_000

    fun install(application: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = "${error.javaClass.name}: ${error.message.orEmpty()}\n" +
                    Log.getStackTraceString(error)
                application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString(KEY, report.take(MAX_CHARS)).commit()
            } catch (_: Exception) {
                // Preserve Android's original crash handler even if storage is unavailable.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else Process.killProcess(Process.myPid())
            }
        }
    }

    fun read(context: Context): String? = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
