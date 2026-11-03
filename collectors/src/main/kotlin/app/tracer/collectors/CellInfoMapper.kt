package app.tracer.collectors

import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import app.tracer.core.cell.RawCell

/**
 * Copies raw CellInfo getter values into [RawCell] — sentinels (CellInfo.UNAVAILABLE) included.
 * All interpretation lives in CellNormalizer (:core). Returns null for CDMA / unknown types.
 */
internal fun CellInfo.toRawCell(): RawCell? = when (this) {
    is CellInfoLte -> {
        val id = cellIdentity
        val ss = cellSignalStrength
        RawCell(
            type = "LTE",
            registered = isRegistered,
            timestampNanos = timestampMillis * 1_000_000L, // CellInfo.getTimestampMillis (API 30), elapsedRealtime base
            mcc = id.mccString,
            mnc = id.mncString,
            tac = id.tac,
            cid = id.ci.toLong(),
            pci = id.pci,
            arfcn = id.earfcn,
            bands = id.bands.toList(),
            bandwidthKhz = id.bandwidth,
            rsrp = ss.rsrp,
            rsrq = ss.rsrq,
            rssi = ss.rssi,
            rssnr = ss.rssnr,
            cqi = ss.cqi,
            timingAdvance = ss.timingAdvance,
        )
    }
    is CellInfoNr -> {
        val id = cellIdentity as? CellIdentityNr
        val ss = cellSignalStrength as? CellSignalStrengthNr
        RawCell(
            type = "NR",
            registered = isRegistered,
            timestampNanos = timestampMillis * 1_000_000L, // CellInfo.getTimestampMillis (API 30), elapsedRealtime base
            mcc = id?.mccString,
            mnc = id?.mncString,
            tac = id?.tac,
            cid = id?.nci,
            pci = id?.pci,
            arfcn = id?.nrarfcn,
            bands = id?.bands?.toList().orEmpty(),
            ssRsrp = ss?.ssRsrp,
            ssRsrq = ss?.ssRsrq,
            ssSinr = ss?.ssSinr,
            csiRsrp = ss?.csiRsrp,
            csiRsrq = ss?.csiRsrq,
            csiSinr = ss?.csiSinr,
            nrTimingAdvanceMicros = if (ss != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ss.timingAdvanceMicros else null,
        )
    }
    is CellInfoWcdma -> {
        val id = cellIdentity
        val ss = cellSignalStrength
        RawCell(
            type = "WCDMA",
            registered = isRegistered,
            timestampNanos = timestampMillis * 1_000_000L, // CellInfo.getTimestampMillis (API 30), elapsedRealtime base
            mcc = id.mccString,
            mnc = id.mncString,
            tac = id.lac,
            cid = id.cid.toLong(),
            pci = id.psc,
            arfcn = id.uarfcn,
            rscp = ss.dbm,
            ecNo = ss.ecNo,
        )
    }
    is CellInfoGsm -> {
        val id = cellIdentity
        val ss = cellSignalStrength
        RawCell(
            type = "GSM",
            registered = isRegistered,
            timestampNanos = timestampMillis * 1_000_000L, // CellInfo.getTimestampMillis (API 30), elapsedRealtime base
            mcc = id.mccString,
            mnc = id.mncString,
            tac = id.lac,
            cid = id.cid.toLong(),
            pci = id.bsic,
            arfcn = id.arfcn,
            rssi = ss.rssi,
            timingAdvance = ss.timingAdvance,
        )
    }
    is CellInfoTdscdma -> {
        val id = cellIdentity
        val ss = cellSignalStrength
        RawCell(
            type = "TDSCDMA",
            registered = isRegistered,
            timestampNanos = timestampMillis * 1_000_000L, // CellInfo.getTimestampMillis (API 30), elapsedRealtime base
            mcc = id.mccString,
            mnc = id.mncString,
            tac = id.lac,
            cid = id.cid.toLong(),
            pci = id.cpid,
            arfcn = id.uarfcn,
            rscp = ss.rscp,
        )
    }
    else -> null
}
