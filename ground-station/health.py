#!/usr/bin/env python3
"""
Health-check + tu dong restart dich vu cua tram SAR, kem trang trang thai HTTP :8095.

VI SAO CAN, KHI systemd DA CO Restart=always:
  Restart= chi cuu duoc khi TIEN TRINH CHET. Ca kho chiu hon trong thuc te la tien trinh
  VAN SONG nhung khong con phuc vu: MediaMTX con day nhung khong nhan RTMP, detector treo
  o vong doc RTSP nen khong ban MQTT nua, broker con listen nhung khong hoan tat handshake.
  systemd khong biet gi. Nen o day kiem tra CHUC NANG THAT: bat tay TCP, goi API va
  round-trip MQTT (gui mot nonce roi doi chinh no quay ve).

CHONG THRASH:
  - Phai HONG LIEN TIEP `FAIL_THRESHOLD` lan moi restart (bo qua truc trac thoang qua).
  - Sau khi restart, `COOLDOWN_S` khong tinh diem hong (cho dich vu khoi dong xong).
  - Toi da `MAX_RESTARTS_PER_HOUR` lan/dich vu; vuot nguong thi NGUNG restart va bao
    "can nguoi xem" - restart vo han chi giau di loi that va quay may.

QUYEN: chay bang user ubuntu, restart qua `sudo -n systemctl restart <unit>` voi
/etc/sudoers.d/sar-health cap NOPASSWD dung 5 lenh do. KHONG nhet mat khau vao code.
"""
import json
import os
import socket
import subprocess
import threading
import time
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import paho.mqtt.client as mqtt

HTTP_PORT = int(os.environ.get("HEALTH_PORT", "8095"))
INTERVAL_S = float(os.environ.get("HEALTH_INTERVAL", "15"))
FAIL_THRESHOLD = int(os.environ.get("HEALTH_FAILS", "3"))
COOLDOWN_S = float(os.environ.get("HEALTH_COOLDOWN", "120"))
MAX_RESTARTS_PER_HOUR = int(os.environ.get("HEALTH_MAX_RESTARTS", "4"))
DETECT_STALE_S = float(os.environ.get("HEALTH_DETECT_STALE", "25"))

MQTT_HOST = "127.0.0.1"
# RLock chu khong Lock: log_event() cung lay khoa nay, ma co cho goi log_event khi
# dang giu khoa -> Lock thuong se tu khoa chinh minh (da dinh deadlock that mot lan).
state_lock = threading.RLock()
events: list = []          # nhat ky hanh dong, moi nhat truoc
last_detect_ts = [0.0]     # thoi diem nhan ban tin drone/detect/# gan nhat


def log_event(kind: str, service: str, msg: str):
    with state_lock:
        events.insert(0, {"ts": time.time(), "kind": kind, "service": service, "msg": msg})
        del events[200:]
    print(f"[health] {kind} {service}: {msg}", flush=True)


# ---------------------------------------------------------------- probes

def _tcp(host: str, port: int, timeout=3.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout):
            return True
    except OSError:
        return False


def _http_json(url: str, timeout=5.0):
    r = urllib.request.urlopen(url, timeout=timeout)
    return json.load(r)


def probe_mediamtx():
    if not _tcp("127.0.0.1", 1935):
        return False, "khong bat tay duoc RTMP :1935"
    try:
        d = _http_json("http://127.0.0.1:9997/v3/paths/list")
        items = d.get("items", [])
        ready = {i["name"] for i in items if i.get("ready")}
    except Exception as e:
        return False, f"API :9997 loi: {e}"

    # Luong goc dang phat thi nhanh "-web" (do runOnReady transcode) cung phai len.
    # Neu khong, dashboard se den si ma khong ai bao -> coi nhu MediaMTX hong.
    missing = [n for n in ready if not n.endswith("-web") and f"{n}-web" not in ready]
    if missing:
        return False, f"dang phat {', '.join(missing)} nhung thieu nhanh -web (transcode chet?)"
    live = sorted(ready) or ["(khong co luong nao)"]
    return True, f"{len(items)} path, dang phat: {', '.join(live[:4])}"


