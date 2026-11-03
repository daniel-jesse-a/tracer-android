package app.tracer.export

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * FileProvider for finished bundles (cache/exports/). A dedicated subclass so its manifest entry
 * cannot clash with another `androidx.core.content.FileProvider` declared by the app.
 */
class ExportFileProvider : FileProvider()

/** Share-sheet intents for exported bundles. */
object ShareIntents {
    const val MIME_ZIP = "application/zip"

    /**
     * Chooser intent that shares [file] (which must live under cacheDir/exports/) through the
     * FileProvider at [authority] — normally [app.tracer.core.runtime.BundleService.fileProviderAuthority].
     */
    fun shareBundle(context: Context, file: File, authority: String): Intent {
        val uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_ZIP
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, file.name).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
