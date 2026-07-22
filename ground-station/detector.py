#!/usr/bin/env python3
"""
Chay YOLO26 tren cac luong cua MediaMTX, phuc vu panel AI DETECTION cua dashboard.

Kien truc:
  MediaMTX --RTSP--> detector --+-- ve khung -> JPEG -> HTTP MJPEG  :8091/stream/<ten>
                                +-- nhan/so luong -> MQTT drone/detect/<ten>

Vi sao anh di HTTP chu khong di MQTT: nhet JPEG base64 vao MQTT la ~300KB/s moi luong,
se lam nghen chinh kenh telemetry dang dung chung broker.
"""
import json
import os
import socket
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2
import numpy as np
import paho.mqtt.client as mqtt
from ultralytics import YOLO

# 07/2026: da bo Raspberry Pi. MediaMTX + MQTT chay CHUNG may voi detector nen mac dinh
# la localhost -- doc RTSP qua loopback, khong con di qua wifi.
PI_HOST = os.environ.get("PI_HOST", "127.0.0.1")
MODEL = os.environ.get("YOLO_MODEL", "yolo26s.pt")           # model NGUOI (COCO)
FIRE_MODEL = os.environ.get("FIRE_MODEL", "")               # model LUA/KHOI (Fire/Smoke); rong = tat
STREAMS = os.environ.get("STREAMS", "drone-1,drone-2,drone-3,gcs").split(",")
FPS = float(os.environ.get("DETECT_FPS", "3"))
CONF = float(os.environ.get("DETECT_CONF", "0.35"))         # nguong NGUOI
FIRE_CONF = float(os.environ.get("FIRE_CONF", "0.4"))       # nguong LUA/KHOI
IMGSZ = int(os.environ.get("DETECT_IMGSZ", "640"))
HTTP_PORT = int(os.environ.get("HTTP_PORT", "8091"))
JPEG_W = int(os.environ.get("DISPLAY_W", "640"))            # be rong khung annotate phat ra

AP_HOST = os.environ.get("AP_HOST", "10.10.10.10")

# ten luong -> (jpeg bytes, thoi diem)
latest: dict[str, tuple[bytes, float]] = {}
latest_lock = threading.Lock()

# Ket qua kiem tra he thong, do StatusChecker cap nhat nen, /status chi doc cache
# -> HTTP tra ve tuc thi, khong bat trinh duyet cho ping/DNS.
status: dict = {"internet": False, "ap": False, "ai": {"ok": False}, "gps": None, "ts": 0}


class Grabber(threading.Thread):
    """
    Doc RTSP, LUON giu khung MOI NHAT.

    OpenCV dem san khung; neu cu read() tuan tu ma xu ly cham hon nguon thi hang doi
    day dan -> panel detect tre cham hon video live vai giay. Nen thread nay read()
    lien tuc va chi giu khung cuoi, con thread suy luan lay khung do.
    """

    def __init__(self, url: str):
        super().__init__(daemon=True)
        self.url = url
        self.frame = None
        self.frame_ts = 0.0          # thoi diem khung cuoi doc duoc; de phat hien nguon chet
        self.ok = False
        self.lock = threading.Lock()
        self.running = True

    def run(self):
        while self.running:
            cap = cv2.VideoCapture(self.url, cv2.CAP_FFMPEG)
            try:
                cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
            except Exception:
                pass
            if not cap.isOpened():
                self.ok = False
                time.sleep(3)  # luong chua phat -> thu lai
                continue
            self.ok = True
            fails = 0
            while self.running:
                ret, f = cap.read()
                if not ret:
                    fails += 1
                    if fails > 30:
                        break  # nguon chet -> mo lai
                    time.sleep(0.05)
                    continue
                fails = 0
                with self.lock:
                    self.frame = f
                    self.frame_ts = time.time()
            cap.release()
            self.ok = False
            # Nguon chet -> BO khung cu, khong phuc vu stale (neu khong /snap va MQTT
            # se treo nguoi/khung cuoi mai mai du LIVE da mat tin hieu).
            with self.lock:
                self.frame = None
                self.frame_ts = 0.0

    def get(self):
        with self.lock:
            # Qua 2.5s khong co khung moi = nguon dung/chet -> coi nhu mat tin hieu.
            if self.frame is None or time.time() - self.frame_ts > 2.5:
                return None
            return self.frame.copy()

    def stop(self):
        self.running = False