def probe_mqtt():
    """Round-trip that: publish mot nonce roi doi no quay lai qua subscribe."""
    nonce = uuid.uuid4().hex
    got = threading.Event()

    def on_msg(_c, _u, m):
        if m.payload.decode("utf-8", "ignore") == nonce:
            got.set()

    c = mqtt.Client()
    c.on_message = on_msg
    try:
        c.connect(MQTT_HOST, 1883, 5)
        c.subscribe("sar/health/probe")
        c.loop_start()
        c.publish("sar/health/probe", nonce)
        ok = got.wait(5.0)
        c.loop_stop()
        c.disconnect()
        return (True, "round-trip OK") if ok else (False, "publish roi nhung khong nhan lai")
    except Exception as e:
        try:
            c.loop_stop()
        except Exception:
            pass
        return False, f"khong ket noi duoc broker: {e}"


def probe_web():
    try:
        d = _http_json("http://127.0.0.1:8080/api/streams")
        return True, f"{d.get('itemCount', len(d.get('items', [])))} path"
    except Exception as e:
        return False, f":8080 loi: {e}"


def probe_detector(mqtt_ok: bool):
    try:
        d = _http_json("http://127.0.0.1:8091/status")
    except Exception as e:
        return False, f"/status loi: {e}"
    if not d.get("ai", {}).get("ok"):
        return False, "model AI bao khong san sang"
    dev = d.get("ai", {}).get("device", "?")
    # Detector ban MQTT lien tuc (ke ca khi khong co luong: live=false). Im lang = treo.
    # Chi tinh khi broker con song, neu khong se do oan detector khi loi thuc su o broker.
    if mqtt_ok:
        age = time.time() - last_detect_ts[0]
        if last_detect_ts[0] == 0:
            return True, f"{dev} (chua nghe duoc drone/detect - moi khoi dong?)"
        if age > DETECT_STALE_S:
            return False, f"khong ban MQTT {int(age)}s (treo vong doc RTSP?)"
        return True, f"{dev}, MQTT moi {int(age)}s truoc"
    return True, f"{dev} (bo qua kiem tra MQTT vi broker dang hong)"


def probe_dashboard():
    try:
        r = urllib.request.urlopen("http://127.0.0.1:5173/", timeout=5)
        return (r.status == 200), f"HTTP {r.status}"
    except Exception as e:
        return False, f":5173 loi: {e}"


SERVICES = [
    {"key": "mediamtx", "unit": "mediamtx", "label": "MediaMTX", "desc": "RTMP 1935 · RTSP 8554 · WebRTC 8889"},
    {"key": "mosquitto", "unit": "mosquitto", "label": "MQTT broker", "desc": "Mosquitto 1883 · WebSocket 9001"},
    {"key": "mediamtx-web", "unit": "mediamtx-web", "label": "Stream Manager", "desc": "Quan ly luong :8080"},
    {"key": "drone-detector", "unit": "drone-detector", "label": "AI Detector", "desc": "YOLO26 nguoi + lua :8091"},
    {"key": "dashboard", "unit": "drone-dashboard", "label": "Dashboard", "desc": "Vite :5173"},
]

status = {
    s["key"]: {
        "label": s["label"], "desc": s["desc"], "unit": s["unit"],
        "ok": None, "detail": "chua kiem tra", "fails": 0,
        "last_ok": 0.0, "last_check": 0.0,
        "restarts": [], "last_restart": 0.0, "last_restart_reason": "",
        "gave_up": False, "cooldown_until": 0.0,
    } for s in SERVICES
}


def systemd_active(unit: str) -> str:
    try:
        r = subprocess.run(["systemctl", "is-active", unit], capture_output=True, text=True, timeout=8)
        return r.stdout.strip() or "unknown"
    except Exception:
        return "unknown"


def restart(unit: str) -> tuple:
    try:
        r = subprocess.run(["sudo", "-n", "systemctl", "restart", unit],
                           capture_output=True, text=True, timeout=60)
        if r.returncode == 0:
            return True, "restart OK"
        return False, (r.stderr or r.stdout).strip()[:200]
    except Exception as e:
        return False, str(e)


