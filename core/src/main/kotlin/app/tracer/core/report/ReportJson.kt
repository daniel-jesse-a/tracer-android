package app.tracer.core.report

import kotlinx.serialization.json.*

/** Hand-written JSON codec for [TestReport] (cache file + "summary" share item). Bump [VERSION] on shape changes. */
object ReportJson {
    const val VERSION = 1

    fun encode(r: TestReport): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("run_id", r.runId); put("title", r.title); put("duration_s", r.durationS)
        put("online_pct", r.onlinePct); put("mobile_covered_s", r.mobileCoveredS); put("offline_s", r.offlineS)
        put("switches", r.switches); put("path_switches", r.pathSwitches); put("ap_changes", r.apChanges)
        put("wifi_speed_median", r.wifiSpeedMedian); put("wifi_speed_max", r.wifiSpeedMax)
        put("mobile_speed_median", r.mobileSpeedMedian); put("mobile_speed_max", r.mobileSpeedMax)
        put("response_ms", r.responseMs); put("response_wifi_ms", r.responseWifiMs); put("response_mobile_ms", r.responseMobileMs)
        put("response_mobile_min_ms", r.responseMobileMinMs); put("response_mobile_max_ms", r.responseMobileMaxMs)
        put("wifi_rssi_median", r.wifiRssiMedian); put("wifi_rssi_worst", r.wifiRssiWorst)
        put("rsrp_median", r.rsrpMedian); put("sinr_median", r.sinrMedian)
        put("mobile_tech", r.mobileTech); put("mobile_band", r.mobileBand); put("nr_seen", r.nrSeen)
        put("position_acc_m", r.positionAccM); put("position_source", r.positionSource); put("positions", r.positions)
        put("gps_fixes", r.gpsFixes); put("satellites_max", r.satellitesMax)
        put("checks_passed_pct", r.checksPassedPct); put("checks", r.checks); put("false_drops", r.falseDrops)
        put("on_schedule_pct", r.onSchedulePct)
        put("movement", buildJsonObject { r.movement.forEach { (k, v) -> put(k, v) } })
        put("segments", buildJsonArray { r.segments.forEach { add(buildJsonArray { add(it.fromS); add(it.toS); add(it.carrier.name) }) } })
        put("moments", buildJsonArray { r.moments.forEach { m -> add(buildJsonObject {
            put("t_s", m.tS); put("kind", m.kind); put("title", m.title); put("severity", m.severity); put("mono_ns", m.eventMonoNs)
            put("lasted_s", m.lastedS); put("stayed_online", m.stayedOnline); put("before", m.before?.name); put("after", m.after?.name)
            put("cause_key", m.causeKey); put("cause_title", m.causeTitle); put("cause_detail", m.causeDetail)
            put("event_id", m.eventId); put("feedback", m.feedback)
        }) } })
        put("wifi_rssi", pts(r.wifiRssi)); put("new_ap_at_s", buildJsonArray { r.newApAtS.forEach { add(it) } })
        put("response_wifi", pts(r.responseWifi)); put("response_mobile", pts(r.responseMobile))
        put("speed_tests", buildJsonArray { r.speedTests.forEach { add(buildJsonArray { add(it.tS); add(it.carrier.name); add(it.mbps) }) } })
        put("speed_test_seconds", r.speedTestSeconds); put("method_fingerprint", r.methodFingerprint)
        put("device_model", r.deviceModel); put("app_version", r.appVersion); put("started_at", r.startedAt)
        // derived, for readers of the shared summary
        put("verdicts", buildJsonObject {
            put("overall", r.overallVerdict.label); put("online", r.onlineVerdict.label); put("wifi_signal", r.wifiVerdict.label)
            put("mobile_signal", r.mobileVerdict.label); put("response", r.responseVerdict.label); put("wifi_speed", r.wifiSpeedVerdict.label)
            put("mobile_speed", r.mobileSpeedVerdict.label); put("stability", r.stabilityVerdict.label); put("position", r.positionVerdict.label)
            put("test_quality", r.qualityVerdict.label)
        })
        put("headline", r.headline); put("subline", r.subline)
    }

    fun decode(o: JsonObject): TestReport? {
        if (o.int("v") != VERSION) return null
        return TestReport(
            runId = o.s("run_id") ?: return null, title = o.s("title") ?: "", durationS = o.d("duration_s") ?: 0.0,
            onlinePct = o.d("online_pct"), mobileCoveredS = o.d("mobile_covered_s") ?: 0.0, offlineS = o.d("offline_s") ?: 0.0,
            switches = o.int("switches") ?: 0, pathSwitches = o.int("path_switches") ?: 0, apChanges = o.int("ap_changes") ?: 0,
            wifiSpeedMedian = o.d("wifi_speed_median"), wifiSpeedMax = o.d("wifi_speed_max"),
            mobileSpeedMedian = o.d("mobile_speed_median"), mobileSpeedMax = o.d("mobile_speed_max"),
            responseMs = o.d("response_ms"), responseWifiMs = o.d("response_wifi_ms"), responseMobileMs = o.d("response_mobile_ms"),
            responseMobileMinMs = o.d("response_mobile_min_ms"), responseMobileMaxMs = o.d("response_mobile_max_ms"),
            wifiRssiMedian = o.d("wifi_rssi_median"), wifiRssiWorst = o.d("wifi_rssi_worst"),
            rsrpMedian = o.d("rsrp_median"), sinrMedian = o.d("sinr_median"),
            mobileTech = o.s("mobile_tech"), mobileBand = o.s("mobile_band"), nrSeen = o.b("nr_seen") ?: false,
            positionAccM = o.d("position_acc_m"), positionSource = o.s("position_source"), positions = o.int("positions") ?: 0,
            gpsFixes = o.int("gps_fixes") ?: 0, satellitesMax = o.int("satellites_max"),
            checksPassedPct = o.d("checks_passed_pct"), checks = o.int("checks") ?: 0, falseDrops = o.int("false_drops") ?: 0,
            onSchedulePct = o.d("on_schedule_pct"),
            movement = (o["movement"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).double } ?: emptyMap(),
            segments = (o["segments"] as? JsonArray)?.map { val a = it.jsonArray
                Segment(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double, Carrier.valueOf(a[2].jsonPrimitive.content)) } ?: emptyList(),
            moments = (o["moments"] as? JsonArray)?.map { val m = it.jsonObject
                Moment(m.d("t_s") ?: 0.0, m.s("kind") ?: "", m.s("title") ?: "", m.s("severity") ?: "info", m.l("mono_ns") ?: 0,
                    m.d("lasted_s"), m.b("stayed_online") ?: true, m.s("before")?.let { Carrier.valueOf(it) }, m.s("after")?.let { Carrier.valueOf(it) },
                    m.s("cause_key"), m.s("cause_title"), m.s("cause_detail"), m.l("event_id"), m.s("feedback"))
            } ?: emptyList(),
            wifiRssi = pts(o["wifi_rssi"]), newApAtS = (o["new_ap_at_s"] as? JsonArray)?.map { it.jsonPrimitive.double } ?: emptyList(),
            responseWifi = pts(o["response_wifi"]), responseMobile = pts(o["response_mobile"]),
            speedTests = (o["speed_tests"] as? JsonArray)?.map { val a = it.jsonArray
                SpeedTest(a[0].jsonPrimitive.double, Carrier.valueOf(a[1].jsonPrimitive.content), a[2].jsonPrimitive.double) } ?: emptyList(),
            speedTestSeconds = o.int("speed_test_seconds"), methodFingerprint = o.s("method_fingerprint"),
            deviceModel = o.s("device_model") ?: "", appVersion = o.s("app_version") ?: "", startedAt = o.l("started_at") ?: 0,
        )
    }

    fun encodeString(r: TestReport): String = encode(r).toString()
    fun decodeString(s: String): TestReport? = runCatching { decode(Json.parseToJsonElement(s).jsonObject) }.getOrNull()

    private fun pts(l: List<Pt>) = buildJsonArray { l.forEach { add(buildJsonArray { add(it.tS); add(it.v) }) } }
    private fun pts(e: JsonElement?) = (e as? JsonArray)?.map { val a = it.jsonArray; Pt(a[0].jsonPrimitive.double, a[1].jsonPrimitive.double) } ?: emptyList()
    private fun JsonObject.p(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.s(k: String) = p(k)?.contentOrNull
    private fun JsonObject.d(k: String) = p(k)?.doubleOrNull
    private fun JsonObject.int(k: String) = p(k)?.intOrNull
    private fun JsonObject.l(k: String) = p(k)?.longOrNull
    private fun JsonObject.b(k: String) = p(k)?.booleanOrNull
}
