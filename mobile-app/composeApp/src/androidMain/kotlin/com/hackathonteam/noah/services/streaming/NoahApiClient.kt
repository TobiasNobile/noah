package com.hackathonteam.noah.services.streaming

import android.content.Context
import android.util.Base64
import android.util.Log
import com.hackathonteam.noah.services.sensor.location.GpsSensor
import com.hackathonteam.noah.tracking.TrackingState
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "NoahApiClient"

private const val CONNECT_TIMEOUT_MS      = 10_000
private const val READ_TIMEOUT_MS         = 15_000
private const val AUDIO_FINISH_TIMEOUT_MS = 120_000   // 2 minutes — LLM can be slow

private var number_of_tries = 3

/**
 * Describes every outcome that [NoahApiClient.register] can return.
 */
sealed class RegisterResult {
    data class Registered(val uuid: String) : RegisterResult()
    data class Error(val reason: String) : RegisterResult()
}

/**
 * Describes every outcome that [NoahApiClient.finishAudio] can return.
 */
sealed class FinishAudioResult {
    /** Server processed the audio and returned an LLM answer + response audio. */
    data class Success(
        val answer: String,
        /** Raw WAV bytes decoded from the base64 response_audio_data field. */
        val audioBytes: ByteArray,
    ) : FinishAudioResult()
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
 * 0. Call [init] once at app startup (e.g. in Application.onCreate or MainActivity.onCreate).
 * 1. Call [register] once per tracking session to obtain a session UUID.
 * 2. Use [sendImage] and [sendAudio] to upload data frames with that UUID.
 */
object NoahApiClient {

    // -------------------------------------------------------------------------
    // URL dynamique lue depuis le stockage interne
    // -------------------------------------------------------------------------

    private var baseUrl: String = "http://localhost:32666"

    private val registerEndpoint    get() = "$baseUrl/register"
    private val askEndpoint         get() = "$baseUrl/ask"
    private val imageEndpoint       get() = "$baseUrl/image"
    private val speechEndpoint      get() = "$baseUrl/speech"
    private val userInfoEndpoint    get() = "$baseUrl/data"
    private val audioChunkEndpoint  get() = "$baseUrl/audio/chunk"
    private val audioFinishEndpoint get() = "$baseUrl/audio/finish"

    /**
     * À appeler une fois au démarrage de l'app pour charger l'IP et le port
     * depuis le stockage interne (fichier server_settings.txt dans filesDir).
     * Si le fichier n'existe pas, utilise localhost:32666 par défaut.
     */
    fun init(context: Context) {
        val (ip, port) = loadSettings(context)
        baseUrl = "http://$ip:$port"
        Log.d(TAG, "NoahApiClient initialized with baseUrl=$baseUrl")
    }

    private fun loadSettings(context: Context): Pair<String, String> {
        val file = java.io.File(context.filesDir, "server_settings.txt")
        return if (file.exists()) {
            val lines = file.readLines()
            val ip   = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "localhost"
            val port = lines.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "32666"
            Pair(ip, port)
        } else {
            Pair("localhost", "32666")
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun register(): RegisterResult {
        return try {
            val raw = postJson(registerEndpoint, null)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "registered" -> {
                    val uuid = json.getString("uuid")
                    Log.d(TAG, "POST /register → REGISTERED uuid=$uuid")
                    RegisterResult.Registered(uuid = uuid)
                }
                else -> {
                    Log.w(TAG, "POST /register → unexpected type=${json.optString("type")}")
                    RegisterResult.Error("Server returned type=${json.optString("type")}")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "POST /register failed: ${e.message}")
            RegisterResult.Error(e.message ?: "IOException")
        } catch (e: Exception) {
            Log.e(TAG, "POST /register unexpected error: ${e.message}")
            RegisterResult.Error(e.message ?: "Unexpected error")
        }
    }

    fun ask(question: String, uuid: String): Any {
        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
            put("question", question)
        }.toString()

        return try {
            val raw = postJson(askEndpoint, jsonBody)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "success" -> {
                    val answer = json.getString("answer")
                    Log.d(TAG, "POST /ask → answer=$answer")
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

    fun sendUserInfo(uuid: String, info: UserInfo): Boolean {
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
            val raw = postJson(userInfoEndpoint, jsonBody)
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

    fun sendImage(uuid: String, jpegBytes: ByteArray): Boolean {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
            put("image_data", base64Image)
        }.toString()

        return try {
            val raw = postJson(imageEndpoint, jsonBody)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "success" -> {
                    Log.d(TAG, "POST /image succeeded — uuid=$uuid  bytes=${jpegBytes.size}")
                    number_of_tries = 3
                }
                "key_error" -> {
                    if (number_of_tries <= 0) {
                        Log.w(TAG, "POST /image → key error and no more tries left")
                        return false
                    } else {
                        number_of_tries--
                        Log.w(TAG, "POST /image → key error, retrying… tries left: $number_of_tries")
                        val registerResult = register()
                        if (registerResult is RegisterResult.Registered) {
                            return sendImage(registerResult.uuid, jpegBytes)
                        }
                        return false
                    }
                }
                else -> {
                    if (number_of_tries <= 0) {
                        Log.w(TAG, "POST /image → other error and no more tries left")
                        return false
                    } else {
                        number_of_tries--
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

    fun sendAudio(uuid: String, pcmChunks: List<ByteArray>): Boolean {
        if (pcmChunks.isEmpty()) {
            Log.d(TAG, "sendAudio skipped — no audio chunks")
            return true
        }

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
            val raw = postJson(speechEndpoint, jsonBody)
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "success" -> {
                    Log.d(TAG, "POST /speech succeeded — uuid=$uuid  totalBytes=$totalSize")
                    number_of_tries = 3
                }
                "key_error" -> {
                    if (number_of_tries <= 0) {
                        Log.w(TAG, "POST /speech → key error and no more tries left")
                        return false
                    } else {
                        number_of_tries--
                        val registerResult = register()
                        if (registerResult is RegisterResult.Registered) {
                            return sendAudio(registerResult.uuid, pcmChunks)
                        }
                        return false
                    }
                }
                else -> {
                    if (number_of_tries <= 0) {
                        Log.w(TAG, "POST /speech → other error and no more tries left")
                        return false
                    } else {
                        number_of_tries--
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

    fun sendAudioChunk(uuid: String, pcmChunk: ByteArray): Boolean {
        val base64Audio = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
            put("audio_data", base64Audio)
        }.toString()

        return try {
            val raw = postJson(audioChunkEndpoint, jsonBody)
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

    fun finishAudio(uuid: String): FinishAudioResult {
        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
        }.toString()

        return try {
            val raw  = postJson(audioFinishEndpoint, jsonBody, readTimeoutMs = AUDIO_FINISH_TIMEOUT_MS)
            if (raw.isBlank()) {
                Log.w(TAG, "POST /audio/finish — empty response body for uuid=$uuid")
                return FinishAudioResult.Error("Empty response from server")
            }
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "success" -> {
                    val answer      = json.optString("answer", "")
                    val audioBase64 = json.optString("response_audio_data", "")
                    val audioBytes  = if (audioBase64.isNotEmpty())
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
        } catch (e: Exception) {
            Log.e(TAG, "POST /audio/finish failed: ${e.message}")
            FinishAudioResult.Error(e.message ?: "Exception")
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    @Throws(IOException::class)
    private fun postJson(
        endpoint: String,
        jsonBody: String?,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): String {
        val url  = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod  = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = readTimeoutMs
            conn.doOutput       = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")

            if (jsonBody == null) {
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