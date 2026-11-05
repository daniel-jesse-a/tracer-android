package app.tracer.ui.report

import android.annotation.SuppressLint
import android.content.Context
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.print.PdfFileWriter
import android.print.PrintAttributes
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTML → PDF with an off-screen WebView: load, wait for onPageFinished plus one rendered frame
 * (postVisualStateCallback), then print with createPrintDocumentAdapter into a file. No dialog.
 * A4 landscape (842 × 595 pt); the template's @page rule sets the 8 mm margins.
 */
object ReportPdfRenderer {
    /** CSS px width of A4 landscape at 96 dpi; the template lays out for print at this width. */
    private const val WIDTH_PX = 1123
    private const val HEIGHT_PX = 794

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(context: Context, html: String, out: File) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val web = WebView(context.applicationContext)
            val cancel = CancellationSignal()
            val main = Handler(Looper.getMainLooper())
            var finished = false
            fun done(err: Throwable?) {
                if (finished) return; finished = true
                main.post { runCatching { web.destroy() } }
                if (!cont.isActive) return
                if (err == null) cont.resume(Unit) else cont.resumeWithException(err)
            }
            cont.invokeOnCancellation { main.post { cancel.cancel(); if (!finished) { finished = true; runCatching { web.destroy() } } } }

            web.settings.javaScriptEnabled = true
            web.settings.allowFileAccess = false
            web.settings.blockNetworkLoads = true      // the template is self-contained; basemap is a data: URL
            web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            web.measure(View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY))
            web.layout(0, 0, WIDTH_PX, HEIGHT_PX)
            web.webViewClient = object : WebViewClient() {
                private var printed = false
                override fun onPageFinished(view: WebView, url: String?) {
                    if (printed) return
                    printed = true
                    // one frame after the page (and its scripts) finished, so the SVGs are laid out
                    view.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            main.post {
                                try {
                                    val attrs = PrintAttributes.Builder()
                                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                                        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                                        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                        .build()
                                    val adapter = view.createPrintDocumentAdapter("Tracer report")
                                    PdfFileWriter.write(adapter, attrs, out, cancel) { ok, error ->
                                        done(if (ok && out.length() > 0) null else IllegalStateException("PDF failed: ${error ?: "empty file"}"))
                                    }
                                } catch (e: Exception) { done(e) }
                            }
                        }
                    })
                }
            }
            web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
            // safety net: never hang the export
            main.postDelayed({ done(IllegalStateException("PDF timed out")) }, 60_000)
        }
    }
}
