#!/usr/bin/env python3
"""
Reference implementation: Tracer run bundle  ->  ReportData JSON  ->  PDF.

  python3 build_report_data.py <bundle_dir> <out.json> [--rail rail.json] [--basemap basemap.json] [--pdf out.pdf]

The app ports this logic to Kotlin / TypeScript. The rules (thresholds, phases, verdicts,
wording) are the spec. Every field is documented in REPORT_DATA_CONTRACT.md.
"""
import sys, json, math, argparse, os, subprocess, collections, datetime
import pandas as pd, numpy as np

ap = argparse.ArgumentParser()
ap.add_argument('bundle'); ap.add_argument('out')
ap.add_argument('--rail'); ap.add_argument('--basemap'); ap.add_argument('--pdf'); ap.add_argument('--template', default=os.path.join(os.path.dirname(__file__), 'tracer-report-template.html'))
a = ap.parse_args()
B = a.bundle.rstrip('/') + '/'
rd = lambda n: pd.read_csv(B + n, low_memory=False) if os.path.exists(B + n) else pd.DataFrame()

RUN = json.load(open(B + 'run.json')); run = RUN['run']; dev = RUN['device']
T0, T1 = int(run['started_at']), int(run['ended_at'])
TZMIN = 240 if RUN.get('time_zone') in ('Asia/Dubai', 'Asia/Muscat') else 0
P, E, L, RAD, NP, W, DEV, CL = rd('probe.csv'), rd('event.csv'), rd('location.csv'), rd('radio.csv'), rd('network_path.csv'), rd('wifi_connection.csv'), rd('device.csv'), rd('x_collector_log.csv')
L = L.sort_values('t_utc_ms').reset_index(drop=True) if len(L) else L
hm = lambda t: (datetime.datetime.utcfromtimestamp(t / 1000) + datetime.timedelta(minutes=TZMIN)).strftime('%H:%M')
fin = lambda x: None if x is None or (isinstance(x, float) and (math.isnan(x) or math.isinf(x))) else float(x)

# ---------- active-path segments (network_path.primary_interface)
NP = NP.sort_values('t_utc_ms')
segs = []
for r in NP.itertuples():
    k = 'wifi' if r.primary_interface == 'wifi' else 'cellular'
    if not segs or segs[-1]['kind'] != k: segs.append({'kind': k, 'start': int(max(r.t_utc_ms, T0)), 'end': T1})
    if len(segs) > 1: segs[-2]['end'] = segs[-1]['start']
segs = [s for s in segs if s['end'] - s['start'] > 30000] or [{'kind': 'cellular', 'start': T0, 'end': T1}]
segs[0]['start'] = T0; segs[-1]['end'] = T1
def active(t):
    for s in segs:
        if s['start'] <= t < s['end'] or (t == T1 and s['end'] == T1): return s['kind']
    return segs[-1]['kind']

ssid = (W.ssid.dropna().mode().iloc[0] if len(W) and W.ssid.notna().any() else 'Wi-Fi')
asn = P[(P.probe_type == 'public_ip') & (P.interface == 'cellular')].asn_org.dropna()
operator = {'Emirates Internet': 'e&', 'Emirates Integrated Telecommunications Company PJSC': 'du'}.get(asn.mode().iloc[0], asn.mode().iloc[0]) if len(asn) else 'mobile'
for s in segs: s['label'] = f'{operator} mobile data' if s['kind'] == 'cellular' else f'{ssid}'

# ---------- probes
lat = P[P.probe_type == 'latency'].copy(); lat['act'] = [active(t) for t in lat.t_utc_ms]
def pstats(df):
    ok = df[df.success == 1]
    return {k: fin(v) for k, v in dict(n=len(df), sr=df.success.mean() if len(df) else None, dns=ok.dns_ms.median(), con=ok.connect_ms.median(), tls=ok.tls_ms.median(),
                                       ttfb=ok.ttfb_ms.median(), tot=ok.total_ms.median(), p95=ok.total_ms.quantile(.95)).items()}

# ---------- stalls: wifi drop -> next wifi reconnect
ev = E.sort_values('t_utc_ms')
drops = ev[(ev.event_type == 'drop')]
recon = ev[ev.event_type == 'reconnect']
def path_of(js):
    try: return json.loads(js).get('path') or json.loads(js).get('interface')
    except Exception: return None
