package com.hackathonteam.noah.services.streaming

import android.util.Base64
import android.util.Log
import com.hackathonteam.noah.services.sensor.location.GpsSensor
import com.hackathonteam.noah.tracking.TrackingState
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "NoahApiClient"

private const val BASE_URL              = "http://88.162.106.12:32666"
private const val REGISTER_ENDPOINT    = "$BASE_URL/register"

private const val ASK_ENDPOINT         = "$BASE_URL/ask"
private const val IMAGE_ENDPOINT       = "$BASE_URL/image"
private const val SPEECH_ENDPOINT      = "$BASE_URL/speech"
private const val USER_INFO_ENDPOINT      = "$BASE_URL/data"
private const val AUDIO_CHUNK_ENDPOINT = "$BASE_URL/audio/chunk"
private const val AUDIO_FINISH_ENDPOINT = "$BASE_URL/audio/finish"

private const val CONNECT_TIMEOUT_MS        = 10_000
private const val READ_TIMEOUT_MS           = 15_000
private const val AUDIO_FINISH_TIMEOUT_MS   = 120_000   // 2 minutes — LLM can be slow

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
 * Describes every outcome that [NoahApiClient.finishAudio] can return.
 */
sealed class FinishAudioResult {
    /** The server successfully processed the audio and returned an answer. */
    data class Success(
        val answer: String,
        /** Raw bytes of the response audio (decoded from base64). May be empty. */
        val audioBytes: ByteArray,
    ) : FinishAudioResult()
    /** The server returned an error or the call failed. */
    data class Error(val reason: String) : FinishAudioResult()
}

object UserInfo {
    var activity: TrackingState? = TrackingState.IDLE
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

    fun ask(question: String, uuid: String) : Any {
        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
            put("question", question)
        }.toString()

        return try {
            val raw = postJson(ASK_ENDPOINT, jsonBody)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "success" -> {
                    val answer = json.getString("answer")
                    Log.d(TAG, "POST /ask → REGISTERED uanswer=$answer")
                    answer
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

    fun sendUserInfo(uuid: String, info: UserInfo) : Boolean {
        // Read GPS live from the sensor at the moment of the call — never stale.
        val gpsReading = GpsSensor.window.getLatest()
        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
            val gpsObj = JSONObject().apply {
                put("lat", gpsReading?.x ?: 0f)
                put("lon", gpsReading?.y ?: 0f)
            }
            val userInfoObj = JSONObject().apply {
                put("gps", gpsObj)
                put("userState", info.activity?.name ?: "UNKNOWN")
            }
            put("user_info", userInfoObj)
        }.toString()

        Log.d("NoahApiClient", "Sending user info: $jsonBody")

        return try {
            val raw = postJson(USER_INFO_ENDPOINT, jsonBody)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "success" -> {
                    Log.d(TAG, "POST /data succeeded — uuid=$uuid  info=$info")
                    true
                }
                else -> {
                    Log.w(TAG, "POST /data → unexpected type=${json.optString("type")}")
                    false
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "POST /user_info failed: ${e.message}")
            false
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
                    number_of_tries = 3 //reset
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
                    number_of_tries = 3//reset
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

    /**
     * POST /audio/chunk — uploads a single raw PCM chunk for [uuid].
     *
     * Request body:
     * ```json
     * {"uuid": "<uuid>", "audio_data": "<base64-pcm>"}
     * ```
     *
     * @param uuid     Session UUID returned by [register].
     * @param pcmChunk A single raw PCM-16 mono chunk.
     * @return `true` when the server acknowledges with a 2xx status.
     */
    fun sendAudioChunk(uuid: String, pcmChunk: ByteArray): Boolean {
        val base64Audio = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
            put("audio_data", base64Audio)
        }.toString()

        return try {
            val raw = postJson(AUDIO_CHUNK_ENDPOINT, jsonBody)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "success" -> {
                    Log.d(TAG, "POST /audio/chunk succeeded — uuid=$uuid  bytes=${pcmChunk.size}")
                    true
                }
                "key_error" -> {
                    Log.w(TAG, "POST /audio/chunk → key_error for uuid=$uuid")
                    false
                }
                else -> {
                    Log.w(TAG, "POST /audio/chunk → unexpected type=${json.optString("type")}")
                    false
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "POST /audio/chunk failed: ${e.message}")
            false
        }
    }

    /**
     * POST /audio/finish — signals that audio recording is complete and
     * instructs the server to convert accumulated PCM chunks to a WAV file,
     * run the LLM pipeline, and return the answer + response audio.
     *
     * Request body:
     * ```json
     * {"uuid": "<uuid>"}
     * ```
     *
     * @param uuid Session UUID returned by [register].
     * @return [FinishAudioResult.Success] with the LLM answer and audio bytes on success,
     *         or [FinishAudioResult.Error] on failure.
     */
    fun finishAudio(uuid: String): FinishAudioResult {
        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
        }.toString()

        return try {
            val raw = postJson(AUDIO_FINISH_ENDPOINT, jsonBody, readTimeoutMs = AUDIO_FINISH_TIMEOUT_MS)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "success" -> {
                    val answer         = json.optString("answer", "")
                    val audioBase64    = json.optString("response_audio_data", "")
                    val audioBytes     = if (audioBase64.isNotEmpty())
                        Base64.decode(audioBase64, Base64.NO_WRAP)
                    else
                        ByteArray(0)
                    Log.d(TAG, "POST /audio/finish succeeded — uuid=$uuid  answer=${answer.take(80)}  audioBytes=${audioBytes.size}")
                    FinishAudioResult.Success(answer = answer, audioBytes = audioBytes)
                }
                "key_error" -> {
                    Log.w(TAG, "POST /audio/finish → key_error for uuid=$uuid")
                    FinishAudioResult.Error("key_error")
                }
                else -> {
                    val errMsg = json.optString("error", "unknown error")
                    Log.w(TAG, "POST /audio/finish → type=${json.optString("type")}  error=$errMsg")
                    FinishAudioResult.Error(errMsg)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "POST /audio/finish failed: ${e.message}")
            FinishAudioResult.Error(e.message ?: "IOException")
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
    private fun postJson(
        endpoint: String,
        jsonBody: String?,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): String {
        val url  = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod    = "POST"
            conn.connectTimeout   = CONNECT_TIMEOUT_MS
            conn.readTimeout      = readTimeoutMs
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