def check_once():
    ok_mqtt, det_mqtt = probe_mqtt()
    results = {
        "mediamtx": probe_mediamtx(),
        "mosquitto": (ok_mqtt, det_mqtt),
        "mediamtx-web": probe_web(),
        "drone-detector": probe_detector(ok_mqtt),
        "dashboard": probe_dashboard(),
    }
    now = time.time()
    for key, (ok, detail) in results.items():
        st = status[key]
        # Goi systemctl NGOAI khoa: no la subprocess, giu khoa trong luc do se chan
        # ca trang web (handler cung can khoa) va lam /api/health treo.
        active = systemd_active(st["unit"])
        with state_lock:
            st["last_check"] = now
            st["detail"] = detail
            st["active"] = active
            st["restarts"] = [t for t in st["restarts"] if now - t < 3600]
            in_cooldown = now < st["cooldown_until"]

        if ok:
            with state_lock:
                st["ok"] = True
                st["last_ok"] = now
                had_failed = st["fails"]
                st["fails"] = 0
                st["gave_up"] = False
            if had_failed:
                log_event("recovered", key, f"khoe lai sau {had_failed} lan hong")
            continue

        with state_lock:
            st["ok"] = False
            if in_cooldown:
                continue
            st["fails"] += 1
            fails = st["fails"]
            gave_up = st["gave_up"]
            n_recent = len(st["restarts"])

        if fails < FAIL_THRESHOLD or gave_up:
            if fails == 1:
                log_event("fail", key, detail)
            continue

        if n_recent >= MAX_RESTARTS_PER_HOUR:
            with state_lock:
                st["gave_up"] = True
            log_event("gaveup", key,
                      f"da restart {n_recent} lan/gio ma van hong - NGUNG tu restart, can nguoi xem")
            continue

        log_event("restarting", key, f"hong {fails} lan lien tiep ({detail}) -> restart {st['unit']}")
        rok, rmsg = restart(st["unit"])
        with state_lock:
            st["restarts"].append(time.time())
            st["last_restart"] = time.time()
            st["last_restart_reason"] = detail
            st["cooldown_until"] = time.time() + COOLDOWN_S
            st["fails"] = 0
        log_event("restarted" if rok else "restart_failed", key, rmsg)


# ---------------------------------------------------------------- he thong

_sys_cache = {"at": 0.0, "data": {}}


def system_info():
    # Cache 4s: trang tu refresh 5s/lan va nvidia-smi la subprocess, khong nen goi
    # lai moi request (nhieu tab mo cung luc la thanh spam).
    if time.time() - _sys_cache["at"] < 4:
        return _sys_cache["data"]
    info = {}
    try:
        with open("/proc/uptime") as f:
            info["uptime_s"] = float(f.read().split()[0])
        info["load"] = os.getloadavg()
        with open("/proc/meminfo") as f:
            mi = {l.split(":")[0]: int(l.split()[1]) for l in f if ":" in l}
        info["ram_used_pct"] = round(100 * (1 - mi.get("MemAvailable", 0) / max(1, mi.get("MemTotal", 1))))
    except Exception:
        pass
    try:
        d = os.statvfs("/")
        info["disk_used_pct"] = round(100 * (1 - d.f_bavail / d.f_blocks))
    except Exception:
        pass
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=name,temperature.gpu,utilization.gpu,memory.used,memory.total",
             "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=6).stdout.strip()
        if out:
            n, t, u, mu, mt = [x.strip() for x in out.split(",")]
            info["gpu"] = {"name": n, "temp": int(t), "util": int(u), "mem_used": int(mu), "mem_total": int(mt)}
    except Exception:
        pass
    _sys_cache.update(at=time.time(), data=info)
    return info


# ---------------------------------------------------------------- web