stalls, mob_drops = [], []
for r in drops.itertuples():
    pth = path_of(r.after_json)
    nxt = recon[(recon.t_utc_ms > r.t_utc_ms) & (recon.before_json.fillna('').str.contains(f'"path":"{pth}"'))]
    end = int(nxt.t_utc_ms.iloc[0]) if len(nxt) else T1
    if pth == 'wifi': stalls.append({'id': int(r.id), 't': int(r.t_utc_ms), 'dur': (end - r.t_utc_ms) / 1000})
    else: mob_drops.append(int(r.t_utc_ms))
stall_iv = np.array([[s['t'], s['t'] + s['dur'] * 1000] for s in stalls]) if stalls else np.zeros((0, 2))

# ---------- health bins (15 s, active path, +-10 s window)
BIN = 15000
lat_s = lat.sort_values('t_utc_ms'); tv = lat_s.t_utc_ms.values
def health(t, kind):
    if kind == 'wifi' and len(stall_iv) and ((stall_iv[:, 0] <= t) & (stall_iv[:, 1] >= t)).any(): return 3
    i0, i1 = np.searchsorted(tv, t - 10000), np.searchsorted(tv, t + 10000)
    w = lat_s.iloc[i0:i1]; w = w[w.interface == kind]
    if not len(w): return -1
    sr, med = w.success.mean(), w[w.success == 1].connect_ms.median()
    if sr < .5: return 3
    if sr >= .95 and med < 150: return 0
    if sr >= .8 and med < 400: return 1
    return 2
codes = [health(t + BIN / 2, active(t + BIN / 2)) for t in range(T0, T1, BIN)]
def share(lo, hi):
    c = collections.Counter(h for i, h in enumerate(codes) if lo <= T0 + i * BIN < hi); n = max(sum(c.values()), 1)
    return {str(k): c.get(k, 0) / n for k in (0, 1, 2, 3, -1)}

# ---------- paths (active segments by kind + mobile measured in parallel while on Wi-Fi)
thr = P[P.probe_type == 'throughput']; dnsS = P[P.probe_type == 'dns_server']; gw = P[P.probe_type == 'gateway']
def in_win(df, kind, act_kind):
    d = df[df.interface == kind]; return d[[active(t) == act_kind for t in d.t_utc_ms]]
def thr_stats(df):
    v = df[df.success == 1].mbps.dropna()
    return {'n': int(len(df)), 'median': fin(v.median()), 'min': fin(v.min()), 'max': fin(v.max()), 'under1': int((df.mbps.fillna(0) < 1).sum())} if len(df) else None
def win(kind):
    ss = [s for s in segs if s['kind'] == kind]; return [ss[0]['start'], ss[-1]['end']] if ss else None
paths = []
for key, kind, act_kind, label, short in [('mob_active', 'cellular', 'cellular', f'{operator} mobile · active path', f'{operator} (active)'),
                                          ('mob_parallel', 'cellular', 'wifi', f'{operator} mobile · in parallel', f'{operator} (parallel)'),
                                          ('wifi_active', 'wifi', 'wifi', f'{ssid}', 'Wi-Fi')]:
    d = lat[(lat.interface == kind) & (lat.act == act_kind)]
    if len(d) < 20 or not win(act_kind): continue
    st = pstats(d); th = thr_stats(in_win(thr, kind, act_kind)); dn = in_win(dnsS, kind, act_kind); g = in_win(gw, kind, act_kind)
    is_active = kind == act_kind
    h = share(*win(act_kind)) if is_active else None
    p = {'key': key, 'kind': kind, 'label': label, 'short': short, 'window': win(act_kind), 'stats': st, 'thr': th,
         'resolver': {'label': ', '.join(dn.endpoint.unique()[:1]) + (f' +{dn.endpoint.nunique() - 1}' if dn.endpoint.nunique() > 1 else ''), 'median_ms': fin(dn.total_ms.median()), 'sr': fin(dn.success.mean())} if len(dn) else None,
         'gateway': {'label': g.endpoint.iloc[0], 'sr': fin(g.success.mean()), 'median_ms': fin(g.total_ms.median()), 'note': 'carrier gateway blocks ICMP' if kind == 'cellular' and g.success.mean() == 0 else ''} if len(g) else None,
         'health': {int(k): v for k, v in h.items()} if h else None}
    # verdict rules
    sr = st['sr'] or 0; p95 = st['p95'] or 0; tm = (th or {}).get('median') or 0; none = (h or {}).get('3', 0)
    if sr < .9 or none > .2: v = 'FAIL'
    elif sr < .99 or p95 > 800 or (th and tm < 20): v = 'WATCH'
    else: v = 'PASS'
    p['verdict'] = v
    p['verdict_text'] = f'{sr * 100:.1f}% of HTTPS checks succeeded, {st["con"]:.0f} ms median connect, p95 {p95:.0f} ms' + (f', {tm:.1f} Mbps median throughput' if th else '') + (f', no internet {none * 100:.0f}% of the time' if h and none > 0 else '') + '.'
    paths.append(p)

