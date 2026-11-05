# Tracer report v1: ReportData contract

The PDF is produced by rendering `tracer-report-template.html` with a `ReportData` JSON object and printing it to A4 landscape. The JSON replaces the `__REPORT_DATA__` token inside `<script id="report-data">` (escape `</` as `<\/`). The template escapes all strings except `findings[]`, which may contain `<b>`.

All times are epoch ms (UTC). Fractions run from 0 to 1. Any section whose data is missing or empty renders "Not captured in this run." rather than failing.

| Field | Type | Rule / source |
|---|---|---|
| `meta.run_id`, `run_id_short`, `platform`, `device`, `os`, `app_version` | string | `run.json` |
| `meta.start_ms`, `end_ms` | int | `run.started_at`, `run.ended_at` |
| `meta.tz_offset_min` | int | the device time-zone offset (Dubai = 240) |
| `meta.mobile_operator` | string | mode of `asn_org` for cellular `public_ip` probes, mapped Emirates Internet→e&, EITC→du |
| `meta.title`, `scope`, `source_note` | string | generated (see the reference script) |
| `kpis[]` | `{label,value,sub,tone}` | up to 6 tiles. `tone` ∈ `bad`, `good`, `warn`, `""` |
| `segments[]` | `{kind:'cellular'\|'wifi', start, end, label}` | runs of `network_path.primary_interface`. Runs under 30 s are dropped; the first segment starts at T0 and the last ends at T1 |
| `paths[]` | see below | up to 3: mobile active, mobile in parallel (while Wi-Fi is active), Wi-Fi active. Skip a path with fewer than 20 checks |
| `paths[].stats` | `{n,sr,dns,con,tls,ttfb,tot,p95}` | latency probes of that interface inside the window. Timings use successes only; `p95` is on `total_ms` |
| `paths[].thr` | `{n,median,min,max,under1}` | throughput probes (`mbps`) |
| `paths[].resolver` / `gateway` | `{label,median_ms,sr,note}` | `dns_server` / `gateway` probes. If a cellular gateway has `sr=0`, note "carrier gateway blocks ICMP" |
| `paths[].health` | `{0..3,-1: share}` or null | share of health bins (active path only) |
| `paths[].verdict` | `PASS`\|`WATCH`\|`FAIL` | **FAIL** if `sr<0.90` or no-internet share >20 %. **WATCH** if `sr<0.99` or `p95>800 ms` or throughput median <20 Mbps. Otherwise **PASS** |
| `health_bins` | `{t0,bin_ms:15000,codes[]}` | evaluated at each bin centre on the **active** path using latency probes within ±10 s. **3** = inside a Wi-Fi stall or success <50 %. **0** = success ≥95 % and median connect <150 ms. **1** = success ≥80 % and connect <400 ms. **2** = otherwise. **-1** = no probes |
| `rat[]` | `{t,dur,rat}` | `radio.csv`; `dur` runs to the next sample. RAT keys: `NR_SA`, `NR_NSA`, `LTE`, `UMTS`, `HSPA`, `GSM`, `NONE` |
| `rat_share` | `{label:{minutes,changes,share{rat:frac}}}` | per active-path window |
| `connect_series` | `{cellular:[[t,ms]], wifi:[[t,ms]]}` | median `connect_ms` of successes per 30 s bin |
| `failures` | `{cellular:[[t,n]], wifi:[[t,n]]}` | failed latency probes per minute |
| `stalls[]` | `{id,t,dur,phase,lat,lon,acc,conf}` | each Wi-Fi `drop` event runs until the next `reconnect` with the same path. `conf` = **precise** (fix ±≤60 m and ≤120 s old), **coarse** (≤120 s old), otherwise **stale** |
| `mobile_drops[]` | int | `drop` events on the cellular path |
| `throughput[]` | `{t,path,mbps,ok}` | every throughput probe |
| `fixes_per_min[]` | `[t,count,median_acc_m]` | location per minute |
| `track.fixes[]` | `[lat,lon,health,t,acc_m]` | every location fix; `health` = health code at that instant |
| `map.basemap` | `{image(dataURL),z,x0,y0,scale,crop[x,y,w,h],attribution}` or null | optional Web-Mercator raster: tiles z/x0/y0 drawn at `scale`, then cropped. With null, the map is drawn on a plain background |
| `map.rail_lines` | `[[[lat,lon],…],…]` | optional track overlay (ERGT asset) |
| `map.stations[]` | `{name,lat,lon,label,anchor,dy}` | optional |
| `map.labels[]` | `{lat,lon,text,anchor,dy}` | start and last fix are auto-generated |
| `map.gaps[]` | `{a,b,kind:'nofix'\|'unrecorded',label}` | **nofix** = consecutive fixes more than 5 min apart. **unrecorded** = last fix → destination, used when the run continued more than 5 min after the last fix and a destination is known |
| `wifi_layers[]` | `{name,status,detail}` | Radio · LAN gateway · DNS · WAN · Application. `status` ∈ Good/Fair/Poor/Failing/No data, by the thresholds in the reference script |
| `endpoints[]`, `endpoint_paths` | `{host,a:stats,b:stats}` | per endpoint: mobile-in-parallel (a) vs Wi-Fi (b) during the Wi-Fi window. With no Wi-Fi, mobile only |
| `phases[]` | `{key,label,start,end,stalls,stall_s}` | the Wi-Fi window split by location state per minute (precise / none / approx), smoothed with a ±3 min majority filter; runs under 5 min are folded into the previous phase |
| `quality[]` | `{collector,captured,note}` | `run.json.completeness` |
| `findings[]` | html strings | rule-generated: fault domain (LAN OK and WAN failing), parallel-path comparison, stalls by phase, location coverage <80 % |
| `recommendations` | `{heading:[string]}` | static, plus rule-based items |
