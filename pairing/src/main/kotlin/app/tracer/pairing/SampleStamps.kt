package app.tracer.pairing

import app.tracer.core.model.AndroidNetworkSample
import app.tracer.core.model.AnnotationSample
import app.tracer.core.model.CellSample
import app.tracer.core.model.DeviceSample
import app.tracer.core.model.EventLabelSample
import app.tracer.core.model.EventSample
import app.tracer.core.model.GapSample
import app.tracer.core.model.CollectorLogSample
import app.tracer.core.model.CarrierSample
import app.tracer.core.model.WifiRoamSample
import app.tracer.core.model.WaypointSample
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
 * Returns a copy of this sample with [stamp] replaced; every other field is unchanged.
 * Exhaustive over the sealed [Sample] hierarchy, so adding a sample type fails compilation here.
 */
fun Sample.withStamp(stamp: Stamp): Sample = when (this) {
    is LocationSample -> copy(stamp = stamp)
    is MotionSample -> copy(stamp = stamp)
    is NetworkPathSample -> copy(stamp = stamp)
    is RadioSample -> copy(stamp = stamp)
    is WifiConnectionSample -> copy(stamp = stamp)
    is CellSample -> copy(stamp = stamp)
    is WifiScanSample -> copy(stamp = stamp)
    is GnssSatelliteSample -> copy(stamp = stamp)
    is ProbeSample -> copy(stamp = stamp)
    is DeviceSample -> copy(stamp = stamp)
    is EventSample -> copy(stamp = stamp)
    is AnnotationSample -> copy(stamp = stamp)
    is MetricKitPayloadSample -> copy(stamp = stamp)
    is AndroidNetworkSample -> copy(stamp = stamp)
    is WifiLinkSample -> copy(stamp = stamp)
    is GnssRawSample -> copy(stamp = stamp)
    is WifiRttSample -> copy(stamp = stamp)
    is EventLabelSample -> copy(stamp = stamp)
    is GapSample -> copy(stamp = stamp)
    is WaypointSample -> copy(stamp = stamp)
    is WifiRoamSample -> copy(stamp = stamp)
    is CarrierSample -> copy(stamp = stamp)
    is CollectorLogSample -> copy(stamp = stamp)
}
