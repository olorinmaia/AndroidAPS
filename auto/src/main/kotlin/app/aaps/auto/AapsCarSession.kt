package app.aaps.auto

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.Session
import app.aaps.auto.screens.MainScreen

class AapsCarSession(private val deps: AutoDependencies) : Session() {

    init {
        // The Car App Library throws a RuntimeException on the main thread when the host
        // rejects a template (e.g. quota exceeded). An UncaughtExceptionHandler cannot
        // prevent the main thread from dying after it runs, so we use the "Looper trick":
        // catch the exception inside the Looper dispatch so the main thread keeps running.
        // Real (non-quota) exceptions are re-thrown and crash normally.
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                } catch (e: RuntimeException) {
                    if (isCarAppQuotaException(e)) {
                        Log.w("AAPS.Auto", "Car App template quota exceeded. " +
                            "Return to the Android Auto home screen and relaunch AAPS to reset.")
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    override fun onCreateScreen(intent: Intent): Screen =
        MainScreen(carContext, deps.autoDataProvider(), deps.config())

    private fun isCarAppQuotaException(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e.message?.contains("No template allowed after") == true) return true
            e = e.cause
        }
        return false
    }
}
