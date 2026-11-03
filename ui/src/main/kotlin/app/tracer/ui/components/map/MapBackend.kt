package app.tracer.ui.components.map

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Which map SDK renders tracks. :ui cannot see :app's BuildConfig.MAP_BACKEND, so the choice is
 * read from the manifest at runtime: a non-blank Google Maps API key → Google Maps, else MapLibre
 * with OpenFreeMap tiles (no key needed).
 */
enum class MapBackend { GOOGLE, MAPLIBRE;

    companion object {
        const val MAPLIBRE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val GOOGLE_KEY_META = "com.google.android.geo.API_KEY"

        private const val BACKEND_META = "app.tracer.MAP_BACKEND"

        /** Google only when a key is present AND the build did not force maplibre (gradle `tracer.mapBackend`). */
        fun resolve(context: Context): MapBackend {
            val meta = runCatching {
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
            }.getOrNull()
            val key = meta?.getString(GOOGLE_KEY_META)
            val forced = meta?.getString(BACKEND_META)
            return if (!key.isNullOrBlank() && forced != "maplibre") GOOGLE else MAPLIBRE
        }
    }
}

@Composable
fun rememberMapBackend(): MapBackend {
    val context = LocalContext.current
    return remember(context) { MapBackend.resolve(context) }
}
