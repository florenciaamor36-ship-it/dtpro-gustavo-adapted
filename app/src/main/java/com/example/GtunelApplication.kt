package com.example

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/** Installs crash persistence before any Activity or VPN service is created. */
class GtunelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                getSharedPreferences("gtunel_diagnostics", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", "${thread.name}: ${sw}")
                    .apply()
                Thread.sleep(120)
            } catch (_: Throwable) {
                // Never interfere with Android's normal crash handling.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
