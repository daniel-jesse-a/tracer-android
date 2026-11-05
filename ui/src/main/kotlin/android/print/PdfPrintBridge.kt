package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives a [PrintDocumentAdapter] straight into a file, with no system print dialog. Lives in android.print
 * because LayoutResultCallback / WriteResultCallback only have package-private constructors.
 */
object PdfPrintBridge {
    suspend fun write(adapter: PrintDocumentAdapter, attributes: PrintAttributes, out: File) {
        val cancel = CancellationSignal()
        try {
            adapter.onStart()
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation { cancel.cancel() }
                adapter.onLayout(null, attributes, cancel, object : PrintDocumentAdapter.LayoutResultCallback() {
                    override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) { if (cont.isActive) cont.resume(Unit) }
                    override fun onLayoutFailed(error: CharSequence?) { if (cont.isActive) cont.resumeWithException(IllegalStateException("PDF layout failed: $error")) }
                    override fun onLayoutCancelled() { if (cont.isActive) cont.cancel() }
                }, null)
            }
            out.parentFile?.mkdirs()
            ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
                suspendCancellableCoroutine<Unit> { cont ->
                    cont.invokeOnCancellation { cancel.cancel() }
                    adapter.onWrite(arrayOf(PageRange.ALL_PAGES), pfd, cancel, object : PrintDocumentAdapter.WriteResultCallback() {
                        override fun onWriteFinished(pages: Array<out PageRange>?) { if (cont.isActive) cont.resume(Unit) }
                        override fun onWriteFailed(error: CharSequence?) { if (cont.isActive) cont.resumeWithException(IllegalStateException("PDF write failed: $error")) }
                        override fun onWriteCancelled() { if (cont.isActive) cont.cancel() }
                    })
                }
            }
        } finally {
            adapter.onFinish()
        }
    }
}
