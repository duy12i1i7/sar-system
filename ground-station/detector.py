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
import paho.mqtt.client as mqtt
from ultralytics import YOLO

PI_HOST = os.environ.get("PI_HOST", "10.10.10.2")
MODEL = os.environ.get("YOLO_MODEL", "yolo26s.pt")
STREAMS = os.environ.get("STREAMS", "drone-1,drone-2,drone-3,gcs").split(",")
FPS = float(os.environ.get("DETECT_FPS", "3"))
CONF = float(os.environ.get("DETECT_CONF", "0.35"))
IMGSZ = int(os.environ.get("DETECT_IMGSZ", "640"))
HTTP_PORT = int(os.environ.get("HTTP_PORT", "8091"))
JPEG_W = 480  # panel nho, khong can to

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
            cap.release()
            self.ok = False

    def get(self):
        with self.lock:
            return None if self.frame is None else self.frame.copy()

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
    print(f"[detector] nap model {MODEL} ...", flush=True)
    model = YOLO(MODEL)
    import torch
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"[detector] thiet bi = {dev}"
          f"{' (' + torch.cuda.get_device_name(0) + ')' if dev == 'cuda' else ''}", flush=True)

    cli = mqtt.Client()
    try:
        cli.connect(PI_HOST, 1883, 30)
        cli.loop_start()
        print(f"[detector] MQTT -> {PI_HOST}:1883", flush=True)
    except Exception as e:
        print(f"[detector] MQTT loi: {e}", flush=True)

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
    }
    StatusChecker(ai_info).start()

    srv = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), MjpegHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print(f"[detector] MJPEG tai http://0.0.0.0:{HTTP_PORT}/stream/<ten>", flush=True)

    period = 1.0 / FPS
    names = model.names
    while True:
        t0 = time.time()
        for s, g in grabbers.items():
            frame = g.get()
            if frame is None:
                continue
            h, w = frame.shape[:2]
            small = cv2.resize(frame, (JPEG_W, int(h * JPEG_W / w)))
            try:
                r = model.predict(small, imgsz=IMGSZ, conf=CONF, device=dev, verbose=False)[0]
            except Exception as e:
                print(f"[detector] {s}: loi suy luan: {e}", flush=True)
                continue

            annotated = r.plot()
            ok, buf = cv2.imencode(".jpg", annotated, [cv2.IMWRITE_JPEG_QUALITY, 70])
            if ok:
                with latest_lock:
                    latest[s] = (buf.tobytes(), time.time())

            labels = {}
            for c in r.boxes.cls.tolist():
                n = names[int(c)]
                labels[n] = labels.get(n, 0) + 1
            payload = {
                "stream": s,
                "count": len(r.boxes),
                "labels": labels,
                "top": max(labels, key=labels.get) if labels else None,
                "ts": int(time.time() * 1000),
            }
            try:
                cli.publish(f"drone/detect/{s}", json.dumps(payload), qos=0)
            except Exception:
                pass

        dt = time.time() - t0
        if dt < period:
            time.sleep(period - dt)


if __name__ == "__main__":
    main()