# ---------- series
def series_connect(kind):
    d = lat[(lat.interface == kind) & (lat.success == 1)].copy()
    if not len(d): return []
    d['b'] = (d.t_utc_ms - T0) // 30000; g = d.groupby('b').connect_ms.median()
    return [[int(T0 + b * 30000 + 15000), round(float(v), 1)] for b, v in g.items()]
def series_fail(kind):
    d = lat[(lat.interface == kind) & (lat.success == 0)].copy()
    if not len(d): return []
    d['b'] = (d.t_utc_ms - T0) // 60000; return [[int(T0 + b * 60000), int(n)] for b, n in d.groupby('b').size().items()]
RAD = RAD.sort_values('t_utc_ms'); RAD['dur'] = (RAD.t_utc_ms.shift(-1).fillna(T1) - RAD.t_utc_ms)
rat = [{'t': int(r.t_utc_ms), 'dur': int(r.dur), 'rat': r.rat} for r in RAD.itertuples()] if len(RAD) else []
rat_share = {}
for kind in ('cellular', 'wifi'):
    ww = win(kind)
    if not ww or not len(RAD): continue
    d = RAD[(RAD.t_utc_ms >= ww[0]) & (RAD.t_utc_ms < ww[1])]
    if not len(d): continue
    rat_share[f'While on {"mobile data" if kind == "cellular" else ssid}'] = {'minutes': (ww[1] - ww[0]) / 60000, 'changes': int((d.rat != d.rat.shift()).sum()),
                                                                          'share': (d.groupby('rat').dur.sum() / d.dur.sum()).to_dict()}

# ---------- location
fixes, fpm = [], []
if len(L):
    for r in L.itertuples():
        fixes.append([round(r.lat, 6), round(r.lon, 6), health(r.t_utc_ms, active(r.t_utc_ms)), int(r.t_utc_ms), round(float(r.h_acc_m), 1)])
    L['b'] = (L.t_utc_ms - T0) // 60000
    fpm = [[int(T0 + b * 60000), int(g.lat.size), float(g.h_acc_m.median())] for b, g in L.groupby('b')]
def loc_at(t):
    if not len(L): return None, None, None, None
    i = max(np.searchsorted(L.t_utc_ms.values, t, side='right') - 1, 0); f = L.iloc[i]
    return f.lat, f.lon, float(f.h_acc_m), (t - f.t_utc_ms) / 1000
for s in stalls:
    la, lo, acc, age = loc_at(s['t'])
    s.update(lat=fin(la), lon=fin(lo), acc=acc, conf=('precise' if acc is not None and acc <= 60 and age <= 120 else 'coarse' if acc is not None and age <= 120 else 'stale'))

# ---------- phases (Wi-Fi window split by location state: precise / no fixes / approximate)
phases = []
ww = win('wifi')
if ww and stalls:
    def state(t):
        m = [x for x in fpm if x[0] <= t < x[0] + 60000]
        return 'none' if not m else ('precise' if m[0][2] <= 60 else 'approx')
    names = {'precise': 'located (GPS)', 'none': 'no location', 'approx': 'approximate position'}
    mins = list(range(ww[0], ww[1], 60000)); raw = [state(t) for t in mins]
    sm = [collections.Counter(raw[max(0, i - 3):i + 4]).most_common(1)[0][0] for i in range(len(raw))]   # +-3 min majority filter
    for t, stt in zip(mins, sm):
        if phases and phases[-1]['state'] == stt: phases[-1]['end'] = min(t + 60000, ww[1])
        else: phases.append({'state': stt, 'start': t, 'end': min(t + 60000, ww[1])})
    merged = []
    for p in phases:                                                     # fold runs < 5 min into the previous phase
        if merged and (p['end'] - p['start'] < 300000 or merged[-1]['state'] == p['state']): merged[-1]['end'] = p['end']
        else: merged.append(p)
    phases = merged; phases[-1]['end'] = ww[1]
    for i, p in enumerate(phases):
        key = chr(65 + i); p['key'] = key; p['label'] = f'{key} · {names[p["state"]]}'
        inn = [s for s in stalls if p['start'] <= s['t'] < p['end']]; p['stalls'] = len(inn); p['stall_s'] = sum(s['dur'] for s in inn)
        for s in inn: s['phase'] = key
    phases = [p for p in phases if p['end'] > p['start']]

