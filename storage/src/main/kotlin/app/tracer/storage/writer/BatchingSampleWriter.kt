package app.tracer.storage.writer

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import app.tracer.core.model.Sample
import app.tracer.core.model.Table
import app.tracer.core.runtime.SampleSink
import app.tracer.core.schema.Schema
import app.tracer.storage.db.SqlBinding
import app.tracer.storage.db.StorageChanges
import app.tracer.storage.db.TableCounts
import app.tracer.storage.db.TracerDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single sample writer. Samples go through a bounded queue (10 000 entries, so [emit] suspends
 * under backpressure and never drops) to ONE consumer coroutine on a dedicated thread, which writes
 * a batch in one transaction when [BATCH_ROWS] rows are buffered or [FLUSH_INTERVAL_MS] has elapsed
 * since the first buffered row, or when [flush] is called.
 *
 * A failed batch is logged and retried once (with freshly compiled statements); if the retry fails
 * too the batch is logged as lost and the writer keeps going — it never throws into the caller.
 */
@Singleton
class BatchingSampleWriter @Inject constructor(
    private val database: TracerDatabase,
    private val changes: StorageChanges,
) : SampleSink {

    private sealed interface Command {
        class Write(val sample: Sample) : Command
        class Flush(val done: CompletableDeferred<Unit>) : Command
    }

    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "tracer-writer").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Writes and flush requests share one FIFO, so a flush covers everything emitted before it. */
    private val queue = Channel<Command>(capacity = QUEUE_CAPACITY)

    private val counts = ConcurrentHashMap<String, MutableStateFlow<Map<Table, Long>>>()

    // Touched only on the writer thread.
    private var statementsDb: SupportSQLiteDatabase? = null
    private val statements = HashMap<Table, SupportSQLiteStatement>()

    @Volatile private var lostRows = 0L

    /** Rows that could not be written even after a retry (diagnostics). */
    val droppedRows: Long get() = lostRows

    private val consumer: Job = scope.launch { consume() }

    override suspend fun emit(sample: Sample) {
        try {
            queue.send(Command.Write(sample))
        } catch (e: ClosedSendChannelException) {
            Log.w(TAG, "emit after close; ${sample.table.sqlName} row discarded")
        }
    }

    override suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        try {
            queue.send(Command.Flush(done))
        } catch (e: ClosedSendChannelException) {
            return
        }
        done.await()
    }

    override fun counts(runId: String): StateFlow<Map<Table, Long>> {
        counts[runId]?.let { return it.asStateFlow() }
        val fresh = MutableStateFlow<Map<Table, Long>>(emptyMap())
        val existing = counts.putIfAbsent(runId, fresh)
        if (existing != null) return existing.asStateFlow()
        // Seed from rows already on disk (e.g. a run resumed after process death). Runs on the
        // writer thread, so it is ordered with respect to flush increments and cannot double-count.
        scope.launch {
            try {
                fresh.value = TableCounts.query(database.openHelper.writableDatabase, runId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "count seed failed for $runId", e)
            }
        }
        return fresh.asStateFlow()
    }

    /** Stops accepting samples, writes everything still queued and releases the writer thread. */
    suspend fun close() {
        queue.close()
        consumer.join()
        dispatcher.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun consume() {
        val buffer = ArrayList<Sample>(BATCH_ROWS)
        var firstBufferedAtMs = 0L
        try {
            while (true) {
                // null = the 1 s window of the oldest buffered row has elapsed.
                val result: ChannelResult<Command>? = if (buffer.isEmpty()) {
                    queue.receiveCatching()
                } else {
                    val waitMs = FLUSH_INTERVAL_MS - (nowMs() - firstBufferedAtMs)
                    if (waitMs <= 0) {
                        null
                    } else {
                        select<ChannelResult<Command>?> {
                            queue.onReceiveCatching { it }
                            onTimeout(waitMs) { null }
                        }
                    }
                }
                if (result == null) {
                    writeBuffered(buffer)
                    continue
                }
                if (result.isClosed) break
                when (val cmd = result.getOrNull()) {
                    is Command.Write -> {
                        if (buffer.isEmpty()) firstBufferedAtMs = nowMs()
                        buffer += cmd.sample
                        if (buffer.size >= BATCH_ROWS) writeBuffered(buffer)
                    }
                    is Command.Flush -> {
                        writeBuffered(buffer)
                        cmd.done.complete(Unit)
                    }
                    null -> Unit
                }
            }
        } finally {
            // Drain: whatever is still buffered or queued goes to disk; pending flushes complete.
            while (true) {
                val cmd = queue.tryReceive().getOrNull() ?: break
                when (cmd) {
                    is Command.Write -> buffer += cmd.sample
                    is Command.Flush -> cmd.done.complete(Unit)
                }
            }
            writeBuffered(buffer)
            closeStatements()
        }
    }

    private fun writeBuffered(buffer: MutableList<Sample>) {
        if (buffer.isEmpty()) return
        val ok = tryWrite(buffer, attempt = 1) || tryWrite(buffer, attempt = 2)
        if (ok) {
            onWritten(buffer)
        } else {
            lostRows += buffer.size
            Log.e(TAG, "dropping ${buffer.size} rows after retry (total lost: $lostRows)")
        }
        buffer.clear()
    }

    private fun tryWrite(batch: List<Sample>, attempt: Int): Boolean = try {
        writeBatch(batch)
        true
    } catch (e: Exception) {
        Log.e(TAG, "batch write failed (attempt $attempt, ${batch.size} rows)", e)
        closeStatements()
        false
    }

    private fun writeBatch(batch: List<Sample>) {
        val db = database.openHelper.writableDatabase
        if (db !== statementsDb) {
            closeStatements()
            statementsDb = db
        }
        db.beginTransactionNonExclusive()
        try {
            for (s in batch) {
                val st = statements.getOrPut(s.table) { db.compileStatement(Schema.of(s.table).insertSql()) }
                SqlBinding.insert(st, s)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun onWritten(batch: List<Sample>) {
        var events = false
        batch.groupBy { it.runId }.forEach { (runId, rows) ->
            val flow = counts[runId] ?: return@forEach
            val delta = rows.groupingBy { it.table }.eachCount()
            val next = HashMap(flow.value)
            delta.forEach { (t, n) -> next[t] = (next[t] ?: 0L) + n }
            flow.value = next
        }
        for (s in batch) if (s.table == Table.EVENT) { events = true; break }
        if (events) changes.bump()
    }

    private fun closeStatements() {
        statements.values.forEach { runCatching { it.close() } }
        statements.clear()
        statementsDb = null
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    companion object {
        private const val TAG = "TracerWriter"
        const val QUEUE_CAPACITY = 10_000
        const val BATCH_ROWS = 200
        const val FLUSH_INTERVAL_MS = 1_000L
    }
}
