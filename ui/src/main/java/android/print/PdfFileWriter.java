package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * Drives a PrintDocumentAdapter (WebView.createPrintDocumentAdapter) straight into a PDF file,
 * without the system print dialog. Lives in android.print because the layout/write result
 * callbacks only have package-private constructors.
 */
public final class PdfFileWriter {
    public interface Callback { void onDone(boolean ok, String error); }

    private PdfFileWriter() {}

    public static void write(final PrintDocumentAdapter adapter, final PrintAttributes attrs, final File out,
                             final CancellationSignal cancel, final Callback cb) {
        adapter.onStart();
        adapter.onLayout(null, attrs, cancel, new PrintDocumentAdapter.LayoutResultCallback() {
            @Override public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                final ParcelFileDescriptor fd;
                try {
                    fd = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
                } catch (Exception e) { adapter.onFinish(); cb.onDone(false, String.valueOf(e)); return; }
                adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, fd, cancel, new PrintDocumentAdapter.WriteResultCallback() {
                    @Override public void onWriteFinished(PageRange[] pages) { close(fd); adapter.onFinish(); cb.onDone(true, null); }
                    @Override public void onWriteFailed(CharSequence error) { close(fd); adapter.onFinish(); cb.onDone(false, String.valueOf(error)); }
                    @Override public void onWriteCancelled() { close(fd); adapter.onFinish(); cb.onDone(false, "cancelled"); }
                });
            }
            @Override public void onLayoutFailed(CharSequence error) { adapter.onFinish(); cb.onDone(false, String.valueOf(error)); }
            @Override public void onLayoutCancelled() { adapter.onFinish(); cb.onDone(false, "cancelled"); }
        }, null);
    }

    private static void close(ParcelFileDescriptor fd) { try { fd.close(); } catch (Exception ignored) {} }
}
