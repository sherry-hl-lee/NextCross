package com.fit5120tp10.nextcross.client.speedlimit

import android.content.Context
import com.fit5120tp10.nextcross.client.recording.RecordingLocator

/** Process-local recorder so Active Trip and End Trip share one open-segment state. */
object SpeedExceedanceRecorderLocator {
    @Volatile
    private var recorder: SpeedExceedanceRecorder? = null

    fun get(context: Context): SpeedExceedanceRecorder {
        return recorder ?: synchronized(this) {
            recorder ?: SpeedExceedanceRecorder(
                RecordingLocator.database(context.applicationContext).recordingDao()
            ).also { recorder = it }
        }
    }

    fun replaceForTests(replacement: SpeedExceedanceRecorder?) {
        synchronized(this) {
            recorder = replacement
        }
    }
}
