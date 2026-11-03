package app.tracer.probes

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import app.tracer.core.runtime.TowerLocation
import app.tracer.core.runtime.TowerLookup
import app.tracer.probes.di.ProbeHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Optional
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the OpenCelliD API key (null = no key). Bind one in :app; optional. */
fun interface TowerKeyProvider {
    fun key(): String?
}

/**
 * [TowerLookup] backed by OpenCelliD: (1) an offline `filesDir/opencellid.sqlite` with the
 * OpenCelliD CSV schema in table `cells`, else (2) the /cell/get web API when a key is
 * available. Hits and definite misses are cached (LRU 500 + `filesDir/tower_cache.json`).
 * Returns null on any failure.
 */
@Singleton
class OpenCellIdTowerLookup @Inject constructor(
    @ApplicationContext private val context: Context,
    @ProbeHttpClient private val client: OkHttpClient,
    private val keyProvider: Optional<TowerKeyProvider>,
) : TowerLookup {

    private val cache = TowerCache(File(context.filesDir, "tower_cache.json"))
    private val offlineDb = File(context.filesDir, "opencellid.sqlite")

    override suspend fun locate(rat: String, mcc: String, mnc: String, tac: Int, cellId: Long): TowerLocation? =
        withContext(Dispatchers.IO) {
            val radio = openCellIdRadio(rat) ?: return@withContext null
            val key = TowerCache.key(radio, mcc, mnc, tac, cellId)
            cache.get(key)?.let { return@withContext it.location }
            val result: Result<TowerLocation?> = if (offlineDb.exists()) {
                queryOffline(radio, mcc, mnc, tac, cellId)
            } else {
                val apiKey = if (keyProvider.isPresent) keyProvider.get().key() else null
                if (apiKey.isNullOrBlank()) return@withContext null
                queryOnline(apiKey, radio, mcc, mnc, tac, cellId)
            }
            // Only definite answers are cached; transient failures are retried next time.
            result.getOrNull().also { if (result.isSuccess) cache.put(key, it) }
        }

    private fun queryOffline(radio: String, mcc: String, mnc: String, tac: Int, cellId: Long): Result<TowerLocation?> = try {
        val mccInt = mcc.toIntOrNull()
        val mncInt = mnc.toIntOrNull()
        if (mccInt == null || mncInt == null) {
            Result.success(null)
        } else {
            SQLiteDatabase.openDatabase(offlineDb.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT lat, lon, range FROM cells WHERE radio = ? AND mcc = ? AND net = ? AND area = ? AND cell = ? LIMIT 1",
                    arrayOf(radio, mccInt.toString(), mncInt.toString(), tac.toString(), cellId.toString()),
                ).use { c ->
                    if (c.moveToFirst()) {
                        Result.success(
                            TowerLocation(
                                lat = c.getDouble(0),
                                lon = c.getDouble(1),
                                rangeM = if (c.isNull(2)) null else c.getDouble(2),
                                source = "opencellid_offline",
                            ),
                        )
                    } else {
                        Result.success(null)
                    }
                }
            }
        }
    } catch (e: SQLiteException) {
        Result.failure(e)
    }

    private fun queryOnline(apiKey: String, radio: String, mcc: String, mnc: String, tac: Int, cellId: Long): Result<TowerLocation?> = try {
        val url = "https://opencellid.org/cell/get".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .addQueryParameter("mcc", mcc)
            .addQueryParameter("mnc", mnc)
            .addQueryParameter("lac", tac.toString())
            .addQueryParameter("cellid", cellId.toString())
            .addQueryParameter("radio", radio)
            .addQueryParameter("format", "json")
            .build()
        val http = client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            val text = r.peekBody(256 * 1024).string()
            when {
                r.code in 200..299 -> parseOpenCellIdResponse(text)
                r.code == 404 -> Result.success(null)
                else -> Result.failure(java.io.IOException("HTTP ${r.code}"))
            }
        }
    } catch (e: java.io.IOException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    }
}
