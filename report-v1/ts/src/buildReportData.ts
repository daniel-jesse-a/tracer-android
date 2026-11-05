/*
 * Tracer report v1: run tables → ReportData JSON (REPORT_DATA_CONTRACT.md).
 * TypeScript port for the Expo (iOS) app. Line-for-line with build_report_data.py and the Kotlin
 * port (core/src/main/kotlin/app/tracer/core/report/v1/ReportData.kt); keep all three in step.
 * Rows use the bundle / SQLite column names. Missing data never throws.
 */

export type Num = number | null | undefined;

export interface RunMeta {
  run_id: string; name?: string | null; mode?: string | null; started_at: number; ended_at?: number | null;
  site?: string | null; test_number?: number | null; time_zone?: string | null;
  platform: string; model?: string | null; os_version?: string | null; app_version?: string | null;
  /** device UTC offset at start in minutes (Dubai = 240); computed from time_zone when absent */
  tz_offset_min?: number | null;
}
export interface ProbeRow { t_utc_ms: number; probe_type: string; endpoint: string; success: number | boolean; dns_ms?: Num; connect_ms?: Num; tls_ms?: Num;
  ttfb_ms?: Num; total_ms?: Num; mbps?: Num; interface?: string | null; is_cellular?: number | boolean | null; public_ip?: string | null; asn_org?: string | null }
export interface EventRow { id: number; t_utc_ms: number; event_type: string; before_json?: string | null; after_json?: string | null }
export interface RadioRow { t_utc_ms: number; service_id?: string | null; rat: string; nr_state?: string | null }
export interface PathRow { t_utc_ms: number; primary_interface?: string | null }
export interface LocationRow { t_utc_ms: number; lat: number; lon: number; h_acc_m?: Num }
export interface DeviceRow { t_utc_ms: number; battery?: Num; thermal_state?: string | null }
export interface WifiRow { t_utc_ms: number; ssid?: string | null; bssid?: string | null; security?: string | null; rssi_dbm?: Num; link_speed_mbps?: Num; frequency_mhz?: Num }
export interface CollectorLogRow { t_utc_ms: number; message?: string | null }
export interface Station { name: string; lat: number; lon: number; label?: boolean; anchor?: string; dy?: number; [k: string]: unknown }
export interface Rail { lines?: number[][][]; stations?: Station[]; destination?: { name: string; lat: number; lon: number } | null; attribution?: string | null }

export interface ReportInput {
  run: RunMeta; completeness?: any; probes?: ProbeRow[]; events?: EventRow[]; radio?: RadioRow[]; networkPath?: PathRow[];
  locations?: LocationRow[]; device?: DeviceRow[]; wifi?: WifiRow[]; collectorLog?: CollectorLogRow[];
  rail?: Rail | null; basemap?: any | null; destination?: { name: string; lat: number; lon: number } | null; sysdiagnose?: string | null;
}

