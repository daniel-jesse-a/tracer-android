import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { buildReportData, injectReport, reportFileName, ReportInput } from '../src/buildReportData';

/** Minimal RFC 4180 parser (quotes, doubled quotes). */
function parseCsv(text: string): Record<string, string | null>[] {
  const rows: (string | null)[][] = []; let row: (string | null)[] = []; let field = ''; let q = false; let quoted = false;
  const end = () => { row.push(field === '' && !quoted ? null : field); field = ''; quoted = false; };
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) { if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else q = false; } else field += c; }
    else if (c === '"') { q = true; quoted = true; } else if (c === ',') end(); else if (c === '\r') { } else if (c === '\n') { end(); rows.push(row); row = []; } else field += c;
  }
  if (field !== '' || row.length) { end(); rows.push(row); }
  const head = rows.shift() as string[];
  return rows.filter(r => r.length > 1).map(r => Object.fromEntries(head.map((h, i) => [h, r[i] ?? null])));
}
const num = (x: string | null) => (x == null || x === '' ? null : Number(x));

/** Loads an unzipped Tracer bundle (run.json + CSVs) the way the Expo app reads its SQLite tables. */
export function loadBundle(dir: string): ReportInput {
  const rj = JSON.parse(readFileSync(join(dir, 'run.json'), 'utf8'));
  const csv = (n: string) => existsSync(join(dir, n)) ? parseCsv(readFileSync(join(dir, n), 'utf8')) : [];
  const t = (r: any) => Number(r.t_utc_ms);
  return {
    run: { ...rj.run, time_zone: rj.time_zone, platform: rj.device.platform, model: rj.device.model, os_version: rj.device.os_version, app_version: rj.device.app_version },
    completeness: rj.completeness,
    probes: csv('probe.csv').map(r => ({ t_utc_ms: t(r), probe_type: r.probe_type!, endpoint: r.endpoint!, success: num(r.success)!, dns_ms: num(r.dns_ms), connect_ms: num(r.connect_ms),
      tls_ms: num(r.tls_ms), ttfb_ms: num(r.ttfb_ms), total_ms: num(r.total_ms), mbps: num(r.mbps), interface: r.interface, is_cellular: num(r.is_cellular), public_ip: r.public_ip, asn_org: r.asn_org })),
    events: csv('event.csv').map((r, i) => ({ id: num(r.id) ?? i + 1, t_utc_ms: t(r), event_type: r.event_type!, before_json: r.before_json, after_json: r.after_json })),
    radio: csv('radio.csv').map(r => ({ t_utc_ms: t(r), service_id: r.service_id, rat: r.rat!, nr_state: r.nr_state })),
    networkPath: csv('network_path.csv').map(r => ({ t_utc_ms: t(r), primary_interface: r.primary_interface })),
    locations: csv('location.csv').map(r => ({ t_utc_ms: t(r), lat: Number(r.lat), lon: Number(r.lon), h_acc_m: num(r.h_acc_m) })),
    device: csv('device.csv').map(r => ({ t_utc_ms: t(r), battery: num(r.battery), thermal_state: r.thermal_state })),
    wifi: csv('wifi_connection.csv').map(r => ({ t_utc_ms: t(r), ssid: r.ssid, bssid: r.bssid, security: r.security, rssi_dbm: num(r.rssi_dbm), link_speed_mbps: num(r.link_speed_mbps), frequency_mhz: num(r.frequency_mhz) })),
    collectorLog: csv('x_collector_log.csv').map(r => ({ t_utc_ms: t(r), message: r.message })),
  };
}

function diff(a: any, b: any, p = ''): string[] {
  if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) <= 1e-6 * Math.max(1, Math.abs(a)) ? [] : [`${p}: ${a} != ${b}`];
  if (Array.isArray(a) && Array.isArray(b)) return a.length !== b.length ? [`${p}: len ${a.length} != ${b.length}`] : a.flatMap((x, i) => diff(x, b[i], `${p}[${i}]`));
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    return [...keys].flatMap(k => (a[k] === undefined && b[k] === null) || (a[k] === null && b[k] === undefined) ? [] : diff(a[k], b[k], `${p}/${k}`));
  }
  return a === b ? [] : [`${p}: ${JSON.stringify(a)} != ${JSON.stringify(b)}`];
}

const FX = join(__dirname, '..', '..', 'test', 'fixtures', 'synthetic');
const RAIL = { stations: [
  { name: 'Abu Dhabi Station', lat: 24.334, lon: 54.5297, label: true, anchor: 'start', dy: 20 },
  { name: 'Dubai Station', lat: 25.017, lon: 55.1679, label: true, anchor: 'end', dy: -6 },
] };

test('TypeScript port matches the Kotlin port on the synthetic rail run', () => {
  const inp = loadBundle(FX); inp.rail = RAIL;
  const ts = buildReportData(inp);
  const kt = JSON.parse(readFileSync(join(FX, 'expected-kotlin.json'), 'utf8'));
  const d = diff(ts, kt);
  assert.deepEqual(d.slice(0, 20), []);
});

test('acceptance values on run 95596452 (REPORT_V1_FIXTURE)', { skip: !process.env.REPORT_V1_FIXTURE }, () => {
  const d = buildReportData(loadBundle(process.env.REPORT_V1_FIXTURE!));
  const p = Object.fromEntries(d.paths.map((x: any) => [x.key, x]));
  const near = (exp: number, got: number) => assert.ok(Math.abs(exp - got) <= exp * 0.001, `${got} vs ${exp}`);
  near(67.3, Math.round(p.wifi_active.stats.sr * 1000) / 10); near(97.9, Math.round(p.mob_parallel.stats.sr * 1000) / 10);
  near(99.9, Math.round(p.mob_active.stats.sr * 1000) / 10); assert.equal(d.stalls.length, 72);
  near(47, Math.round(p.wifi_active.health['3'] * 100));
  near(81.7, Math.round(p.mob_active.thr.median * 10) / 10); near(31.1, Math.round(p.mob_parallel.thr.median * 10) / 10); near(4.4, Math.round(p.wifi_active.thr.median * 10) / 10);
});

test('empty and tiny runs never throw', () => {
  const run = { run_id: '00000000-0000-4000-8000-00000000000e', started_at: 1793796000000, ended_at: 1793796090000, platform: 'ios', time_zone: 'Asia/Dubai' };
  const d = buildReportData({ run });
  assert.equal(d.paths.length, 0); assert.equal(d.segments.length, 1); assert.equal(d.meta.tz_offset_min, 240);
  assert.equal(d.quality[3].note, 'not exposed on iOS');
  const html = injectReport('<script>__REPORT_DATA__</script>', { x: '</script>' });
  assert.ok(!html.includes('"</script>'));
  assert.equal(reportFileName({ ...run, site: 'Rail', test_number: 3 }), 'Tracer_Rail_T3_20261104-1640_00000000.pdf');
});
