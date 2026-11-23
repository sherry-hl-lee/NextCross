package com.fit5120tp10.nextcross.client.aireflection

import android.content.Context
import android.util.Log
import com.fit5120tp10.nextcross.BuildConfig
import com.fit5120tp10.nextcross.R

/**
 * Process-local facade for the post-trip AI reflection.
 *
 * There is no API key on the device. The app POSTs whitelisted numbers to the NextCross
 * backend, which holds the provider credential and the prompts. While the backend's
 * two-turn routes are still being deployed, `ai_reflection_use_mock` keeps the card usable
 * offline.
 */
object AiReflectionLocator {

    /** `adb logcat -s NextCrossAI` shows exactly what the app sends and receives. */
    const val LOG_TAG: String = "NextCrossAI"

    @Volatile
    private var client: AiReflectionClient? = null

    fun get(context: Context): AiReflectionClient = client ?: synchronized(this) {
        client ?: createClient(context.applicationContext).also { client = it }
    }

    /**
     * Where answers come from, for developer diagnostics only. Deliberately a log line and
     * not something the card can render: a driver has no use for a hostname, and a URL or a
     * mock label on screen is a diagnostic leaking into user content.
     */
    private fun logSource(source: String) {
        if (BuildConfig.DEBUG) Log.i(LOG_TAG, "reflection source: $source")
    }

    /** True when the card is answering from offline sample text rather than the backend. */
    fun isMock(context: Context): Boolean =
        context.resources.getBoolean(R.bool.ai_reflection_use_mock)

    /** Test / debug override. Pass null to rebuild from resources on the next [get]. */
    fun replaceForTests(replacement: AiReflectionClient?) {
        synchronized(this) {
            client = replacement
        }
    }

    private fun createClient(context: Context): AiReflectionClient {
        if (isMock(context)) {
            logSource("offline sample")
            return MockAiReflectionClient()
        }
        val endpointUrl = context.getString(R.string.ai_reflection_url).trim()
        require(endpointUrl.isNotEmpty()) {
            "ai_reflection_url must be set when mock mode is disabled."
        }
        logSource(endpointUrl)
        return BackendAiReflectionClient(
            http = ReflectionBackendHttpClient(endpointUrl),
            logger = if (BuildConfig.DEBUG) LogcatAiReflectionLogger else NoAiReflectionLogging
        )
    }
}

/**
 * Debug builds only: prints the request and the reply in chunks logcat will not truncate.
 *
 * The advice request contains the driver's own sentence, so do not leave this stream open
 * on a projector during a demo.
 */
private val LogcatAiReflectionLogger = AiReflectionLogger { message ->
    message.chunked(MAX_LOGCAT_CHARACTERS).forEach { chunk ->
        Log.i(AiReflectionLocator.LOG_TAG, chunk)
    }
}

private const val MAX_LOGCAT_CHARACTERS = 3_000