# ---------- Wi-Fi layer diagnosis (rules)
wp = next((p for p in paths if p['key'] == 'wifi_active'), None); mp = next((p for p in paths if p['key'] == 'mob_parallel'), None)
layers = []
if wp:
    g, dn = wp['gateway'], wp['resolver']
    layers.append({'name': 'Radio · phone ↔ AP', 'status': 'No data', 'detail': f'SSID "{ssid}", {W.bssid.dropna().nunique()} BSSID(s), security {W.security.dropna().mode().iloc[0] if W.security.notna().any() else "unknown"}. RSSI not exposed by the OS API; use the Android companion or sysdiagnose.'})
    if g: layers.append({'name': f'LAN · gateway {g["label"]}', 'status': 'Good' if g['sr'] >= .99 and (g['median_ms'] or 0) < 30 else 'Fair' if g['sr'] >= .9 else 'Failing', 'detail': f'{g["sr"] * 100:.0f}% reachable, median {g["median_ms"]:.1f} ms'})
    if dn: layers.append({'name': f'DNS · {dn["label"]}', 'status': 'Good' if dn['sr'] >= .99 and dn['median_ms'] < 50 else 'Fair' if dn['sr'] >= .9 else 'Failing', 'detail': f'{dn["sr"] * 100:.0f}% reachable, median {dn["median_ms"]:.1f} ms'})
    sr = wp['stats']['sr']; ips = P[(P.probe_type == 'public_ip') & (P.interface == 'wifi')].public_ip.dropna()
    changes = int((ips != ips.shift()).sum()) - 1 if len(ips) else 0
    layers.append({'name': 'WAN · backhaul to internet', 'status': 'Good' if sr >= .99 else 'Fair' if sr >= .95 else 'Poor' if sr >= .9 else 'Failing',
                   'detail': f'{sr * 100:.1f}% of internet checks succeed, p95 {wp["stats"]["p95"] / 1000:.1f} s, {len(stalls)} stalls ({sum(s["dur"] for s in stalls) / 60:.0f} min). Public IP changed {max(changes, 0)}× ({", ".join(pd.unique(ips))}).'})
    layers.append({'name': 'Application · HTTPS', 'status': 'Good' if wp['stats']['tot'] < 400 else 'Fair' if wp['stats']['tot'] < 600 else 'Poor',
                   'detail': f'Median total {wp["stats"]["tot"]:.0f} ms' + (f' vs {mp["stats"]["tot"]:.0f} ms on mobile in parallel.' if mp else '.')})

# ---------- endpoints (Wi-Fi window: mobile-parallel vs Wi-Fi; else mobile only)
eps = []
ka, kb = ('cellular', 'wifi') if wp else ('cellular', None)
act = 'wifi' if wp else 'cellular'
for host in lat.endpoint.dropna().unique():
    da = lat[(lat.endpoint == host) & (lat.interface == ka) & (lat.act == act)]; db = lat[(lat.endpoint == host) & (lat.interface == kb) & (lat.act == act)] if kb else pd.DataFrame()
    eps.append({'host': host.split('//')[-1].split('/')[0], 'a': pstats(da) if len(da) else None, 'b': pstats(db) if len(db) else None})

