# SAR System — Drone Search & Rescue Ground Station

A three-part system for flying DJI drones with live video, telemetry and on-GPU object
detection streamed to a ground station dashboard.

```
   DJI Mini 3            Android phone            Raspberry Pi           Ubuntu station
  ┌──────────┐  OcuSync ┌───────────┐   RTMP    ┌────────────┐  WebRTC  ┌──────────────┐
  │  drone   │ ───────▶ │  RC + app │ ────────▶ │  MediaMTX  │ ───────▶ │  dashboard   │
  │  camera  │          │           │           │            │          │              │
  └──────────┘          │ telemetry │   MQTT    │  Mosquitto │   MQTT   │  + YOLO26    │
                        └───────────┘ ────────▶ └────────────┘ ───────▶ │   (GPU)      │
                                                                        └──────────────┘
                                                     10.10.10.2            10.10.10.3
```

| Component | Runs on | What it does |
|---|---|---|
| [`mobile-app/`](mobile-app) | Android phone on the RC | DJI Fly-style flight UI, publishes video over RTMP and telemetry over MQTT |
| [`streaming-server/`](streaming-server) | Raspberry Pi | MediaMTX (RTMP→WebRTC/RTSP), Mosquitto broker, web stream manager |
| [`ground-station/`](ground-station) | Ubuntu desktop w/ NVIDIA GPU | React dashboard (video, telemetry, radar) + YOLO26 detector |

---

## 1. Mobile app (Android)

Built on the **DJI Mobile SDK v5** and the UX SDK, restyled to look like DJI Fly.

**Features**
- Full flight UI: FPV, top bar, telemetry, camera controls, gimbal pitch slider, map/radar toggle
- Pilot + drone + home markers on the map, auto-fit so all three stay visible
- `STREAM` button: scans the streams defined on the Pi and lets you pick one to publish to
- Publishes telemetry to `drone/telemetry/<stream>` at 2 Hz: position, attitude, battery,
  real link quality (drone↔RC and RC↔station), and the pilot's GPS

### Custom RTMP publisher — and why

The app does **not** use DJI's `LiveStreamManager`. Its internal RTMP muxer emits the
`SEI` NAL as a **separate FLV video tag** with the same timestamp as the P-slice:

```
ts=0    frameType=1 len=37     <- sequence header (SPS/PPS)
ts=0    frameType=1 len=12309  -> IDR                  <- one tag, fine
ts=27   frameType=2 len=61     -> SEI                  <- separate tag
ts=27   frameType=2 len=2101   -> P-slice              <- another tag, same ts
```

Per spec, SEI and the slice belong to **one** access unit in **one** tag. MediaMTX treats
every FLV video tag as a complete access unit, so it forwards a bogus SEI-only unit into
WebRTC. Browsers then decode **keyframes only** and spam PLI (keyframe requests), giving
exactly **1 fps** — while the phone itself shows perfectly smooth video, because DJI's own
decoder tolerates it.

`RtmpPublisher.kt` takes raw H.264 straight from
`ICameraStreamManager.addReceiveStreamListener()` — before DJI's muxer touches it — packs the
whole access unit into one tag, and speaks RTMP directly. Result: **24 fps, zero PLI**.

It also auto-reconnects (1s→15s backoff) and keeps output timestamps monotonic across drone
power-cycles, when `presentationTimeMs` resets to zero.

### Build