const BIN = 15000;
const isNum = (x: Num): x is number => typeof x === 'number' && !Number.isNaN(x) && Number.isFinite(x);
const ok = (p: ProbeRow) => p.success === 1 || p.success === true;
const pathOfProbe = (p: ProbeRow) => (p.is_cellular === 1 || p.is_cellular === true || p.interface === 'cellular') ? 'cellular' : (p.interface || 'wifi');
function median(xs: Num[]): number | null {
  const v = xs.filter(isNum).sort((a, b) => a - b); if (!v.length) return null;
  const n = v.length; return n % 2 ? v[(n - 1) / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
}
function quantile(xs: Num[], q: number): number | null {
  const v = xs.filter(isNum).sort((a, b) => a - b); if (!v.length) return null;
  const pos = (v.length - 1) * q, lo = Math.floor(pos), hi = Math.min(lo + 1, v.length - 1);
  return v[lo] + (v[hi] - v[lo]) * (pos - lo);
}
const f = (x: number, d: number) => x.toFixed(d);
const thousands = (n: number) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
const pad = (n: number) => String(n).padStart(2, '0');
function mode(xs: string[]): string { const c = new Map<string, number>(); xs.forEach(x => c.set(x, (c.get(x) || 0) + 1));
  const top = Math.max(...c.values()); return [...c.entries()].filter(([, n]) => n === top).map(([k]) => k).sort()[0]; }
function majority<T>(xs: T[]): T { const c = new Map<T, number>(); xs.forEach(x => c.set(x, (c.get(x) || 0) + 1));
  let best = xs[0], bn = -1; for (const [k, n] of c) if (n > bn) { best = k; bn = n; } return best; }
function tzOffsetMin(run: RunMeta): number {
  if (isNum(run.tz_offset_min)) return run.tz_offset_min;
  try {
    const s = new Intl.DateTimeFormat('en-US', { timeZone: run.time_zone || 'UTC', timeZoneName: 'shortOffset' })
      .formatToParts(new Date(run.started_at)).find(p => p.type === 'timeZoneName')?.value || 'GMT';
    const m = /GMT([+-])(\d{1,2})(?::(\d{2}))?/.exec(s); if (!m) return 0;
    return (m[1] === '-' ? -1 : 1) * (Number(m[2]) * 60 + Number(m[3] || 0));
  } catch { return 0; }
}
interface PStats { n: number; sr: number | null; dns: number | null; con: number | null; tls: number | null; ttfb: number | null; tot: number | null; p95: number | null }
function pstats(df: ProbeRow[]): PStats {
  const s = df.filter(ok);
  return { n: df.length, sr: df.length ? df.filter(ok).length / df.length : null, dns: median(s.map(p => p.dns_ms)), con: median(s.map(p => p.connect_ms)),
    tls: median(s.map(p => p.tls_ms)), ttfb: median(s.map(p => p.ttfb_ms)), tot: median(s.map(p => p.total_ms)), p95: quantile(s.map(p => p.total_ms), 0.95) };
}
function ratKey(rat: string, nr?: string | null): string {
  if (rat === 'NR_SA' || nr === 'sa') return 'NR_SA';
  if (rat.startsWith('NR') || nr === 'nsa' || nr === 'nsa_advanced') return 'NR_NSA';
  if (rat.startsWith('LTE')) return 'LTE';
  if (rat === 'UMTS' || rat === 'WCDMA' || rat === 'TDSCDMA') return 'UMTS';
  if (rat.startsWith('HSPA')) return 'HSPA';
  if (rat === 'GSM' || rat === 'EDGE' || rat === 'GPRS') return 'GSM';
  if (rat === 'NONE' || rat === 'UNKNOWN') return 'NONE';
  return rat;
}

export function buildReportData(inp: ReportInput): any {
  const run = inp.run; const ios = run.platform === 'ios';
  const probes = inp.probes || [], events = inp.events || [], wifiRows = inp.wifi || [], dev = [...(inp.device || [])].sort((a, b) => a.t_utc_ms - b.t_utc_ms);
  const T0 = run.started_at;
  const T1 = run.ended_at ?? Math.max(T0, ...probes.map(p => p.t_utc_ms), ...(inp.locations || []).map(l => l.t_utc_ms));
  const TZ = tzOffsetMin(run);
  const hm = (t: number) => { const s = Math.floor((((t + TZ * 60000) % 86400000) + 86400000) % 86400000 / 60000); return pad(Math.floor(s / 60)) + ':' + pad(s % 60); };

  // ---------- segments
  type Seg = { kind: string; start: number; end: number; label: string };
  let segsRaw: Seg[] = [];
  for (const r of [...(inp.networkPath || [])].sort((a, b) => a.t_utc_ms - b.t_utc_ms)) {
    const k = r.primary_interface === 'wifi' ? 'wifi' : 'cellular';
    if (!segsRaw.length || segsRaw[segsRaw.length - 1].kind !== k) segsRaw.push({ kind: k, start: Math.max(r.t_utc_ms, T0), end: T1, label: '' });
    if (segsRaw.length > 1) segsRaw[segsRaw.length - 2].end = segsRaw[segsRaw.length - 1].start;
  }
  let segs = segsRaw.filter(s => s.end - s.start > 30000);
  if (!segs.length) segs = [{ kind: 'cellular', start: T0, end: T1, label: '' }];
  segs[0].start = T0; segs[segs.length - 1].end = T1;
  const active = (t: number) => { for (const s of segs) if ((s.start <= t && t < s.end) || (t === T1 && s.end === T1)) return s.kind; return segs[segs.length - 1].kind; };

  const ssids = wifiRows.map(w => w.ssid).filter((s): s is string => !!s && s.trim() !== '');
  const ssid = ssids.length ? mode(ssids) : 'Wi-Fi';
  const asn = probes.filter(p => p.probe_type === 'public_ip' && pathOfProbe(p) === 'cellular').map(p => p.asn_org).filter((s): s is string => !!s);
  const operator = asn.length ? (({ 'Emirates Internet': 'e&', 'Emirates Integrated Telecommunications Company PJSC': 'du' } as Record<string, string>)[mode(asn)] ?? mode(asn)) : 'mobile';
  segs.forEach(s => { s.label = s.kind === 'cellular' ? `${operator} mobile data` : ssid; });

  const lat = probes.filter(p => p.probe_type === 'latency').map(p => ({ p, act: active(p.t_utc_ms) }));
  const latSorted = lat.map(x => x.p).sort((a, b) => a.t_utc_ms - b.t_utc_ms); const tv = latSorted.map(p => p.t_utc_ms);
  const lowerBound = (x: number) => { let lo = 0, hi = tv.length; while (lo < hi) { const m = (lo + hi) >>> 1; if (tv[m] < x) lo = m + 1; else hi = m; } return lo; };

  // ---------- stalls
  const pathOf = (js?: string | null) => { try { const o = JSON.parse(js || ''); return o.path || o.interface || null; } catch { return null; } };
  const ev = [...events].sort((a, b) => a.t_utc_ms - b.t_utc_ms); const recon = ev.filter(e => e.event_type === 'reconnect');
  const stalls: any[] = []; const mobDrops: number[] = [];
  for (const e of ev.filter(x => x.event_type === 'drop')) {
    const pth = pathOf(e.after_json);
    const nxt = recon.find(r => r.t_utc_ms > e.t_utc_ms && (r.before_json || '').includes(`"path":"${pth}"`));
    const end = nxt ? nxt.t_utc_ms : T1;
    if (pth === 'wifi') stalls.push({ id: e.id, t: e.t_utc_ms, dur: (end - e.t_utc_ms) / 1000 }); else mobDrops.push(e.t_utc_ms);
  }

  // ---------- health
  const health = (t: number, kind: string): number => {
    if (kind === 'wifi' && stalls.some(s => s.t <= t && s.t + s.dur * 1000 >= t)) return 3;
    const w = latSorted.slice(lowerBound(t - 10000), lowerBound(t + 10000)).filter(p => pathOfProbe(p) === kind);
    if (!w.length) return -1;
    const sr = w.filter(ok).length / w.length; const med = median(w.filter(ok).map(p => p.connect_ms));
    if (sr < .5) return 3;
    if (sr >= .95 && med != null && med < 150) return 0;
    if (sr >= .8 && med != null && med < 400) return 1;
    return 2;
  };
  const codes: number[] = []; for (let t = T0; t < T1; t += BIN) { const c = t + BIN / 2; codes.push(health(c, active(c))); }
  const share = (lo: number, hi: number) => { const c = new Map<number, number>(); codes.forEach((h, i) => { const tt = T0 + i * BIN; if (lo <= tt && tt < hi) c.set(h, (c.get(h) || 0) + 1); });
    const n = Math.max([...c.values()].reduce((a, b) => a + b, 0), 1); const o: Record<string, number> = {}; [0, 1, 2, 3, -1].forEach(k => { o[String(k)] = (c.get(k) || 0) / n; }); return o; };
  const win = (kind: string): [number, number] | null => { const ss = segs.filter(s => s.kind === kind); return ss.length ? [ss[0].start, ss[ss.length - 1].end] : null; };

  // ---------- paths
  const inWin = (type: string, kind: string, act: string) => probes.filter(p => p.probe_type === type && pathOfProbe(p) === kind && active(p.t_utc_ms) === act);
  const thrStats = (df: ProbeRow[]) => { if (!df.length) return null; const v = df.filter(ok).map(p => p.mbps).filter(isNum);
    return { n: df.length, median: median(v), min: v.length ? Math.min(...v) : null, max: v.length ? Math.max(...v) : null, under1: df.filter(p => (isNum(p.mbps) ? p.mbps : 0) < 1).length }; };
  const paths: any[] = [];
  for (const [key, kind, act, label, short] of [['mob_active', 'cellular', 'cellular', `${operator} mobile · active path`, `${operator} (active)`],
    ['mob_parallel', 'cellular', 'wifi', `${operator} mobile · in parallel`, `${operator} (parallel)`], ['wifi_active', 'wifi', 'wifi', ssid, 'Wi-Fi']]) {
    const d = lat.filter(x => pathOfProbe(x.p) === kind && x.act === act).map(x => x.p); const w = win(act);
    if (d.length < 20 || !w) continue;
    const st = pstats(d); const th = thrStats(inWin('throughput', kind, act)); const dn = inWin('dns_server', kind, act); const g = inWin('gateway', kind, act);
    const h = kind === act ? share(w[0], w[1]) : null;
    const eps = [...new Set(dn.map(p => p.endpoint))];
    const resolver = dn.length ? { label: eps.slice(0, 1).join(', ') + (eps.length > 1 ? ` +${eps.length - 1}` : ''), median_ms: median(dn.map(p => p.total_ms)), sr: dn.filter(ok).length / dn.length } : null;
    const gsr = g.length ? g.filter(ok).length / g.length : 0;
    const gateway = g.length ? { label: g[0].endpoint, sr: gsr, median_ms: median(g.map(p => p.total_ms)), note: kind === 'cellular' && gsr === 0 ? 'carrier gateway blocks ICMP' : '' } : null;
    const sr = st.sr ?? 0, p95 = st.p95 ?? 0, tm = th?.median ?? 0, none = h ? h['3'] : 0;
    const verdict = sr < .9 || none > .2 ? 'FAIL' : (sr < .99 || p95 > 800 || (th && tm < 20)) ? 'WATCH' : 'PASS';
    const text = `${f(sr * 100, 1)}% of HTTPS checks succeeded, ${st.con != null ? f(st.con, 0) : '–'} ms median connect, p95 ${f(p95, 0)} ms` +
      (th ? `, ${f(tm, 1)} Mbps median throughput` : '') + (h && none > 0 ? `, no internet ${f(none * 100, 0)}% of the time` : '') + '.';
    paths.push({ key, kind, label, short, window: w, stats: st, thr: th, resolver, gateway, health: h, verdict, verdict_text: text });
  }

  // ---------- series
  const seriesConnect = (kind: string) => { const g = new Map<number, number[]>();
    lat.map(x => x.p).filter(p => pathOfProbe(p) === kind && ok(p) && isNum(p.connect_ms)).forEach(p => { const b = Math.floor((p.t_utc_ms - T0) / 30000); (g.get(b) || g.set(b, []).get(b)!).push(p.connect_ms as number); });
    return [...g.keys()].sort((a, b) => a - b).map(b => [T0 + b * 30000 + 15000, Math.round(median(g.get(b)!)! * 10) / 10]); };
  const seriesFail = (kind: string) => { const g = new Map<number, number>();
    lat.map(x => x.p).filter(p => pathOfProbe(p) === kind && !ok(p)).forEach(p => { const b = Math.floor((p.t_utc_ms - T0) / 60000); g.set(b, (g.get(b) || 0) + 1); });
    return [...g.keys()].sort((a, b) => a - b).map(b => [T0 + b * 60000, g.get(b)!]); };
  const radioAll = inp.radio || [];
  const subCount = new Map<string, number>(); radioAll.forEach(r => subCount.set(r.service_id || '', (subCount.get(r.service_id || '') || 0) + 1));
  const mainSub = [...subCount.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
  const rad = radioAll.filter(r => (r.service_id || '') === mainSub).sort((a, b) => a.t_utc_ms - b.t_utc_ms).map(r => ({ t: r.t_utc_ms, rat: ratKey(r.rat, r.nr_state) }));
  const radDur = (i: number) => (i + 1 < rad.length ? rad[i + 1].t : T1) - rad[i].t;
  const ratShare: Record<string, any> = {};
  for (const kind of ['cellular', 'wifi']) {
    const ww = win(kind); if (!ww) continue;
    const idx = rad.map((_, i) => i).filter(i => rad[i].t >= ww[0] && rad[i].t < ww[1]); if (!idx.length) continue;
    const total = idx.reduce((a, i) => a + radDur(i), 0); const by: Record<string, number> = {};
    idx.forEach(i => { by[rad[i].rat] = (by[rad[i].rat] || 0) + radDur(i); });
    const sh: Record<string, number> = {}; Object.keys(by).sort().forEach(k => { sh[k] = total > 0 ? by[k] / total : 0; });
    ratShare[`While on ${kind === 'cellular' ? 'mobile data' : ssid}`] = { minutes: (ww[1] - ww[0]) / 60000, changes: idx.filter((i, j) => j === 0 || rad[i].rat !== rad[idx[j - 1]].rat).length, share: sh };
  }

  // ---------- location
  const L = [...(inp.locations || [])].sort((a, b) => a.t_utc_ms - b.t_utc_ms);
  const fpmMap = new Map<number, LocationRow[]>(); L.forEach(l => { const b = Math.floor((l.t_utc_ms - T0) / 60000); (fpmMap.get(b) || fpmMap.set(b, []).get(b)!).push(l); });
  const fpm = [...fpmMap.keys()].sort((a, b) => a - b).map(b => [T0 + b * 60000, fpmMap.get(b)!.length, median(fpmMap.get(b)!.map(l => l.h_acc_m))] as [number, number, number | null]);
  const locAt = (t: number) => { if (!L.length) return null; let lo = 0, hi = L.length; while (lo < hi) { const m = (lo + hi) >>> 1; if (L[m].t_utc_ms <= t) lo = m + 1; else hi = m; } return L[Math.max(lo - 1, 0)]; };
  for (const s of stalls) {
    const fx = locAt(s.t); s.lat = null; s.lon = null; s.acc = null; s.conf = 'stale'; if (!fx) continue;
    const age = (s.t - fx.t_utc_ms) / 1000; const acc = isNum(fx.h_acc_m) ? fx.h_acc_m : null;
    s.lat = fx.lat; s.lon = fx.lon; s.acc = acc;
    s.conf = acc != null && acc <= 60 && age <= 120 ? 'precise' : acc != null && age <= 120 ? 'coarse' : 'stale';
  }

  // ---------- phases
  let phases: any[] = [];
  const ww = win('wifi');
  if (ww && stalls.length) {
    const state = (t: number) => { const m = fpm.find(x => x[0] <= t && t < x[0] + 60000); if (!m) return 'none'; return m[2] != null && m[2] <= 60 ? 'precise' : 'approx'; };
    const names: Record<string, string> = { precise: 'located (GPS)', none: 'no location', approx: 'approximate position' };
    const mins: number[] = []; for (let t = ww[0]; t < ww[1]; t += 60000) mins.push(t);
    const raw = mins.map(state); const sm = raw.map((_, i) => majority(raw.slice(Math.max(0, i - 3), Math.min(raw.length, i + 4))));
    const ph: any[] = [];
    mins.forEach((t, i) => { const st = sm[i]; if (ph.length && ph[ph.length - 1].state === st) ph[ph.length - 1].end = Math.min(t + 60000, ww[1]); else ph.push({ state: st, start: t, end: Math.min(t + 60000, ww[1]) }); });
    const merged: any[] = [];
    for (const p of ph) { if (merged.length && (p.end - p.start < 300000 || merged[merged.length - 1].state === p.state)) merged[merged.length - 1].end = p.end; else merged.push(p); }
    if (merged.length) {
      merged[merged.length - 1].end = ww[1];
      merged.forEach((p, i) => { p.key = String.fromCharCode(65 + i); p.label = `${p.key} · ${names[p.state]}`;
        const inn = stalls.filter(s => p.start <= s.t && s.t < p.end); p.stalls = inn.length; p.stall_s = inn.reduce((a, s) => a + s.dur, 0); inn.forEach(s => { s.phase = p.key; }); });
      phases = merged.filter(p => p.end > p.start);
    }
  }

  // ---------- Wi-Fi layers
  const wp = paths.find(p => p.key === 'wifi_active'); const mp = paths.find(p => p.key === 'mob_parallel'); const ma = paths.find(p => p.key === 'mob_active');
  const band = (fr: number | null) => fr == null ? null : fr < 3000 ? '2.4 GHz' : fr < 5925 ? '5 GHz' : '6 GHz';
  const chan = (fr: number | null) => fr == null ? null : fr === 2484 ? 14 : fr >= 2412 && fr <= 2472 ? (fr - 2407) / 5 : fr >= 5160 && fr <= 5885 ? (fr - 5000) / 5 : fr >= 5955 && fr <= 7115 ? (fr - 5950) / 5 : null;
  const layers: any[] = [];
  if (wp) {
    const bssids = new Set(wifiRows.map(w => w.bssid).filter(Boolean)).size;
    const secs = wifiRows.map(w => w.security).filter((s): s is string => !!s); const sec = secs.length ? mode(secs) : 'unknown';
    const rssi = wifiRows.map(w => w.rssi_dbm).filter(isNum);
    if (ios || !rssi.length) layers.push({ name: 'Radio · phone ↔ AP', status: 'No data', detail: `SSID "${ssid}", ${bssids} BSSID(s), security ${sec}. ` +
      (ios ? 'RSSI not exposed by the OS API; use the Android companion or sysdiagnose.' : 'No Wi-Fi radio readings captured.') });
    else {
      const med = median(rssi)!; const worst = Math.min(...rssi); const link = median(wifiRows.map(w => w.link_speed_mbps));
      const frs = wifiRows.map(w => w.frequency_mhz).filter(isNum); const fr = frs.length ? Number(mode(frs.map(String))) : null;
      const status = med >= -67 ? 'Good' : med >= -75 ? 'Fair' : med >= -82 ? 'Poor' : 'Failing';
      layers.push({ name: 'Radio · phone ↔ AP', status, detail: `SSID "${ssid}", ${bssids} BSSID(s), security ${sec}. RSSI median ${f(med, 0).replace('-', '−')} dBm (worst ${f(worst, 0).replace('-', '−')} dBm)` +
        (link != null ? `, link ${f(link, 0)} Mbps` : '') + (band(fr) ? `, ${band(fr)}` + (chan(fr) != null ? ` ch ${chan(fr)}` : '') : '') + '.' });
    }
    if (wp.gateway) { const g = wp.gateway; layers.push({ name: `LAN · gateway ${g.label}`, status: g.sr >= .99 && (g.median_ms ?? 0) < 30 ? 'Good' : g.sr >= .9 ? 'Fair' : 'Failing',
      detail: `${f(g.sr * 100, 0)}% reachable, median ${g.median_ms != null ? f(g.median_ms, 1) : '–'} ms` }); }
    if (wp.resolver) { const d = wp.resolver; layers.push({ name: `DNS · ${d.label}`, status: d.sr >= .99 && d.median_ms != null && d.median_ms < 50 ? 'Good' : d.sr >= .9 ? 'Fair' : 'Failing',
      detail: `${f(d.sr * 100, 0)}% reachable, median ${d.median_ms != null ? f(d.median_ms, 1) : '–'} ms` }); }
    const sr = wp.stats.sr ?? 0;
    const ips = probes.filter(p => p.probe_type === 'public_ip' && pathOfProbe(p) === 'wifi').sort((a, b) => a.t_utc_ms - b.t_utc_ms).map(p => p.public_ip).filter((s): s is string => !!s);
    const changes = ips.length ? ips.filter((x, i) => i === 0 || x !== ips[i - 1]).length - 1 : 0;
    layers.push({ name: 'WAN · backhaul to internet', status: sr >= .99 ? 'Good' : sr >= .95 ? 'Fair' : sr >= .9 ? 'Poor' : 'Failing',
      detail: `${f(sr * 100, 1)}% of internet checks succeed, p95 ${f((wp.stats.p95 ?? 0) / 1000, 1)} s, ${stalls.length} stalls (${f(stalls.reduce((a, s) => a + s.dur, 0) / 60, 0)} min). Public IP changed ${Math.max(changes, 0)}× (${[...new Set(ips)].join(', ')}).` });
    const tot = wp.stats.tot;
    layers.push({ name: 'Application · HTTPS', status: tot != null && tot < 400 ? 'Good' : tot != null && tot < 600 ? 'Fair' : 'Poor',
      detail: `Median total ${tot != null ? f(tot, 0) : '–'} ms` + (mp && mp.stats.tot != null ? ` vs ${f(mp.stats.tot, 0)} ms on mobile in parallel.` : '.') });
  }

  // ---------- endpoints
  const ka = 'cellular', kb = wp ? 'wifi' : null, act = wp ? 'wifi' : 'cellular';
  const endpoints = [...new Set(lat.map(x => x.p.endpoint))].map(host => {
    const da = lat.filter(x => x.p.endpoint === host && pathOfProbe(x.p) === ka && x.act === act).map(x => x.p);
    const db = kb ? lat.filter(x => x.p.endpoint === host && pathOfProbe(x.p) === kb && x.act === act).map(x => x.p) : [];
    return { host: host.split('//').pop()!.split('/')[0], a: da.length ? pstats(da) : null, b: db.length ? pstats(db) : null };
  });

  // ---------- quality
  const comp = (inp.completeness && inp.completeness.collectors) || {};
  const cv = (k: string): number | null => isNum(comp[k]) ? comp[k] : null;
  const logGap = Math.max(0, ...(inp.collectorLog || []).map(c => { const m = /(\d+) s/.exec(c.message || ''); return m ? Number(m[1]) : 0; }));
  const fixGap = L.length > 1 ? Math.floor(Math.max(...L.slice(1).map((l, i) => l.t_utc_ms - L[i].t_utc_ms)) / 1000) : 0;
  const radioCaps = ['radio', 'motion', 'device', 'network_path'].map(cv).filter(isNum);
  const therm = dev.map(d => d.thermal_state).filter((s): s is string => !!s);
  const quality = [
    { collector: 'Latency probes', captured: cv('latency_probes'), note: `${thousands(probes.length)} probes in total` },
    { collector: 'Location', captured: cv('location'), note: `${thousands(L.length)} fixes; longest gap ${Math.max(logGap, ios ? 0 : fixGap)} s` },
    { collector: 'Radio / motion / device / path', captured: radioCaps.length ? Math.min(...radioCaps) : null,
      note: dev.length ? ((isNum(dev[0].battery) && isNum(dev[dev.length - 1].battery) ? `battery ${f((dev[0].battery as number) * 100, 0)}% → ${f((dev[dev.length - 1].battery as number) * 100, 0)}%, ` : '') + `thermal ${therm.length ? mode(therm) : 'unknown'}`) : '' },
    { collector: 'Cell RF (RSRP/SINR)', captured: ios ? 0 : (cv('cell_serving_polls') ?? (inp.completeness?.cell_rf ? 1 : 0)), note: ios ? 'not exposed on iOS' : '' },
    { collector: 'sysdiagnose', captured: null, note: ios ? (inp.sysdiagnose ?? (typeof inp.completeness?.sysdiagnose === 'string' ? inp.completeness.sysdiagnose : 'none')) : 'not applicable on Android' },
  ];

  // ---------- KPIs
  const stallMin = stalls.reduce((a, s) => a + s.dur, 0) / 60;
  const kp: any[] = [];
  if (wp) {
    const none = wp.health ? wp.health['3'] : 0;
    kp.push({ label: 'Wi-Fi · HTTPS success', value: `${f((wp.stats.sr ?? 0) * 100, 1)}%`, sub: mp ? `mobile in parallel: ${f((mp.stats.sr ?? 0) * 100, 1)}%` : '', tone: wp.verdict === 'FAIL' ? 'bad' : '' });
    kp.push({ label: 'Wi-Fi time without internet', value: `${f(none * 100, 0)}%`, sub: `${stalls.length} stalls · ${f(stallMin, 0)} min`, tone: none > .2 ? 'bad' : '' });
    kp.push({ label: 'Wi-Fi throughput (median)', value: `${f(wp.thr?.median ?? 0, 1)} Mbps`, sub: mp && mp.thr ? `mobile in parallel: ${f(mp.thr.median ?? 0, 1)} Mbps` : '', tone: '' });
    kp.push({ label: 'Wi-Fi request p95', value: `${f((wp.stats.p95 ?? 0) / 1000, 1)} s`, sub: mp ? `mobile in parallel: ${f((mp.stats.p95 ?? 0) / 1000, 2)} s` : '', tone: '' });
  }
  const lan = layers.find(l => l.name.startsWith('LAN'));
  if (wp && lan) kp.push({ label: 'Wi-Fi LAN gateway', value: lan.status, sub: lan.detail, tone: lan.status === 'Good' ? 'good' : 'bad' });
  if (ma) kp.push({ label: `${operator} mobile (active) · success`, value: `${f((ma.stats.sr ?? 0) * 100, 1)}%`, sub: `${f(ma.thr?.median ?? 0, 0)} Mbps median`, tone: ma.verdict === 'PASS' ? 'good' : '' });

  // ---------- findings
  const findings: string[] = [];
  if (wp && layers.length) {
    const lanOk = layers.some(l => l.name.startsWith('LAN') && l.status === 'Good'); const sr = wp.stats.sr ?? 0;
    if (lanOk && sr < .9) findings.push(`<b>Fault domain is the Wi-Fi WAN/backhaul.</b> The LAN gateway answered reliably while ${f((1 - sr) * 100, 0)}% of internet checks failed.`);
    if (mp) findings.push(`<b>Mobile in parallel</b> succeeded ${f((mp.stats.sr ?? 0) * 100, 1)}% of the time over the same minutes.`);
  }
  if (phases.length) findings.push('<b>Stalls by phase:</b> ' + phases.map(p => `${p.key} ${f(p.stall_s / Math.max((p.end - p.start) / 1000, 1) * 100, 0)}% down`).join('; ') + '.');
  const locCov = cv('location');
  if (L.length && (locCov ?? 1) < .8) findings.push(`<b>Location coverage was ${f((locCov ?? 0) * 100, 0)}%.</b> Positions are missing or approximate for part of the route.`);

  const thrN = probes.filter(p => p.probe_type === 'throughput').length;
  const meta = { run_id: run.run_id, run_id_short: run.run_id.slice(0, 8), platform: run.platform, device: run.model ?? '', os: `${ios ? 'iOS' : 'Android'} ${run.os_version ?? ''}`,
    app_version: run.app_version ?? '', start_ms: T0, end_ms: T1, tz_offset_min: TZ, mobile_operator: operator,
    title: `${run.name || 'Network test'} · ${hm(T0)}–${hm(T1)}` + (wp ? `: ${ssid} vs ${operator} mobile` : ''),
    scope: `${f((T1 - T0) / 60000, 0)} min of continuous testing; ` + segs.map(s => `${s.label} ${hm(s.start)}–${hm(s.end)}`).join('; ') +
      `. ${thousands(probes.length)} probes, ${thrN} throughput tests, ${rad.length} radio samples, ${thousands(L.length)} location fixes.`,
    source_note: `Source: Tracer bundle ${run.run_id} · generated by Tracer report v1.` };

  // ---------- map
  const rail = inp.rail || null;
  const nearest = (la: number, lo: number) => { let best: any = null, bd = Infinity;
    for (const s of rail?.stations || []) { const dx = (s.lon - lo) * Math.cos(la * Math.PI / 180), dy = s.lat - la, d = dx * dx + dy * dy; if (d < bd) { bd = d; best = { name: s.name, lat: s.lat, lon: s.lon }; } }
    return best; };
  const last = L[L.length - 1];
  // the station the run ends nearest to; with > 5 min recorded after the last fix, the terminal ahead in the direction of travel
  const autoDest = () => {
    if (!last || !rail) return null;
    if (T1 - last.t_utc_ms > 300000) {
      // heading: from the station the run started nearest to (else the first fix) to the last fix
      const first = nearest(L[0].lat, L[0].lon) ?? { lat: L[0].lat, lon: L[0].lon };
      const k = Math.cos(last.lat * Math.PI / 180), vx = (last.lon - first.lon) * k, vy = last.lat - first.lat, len = Math.hypot(vx, vy);
      if (len > 0.005) {
        const proj = (la: number, lo: number) => ((lo - first.lon) * k * vx + (la - first.lat) * vy) / len; const pl = proj(last.lat, last.lon);
        let best: Station | null = null; for (const st of rail.stations || []) if (proj(st.lat, st.lon) > pl && (!best || proj(st.lat, st.lon) > proj(best.lat, best.lon))) best = st;
        if (best) return { name: best.name, lat: best.lat, lon: best.lon };
      }
    }
    return nearest(last.lat, last.lon);
  };
  const destination = inp.destination ?? autoDest() ?? rail?.destination ?? null;
  const gaps: any[] = [];
  for (let i = 1; i < L.length; i++) if (L[i].t_utc_ms - L[i - 1].t_utc_ms > 300000)
    gaps.push({ a: [L[i - 1].lat, L[i - 1].lon], b: [L[i].lat, L[i].lon], kind: 'nofix', label: `${hm(L[i - 1].t_utc_ms)}–${hm(L[i].t_utc_ms)} no fixes` });
  if (last && T1 - last.t_utc_ms > 300000 && destination) gaps.push({ a: [last.lat, last.lon], b: [destination.lat, destination.lon], kind: 'unrecorded', label: 'not recorded' });
  // stations far from this run's track stay off the map bounds (template: in_view=false) and unlabelled
  const unrecorded = gaps.some(g => g.kind === 'unrecorded');
  const bb = L.length ? [Math.min(...L.map(l => l.lat)), Math.max(...L.map(l => l.lat)), Math.min(...L.map(l => l.lon)), Math.max(...L.map(l => l.lon))] : null;
  const inView = (st: Station) => {
    if (!bb) return false;
    if (unrecorded && destination && st.name === destination.name) return true;
    const pLa = Math.max((bb[1] - bb[0]) * 0.2, 0.05), pLo = Math.max((bb[3] - bb[2]) * 0.2, 0.05);
    return st.lat >= bb[0] - pLa && st.lat <= bb[1] + pLa && st.lon >= bb[2] - pLo && st.lon <= bb[3] + pLo;
  };
  const stations = (rail?.stations || []).map(st => inView(st) ? st : { ...st, in_view: false });
  const labels: any[] = [];
  if (L.length) { labels.push({ lat: L[0].lat, lon: L[0].lon, text: `Start ${hm(T0)}`, anchor: 'start', dy: -6 });
    labels.push({ lat: last.lat, lon: last.lon, text: `Last fix ${hm(last.t_utc_ms)}`, anchor: 'end', dy: 14 }); }
  for (const s of stations) if (s.label && (s as any).in_view !== false) labels.push({ lat: s.lat, lon: s.lon, text: s.name, anchor: s.anchor ?? 'start', dy: s.dy ?? 0 });

  const recs: string[] = ['Keep recording until arrival; auto-stop only inside a station geofence.'];
  if (ios) recs.push('Run the Android companion for RSRP/SINR, cell ID, Wi-Fi RSSI and raw GNSS.');
  recs.push('Repeat at the same time of day with Wi-Fi-only and mobile-only legs.');
  if (L.length && (locCov ?? 1) < .8) recs.push('Keep location on "Allow all the time" and the phone near a window; location coverage was low.');
  if (wp && (wp.stats.sr ?? 1) < .9 && layers.some(l => l.name.startsWith('LAN') && l.status === 'Good')) recs.push('Escalate the Wi-Fi backhaul (WAN) with the operator: the LAN side was healthy.');
  if (T1 - T0 < 300000) recs.push('Record at least 5 minutes so results are comparable.');

  return {
    meta, kpis: kp.slice(0, 6), paths, segments: segs.map(s => ({ kind: s.kind, start: s.start, end: s.end, label: s.label })),
    health_bins: { t0: T0, bin_ms: BIN, codes }, rat: rad.map((r, i) => ({ t: r.t, dur: radDur(i), rat: r.rat })), rat_share: ratShare,
    rat_note: ios ? 'iOS does not expose RSRP/SINR; signal levels need the Android companion.' : '',
    connect_series: { cellular: seriesConnect('cellular'), wifi: seriesConnect('wifi') }, failures: { cellular: seriesFail('cellular'), wifi: seriesFail('wifi') },
    stalls, mobile_drops: mobDrops,
    throughput: probes.filter(p => p.probe_type === 'throughput').map(p => ({ t: p.t_utc_ms, path: pathOfProbe(p), mbps: isNum(p.mbps) ? p.mbps : null, ok: ok(p) })),
    fixes_per_min: fpm,
    track: { fixes: L.map(l => [Math.round(l.lat * 1e6) / 1e6, Math.round(l.lon * 1e6) / 1e6, health(l.t_utc_ms, active(l.t_utc_ms)), l.t_utc_ms, isNum(l.h_acc_m) ? Math.round(l.h_acc_m * 10) / 10 : 0]) },
    map: { basemap: inp.basemap ?? null, rail_lines: rail?.lines ?? [], stations, labels, gaps,
      attribution: [inp.basemap?.attribution, rail?.attribution].filter(x => !!x).join(' · ') },
    wifi_layers: layers, endpoints, endpoint_paths: [`${operator} mobile`, wp ? 'Wi-Fi' : '–'],
    phases: phases.map(p => ({ state: p.state, start: p.start, end: p.end, key: p.key, label: p.label, stalls: p.stalls, stall_s: p.stall_s })),
    quality, findings, recommendations: { 'Next test': recs },
  };
}

/** HTML with the JSON in place of __REPORT_DATA__ ("</" escaped). */
export function injectReport(template: string, data: any): string {
  return template.replace('__REPORT_DATA__', () => JSON.stringify(data).replace(/<\//g, '<\\/'));
}

/** Tracer_<site|mode>_<test#>_<yyyyMMdd-HHmm>_<runId8>.pdf (local time of the run). */
export function reportFileName(run: RunMeta): string {
  const d = new Date(run.started_at + tzOffsetMin(run) * 60000);
  const ts = `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}-${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}`;
  const who = String(run.site || run.mode || 'run').replace(/[^A-Za-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'run';
  return `Tracer_${who}_T${run.test_number ?? 0}_${ts}_${run.run_id.slice(0, 8)}.pdf`;
}

/**
 * Expo: render to PDF with expo-print (A4 landscape 842 × 595 pt). The template's @page rule sets
 * the 8 mm margins. Usage in the app:
 *
 *   import * as Print from 'expo-print';
 *   const html = injectReport(template, buildReportData(input));
 *   const { uri } = await Print.printToFileAsync({ html, width: 842, height: 595 });
 */
export const PDF_SIZE = { width: 842, height: 595 } as const;