class StatusChecker(threading.Thread):
    """Kiem tra ha tang dinh ky. Chay nen vi ping/HTTP deu co the treo vai giay."""

    def __init__(self, ai_info: dict):
        super().__init__(daemon=True)
        self.ai_info = ai_info
        self.gps = None
        self.gps_at = 0.0

    @staticmethod
    def _internet() -> bool:
        # Endpoint chuan de kiem tra captive portal: co internet THAT (khong bi chan
        # boi trang dang nhap) thi tra ve 204 rong.
        try:
            r = urllib.request.urlopen(
                "http://connectivitycheck.gstatic.com/generate_204", timeout=5)
            return r.status == 204
        except Exception:
            return False

    @staticmethod
    def _ap_up() -> bool:
        # Ping ICMP thay vi TCP: khong biet AP mo cong nao, ma ping thi luon tra loi.
        try:
            r = subprocess.run(["ping", "-c", "1", "-W", "2", AP_HOST],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               timeout=5)
            return r.returncode == 0
        except Exception:
            return False

    def _locate(self):
        # Vi tri theo IP (thanh pho), du cho panel. Chi lay lai moi 10 phut va chi khi
        # co internet - IP cong cong hau nhu khong doi.
        if self.gps and time.time() - self.gps_at < 600:
            return self.gps
        try:
            r = urllib.request.urlopen("http://ip-api.com/json/?fields=status,lat,lon,city", timeout=6)
            d = json.load(r)
            if d.get("status") == "success":
                self.gps = {"lat": d["lat"], "lon": d["lon"], "city": d.get("city"), "source": "ip"}
                self.gps_at = time.time()
        except Exception:
            pass
        return self.gps

    def run(self):
        while True:
            net = self._internet()
            status["internet"] = net
            status["ap"] = self._ap_up()
            status["ai"] = self.ai_info
            status["gps"] = self._locate() if net else self.gps
            status["ts"] = int(time.time() * 1000)
            time.sleep(10)


class MjpegHandler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass  # khong spam log

    def do_GET(self):
        # /stream/<ten> = MJPEG lien tuc; /snap/<ten> = 1 anh
        if self.path.split("?")[0] == "/status":
            body = json.dumps(status).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        parts = self.path.strip("/").split("/")
        if len(parts) != 2 or parts[0] not in ("stream", "snap"):
            self.send_error(404)
            return
        name = parts[1].split("?")[0]

        if parts[0] == "snap":
            item = latest.get(name)
            if not item:
                self.send_error(503)
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(item[0])
            return

        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=f")
        self.end_headers()
        last_ts = 0.0
        try:
            while True:
                item = latest.get(name)
                if not item or item[1] == last_ts:
                    time.sleep(0.05)
                    continue
                jpg, last_ts = item
                self.wfile.write(b"--f\r\nContent-Type: image/jpeg\r\n"
                                 b"Content-Length: " + str(len(jpg)).encode() + b"\r\n\r\n")
                self.wfile.write(jpg)
                self.wfile.write(b"\r\n")
        except (BrokenPipeError, ConnectionResetError):
            pass  # trinh duyet dong tab


