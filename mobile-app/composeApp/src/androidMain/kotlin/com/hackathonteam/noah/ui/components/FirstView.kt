package com.hackathonteam.noah.ui.components

import androidx.compose.foundation.layout.*
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hackathonteam.noah.R
import com.hackathonteam.noah.tracking.TrackingManager
import java.io.File
import java.util.*

// Couleurs Mistral AI
val MistralOrange = Color(0xFFFA500F)
val MistralDarkBackground = Color(0xFF1A1A2E)
val MistralTextWhite = Color(0xFFFFFFFF)

private const val SETTINGS_FILE = "server_settings.txt"
private const val DEFAULT_IP = "localhost"
private const val DEFAULT_PORT = "32666"

fun loadSettings(context: Context): Pair<String, String> {
    val file = File(context.filesDir, SETTINGS_FILE)
    return if (file.exists()) {
        val lines = file.readLines()
        val ip = lines.getOrNull(0) ?: DEFAULT_IP
        val port = lines.getOrNull(1) ?: DEFAULT_PORT
        Pair(ip, port)
    } else {
        Pair(DEFAULT_IP, DEFAULT_PORT)
    }
}

fun saveSettings(context: Context, ip: String, port: String) {
    val file = File(context.filesDir, SETTINGS_FILE)
    file.writeText("$ip\n$port")
}

enum class Screen { MAIN, SETTINGS }

@Composable
fun App() {
    val context: Context = LocalContext.current
    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }
    var currentScreen by remember { mutableStateOf(Screen.MAIN) }

    LaunchedEffect(Unit) {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { textToSpeech?.shutdown() }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MistralOrange,
            background = MistralDarkBackground,
            onPrimary = MistralTextWhite,
            onBackground = MistralTextWhite
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (currentScreen) {
                Screen.MAIN -> MainScreen(
                    textToSpeech = textToSpeech,
                    onNavigateToSettings = { currentScreen = Screen.SETTINGS }
                )
                Screen.SETTINGS -> SettingsScreen(
                    onNavigateBack = { currentScreen = Screen.MAIN }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    textToSpeech: TextToSpeech?,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        GiantAccessibleButton(
            isTrackingActive = TrackingManager.isTrackingActive,
            onClick = {
                if (TrackingManager.isTrackingActive) {
                    TrackingManager.stopListening()
                    textToSpeech?.speak("Stopped tracking", TextToSpeech.QUEUE_FLUSH, null, null)
                } else {
                    TrackingManager.startListening(context)
                    textToSpeech?.speak("Started tracking", TextToSpeech.QUEUE_FLUSH, null, null)
                }
                triggerHapticFeedback(context)
            },
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .align(Alignment.Center)
                .padding(32.dp)
        )

        Image(
            painter = painterResource(id = R.drawable.mistral_logo),
            contentDescription = "Logo Mistral AI",
            modifier = Modifier
                .size(80.dp)
                .align(Alignment.TopStart)
                .padding(16.dp)
        )

        // Roue dentée orange, sans fond
        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(48.dp)
                .semantics { contentDescription = "Open settings" }
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MistralOrange,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val (initialIp, initialPort) = remember { loadSettings(context) }

    var ip by remember { mutableStateOf(initialIp) }
    var port by remember { mutableStateOf(initialPort) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Button(
            onClick = onNavigateBack,
            modifier = Modifier.widthIn(min = 120.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2A2A4E),
                contentColor = MistralTextWhite
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "",
                fontSize = 14.sp,
                modifier = Modifier.semantics {
                    contentDescription = "Back to home screen"
                }
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Server settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MistralTextWhite
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = "IP Address",
            fontSize = 14.sp,
            color = MistralTextWhite.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = ip,
            onValueChange = { newIp ->
                ip = newIp
                saveSettings(context, newIp, port)
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Server IP Address field" },
            label = { Text("Server IP") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MistralOrange,
                unfocusedBorderColor = MistralTextWhite.copy(alpha = 0.3f),
                focusedTextColor = MistralTextWhite,
                unfocusedTextColor = MistralTextWhite,
                focusedLabelColor = MistralOrange,
                unfocusedLabelColor = MistralTextWhite.copy(alpha = 0.5f),
                cursorColor = MistralOrange
            )
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Port",
            fontSize = 14.sp,
            color = MistralTextWhite.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = port,
            onValueChange = { newPort ->
                port = newPort
                saveSettings(context, ip, newPort)
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Server port field" },
            label = { Text("Port server") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MistralOrange,
                unfocusedBorderColor = MistralTextWhite.copy(alpha = 0.3f),
                focusedTextColor = MistralTextWhite,
                unfocusedTextColor = MistralTextWhite,
                focusedLabelColor = MistralOrange,
                unfocusedLabelColor = MistralTextWhite.copy(alpha = 0.5f),
                cursorColor = MistralOrange
            )
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Changes are saved automatically",
            fontSize = 12.sp,
            color = MistralTextWhite.copy(alpha = 0.4f)
        )
    }
}

@Composable
fun GiantAccessibleButton(
    isTrackingActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isTrackingActive) Color(0xFFCC2200) else MistralOrange,
            contentColor = MistralTextWhite
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(Modifier.height(24.dp))

            Icon(
                imageVector = if (isTrackingActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isTrackingActive) "Stop tracking button" else "Start tracking button",
                modifier = Modifier.size(120.dp)
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = if (isTrackingActive) "STOP" else "START",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics {
                    contentDescription = if (isTrackingActive) "Stop tracking" else "Start tracking"
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MistralOrange,
            background = MistralDarkBackground,
            onPrimary = MistralTextWhite,
            onBackground = MistralTextWhite
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MistralDarkBackground
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                GiantAccessibleButton(
                    isTrackingActive = false,
                    onClick = {},
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                )
            }
        }
    }
}

private fun triggerHapticFeedback(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(100)
    }
}