```bash
cd mobile-app
cp local.properties.example local.properties   # then fill in your keys + sdk.dir
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You need your own **DJI SDK key** (bound to `applicationId`) and **Google Maps key**.
`local.properties` is git-ignored — never commit real keys.

---

## 2. Streaming server (Raspberry Pi)

- **MediaMTX** — RTMP in (`:1935`), WebRTC out (`:8889`), RTSP out (`:8554`), API (`:9997`)
- **Mosquitto** — MQTT (`:1883`), WebSocket for the browser (`:9001`)
- **web-manager** — small Flask app (`:8080`) to create/list/delete streams

### Stream paths must live in the config file

`mediamtx.yml` declares `drone-1`, `drone-2`, `drone-3`, `gcs` under `paths:`.

Paths created through the MediaMTX **API** (`POST /v3/config/paths/add/…`, which is what the
web manager uses) exist **only in memory** — a restart wipes them and leaves just
`all_others`. Anything permanent belongs in the file.

### Setup

```bash
./install_mediamtx.sh
sudo cp mediamtx.yml /opt/mediamtx/mediamtx.yml
sudo systemctl restart mediamtx
cd web-manager && python3 server.py    # or run under systemd
```

---

## 3. Ground station (Ubuntu + NVIDIA GPU)

### Dashboard (`dashboard/`, React + Vite)

- **3 live feeds + ground-station cam** over WebRTC/WHEP, each with a source picker
- Per-feed telemetry: battery, drone↔RC and RC↔station link quality, gimbal, altitude
- **AI DETECTION** — YOLO26 output for whatever source each feed is showing
- **RADAR** — station at the centre, drones and pilots around it, range auto-scales to fit
- **SYSTEM STATUS** — internet / access point / AI / video-ready / GPS, all real checks

Both the WebRTC player and the detection thumbnails **reconnect on their own**. A WHEP
session that dies (drone powered off > 10 s) otherwise leaves `<video>` frozen on the last
frame forever, and the detection images are polled per-frame rather than served as one
long-lived MJPEG connection for the same reason: every request stands alone, so nothing can
stay dead.

### Detector (`detector.py`)

```
MediaMTX --RTSP--> detector --+-- annotated JPEG -> HTTP  :8091/snap/<stream>
                              +-- labels/counts  -> MQTT  drone/detect/<stream>
```

Images travel over **HTTP, not MQTT** — base64 JPEG over MQTT is ~300 KB/s per stream and
would drown the telemetry channel sharing the broker.

The RTSP reader always keeps only the **newest** frame: OpenCV buffers frames, so reading
sequentially while inference runs slower than the source makes the panel drift seconds behind
the live view.

Runs ~3 fps on a GTX 1660 SUPER (~32 % GPU, ~580 MB VRAM).

### Setup

```bash
cd ground-station
python3 -m virtualenv detector-env
detector-env/bin/pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
detector-env/bin/pip install ultralytics paho-mqtt

sudo cp drone-detector.service /etc/systemd/system/
sudo systemctl enable --now drone-detector

cd dashboard && npm install && npm run dev -- --host
```

`run_detector.sh` holds the tunables: `YOLO_MODEL` (default `yolo26s.pt`, auto-downloaded),
`PI_HOST`, `DETECT_FPS`, `DETECT_CONF`, `DETECT_IMGSZ`, `AP_HOST`.

`start_dashboard.sh` launches Vite + a kiosk browser and is wired to XDG autostart. It relies
on running inside the graphical session for `DISPLAY`.

---

## Network

| Host | Address | Ports |
|---|---|---|
| Raspberry Pi | `10.10.10.2` | 1935 RTMP · 8554 RTSP · 8889 WebRTC · 9997 API · 1883/9001 MQTT · 8080 manager |
| Ubuntu station | `10.10.10.3` | 5173 dashboard · 8091 detector |
| Access point | `10.10.10.10` | health-checked by the dashboard |
| Android phone | DHCP | RTMP + MQTT client |

## MQTT topics

| Topic | Payload |
|---|---|
| `drone/telemetry/<stream>` | `latitude, longitude, altitude, pitch, roll, yaw, battery, linkDown, linkUp, wifiRssi, wifiPercent, pilotLat, pilotLon, pilotAcc` |
| `drone/detect/<stream>` | `count, labels{}, top, ts` |

## Known limitations

- **Station position is coarse.** The Ubuntu box has no GPS and is on Ethernet, so browser
  geolocation has no Wi-Fi networks to scan and falls back to IP lookup — roughly **1 km**
  off. IP geolocation alone is ~5 km off. For an accurate radar centre, hard-code the
  station's coordinates or use the drone's HOME position.
- The dashboard layout is wider than 1920 px and gets clipped on a 1080p screen.
- Audio is not streamed (video only).

## Licence

The `mobile-app/android-sdk-v5-uxsdk` module is DJI's UX SDK, redistributed under its own
licence (MIT — see the headers in its source files).