PAGE = """<!doctype html><html lang="vi"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SAR — Trang thai he thong</title><style>
:root{--bg:#0f1115;--panel:rgba(26,29,36,.72);--bd:rgba(255,255,255,.1);--acc:#00f0ff;
--ok:#00ff88;--bad:#ff3366;--warn:#ffb800;--mut:#8b92a5}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:Inter,system-ui,sans-serif;background:var(--bg);color:#fff;padding:22px;
background-image:radial-gradient(circle at 50% 0,#1a1f2e,var(--bg) 55%);min-height:100vh}
h1{font-size:1.15rem;letter-spacing:.09em;color:var(--acc);margin-bottom:2px}
.sub{color:var(--mut);font-size:.78rem;margin-bottom:18px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(320px,1fr));gap:14px}
.card{background:var(--panel);border:1px solid var(--bd);border-radius:12px;padding:14px 16px}
.card.ok{border-color:rgba(0,255,136,.45)}.card.bad{border-color:var(--bad);background:rgba(255,51,102,.09)}
.card.gave{border-color:var(--warn);background:rgba(255,184,0,.09)}
.row{display:flex;justify-content:space-between;align-items:center;gap:10px}
.nm{font-weight:700;letter-spacing:.03em}
.badge{font-size:.68rem;font-weight:700;padding:3px 9px;border-radius:20px}
.b-ok{background:rgba(0,255,136,.16);color:var(--ok)}.b-bad{background:rgba(255,51,102,.18);color:var(--bad)}
.b-warn{background:rgba(255,184,0,.18);color:var(--warn)}.b-mut{background:rgba(255,255,255,.1);color:var(--mut)}
.desc{color:var(--mut);font-size:.72rem;margin-top:3px}
.det{font-family:ui-monospace,monospace;font-size:.74rem;margin-top:9px;color:#c7d2dc;
word-break:break-word;border-top:1px solid var(--bd);padding-top:8px}
.meta{color:var(--mut);font-size:.68rem;margin-top:6px;display:flex;gap:12px;flex-wrap:wrap}
.sys{margin:20px 0 8px;display:flex;gap:10px;flex-wrap:wrap}
.chip{background:var(--panel);border:1px solid var(--bd);border-radius:9px;padding:8px 13px;font-size:.76rem}
.chip b{color:var(--acc);font-family:ui-monospace,monospace}
h2{font-size:.8rem;letter-spacing:.09em;color:var(--mut);margin:22px 0 9px}
.ev{background:var(--panel);border:1px solid var(--bd);border-radius:10px;overflow:hidden}
.ev div{display:flex;gap:12px;padding:7px 13px;font-size:.74rem;border-bottom:1px solid var(--bd)}
.ev div:last-child{border:0}.ev time{color:var(--mut);font-family:ui-monospace,monospace;flex:0 0 68px}
.k{flex:0 0 92px;font-weight:700}.k-restarting,.k-restarted{color:var(--warn)}
.k-fail,.k-restart_failed,.k-gaveup{color:var(--bad)}.k-recovered{color:var(--ok)}
</style></head><body>
<h1>SAR — TRANG THAI HE THONG</h1>
<div class="sub" id="sub">dang tai…</div>
<div class="sys" id="sys"></div>
<div class="grid" id="grid"></div>
<h2>NHAT KY (health tu xu ly)</h2>
<div class="ev" id="ev"></div>
<script>
const hhmm=t=>new Date(t*1000).toLocaleTimeString('vi-VN');
const ago=t=>{if(!t)return '--';const s=Math.round(Date.now()/1000-t);
 return s<60?s+'s':s<3600?Math.round(s/60)+'m':Math.round(s/3600)+'h';};
async function tick(){
 let d;try{d=await (await fetch('./api/health',{cache:'no-store'})).json()}catch(e){
   document.getElementById('sub').textContent='mat ket noi toi health service';return}
 const s=d.system||{};
 document.getElementById('sub').textContent=
  `cap nhat ${hhmm(d.now)} · kiem tra moi ${d.interval}s · restart sau ${d.fail_threshold} lan hong lien tiep`;
 const chips=[];
 if(s.uptime_s!=null)chips.push(`uptime <b>${Math.floor(s.uptime_s/3600)}h${Math.floor(s.uptime_s%3600/60)}m</b>`);
 if(s.load)chips.push(`load <b>${s.load.map(x=>x.toFixed(2)).join(' ')}</b>`);
 if(s.ram_used_pct!=null)chips.push(`RAM <b>${s.ram_used_pct}%</b>`);
 if(s.disk_used_pct!=null)chips.push(`disk <b>${s.disk_used_pct}%</b>`);
 if(s.gpu)chips.push(`GPU <b>${s.gpu.temp}&deg;C</b> · util <b>${s.gpu.util}%</b> · vram <b>${s.gpu.mem_used}/${s.gpu.mem_total}MB</b>`);
 document.getElementById('sys').innerHTML=chips.map(c=>`<div class="chip">${c}</div>`).join('');
 document.getElementById('grid').innerHTML=d.services.map(x=>{
  const cls=x.gave_up?'gave':x.ok===true?'ok':x.ok===false?'bad':'';
  const bdg=x.gave_up?['b-warn','CAN NGUOI XEM']:x.ok===true?['b-ok','BINH THUONG']:
            x.ok===false?['b-bad','HONG']:['b-mut','...'];
  const rs=x.restarts_last_hour?`<span>restart 1h qua: <b>${x.restarts_last_hour}</b></span>`:'';
  const lr=x.last_restart?`<span>restart cuoi: ${ago(x.last_restart)} truoc</span>`:'';
  return `<div class="card ${cls}"><div class="row"><div><div class="nm">${x.label}</div>
   <div class="desc">${x.desc}</div></div><span class="badge ${bdg[0]}">${bdg[1]}</span></div>
   <div class="det">${x.detail}</div>
   <div class="meta"><span>unit: ${x.unit} (${x.active||'?'})</span>
   <span>OK cuoi: ${ago(x.last_ok)} truoc</span>${rs}${lr}</div></div>`}).join('');
 document.getElementById('ev').innerHTML = d.events.length
  ? d.events.map(e=>`<div><time>${hhmm(e.ts)}</time><span class="k k-${e.kind}">${e.kind}</span>
     <span>${e.service}</span><span style="color:#c7d2dc">${e.msg}</span></div>`).join('')
  : '<div style="color:#8b92a5">chua co su kien nao — moi thu van chay binh thuong</div>';
}
tick();setInterval(tick,5000);
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, body: bytes, ctype: str):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0].rstrip("/") or "/"
        if path in ("/", ""):
            return self._send(PAGE.encode(), "text/html; charset=utf-8")
        if path == "/api/health":
            now = time.time()
            with state_lock:
                svcs = []
                for s in SERVICES:
                    st = status[s["key"]]
                    svcs.append({
                        "key": s["key"], "label": st["label"], "desc": st["desc"], "unit": st["unit"],
                        "ok": st["ok"], "detail": st["detail"], "active": st.get("active"),
                        "last_ok": st["last_ok"], "last_check": st["last_check"],
                        "restarts_last_hour": len([t for t in st["restarts"] if now - t < 3600]),
                        "last_restart": st["last_restart"], "gave_up": st["gave_up"],
                    })
                evs = list(events[:40])
            body = json.dumps({
                "now": now, "interval": INTERVAL_S, "fail_threshold": FAIL_THRESHOLD,
                "services": svcs, "events": evs, "system": system_info(),
            }).encode()
            return self._send(body, "application/json")
        self.send_error(404)


# ---------------------------------------------------------------- main

def detect_listener():
    """Nghe drone/detect/# de biet detector con ban tin hay da treo.
    connect_async + loop_start -> tu noi lai khi broker restart (khong chet vinh vien)."""
    def on_connect(c, *_a):
        c.subscribe("drone/detect/#")

    def on_message(*_a):
        last_detect_ts[0] = time.time()

    c = mqtt.Client()
    c.on_connect = on_connect
    c.on_message = on_message
    c.reconnect_delay_set(min_delay=1, max_delay=30)
    c.loop_start()
    c.connect_async(MQTT_HOST, 1883, 30)


def main():
    print(f"[health] trang trang thai: http://0.0.0.0:{HTTP_PORT}/", flush=True)
    detect_listener()
    srv = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    log_event("start", "health", "bat dau giam sat")
    while True:
        t0 = time.time()
        try:
            check_once()
        except Exception as e:
            log_event("error", "health", f"vong kiem tra loi: {e}")
        time.sleep(max(1.0, INTERVAL_S - (time.time() - t0)))


if __name__ == "__main__":
    main()
