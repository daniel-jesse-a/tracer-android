package app.tracer.pairing

import app.tracer.core.model.LocationSample
import app.tracer.core.model.RunRecord
import app.tracer.core.model.Sample
import app.tracer.core.model.Stamp
import app.tracer.core.model.Table
import app.tracer.core.pairing.ClockAlignment
import app.tracer.core.pairing.PairingPayload
import app.tracer.core.runtime.LiveLink
import app.tracer.core.runtime.PairingService
import app.tracer.core.runtime.RunRepository
import app.tracer.pairing.live.NoopLiveLink
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown (inside a failed [Result]) when scanned QR text cannot be accepted. */
class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * QR pairing of two simultaneous runs (Android↔Android or Android↔iOS) and the offline overlay of
 * the linked run onto this run's clocks via GNSS time ([ClockAlignment]).
 */
@Singleton
class PairingServiceImpl @Inject constructor(
    private val repository: RunRepository,
    private val noopLive: NoopLiveLink,
) : PairingService {

    override val live: LiveLink get() = noopLive

    override fun payloadFor(run: RunRecord, gpsOffsetMs: Long?): String = PairingPayload(
        runId = run.runId,
        platform = run.devicePlatform,
        model = run.deviceModel,
        osVersion = run.osVersion,
        appVersion = run.appVersion,
        runName = run.name,
        startedAt = run.startedAt,
        gpsOffsetMs = gpsOffsetMs,
    ).toUri()

    override suspend fun acceptScanned(currentRunId: String, qrText: String): Result<String> {
        val payload = PairingPayload.parse(qrText.trim())
            ?: return Result.failure(PairingException("Not a Tracer pairing code"))
        val otherRunId = payload.runId.trim()
        if (otherRunId.isEmpty()) return Result.failure(PairingException("Pairing code has no run id"))
        if (otherRunId == currentRunId) return Result.failure(PairingException("That is this run's own code"))
        return try {
            val run = repository.getRun(currentRunId)
                ?: return Result.failure(PairingException("Run $currentRunId not found"))
            if (run.linkedRunId != otherRunId) repository.updateRun(run.copy(linkedRunId = otherRunId))
            Result.success(otherRunId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PairingException("Could not store pairing", e))
        }
    }

    override suspend fun alignedOverlay(runId: String, linkedRunId: String, table: Table): List<Sample> {
        require(table != Table.RUNS) { "runs is not a sample table" }
        val runA = repository.getRun(runId)
        val runB = repository.getRun(linkedRunId)
        val locA = repository.samples(runId, Table.LOCATION).filterIsInstance<LocationSample>()
        val locB = repository.samples(linkedRunId, Table.LOCATION).filterIsInstance<LocationSample>()
        val alignment = ClockAlignment.align(locA, locB, runA?.gpsOffsetMsStart, runB?.gpsOffsetMsStart)
        val samplesB = if (table == Table.LOCATION) locB else repository.samples(linkedRunId, table)
        return overlay(samplesB, alignment, monoAnchor(locA, runA))
    }

    companion object {
        /** A (utc, mono) pair on run A's timeline: its first fix, else the run row's own stamp. */
        internal fun monoAnchor(locA: List<LocationSample>, runA: RunRecord?): Stamp? =
            locA.firstOrNull()?.stamp ?: runA?.takeIf { it.tMonoNs > 0 }?.let { Stamp(it.tUtcMs, it.tMonoNs) }

        /**
         * Shifts each B sample onto A's wall clock (`bToA`) and maps its mono time onto A's monotonic
         * timeline through [anchor]. Without an anchor B's mono stamps are kept. GNSS time is a
         * shared absolute clock and is left as is.
         */
        internal fun overlay(samplesB: List<Sample>, alignment: ClockAlignment.Alignment, anchor: Stamp?): List<Sample> =
            samplesB.map { s ->
                val utcA = alignment.bToA(s.stamp.tUtcMs)
                val monoA = if (anchor != null) anchor.tMonoNs + (utcA - anchor.tUtcMs) * 1_000_000L else s.stamp.tMonoNs
                s.withStamp(s.stamp.copy(tUtcMs = utcA, tMonoNs = monoA))
            }
    }
}
