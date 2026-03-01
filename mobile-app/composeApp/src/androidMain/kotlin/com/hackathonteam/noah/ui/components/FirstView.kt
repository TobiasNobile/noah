package com.hackathonteam.noah.ui.components
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
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
import androidx.compose.material.icons.filled.Stop
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
import java.util.*

// Couleurs Mistral AI
val MistralOrange = Color(0xFFFA500F)
val MistralDarkBackground = Color(0xFF1A1A2E)
val MistralTextWhite = Color(0xFFFFFFFF)

@Composable
fun App() {
    val context: Context = LocalContext.current
    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }

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
            Box(modifier = Modifier.fillMaxSize()) {
                GiantAccessibleButton(
                    isTrackingActive = TrackingManager.isTrackingActive,
                    onClick = {
                        if (TrackingManager.isTrackingActive) {
                            TrackingManager.stopListening()
                            textToSpeech?.speak("Suivi arrêté", TextToSpeech.QUEUE_FLUSH, null, null)
                        } else {
                            TrackingManager.startListening(context)
                            textToSpeech?.speak("Suivi démarré", TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                        triggerHapticFeedback(context)
                    },
                    modifier = Modifier
                        .fillMaxSize()
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


            }
        }
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

            // Icône Play / Stop
            Icon(
                imageVector = if (isTrackingActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (isTrackingActive) "Bouton arrêter le suivi" else "Bouton démarrer le suivi",
                modifier = Modifier.size(120.dp)
            )

            Spacer(Modifier.height(24.dp))

            // Texte
            Text(
                text = if (isTrackingActive) "ARRÊTER" else "DÉMARRER",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics {
                    contentDescription = if (isTrackingActive) "Arrêter le suivi" else "Démarrer le suivi"
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