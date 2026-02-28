package com.hackathonteam.noah.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hackathonteam.noah.services.sensor.SensorReading

/**
 * A Compose Canvas time-series line chart that plots the Euclidean magnitude
 * of accelerometer readings stored in the 5-second sliding window.
 *
 * - X axis : relative time inside the window (oldest → newest, left → right).
 * - Y axis : magnitude in m/s² (gravity at rest ≈ 9.81 m/s²).
 * - The chart recomposes automatically whenever [readings] changes because the
 *   caller collects AccelerometerSensor.window.readings as Compose state.
 *
 * @param readings   Ordered list of [SensorReading] from the sliding window (oldest first).
 * @param windowMs   Width of the time window in ms — used to scale the X axis.
 * @param modifier   Standard Compose modifier.
 * @param lineColor  Color of the signal line.
 * @param height     Fixed chart height.
 */
@Composable
fun MagnitudeChart(
    readings: List<SensorReading>,
    modifier: Modifier = Modifier,
    windowMs: Long = 5_000L,
    lineColor: Color = Color(0xFF2196F3),   // Material Blue
    gridColor: Color = Color(0x33FFFFFF),
    height: Dp = 180.dp,
) {
    val yMin = 0f
    val yMax = 30f                          // reasonable upper bound for running (m/s²)
    val yGridLines = listOf(0f, 9.81f, 20f, 30f)
    val labelColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier) {

        Text(
            text = "Accelerometer magnitude (m/s²) — last ${windowMs / 1_000}s",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                .padding(start = 36.dp, end = 8.dp, top = 8.dp, bottom = 24.dp)
        ) {

            // grid + signal
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                fun yToPixel(v: Float) = h - ((v - yMin) / (yMax - yMin)) * h

                // Horizontal grid lines
                yGridLines.forEach { yVal ->
                    val py = yToPixel(yVal)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, py),
                        end   = Offset(w, py),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                if (readings.size >= 2) {
                    val tFirst = readings.first().timestampMs
                    val tLast  = readings.last().timestampMs
                    val tRange = (tLast - tFirst).coerceAtLeast(1L)

                    val path = Path()
                    readings.forEachIndexed { idx, r ->
                        val px = ((r.timestampMs - tFirst).toFloat() / tRange) * w
                        val py = yToPixel(r.magnitude.coerceIn(yMin, yMax))
                        if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(
                        path        = path,
                        color       = lineColor,
                        style       = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Latest value dot
                    val last = readings.last()
                    val lx = w
                    val ly = yToPixel(last.magnitude.coerceIn(yMin, yMax))
                    drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(lx, ly))
                }
            }

            // Labels Y-axis
            yGridLines.forEach { yVal ->
                val frac = (yVal - yMin) / (yMax - yMin)           // 0 (bottom) → 1 (top)
                val topFrac = 1f - frac
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(32.dp)
                        .align(Alignment.CenterStart)
                        .offset(x = (-36).dp)
                ) {
                    Text(
                        text = yVal.toInt().toString(),
                        fontSize = 9.sp,
                        color = labelColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopStart)
                            .offset(y = (topFrac * height.value - 8).dp)
                    )
                }
            }
        }

        //X labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 36.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "-${windowMs / 1_000}s", fontSize = 9.sp, color = labelColor)
            Text(text = "now",                   fontSize = 9.sp, color = labelColor)
        }
    }
}



