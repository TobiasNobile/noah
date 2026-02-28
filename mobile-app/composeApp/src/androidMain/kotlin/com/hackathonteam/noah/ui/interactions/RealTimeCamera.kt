package com.hackathonteam.noah.ui.interactions

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CameraPreview"
private const val SEND_REQUEST_COOLDOWN_MS = 2000L

/**
 * Latest JPEG-compressed camera frame, updated every time [CameraPreview] captures one.
 * [StreamDispatcher] reads this to include the latest frame in each [StreamPayload].
 * `null` when the camera is inactive or no frame has been captured yet.
 */
private val _latestFrame = MutableStateFlow<ByteArray?>(null)
val latestCameraFrame: StateFlow<ByteArray?> = _latestFrame.asStateFlow()

/**
 * Compresses an [ImageProxy] (YUV_420_888) to a JPEG [ByteArray] at 60 % quality.
 *
 * Must be called while the [ImageProxy] is still open (before [ImageProxy.close]).
 * Called on the analysis executor thread — do NOT touch the UI here.
 */
private fun compressFrame(imageProxy: ImageProxy): ByteArray {
    val width  = imageProxy.width
    val height = imageProxy.height

    val yPlane = imageProxy.planes[0] //brightness
    val uPlane = imageProxy.planes[1] //color
    val vPlane = imageProxy.planes[2] //color

    //NV21: default encoding format for the Android format YCrCb
    //NV21 is exactly width*height (Y) + width*height/2 (interleaved VU)
    val nv21 = ByteArray(width * height * 3 / 2)

    // --- Copy Y plane (respecting row stride) ---
    val yBuffer   = yPlane.buffer
    val yRowStride = yPlane.rowStride
    var destIndex = 0
    for (row in 0 until height) {
        yBuffer.position(row * yRowStride)
        yBuffer.get(nv21, destIndex, width)
        destIndex += width
    }

    // --- Interleave V and U into NV21 chroma (respecting pixel stride = 2) ---
    val vBuffer     = vPlane.buffer
    val uBuffer     = uPlane.buffer
    val chromaRowStride  = vPlane.rowStride   // U and V share the same row stride
    val chromaPixelStride = vPlane.pixelStride // Always 2 for NV21-compatible formats

    for (row in 0 until height / 2) {
        val vRowStart = row * chromaRowStride
        val uRowStart = row * chromaRowStride
        for (col in 0 until width / 2) {
            val chromaCol = col * chromaPixelStride
            nv21[destIndex++] = vBuffer.get(vRowStart + chromaCol)
            nv21[destIndex++] = uBuffer.get(uRowStart + chromaCol)
        }
    }

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 60, out)
    return out.toByteArray()
}

/**
 * Displays a live camera preview using CameraX.
 *
 * @param modifier        Standard Compose modifier.
 * @param isActive        When `true` the camera is bound and streaming; when `false` it is
 *                        unbound (no battery/CPU used) but the [PreviewView] stays in the layout.
 * @param onFrameCaptured Called on every frame with a JPEG-compressed [ByteArray].
 *                        Defaults to a no-op so callers that only need the preview are unaffected.
 *                        Runs on a background thread — do not touch Compose state directly.
 *
 * Requires the CAMERA permission to be granted before calling this composable.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    onFrameCaptured: (ByteArray) -> Unit = {},
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val previewView = remember { PreviewView(context) }

    // Shut the executor down only when the composable permanently leaves the tree.
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    // Re-run whenever isActive changes so we bind / unbind cleanly.
    DisposableEffect(isActive) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            if (!isActive) {
                cameraProvider.unbindAll()
                Log.d(TAG, "Camera unbound (isActive=false)")
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        //Add a cooldown to avoid sending too many frames to the server.
                        if(System.currentTimeMillis() - imageProxy.imageInfo.timestamp / 1_000_000L < SEND_REQUEST_COOLDOWN_MS) {
                            imageProxy.close()
                            return@setAnalyzer
                        } else {
                        try {
                            val jpeg = compressFrame(imageProxy)
                            _latestFrame.value = jpeg
                            Log.d(TAG, "Frame captured: ${jpeg.size} bytes")
                            onFrameCaptured(jpeg)
                            } catch (e: Exception) {
                                Log.e(TAG, "Frame compression failed", e)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
                Log.d(TAG, "Camera bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            ProcessCameraProvider.getInstance(context).get()?.unbindAll()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}