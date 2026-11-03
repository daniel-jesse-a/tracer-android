package app.tracer.run

import app.tracer.core.model.AndroidNetworkSample
import app.tracer.core.model.AnnotationSample
import app.tracer.core.model.CellSample
import app.tracer.core.model.DeviceSample
import app.tracer.core.model.EventLabelSample
import app.tracer.core.model.EventSample
import app.tracer.core.model.GapSample
import app.tracer.core.model.GnssRawSample
import app.tracer.core.model.GnssSatelliteSample
import app.tracer.core.model.LocationSample
import app.tracer.core.model.MetricKitPayloadSample
import app.tracer.core.model.MotionSample
import app.tracer.core.model.NetworkPathSample
import app.tracer.core.model.ProbeSample
import app.tracer.core.model.RadioSample
import app.tracer.core.model.Sample
import app.tracer.core.model.Stamp
import app.tracer.core.model.WifiConnectionSample
import app.tracer.core.model.WifiLinkSample
import app.tracer.core.model.WifiRttSample
import app.tracer.core.model.WifiScanSample

/**
 * Returns a copy of this sample belonging to [runId] and stamped with [stamp]. Payload fields that
 * are themselves wall-clock times (LocationSample.gpsTimeMs, GapSample start/end) are shifted by the
 * same wall-clock delta so the row stays internally consistent.
 *
 * The `when` is exhaustive over the sealed [Sample] hierarchy (no `else`), so adding a new sample
 * type fails compilation here until it is handled.
 */
fun Sample.restamp(runId: String, stamp: Stamp): Sample {
    val dUtc = stamp.tUtcMs - this.stamp.tUtcMs
    return when (this) {
        is LocationSample -> copy(runId = runId, stamp = stamp, gpsTimeMs = gpsTimeMs?.plus(dUtc))
        is MotionSample -> copy(runId = runId, stamp = stamp)
        is NetworkPathSample -> copy(runId = runId, stamp = stamp)
        is RadioSample -> copy(runId = runId, stamp = stamp)
        is WifiConnectionSample -> copy(runId = runId, stamp = stamp)
        is CellSample -> copy(runId = runId, stamp = stamp)
        is WifiScanSample -> copy(runId = runId, stamp = stamp)
        is GnssSatelliteSample -> copy(runId = runId, stamp = stamp)
        is ProbeSample -> copy(runId = runId, stamp = stamp)
        is DeviceSample -> copy(runId = runId, stamp = stamp)
        is EventSample -> copy(runId = runId, stamp = stamp)
        is AnnotationSample -> copy(runId = runId, stamp = stamp)
        is MetricKitPayloadSample -> copy(runId = runId, stamp = stamp)
        is AndroidNetworkSample -> copy(runId = runId, stamp = stamp)
        is WifiLinkSample -> copy(runId = runId, stamp = stamp)
        is GnssRawSample -> copy(runId = runId, stamp = stamp)
        is WifiRttSample -> copy(runId = runId, stamp = stamp)
        is EventLabelSample -> copy(runId = runId, stamp = stamp)
        is GapSample -> copy(runId = runId, stamp = stamp, startUtcMs = startUtcMs + dUtc, endUtcMs = endUtcMs + dUtc)
    }
}
