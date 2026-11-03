package app.tracer.collectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappingsTest {

    @Test fun overrideTypeWinsOverNetworkType() {
        assertEquals("NR_NSA", ratOf(OverrideType.NR_NSA, NetType.LTE))
        assertEquals("NR_ADVANCED", ratOf(OverrideType.NR_ADVANCED, NetType.LTE))
        assertEquals("LTE_CA", ratOf(OverrideType.LTE_CA, NetType.LTE))
        assertEquals("LTE_ADV_PRO", ratOf(OverrideType.LTE_ADVANCED_PRO, NetType.LTE))
        assertEquals("NR_NSA_MMWAVE", ratOf(4, NetType.LTE))
    }

    @Test fun networkTypeFallback() {
        assertEquals("NR_SA", ratOf(OverrideType.NONE, NetType.NR))
        assertEquals("LTE", ratOf(0, 13))
        assertEquals("HSPA", ratOf(0, NetType.HSPAP))
        assertEquals("HSPA", ratOf(0, NetType.HSDPA))
        assertEquals("UMTS", ratOf(0, NetType.UMTS))
        assertEquals("GSM", ratOf(0, NetType.EDGE))
        assertEquals("GSM", ratOf(0, NetType.GPRS))
        assertEquals("IWLAN", ratOf(0, NetType.IWLAN))
        assertEquals("NONE", ratOf(0, NetType.UNKNOWN))
        assertEquals("UNKNOWN", ratOf(0, 4 /* CDMA */))
    }

    @Test fun nrStatePicksMostAdvancedEntry() {
        val text = "{mVoiceRegState=0(IN_SERVICE), NetworkRegistrationInfo{ domain=CS nrState=NONE} " +
            "NetworkRegistrationInfo{ domain=PS transportType=WWAN nrState=CONNECTED}}"
        assertEquals("connected", parseNrState(text))
        assertEquals("not_restricted", parseNrState("nrState=NOT_RESTRICTED"))
        assertNull(parseNrState("no nr here"))
        assertNull(parseNrState(null))
    }

    @Test fun securityTypes() {
        assertEquals("OPEN", securityName(0))
        assertEquals("WEP", securityName(1))
        assertEquals("WPA2", securityName(2))
        assertEquals("ENTERPRISE", securityName(3))
        assertEquals("WPA3", securityName(4))
        assertEquals("ENTERPRISE", securityName(5))
        assertEquals("OWE", securityName(6))
        assertEquals("ENTERPRISE", securityName(9))
        assertEquals("UNKNOWN", securityName(-1))
        assertEquals("UNKNOWN", securityName(7))
    }

    @Test fun ssidUnquoting() {
        assertEquals("Home", unquoteSsid("\"Home\""))
        assertEquals("a\"b", unquoteSsid("\"a\"b\""))
        assertEquals("deadbeef", unquoteSsid("deadbeef"))
        assertNull(unquoteSsid("<unknown ssid>"))
        assertNull(unquoteSsid("\"\""))
        assertNull(unquoteSsid(null))
    }

    @Test fun bssidPlaceholders() {
        assertNull(normalizeBssid("02:00:00:00:00:00"))
        assertNull(normalizeBssid(null))
        assertEquals("aa:bb:cc:dd:ee:ff", normalizeBssid("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun linkQualityBuckets() {
        assertEquals("good", linkQualityOf(20_000))
        assertEquals("moderate", linkQualityOf(19_999))
        assertEquals("moderate", linkQualityOf(2_000))
        assertEquals("minimal", linkQualityOf(1))
        assertEquals("unknown", linkQualityOf(0))
        assertEquals("unknown", linkQualityOf(null))
    }

    @Test fun primaryInterfacePrefersUnderlyingTransport() {
        assertEquals("wifi", primaryInterfaceOf(listOf("vpn", "wifi")))
        assertEquals("cellular", primaryInterfaceOf(listOf("cellular", "vpn")))
        assertEquals("vpn", primaryInterfaceOf(listOf("vpn")))
        assertEquals("none", primaryInterfaceOf(emptyList()))
        assertEquals("other", primaryInterfaceOf(listOf("usb")))
    }

    @Test fun constellationsThermalActivities() {
        assertEquals("GALILEO", constellationName(6))
        assertEquals("BEIDOU", constellationName(5))
        assertEquals("UNKNOWN", constellationName(42))
        assertEquals("severe", thermalName(3))
        assertEquals("in_vehicle", activityName(0))
        assertEquals("running", activityName(8))
    }

    @Test fun mloFrequency() {
        assertEquals(2412, mloFrequencyMhz(1, 1))
        assertEquals(5180, mloFrequencyMhz(2, 36))
        assertEquals(5955, mloFrequencyMhz(8, 1))
        assertNull(mloFrequencyMhz(99, 1))
    }
}
