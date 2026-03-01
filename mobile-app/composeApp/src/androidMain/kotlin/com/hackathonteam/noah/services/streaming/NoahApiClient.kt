package com.hackathonteam.noah.services.streaming

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "NoahApiClient"

private const val BASE_URL        = "http://88.162.106.12:32666"
private const val REGISTER_ENDPOINT    = "$BASE_URL/register"
private const val ASK_ENDPOINT    = "$BASE_URL/ask"
private const val IMAGE_ENDPOINT  = "$BASE_URL/image"
private const val SPEECH_ENDPOINT = "$BASE_URL/speech"

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS    = 15_000

private var number_of_tries = 3

/**
 * Describes every outcome that [NoahApiClient.register] can return.
 */
sealed class RegisterResult {
    /** Session successfully registered; [uuid] identifies all subsequent requests. */
    data class Registered(val uuid: String) : RegisterResult()
    /** The server returned `{"type": "ERROR"}` or a network / parse error occurred. */
    data class Error(val reason: String) : RegisterResult()
}

/**
 * Thin HTTP client for the Noah FastAPI backend.
 *
 * All methods are **blocking** — always call them from an IO-bound coroutine
 * (e.g. `withContext(Dispatchers.IO) { … }`).
 *
 * Lifecycle
 * ---------
 * 1. Call [register] once per tracking session to obtain a session UUID.
 * 2. Use [sendImage] and [sendAudio] to upload data frames with that UUID.
 */
object NoahApiClient {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * POST /ask — registers a new session with a dummy question.
     *
     * Request body: empty
     * Expected success response:
     * ```json
     * {"uuid": "<uuid>", "answer": "<text>", "type": "registered"}
     * ```
     * Expected error response:
     * ```json
     * {"type": "error"}
     * ```
     *
     * @return [RegisterResult.Registered] on success, [RegisterResult.Error] otherwise.
     */
    fun register(): RegisterResult {

        return try {
            val raw = postJson(REGISTER_ENDPOINT, null)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "registered" -> {
                    val uuid   = json.getString("uuid")
                    Log.d(TAG, "POST /ask → REGISTERED uuid=$uuid")
                    RegisterResult.Registered(uuid = uuid)
                }
                else -> {
                    Log.w(TAG, "POST /ask → unexpected type=${json.optString("type")}")
                    RegisterResult.Error("Server returned type=${json.optString("type")}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "POST /ask failed: ${e.message}")
            RegisterResult.Error(e.message ?: "IOException")
        } catch (e: Exception) {
            Log.e(TAG, "POST /ask unexpected error: ${e.message}")
            RegisterResult.Error(e.message ?: "Unexpected error")
        }
    }

    /**
     * POST /image — uploads a JPEG frame associated with [uuid].
     *
     * Request body:
     * ```json
     * {"uuid": "<uuid>", "image_data": "<base64>"}
     * ```
     *
     * @param uuid       Session UUID returned by [register].
     * @param jpegBytes  Raw JPEG bytes (as produced by the camera pipeline).
     * @return `true` when the server acknowledges with a 2xx status.
     */
    fun sendImage(uuid: String, jpegBytes: ByteArray): Boolean {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
            put("image_data", base64Image)
        }.toString()

        return try {
            val raw = postJson(IMAGE_ENDPOINT, jsonBody)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "success" -> {
                    Log.d(TAG, "POST /image succeeded — uuid=$uuid  bytes=${jpegBytes.size}")
                }
                "key_error" -> {
                    if(number_of_tries <= 0) {
                        Log.w(TAG, "POST /image → key error and no more tries left")
                        return false
                    } else {
                        number_of_tries--
                        Log.w(TAG, "POST /image → key error, retrying… tries left: $number_of_tries")
                        val registerResult = register()
                        if(registerResult is RegisterResult.Registered) {
                            Log.d(TAG, "Re-registered with new uuid=${registerResult.uuid} after key error")
                            return sendImage(registerResult.uuid, jpegBytes)
                        } else {
                            Log.w(TAG, "Failed to re-register after key error")
                        }
                        return false
                    }
                }
                else -> {
                    if(number_of_tries <= 0) {
                        Log.w(TAG, "POST /image → other error and no more tries left")
                        return false
                    } else {
                        number_of_tries--
                        Log.w(TAG, "POST /image → other error, retrying… tries left: $number_of_tries")
                        return sendImage(uuid, jpegBytes)
                    }
                }
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "POST /image failed: ${e.message}")
            false
        }
    }

    /**
     * POST /speech — uploads a PCM audio buffer associated with [uuid].
     *
     * The [pcmChunks] list is concatenated and Base64-encoded before sending.
     *
     * Request body:
     * ```json
     * {"uuid": "<uuid>", "audio_data": "<base64>"}
     * ```
     *
     * @param uuid       Session UUID returned by [register].
     * @param pcmChunks  Ordered list of raw PCM-16 mono chunks to concatenate.
     * @return `true` when the server acknowledges with a 2xx status.
     */
    fun sendAudio(uuid: String, pcmChunks: List<ByteArray>): Boolean {
        if (pcmChunks.isEmpty()) {
            Log.d(TAG, "sendAudio skipped — no audio chunks")
            return true
        }

        // Concatenate all chunks into one contiguous buffer.
        val totalSize = pcmChunks.sumOf { it.size }
        val combined = ByteArray(totalSize)
        var offset = 0
        for (chunk in pcmChunks) {
            chunk.copyInto(combined, offset)
            offset += chunk.size
        }

        val base64Audio = Base64.encodeToString(combined, Base64.NO_WRAP)
        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
            put("audio_data", base64Audio)
        }.toString()

        return try {
            val raw = postJson(SPEECH_ENDPOINT, jsonBody)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "success" -> {
                    Log.d(TAG, "POST /speech succeeded — uuid=$uuid  totalBytes=$totalSize")
                }
                "key_error" -> {
                    if(number_of_tries <= 0) {
                        Log.w(TAG, "POST /speech → key error and no more tries left")
                        return false
                    } else {
                        number_of_tries--
                        val registerResult = register()
                        if(registerResult is RegisterResult.Registered) {
                            Log.d(TAG, "Re-registered with new uuid=${registerResult.uuid} after key error")
                            return sendAudio(registerResult.uuid, pcmChunks)
                        } else {
                            Log.w(TAG, "Failed to re-register after key error")
                        }
                        return false
                    }
                }
                else -> {
                    if(number_of_tries <= 0) {
                        Log.w(TAG, "POST /speech → other error and no more tries left")
                        return false
                    } else {
                        number_of_tries--
                        Log.w(TAG, "POST /speech → other error, retrying… tries left: $number_of_tries")
                        return sendAudio(uuid, pcmChunks)
                    }
                }
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "POST /speech failed: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Performs a blocking HTTP POST with a JSON body and returns the response
     * body as a [String]. Throws [IOException] on network or HTTP errors.
     */
    @Throws(IOException::class)
    private fun postJson(endpoint: String, jsonBody: String?): String {
        val url  = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod    = "POST"
            conn.connectTimeout   = CONNECT_TIMEOUT_MS
            conn.readTimeout      = READ_TIMEOUT_MS
            conn.doOutput         = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")

            if(jsonBody == null) {
                conn.setFixedLengthStreamingMode(0)
            } else {
                val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bodyBytes.size)
                conn.outputStream.use { it.write(bodyBytes) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.readText() ?: ""
            Log.d(TAG, "POST $endpoint  [$code]  response=$raw")

            if (code !in 200..299) {
                throw IOException("HTTP $code: $raw")
            }
            return raw
        } finally {
            conn.disconnect()
        }
    }
}
