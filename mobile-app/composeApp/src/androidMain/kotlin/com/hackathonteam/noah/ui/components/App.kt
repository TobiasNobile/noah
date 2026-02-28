package com.hackathonteam.noah.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.hackathonteam.noah.tracking.TrackingManager
import com.hackathonteam.noah.ui.interactions.Greeting
import org.jetbrains.compose.resources.painterResource

import noah.composeapp.generated.resources.Res
import noah.composeapp.generated.resources.compose_multiplatform

@Composable
@Preview
fun App() {

    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = {
                showContent = !showContent
                TrackingManager.setTracking(!TrackingManager.isTrackingActive)
            }) {
                Text(if (TrackingManager.isTrackingActive) "Stop tracking" else "Start tracking")
            }
        }
    }
}