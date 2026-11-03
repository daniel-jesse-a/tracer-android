package app.tracer.pairing.ui

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Back-camera QR scanner (CameraX preview + ML Kit). [onResult] is called on the main thread once
 * per distinct decoded value. The caller must already hold the CAMERA permission.
 */
@Composable
fun QrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnResult by rememberUpdatedState(onResult)
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        val disposed = AtomicBoolean(false)
        val preview = Preview.Builder().build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // RGBA frames make ImageProxy.toBitmap() a plain copy and avoid the experimental getImage().
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(
            analysisExecutor,
            QrAnalyzer(scanner, mainExecutor) { value -> if (!disposed.get()) latestOnResult(value) },
        )

        var provider: ProcessCameraProvider? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (disposed.get()) return@addListener
            val p = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "camera provider unavailable", e)
                return@addListener
            }
            try {
                preview.setSurfaceProvider(previewView.surfaceProvider)
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                provider = p
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
            }
        }, mainExecutor)

        onDispose {
            disposed.set(true)
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private const val TAG = "QrScanner"

/** Feeds frames to ML Kit; closes each frame when its scan completes (keeps KEEP_ONLY_LATEST flowing). */
private class QrAnalyzer(
    private val scanner: BarcodeScanner,
    private val callbackExecutor: Executor,
    private val onValue: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val last = AtomicReference<String?>(null)

    override fun analyze(image: ImageProxy) {
        val input = try {
            InputImage.fromBitmap(image.toBitmap(), image.imageInfo.rotationDegrees)
        } catch (e: Exception) {
            Log.w(TAG, "frame conversion failed", e)
            image.close()
            return
        }
        scanner.process(input)
            .addOnSuccessListener(callbackExecutor) { codes ->
                val value = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.isNotEmpty() } }
                if (value != null && last.getAndSet(value) != value) onValue(value)
            }
            .addOnCompleteListener(callbackExecutor) { image.close() }
    }
}