def main():
    print(f"[detector] nap model NGUOI {MODEL} ...", flush=True)
    model = YOLO(MODEL)
    model_fire = None
    if FIRE_MODEL:
        print(f"[detector] nap model LUA/KHOI {FIRE_MODEL} ...", flush=True)
        model_fire = YOLO(FIRE_MODEL)
    import torch
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[detector] thiet bi = {dev}"
          f"{' (' + torch.cuda.get_device_name(0) + ')' if dev == 'cuda' else ''}", flush=True)

    # connect_async + loop_start: KHONG chet neu broker chua toi duoc luc khoi dong
    # (vd may vua doi mang/VPN, hoac reboot detector len truoc broker). loop_start tu
    # ket noi lai voi backoff -> khi mang tro lai la MQTT tu song, khong can restart tay.
    cli = mqtt.Client()
    cli.reconnect_delay_set(min_delay=1, max_delay=30)
    cli.on_connect = lambda c, u, f, rc: print(f"[detector] MQTT connected rc={rc}", flush=True)
    cli.on_disconnect = lambda c, u, rc: print(f"[detector] MQTT mat ket noi rc={rc} (se tu noi lai)", flush=True)
    cli.loop_start()
    cli.connect_async(PI_HOST, 1883, 30)
    print(f"[detector] MQTT (async) -> {PI_HOST}:1883", flush=True)

    grabbers = {s: Grabber(f"rtsp://{PI_HOST}:8554/{s}") for s in STREAMS}
    for g in grabbers.values():
        g.start()

    # "AI Available" = chinh dich vu nay chay duoc model. Dich vu chet thi /status
    # khong tra loi -> dashboard tu bao do. Khong can kiem tra rieng.
    ai_info = {
        "ok": True,
        "device": dev,
        "gpu": torch.cuda.get_device_name(0) if dev == "cuda" else None,
        "model": MODEL,
        "fireModel": FIRE_MODEL or None,
    }
    StatusChecker(ai_info).start()

    srv = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), MjpegHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print(f"[detector] MJPEG tai http://0.0.0.0:{HTTP_PORT}/stream/<ten>", flush=True)

    # Khung "NO SIGNAL" phat khi nguon chet -> DETECT box + LIVE + radar dong bo.
    _blank = np.zeros((int(JPEG_W * 9 / 16), JPEG_W, 3), dtype=np.uint8)
    cv2.putText(_blank, "NO SIGNAL", (JPEG_W // 2 - 95, int(JPEG_W * 9 / 16) // 2),
                cv2.FONT_HERSHEY_SIMPLEX, 1.0, (90, 90, 90), 2)
    _ok, _nb = cv2.imencode(".jpg", _blank, [cv2.IMWRITE_JPEG_QUALITY, 60])
    NOSIG_JPEG = _nb.tobytes() if _ok else b""

    period = 1.0 / FPS
    names = model.names
    fire_names = model_fire.names if model_fire is not None else {}
    FONT = cv2.FONT_HERSHEY_SIMPLEX
    while True:
        t0 = time.time()
        for stream, g in grabbers.items():
            frame = g.get()
            if frame is None:
                # Nguon chet/chua phat: phat khung NO SIGNAL, publish rong voi live:false
                # -> radar/o dem/DETECT box KHONG con treo nguoi cu.
                with latest_lock:
                    latest[stream] = (NOSIG_JPEG, time.time())
                dead = {
                    "stream": stream, "people": 0,
                    "fire": {"on": False, "conf": 0.0}, "smoke": {"on": False, "conf": 0.0},
                    "objects": [], "live": False, "ts": int(time.time() * 1000),
                }
                try:
                    cli.publish(f"drone/detect/{stream}", json.dumps(dead), qos=0)
                except Exception:
                    pass
                continue
            h, w = frame.shape[:2]
            small = cv2.resize(frame, (JPEG_W, int(h * JPEG_W / w)))
            annotated = small.copy()
            H, W = small.shape[:2]
            objects = []

            # --- NGUOI ---
            people = 0
            try:
                rp = model.predict(small, imgsz=IMGSZ, conf=CONF, device=dev, verbose=False)[0]
                for box, cls, cf in zip(rp.boxes.xyxy.tolist(), rp.boxes.cls.tolist(), rp.boxes.conf.tolist()):
                    if names[int(cls)] != "person":
                        continue  # demo cuu ho: chi quan tam NGUOI, bo ghe/sach...
                    people += 1
                    x1, y1, x2, y2 = map(int, box)
                    objects.append({"label": "person", "cx": round((x1 + x2) / 2 / W, 3), "by": round(y2 / H, 3), "conf": round(cf, 2)})
                    cv2.rectangle(annotated, (x1, y1), (x2, y2), (0, 220, 0), 2)
                    cv2.putText(annotated, f"PERSON {cf:.2f}", (x1, max(12, y1 - 6)),
                                FONT, 0.5, (0, 220, 0), 2)
            except Exception as e:
                print(f"[detector] {stream}: loi model nguoi: {e}", flush=True)

            # --- LUA / KHOI ---
            fire_conf = smoke_conf = 0.0
            if model_fire is not None:
                try:
                    rf = model_fire.predict(small, imgsz=IMGSZ, conf=FIRE_CONF, device=dev, verbose=False)[0]
                    for box, cls, cf in zip(rf.boxes.xyxy.tolist(), rf.boxes.cls.tolist(), rf.boxes.conf.tolist()):
                        label = str(fire_names[int(cls)]).lower()
                        x1, y1, x2, y2 = map(int, box)
                        objects.append({"label": label, "cx": round((x1 + x2) / 2 / W, 3), "by": round(y2 / H, 3), "conf": round(cf, 2)})
                        if label.startswith("fire"):
                            fire_conf = max(fire_conf, cf); color = (0, 60, 255)
                        else:
                            smoke_conf = max(smoke_conf, cf); color = (170, 170, 170)
                        cv2.rectangle(annotated, (x1, y1), (x2, y2), color, 2)
                        cv2.putText(annotated, f"{label.upper()} {cf:.2f}", (x1, max(12, y1 - 6)),
                                    FONT, 0.5, color, 2)
                except Exception as e:
                    print(f"[detector] {stream}: loi model lua: {e}", flush=True)

            ok, buf = cv2.imencode(".jpg", annotated, [cv2.IMWRITE_JPEG_QUALITY, 72])
            if ok:
                with latest_lock:
                    latest[stream] = (buf.tobytes(), time.time())

            payload = {
                "stream": stream,
                "live": True,
                "people": people,
                "fire": {"on": fire_conf > 0, "conf": round(fire_conf, 2)},
                "smoke": {"on": smoke_conf > 0, "conf": round(smoke_conf, 2)},
                "objects": objects[:16],
                "ts": int(time.time() * 1000),
            }
            try:
                cli.publish(f"drone/detect/{stream}", json.dumps(payload), qos=0)
            except Exception:
                pass

        dt = time.time() - t0
        if dt < period:
            time.sleep(period - dt)


if __name__ == "__main__":
    main()
