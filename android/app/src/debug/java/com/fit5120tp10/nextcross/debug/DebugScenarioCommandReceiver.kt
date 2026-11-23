package com.fit5120tp10.nextcross.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.fit5120tp10.nextcross.client.recording.RecordingLocator

/** Debug-build entry point used only by tools/debug/RunDrivingScenario.ps1. */
class DebugScenarioCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scenario = intent.getStringExtra(EXTRA_SCENARIO)
        val pendingResult = goAsync()
        RecordingLocator.io.execute {
            val message = DebugScenarioInjector.run(context.applicationContext, scenario)
            Log.i(LOG_TAG, message)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
            }
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_RUN_SCENARIO = "com.fit5120tp10.nextcross.debug.RUN_SCENARIO"
        const val EXTRA_SCENARIO = "scenario"
        const val LOG_TAG = "DebugScenario"
    }
}