# ---------- KPIs, findings, quality
comp = RUN.get('completeness', {}).get('collectors', {})
quality = [{'collector': 'Latency probes', 'captured': comp.get('latency_probes'), 'note': f'{len(P):,} probes in total'},
           {'collector': 'Location', 'captured': comp.get('location'), 'note': f'{len(L):,} fixes; longest gap {max([0] + [int(x) for x in CL.message.str.extract(r"(\d+) s")[0].dropna()]) if len(CL) else 0} s'},
           {'collector': 'Radio / motion / device / path', 'captured': min([v for k, v in comp.items() if k in ('radio', 'motion', 'device', 'network_path')] or [None]), 'note': f'battery {DEV.battery.iloc[0] * 100:.0f}% → {DEV.battery.iloc[-1] * 100:.0f}%, thermal {DEV.thermal_state.mode().iloc[0]}' if len(DEV) else ''},
           {'collector': 'Cell RF (RSRP/SINR)', 'captured': 1.0 if RUN.get('completeness', {}).get('cell_rf') else 0.0, 'note': 'not exposed on iOS' if dev.get('platform') == 'ios' else ''},
           {'collector': 'sysdiagnose', 'captured': None, 'note': RUN.get('completeness', {}).get('sysdiagnose', 'none')}]
kp = []
if wp:
    kp += [{'label': 'Wi-Fi · HTTPS success', 'value': f'{wp["stats"]["sr"] * 100:.1f}%', 'sub': f'mobile in parallel: {mp["stats"]["sr"] * 100:.1f}%' if mp else '', 'tone': 'bad' if wp['verdict'] == 'FAIL' else ''},
              {'label': 'Wi-Fi time without internet', 'value': f'{wp["health"][3] * 100:.0f}%', 'sub': f'{len(stalls)} stalls · {sum(s["dur"] for s in stalls) / 60:.0f} min', 'tone': 'bad' if wp['health'][3] > .2 else ''},
              {'label': 'Wi-Fi throughput (median)', 'value': f'{(wp["thr"] or {}).get("median") or 0:.1f} Mbps', 'sub': f'mobile in parallel: {(mp["thr"] or {}).get("median") or 0:.1f} Mbps' if mp and mp['thr'] else ''},
              {'label': 'Wi-Fi request p95', 'value': f'{wp["stats"]["p95"] / 1000:.1f} s', 'sub': f'mobile in parallel: {mp["stats"]["p95"] / 1000:.2f} s' if mp else ''}]
lan = [l for l in layers if l['name'].startswith('LAN')]
if wp and lan: kp.append({'label': 'Wi-Fi LAN gateway', 'value': lan[0]['status'], 'sub': lan[0]['detail'], 'tone': 'good' if lan[0]['status'] == 'Good' else 'bad'})
ma = next((p for p in paths if p['key'] == 'mob_active'), None)
if ma: kp.append({'label': f'{operator} mobile (active) · success', 'value': f'{ma["stats"]["sr"] * 100:.1f}%', 'sub': f'{(ma["thr"] or {}).get("median") or 0:.0f} Mbps median', 'tone': 'good' if ma['verdict'] == 'PASS' else ''})
kp = kp[:6]
findings = []
if wp and layers:
    lan_ok = any(l['name'].startswith('LAN') and l['status'] == 'Good' for l in layers)
    if lan_ok and wp['stats']['sr'] < .9: findings.append(f'<b>Fault domain is the Wi-Fi WAN/backhaul.</b> The LAN gateway answered reliably while {(1 - wp["stats"]["sr"]) * 100:.0f}% of internet checks failed.')
    if mp: findings.append(f'<b>Mobile in parallel</b> succeeded {mp["stats"]["sr"] * 100:.1f}% of the time over the same minutes.')
if phases: findings.append('<b>Stalls by phase:</b> ' + '; '.join(f'{p["key"]} {p["stall_s"] / max((p["end"] - p["start"]) / 1000, 1) * 100:.0f}% down' for p in phases) + '.')
if len(L) and comp.get('location', 1) < .8: findings.append(f'<b>Location coverage was {comp.get("location", 0) * 100:.0f}%.</b> Positions are missing or approximate for part of the route.')

meta = {'run_id': run['run_id'], 'run_id_short': run['run_id'][:8], 'platform': dev.get('platform'), 'device': dev.get('model'), 'os': f'{"iOS" if dev.get("platform") == "ios" else "Android"} {dev.get("os_version")}',
        'app_version': dev.get('app_version'), 'start_ms': T0, 'end_ms': T1, 'tz_offset_min': TZMIN, 'mobile_operator': operator,
        'title': f'{run.get("name") or "Network test"} · {hm(T0)}–{hm(T1)}' + (f': {ssid} vs {operator} mobile' if wp else ''),
        'scope': f'{(T1 - T0) / 60000:.0f} min of continuous testing; ' + '; '.join(f'{s["label"]} {hm(s["start"])}–{hm(s["end"])}' for s in segs) + f'. {len(P):,} probes, {len(thr)} throughput tests, {len(RAD)} radio samples, {len(L):,} location fixes.',
        'source_note': f'Source: Tracer bundle {run["run_id"]} · generated by Tracer report v1.'}

rail = json.load(open(a.rail)) if a.rail else {}
basemap = json.load(open(a.basemap)) if a.basemap else None
gaps, labels = [], []
if len(L):
    dt = L.t_utc_ms.diff().fillna(0)
    for i in np.where(dt > 300000)[0]:
        gaps.append({'a': [L.lat[i - 1], L.lon[i - 1]], 'b': [L.lat[i], L.lon[i]], 'kind': 'nofix', 'label': f'{hm(L.t_utc_ms[i - 1])}–{hm(L.t_utc_ms[i])} no fixes'})
    labels = [{'lat': L.lat.iloc[0], 'lon': L.lon.iloc[0], 'text': f'Start {hm(T0)}', 'anchor': 'start', 'dy': -6},
              {'lat': L.lat.iloc[-1], 'lon': L.lon.iloc[-1], 'text': f'Last fix {hm(L.t_utc_ms.iloc[-1])}', 'anchor': 'end', 'dy': 14}]
    if T1 - L.t_utc_ms.iloc[-1] > 300000 and rail.get('destination'):
        dsn = rail['destination']; gaps.append({'a': [L.lat.iloc[-1], L.lon.iloc[-1]], 'b': [dsn['lat'], dsn['lon']], 'kind': 'unrecorded', 'label': 'not recorded'})
for s in rail.get('stations', []): labels.append({'lat': s['lat'], 'lon': s['lon'], 'text': s['name'], 'anchor': s.get('anchor', 'start'), 'dy': s.get('dy', 0)}) if s.get('label') else None

data = {'meta': meta, 'kpis': kp, 'paths': paths, 'segments': segs,
        'health_bins': {'t0': T0, 'bin_ms': BIN, 'codes': codes}, 'rat': rat, 'rat_share': rat_share,
        'rat_note': 'iOS does not expose RSRP/SINR; signal levels need the Android companion.' if dev.get('platform') == 'ios' else '',
        'connect_series': {k: series_connect(k) for k in ('cellular', 'wifi')}, 'failures': {k: series_fail(k) for k in ('cellular', 'wifi')},
        'stalls': stalls, 'mobile_drops': mob_drops,
        'throughput': [{'t': int(r.t_utc_ms), 'path': r.interface, 'mbps': fin(r.mbps), 'ok': bool(r.success)} for r in thr.itertuples()],
        'fixes_per_min': fpm, 'track': {'fixes': fixes},
        'map': {'basemap': basemap, 'rail_lines': rail.get('lines', []), 'stations': rail.get('stations', []), 'labels': labels, 'gaps': gaps,
                'attribution': ' · '.join(x for x in [(basemap or {}).get('attribution'), rail.get('attribution')] if x)},
        'wifi_layers': layers, 'endpoints': eps, 'endpoint_paths': [f'{operator} mobile', 'Wi-Fi'] if wp else [f'{operator} mobile', '—'],
        'phases': phases, 'quality': quality, 'findings': findings,
        'recommendations': {'Next test': ['Keep recording until arrival; auto-stop only inside a station geofence.', 'Run the Android companion for RSRP/SINR, cell ID, Wi-Fi RSSI and raw GNSS.', 'Repeat at the same time of day with Wi-Fi-only and mobile-only legs.']}}
json.dump(data, open(a.out, 'w'), separators=(',', ':'), default=lambda o: o.item() if hasattr(o, 'item') else str(o))
print('wrote', a.out, os.path.getsize(a.out) // 1024, 'KB')
if a.pdf:
    html = open(a.template).read().replace('__REPORT_DATA__', open(a.out).read().replace('</', '<\\/'))
    tmp = a.pdf + '.html'; open(tmp, 'w').write(html)
    subprocess.run(['google-chrome', '--headless=new', '--no-sandbox', '--disable-gpu', '--no-pdf-header-footer', f'--print-to-pdf={a.pdf}', 'file://' + os.path.abspath(tmp)], check=True, capture_output=True)
    os.remove(tmp); print('wrote', a.pdf)
