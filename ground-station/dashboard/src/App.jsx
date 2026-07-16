import React, { useState, useEffect, useRef, useMemo } from 'react';
import { Battery, BatteryMedium, BatteryWarning, Wifi, Navigation, ShieldAlert, Cpu, Activity, Server, Radio, Crosshair } from 'lucide-react';
import mqtt from 'mqtt';
import './index.css';

const PI_HOST = '10.10.10.2';
const STREAMS_API = `http://${PI_HOST}:8080/api/streams`;
const WHEP_BASE = `http://${PI_HOST}:8889`;
const CATCH_ALL = 'all_others';

const EMPTY_TELEMETRY = {
  battery: 0, altitude: 0, pitch: 0, roll: 0, yaw: 0, latitude: 0, longitude: 0,
  linkDown: null, linkUp: null, wifiRssi: null, wifiPercent: null,
};

/** Màu theo chất lượng sóng 0-100. */
const signalColor = (pct) => {
  if (pct === null || pct === undefined) return 'var(--text-muted)';
  if (pct >= 60) return 'var(--success)';
  if (pct >= 30) return 'var(--warning)';
  return 'var(--danger, #ff3366)';
};

/**
 * Phát video WebRTC từ MediaMTX qua giao thức WHEP.
 * Drone đẩy RTMP lên rtmp://PI:1935/<tên>, MediaMTX chuyển sang WebRTC tại :8889.
 * Giữ nguyên .video-container + .cam-overlay để không đổi giao diện.
 */
function WebRTCPlayer({ streamName, children, onStatus }) {
  const videoRef = useRef(null);
  const [status, setStatus] = useState('idle');
  // Đẩy trạng thái lên App để đếm "Video Ready": chỉ trình duyệt mới biết luồng nào
  // THỰC SỰ có dữ liệu (server chỉ biết có phiên, không biết khung có tới nơi không).
  useEffect(() => { onStatus?.(status); }, [status, onStatus]);
  // Tăng số này = kết nối lại. Cần vì phiên WHEP chết là chết hẳn: tắt drone thay pin
  // (>10s không có khung) -> MediaMTX bỏ luồng -> phiên đóng -> <video> ĐỨNG NGUYÊN
  // khung cuối mãi mãi. Bản đầu chỉ kết nối một lần nên trạm treo cho tới khi F5 tay.
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!streamName) { setStatus('idle'); return; }

    let cancelled = false;
    let pc = null;
    let resourceUrl = null;
    let retryTimer = null;
    let stallTimer = null;

    // Hẹn nối lại sau delay, trừ khi component đã unmount / đổi nguồn.
    const scheduleRetry = (why, delayMs = 2000) => {
      if (cancelled || retryTimer) return;
      setStatus('error');
      retryTimer = setTimeout(() => {
        if (!cancelled) setAttempt((n) => n + 1);
      }, delayMs);
      console.warn('[WebRTC]', streamName, 'noi lai vi:', why);
    };

    // Dọn cả 2 phía: đóng peer ở client VÀ gửi DELETE để MediaMTX huỷ phiên ngay.
    // Chỉ pc.close() thì phiên còn treo trên server tới khi ICE timeout -> tốn CPU/băng thông.
    const teardown = () => {
      if (stallTimer) { clearInterval(stallTimer); stallTimer = null; }
      try { pc?.close(); } catch { /* ignore */ }
      if (resourceUrl) {
        fetch(resourceUrl, { method: 'DELETE' }).catch(() => { /* best-effort */ });
        resourceUrl = null;
      }
    };

    // Hoãn 150ms trước khi kết nối. React StrictMode (dev) cố ý mount -> unmount -> mount
    // ngay lập tức; hoãn lại thì lần "mount giả" bị huỷ TRƯỚC khi kịp gửi WHEP,
    // nên không tạo phiên thừa trên server (nguyên nhân 2 phiên -> GPU decode 2 lần -> lag).
    const timer = setTimeout(async () => {
      if (cancelled) return;
      try {
        setStatus('connecting');
        pc = new RTCPeerConnection({ iceServers: [] });
        pc.addTransceiver('video', { direction: 'recvonly' });
        pc.addTransceiver('audio', { direction: 'recvonly' });
        pc.ontrack = (e) => { if (videoRef.current) videoRef.current.srcObject = e.streams[0]; };

        // Phiên chết -> nối lại.
        pc.onconnectionstatechange = () => {
          if (cancelled || !pc) return;
          if (['failed', 'disconnected', 'closed'].includes(pc.connectionState)) {
            scheduleRetry('connectionState=' + pc.connectionState);
          }
        };

        // Có phiên sống nhưng không còn khung mới (drone tắt) -> currentTime đứng im.
        // connectionState không đổi trong ca này nên phải tự canh.
        let lastT = -1, stallCount = 0;
        stallTimer = setInterval(() => {
          const v = videoRef.current;
          if (cancelled || !v || v.readyState < 2) return;
          if (v.currentTime === lastT) {
            if (++stallCount >= 5) scheduleRetry('video dung hinh ~5s');
          } else {
            stallCount = 0;
            lastT = v.currentTime;
          }
        }, 1000);

        await pc.setLocalDescription(await pc.createOffer());
        await new Promise((resolve) => {
          if (pc.iceGatheringState === 'complete') return resolve();
          const onChange = () => {
            if (pc.iceGatheringState === 'complete') {
              pc.removeEventListener('icegatheringstatechange', onChange);
              resolve();
            }
          };
          pc.addEventListener('icegatheringstatechange', onChange);
          setTimeout(resolve, 1000);
        });
        if (cancelled) { teardown(); return; }

        const whepUrl = `${WHEP_BASE}/${streamName}/whep`;
        const resp = await fetch(whepUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/sdp' },
          body: pc.localDescription.sdp,
        });
        if (!resp.ok) throw new Error(`WHEP HTTP ${resp.status}`);

        // Location = URL của phiên, cần để DELETE. Có thể null nếu server không expose qua CORS.
        const loc = resp.headers.get('Location');
        if (loc) resourceUrl = new URL(loc, whepUrl).href;

        const answer = await resp.text();
        if (cancelled) { teardown(); return; }
        await pc.setRemoteDescription({ type: 'answer', sdp: answer });
        setStatus('live');
      } catch (err) {
        // WHEP hong (vd drone tat -> MediaMTX bo luong) -> PHAI hen noi lai, khong
        // duoc chi setStatus('error') roi nam im: trang se dung hinh khung cuoi vinh vien.
        console.error(`WHEP '${streamName}':`, err);
        teardown();
        scheduleRetry('WHEP that bai: ' + err);
      }
    }, 150);

    return () => {
      cancelled = true;
      clearTimeout(timer);
      if (retryTimer) clearTimeout(retryTimer);
      teardown();
    };
  }, [streamName, attempt]);

  return (
    <div className="video-container">
      <video ref={videoRef} autoPlay muted playsInline
             style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
      <div className="cam-overlay"></div>
      {children}
      {status !== 'live' && (
        <div style={{
          position: 'absolute', inset: 0, display: 'flex', alignItems: 'center',
          justifyContent: 'center', color: 'var(--text-muted)', fontSize: '0.75rem',
          letterSpacing: '0.05em', textTransform: 'uppercase', pointerEvents: 'none',
        }}>
          {status === 'idle' ? 'Chưa chọn nguồn' : status === 'connecting' ? 'Đang kết nối...' : 'Không có tín hiệu'}
        </div>
      )}
    </div>
  );
}

/**
 * Radar thật: tâm = trạm mặt đất, xung quanh = drone và người lái.
 *
 * Vì sao tâm là trạm chứ không phải người lái: mỗi drone có một người lái riêng,
 * nên người lái là ĐIỂM cần vẽ, không phải gốc toạ độ.
 *
 * Tầm quét tự co giãn theo điểm xa nhất -> luôn nhìn thấy hết mọi điểm.
 */
function RealRadar({ station, points }) {
  // Quy lat/lon ra mét trong hệ toạ độ phẳng lấy trạm làm gốc. Ở tầm vài km thì
  // xấp xỉ phẳng sai không đáng kể, khỏi cần công thức cầu cho nặng.
  const M_PER_DEG_LAT = 111320;
  const rel = React.useMemo(() => {
    if (!station) return [];
    const mPerDegLon = M_PER_DEG_LAT * Math.cos((station.lat * Math.PI) / 180);
    return points
      .filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lon))
      .map((p) => ({
        ...p,
        x: (p.lon - station.lon) * mPerDegLon,   // đông = +
        y: (p.lat - station.lat) * M_PER_DEG_LAT, // bắc = +
      }));
  }, [station, points]);

  // Tầm quét = điểm xa nhất + 20% lề. Sàn 50m để mấy điểm sát nhau không dồn hết
  // vào tâm thành một cục.
  const maxDist = rel.reduce((m, p) => Math.max(m, Math.hypot(p.x, p.y)), 0);
  const range = Math.max(50, maxDist * 1.2);

  const fmtRange = range >= 1000 ? `${(range / 1000).toFixed(1)} km` : `${Math.round(range)} m`;

  return (
    <>
      <div className="panel-header" style={{ display: 'flex', justifyContent: 'space-between' }}>
        <span>RADAR</span>
        <span style={{ color: 'var(--accent)', fontSize: '0.7rem' }}>
          {station ? `⌀ ${fmtRange}` : 'CHƯA CÓ VỊ TRÍ TRẠM'}
        </span>
      </div>
      <div className="radar-grid">
        <div className="radar-circle"></div>
        <div className="radar-circle"></div>
        <div className="radar-circle"></div>
        <div className="radar-cross v"></div>
        <div className="radar-cross h"></div>
        <div className="radar-sweep"></div>

        {/* Tâm = trạm */}
        {station && (
          <div
            data-label="TRẠM"
            style={{
              position: 'absolute', top: '50%', left: '50%', width: 10, height: 10,
              transform: 'translate(-50%,-50%) rotate(45deg)',
              background: 'var(--accent)', boxShadow: '0 0 10px var(--accent)',
            }}
          />
        )}

        {station && rel.map((p) => {
          // 50% = mép radar; điểm ở đúng `range` sẽ nằm sát mép.
          const left = 50 + (p.x / range) * 50;
          const top = 50 - (p.y / range) * 50;
          const isPilot = p.kind === 'pilot';
          return (
            <div
              key={p.id}
              className="radar-dot"
              data-label={p.label}
              style={{
                left: `${left}%`, top: `${top}%`,
                width: isPilot ? 6 : 8, height: isPilot ? 6 : 8,
                background: isPilot ? 'var(--warning, #ffb020)' : 'var(--accent)',
                boxShadow: `0 0 10px ${isPilot ? 'var(--warning, #ffb020)' : 'var(--accent)'}`,
                borderRadius: isPilot ? 2 : '50%',
              }}
            />
          );
        })}

        {!station && (
          <div style={{
            position: 'absolute', inset: 0, display: 'flex', alignItems: 'center',
            justifyContent: 'center', color: 'var(--text-muted)', fontSize: '0.7rem',
            textAlign: 'center', padding: 12,
          }}>
            Chưa lấy được vị trí trạm<br />(cần cho phép định vị)
          </div>
        )}
      </div>
    </>
  );
}

/** Một dòng trong panel SYSTEM STATUS. */
function SysRow({ icon, label, ok, text }) {
  return (
    <div className="sys-item">
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>{icon} {label}</div>
      <span
        className={ok ? 'badge ok' : 'badge'}
        style={ok ? undefined : { background: 'rgba(255,255,255,0.12)', color: 'var(--text-muted)' }}
      >{text}</span>
    </div>
  );
}

/** Máy chạy YOLO26 (cùng con Ubuntu này). Ảnh đi HTTP, nhãn đi MQTT. */
const DETECT_BASE = `http://${window.location.hostname}:8091`;

/**
 * Một ô trong panel AI DETECTION: ảnh đã vẽ khung (MJPEG) + thanh nhãn.
 *
 * `streamName` bám theo NGUỒN mà feed tương ứng đang chọn, không hardcode drone-1/2/3
 * - vì feed là dropdown người dùng đổi được, ô phải đi theo feed.
 * `det` là bản tin MQTT drone/detect/<luồng> gần nhất.
 */
function DetectionBox({ streamName, det, label }) {
  const [alive, setAlive] = useState(false);
  // Moi lan tang = xin mot anh moi. Tu nhip bang onLoad/onError nen khong dồn request.
  const [tick, setTick] = useState(0);
  const timerRef = useRef(null);
  // ?t= de khoi dinh cache; moi tick la mot anh moi.
  const src = streamName ? `${DETECT_BASE}/snap/${streamName}?t=${tick}` : null;
  useEffect(() => {
    setAlive(false);
    return () => { if (timerRef.current) clearTimeout(timerRef.current); };
  }, [streamName]);

  // Xin anh ke tiep SAU KHI anh hien tai ve xong (hoac loi) -> tu nhip, tu hoi phuc.
  const scheduleNext = (delay) => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => setTick((n) => n + 1), delay);
  };

  const fresh = det && Date.now() - det.ts < 8000;
  const count = fresh ? det.count : 0;
  const top = fresh ? det.top : null;

  if (!streamName) {
    return (
      <div className="detection-item" style={{ border: '1px solid rgba(255,255,255,0.2)', boxShadow: 'none' }}>
        <div style={{
          width: '100%', height: '100%', display: 'flex', alignItems: 'center',
          justifyContent: 'center', color: 'var(--text-muted)', fontSize: '0.65rem',
          letterSpacing: '0.05em',
        }}>{label} — CHƯA CHỌN NGUỒN</div>
      </div>
    );
  }

  return (
    <div
      className="detection-item"
      style={count === 0 ? { border: '1px solid rgba(255,255,255,0.2)', boxShadow: 'none' } : undefined}
    >
      <img
        src={src}
        onLoad={() => { setAlive(true); scheduleNext(300); }}
        onError={() => { setAlive(false); scheduleNext(1500); }}
        style={{
          width: '100%', height: '100%', objectFit: 'cover',
          filter: count === 0 ? 'grayscale(1)' : 'none',
        }}
        alt={`Detection ${label}`}
      />
      {!alive && (
        <div style={{
          position: 'absolute', inset: 0, display: 'flex', alignItems: 'center',
          justifyContent: 'center', color: 'var(--text-muted)', fontSize: '0.65rem',
        }}>ĐANG KẾT NỐI…</div>
      )}
      <div style={{
        position: 'absolute', bottom: 0, left: 0, right: 0,
        background: count > 0 ? 'rgba(255,51,102,0.8)' : 'rgba(255,255,255,0.12)',
        color: 'white', fontSize: '0.7rem', padding: '4px', textAlign: 'center', fontWeight: 'bold',
      }}>
        {!fresh ? `${label} — KHÔNG CÓ DỮ LIỆU`
          : count > 0 ? `${String(top).toUpperCase()} ×${count} — ${label}`
          : `KHÔNG PHÁT HIỆN — ${label}`}
      </div>
    </div>
  );
}

/** Dropdown chọn nguồn WebRTC, đặt nổi ở góc phải-trên của khung. */
function SourcePicker({ streams, selected, onSelect }) {
  return (
    <select
      value={selected || ''}
      onChange={(e) => onSelect(e.target.value || null)}
      style={{
        position: 'absolute', top: 8, right: 8, zIndex: 25,
        background: 'rgba(0,0,0,0.65)', color: '#e6edf3',
        border: '1px solid rgba(255,255,255,0.25)', borderRadius: 4,
        fontSize: '0.7rem', padding: '2px 4px', cursor: 'pointer',
      }}
    >
      <option value="">-- nguồn --</option>
      {streams.map((s) => <option key={s} value={s}>{s}</option>)}
    </select>
  );
}

function App() {
  const [time, setTime] = useState(new Date().toLocaleTimeString());

  const onVideoStatus = useMemo(() => {
    const mk = (k) => (st) => setVideoStatus((prev) => (prev[k] === st ? prev : { ...prev, [k]: st }));
    return { feed1: mk('feed1'), feed2: mk('feed2'), feed3: mk('feed3'), gcs: mk('gcs') };
  }, []);

  // Vị trí trạm cho tâm radar. Dùng GPS trình duyệt (định vị theo WiFi, ~50m) chứ
  // KHÔNG dùng định vị theo IP: đã đo, IP lệch 5.6km -> radar vô nghĩa.
  useEffect(() => {
    if (!navigator.geolocation) return;
    const id = navigator.geolocation.watchPosition(
      (pos) => setStation({ lat: pos.coords.latitude, lon: pos.coords.longitude, acc: pos.coords.accuracy }),
      (err) => { console.warn('[radar] khong lay duoc vi tri tram:', err.message); setStation(null); },
      { enableHighAccuracy: true, maximumAge: 30000, timeout: 20000 },
    );
    return () => navigator.geolocation.clearWatch(id);
  }, []);

  // Panel SYSTEM STATUS: hỏi máy chạy AI 5s/lần. Các mục internet / access point /
  // GPU đều phải kiểm tra PHÍA MÁY - trình duyệt không ping được 10.10.10.10 cũng
  // không biết có GPU hay không. Không trả lời = coi như AI service chết.
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const r = await fetch(`${DETECT_BASE}/status`, { cache: 'no-store' });
        const d = await r.json();
        if (alive) setSys(d);
      } catch {
        if (alive) setSys(null);
      }
    };
    tick();
    const t = setInterval(tick, 5000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  // Danh sách luồng có trên Pi + nguồn đang chọn cho từng khung.
  // Lựa chọn được lưu lại để trạm reboot / mở lại kiosk là tự khôi phục, không phải chọn tay.
  const [streams, setStreams] = useState([]);
  const [detections, setDetections] = useState({});
  const [sys, setSys] = useState(null);        // /status cua may chay AI
  const [station, setStation] = useState(null);  // vi tri tram, tu GPS trinh duyet
  // useMemo de callback khong doi moi lan render -> khong lam WebRTCPlayer chay lai effect.
  // "Co du lieu that" = player bao 'live'. Chi dem khung DA CHON nguon.
  const [videoStatus, setVideoStatus] = useState({});  // khung -> 'live' | ...
  const [feeds, setFeeds] = useState(() => {
    const empty = { feed1: null, feed2: null, feed3: null, gcs: null };
    try {
      return { ...empty, ...(JSON.parse(localStorage.getItem('dashboardFeeds')) || {}) };
    } catch {
      return empty;
    }
  });

  // "Co du lieu that" = player bao 'live'. Chi dem khung DA CHON nguon.
  // PHAI dat SAU `const [feeds]` - dung truoc la ReferenceError (TDZ) -> trang trang.
  const videoWanted = ['feed1', 'feed2', 'feed3', 'gcs'].filter((k) => feeds?.[k]).length;
  const videoReady = ['feed1', 'feed2', 'feed3', 'gcs']
    .filter((k) => feeds?.[k] && videoStatus[k] === 'live').length;


  useEffect(() => {
    try {
      localStorage.setItem('dashboardFeeds', JSON.stringify(feeds));
    } catch { /* localStorage bi chan thi bo qua */ }
  }, [feeds]);

  // Telemetry gom theo TÊN LUỒNG (topic drone/telemetry/<tên-luồng>)
  const [telemetryByStream, setTelemetryByStream] = useState({});

  // Điểm vẽ trên radar: mỗi luồng cho tối đa 2 điểm - drone và người lái của nó.
  const radarPoints = useMemo(() => {
    const out = [];
    ['feed1', 'feed2', 'feed3'].forEach((k, i) => {
      const name = feeds?.[k];
      if (!name) return;
      const t = telemetryByStream[name];
      if (!t) return;
      if (t.latitude && t.longitude) {
        out.push({ id: `d${i}`, lat: t.latitude, lon: t.longitude, kind: 'drone', label: `Drone ${i + 1}` });
      }
      if (t.pilotLat && t.pilotLon) {
        out.push({ id: `p${i}`, lat: t.pilotLat, lon: t.pilotLon, kind: 'pilot', label: `Lái ${i + 1}` });
      }
    });
    return out;
  }, [feeds, telemetryByStream]);


  useEffect(() => {
    const timer = setInterval(() => {
      setTime(new Date().toLocaleTimeString());
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  // Lấy danh sách luồng đã tạo trên Pi (bỏ path catch-all)
  useEffect(() => {
    let stop = false;
    const load = async () => {
      try {
        const r = await fetch(STREAMS_API);
        const d = await r.json();
        const names = (d.items || []).map(i => i.name).filter(n => n && n !== CATCH_ALL).sort();
        if (!stop) setStreams(names);
      } catch (e) {
        console.error('Không lấy được danh sách luồng', e);
      }
    };
    load();
    const t = setInterval(load, 15000);
    return () => { stop = true; clearInterval(t); };
  }, []);

  // MQTT: nghe TẤT CẢ drone rồi gom theo tên luồng -> khung nào chọn luồng nào thì lấy đúng data đó
  useEffect(() => {
    const client = mqtt.connect('ws://10.10.10.2:9001');

    client.on('connect', () => {
      console.log('Connected to MQTT WebSocket');
      client.subscribe('drone/telemetry/#');
      client.subscribe('drone/detect/#');
    });

    client.on('message', (topic, message) => {
      const name = topic.split('/').pop();
      try {
        const data = JSON.parse(message.toString());
        // Cung broker dung chung cho 2 loai ban tin -> phai dinh tuyen theo topic,
        // khong duoc coi tat ca la telemetry.
        if (topic.startsWith('drone/detect/')) {
          setDetections(prev => ({ ...prev, [name]: data }));
          return;
        }
        setTelemetryByStream(prev => ({
          ...prev,
          [name]: { ...(prev[name] || EMPTY_TELEMETRY), ...data },
        }));
      } catch (e) {
        console.error("Telemetry parsing error", e);
      }
    });

    return () => {
      client.end();
    };
  }, []);

  const setFeed = (key) => (value) => setFeeds(p => ({ ...p, [key]: value }));
  const telemetryOf = (name) => (name && telemetryByStream[name]) || EMPTY_TELEMETRY;

  return (
    <div className="dashboard">
      {/* MAIN VIEW */}
      <div className="main-view">
        <div className="top-row">
          <div className="panel" style={{ padding: 0 }}>
            <div className="panel-header" style={{ padding: '12px 12px 0', position: 'absolute', zIndex: 20 }}>
              LIVE FEED 1
            </div>
            <SourcePicker streams={streams} selected={feeds.feed1} onSelect={setFeed('feed1')} />
            <WebRTCPlayer streamName={feeds.feed1} onStatus={onVideoStatus.feed1}>
              <div className="crosshair"></div>
            </WebRTCPlayer>
          </div>
          
          <div className="panel" style={{ padding: 0, background: '#05070a' }}>
            <RealRadar station={station} points={radarPoints} />
          </div>
        </div>

        <div className="middle-row">
          <div className="panel" style={{ padding: 0 }}>
            <div className="panel-header" style={{ padding: '8px 12px 0', position: 'absolute', zIndex: 20 }}>LIVE FEED 2</div>
            <SourcePicker streams={streams} selected={feeds.feed2} onSelect={setFeed('feed2')} />
            <WebRTCPlayer streamName={feeds.feed2} onStatus={onVideoStatus.feed2} />
          </div>
          <div className="panel" style={{ padding: 0 }}>
            <div className="panel-header" style={{ padding: '8px 12px 0', position: 'absolute', zIndex: 20 }}>LIVE FEED 3</div>
            <SourcePicker streams={streams} selected={feeds.feed3} onSelect={setFeed('feed3')} />
            <WebRTCPlayer streamName={feeds.feed3} onStatus={onVideoStatus.feed3} />
          </div>
          <div className="panel" style={{ padding: 0 }}>
            <div className="panel-header" style={{ padding: '8px 12px 0', position: 'absolute', zIndex: 20 }}>GROUND STATION CAM</div>
            <SourcePicker streams={streams} selected={feeds.gcs} onSelect={setFeed('gcs')} />
            <WebRTCPlayer streamName={feeds.gcs} onStatus={onVideoStatus.gcs} />
          </div>
        </div>
      </div>

      {/* RIGHT SIDEBAR (AI DETECTION) */}
      <div className="sidebar panel">
        <div className="sidebar-title">
          <ShieldAlert size={16} style={{ display: 'inline', marginRight: 8, verticalAlign: 'middle' }} />
          AI DETECTION
        </div>
        <div className="detection-list">
          <DetectionBox streamName={feeds.feed1} det={detections[feeds.feed1]} label="D1" />
          <DetectionBox streamName={feeds.feed2} det={detections[feeds.feed2]} label="D2" />
          <DetectionBox streamName={feeds.feed3} det={detections[feeds.feed3]} label="D3" />
        </div>
      </div>

      {/* BOTTOM FOOTER (TELEMETRY) */}
      <div className="footer">
        <FeedTelemetryPanel label="DRONE 1" streamName={feeds.feed1} data={telemetryOf(feeds.feed1)} />
        <FeedTelemetryPanel label="DRONE 2" streamName={feeds.feed2} data={telemetryOf(feeds.feed2)} />
        <FeedTelemetryPanel label="DRONE 3" streamName={feeds.feed3} data={telemetryOf(feeds.feed3)} />
        
        <div className="panel">
          <div className="panel-header">SYSTEM STATUS <span style={{color: 'var(--accent)'}}>{time}</span></div>
          <div className="sys-list">
             <SysRow icon={<Server size={14} color="var(--text-muted)" />} label="Internet"
                     ok={!!sys?.internet} text={sys ? (sys.internet ? 'OK' : 'NO NET') : '- -'} />
             <SysRow icon={<Radio size={14} color="var(--text-muted)" />} label="Access Point"
                     ok={!!sys?.ap} text={sys ? (sys.ap ? 'OK' : 'OFFLINE') : '- -'} />
             <SysRow icon={<Cpu size={14} color="var(--text-muted)" />} label="AI Available"
                     ok={!!sys?.ai?.ok}
                     text={!sys ? '- -' : sys.ai?.ok ? (sys.ai.device === 'cuda' ? 'GPU' : 'CPU') : 'NO'} />
             <SysRow icon={<Activity size={14} color="var(--text-muted)" />} label="Video Ready"
                     ok={videoReady > 0} text={`${videoReady}/${videoWanted}`} />
             <SysRow icon={<Navigation size={14} color="var(--text-muted)" />} label="GPS"
                     ok={!!sys?.gps}
                     text={sys?.gps ? `${sys.gps.lat.toFixed(3)}, ${sys.gps.lon.toFixed(3)}` : '- -'} />
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * Ghép telemetry với LIVE FEED tương ứng: khung feed chọn luồng nào thì
 * panel này hiện thông số của chính drone đó (topic drone/telemetry/<tên-luồng>).
 * Chỉ định dạng số rồi giao cho DronePanel gốc render - không đổi giao diện.
 */
function FeedTelemetryPanel({ label, streamName, data }) {
  const title = streamName ? `${label} (${streamName})` : `${label} — chưa chọn nguồn`;
  const n = (v) => (typeof v === 'number' ? v : 0);
  const orNull = (v) => (typeof v === 'number' ? v : null);
  return (
    <DronePanel
      title={title}
      battery={n(data.battery)}
      alt={`${n(data.altitude).toFixed(1)} m`}
      pitch={`${n(data.pitch).toFixed(1)}°`}
      roll={`${n(data.roll).toFixed(1)}°`}
      warning={!!streamName && n(data.battery) > 0 && n(data.battery) <= 20}
      linkDown={orNull(data.linkDown)}
      wifiPercent={orNull(data.wifiPercent)}
      wifiRssi={orNull(data.wifiRssi)}
    />
  );
}

function DronePanel({ title, battery, alt, pitch, roll, warning = false,
                      linkDown = null, wifiPercent = null, wifiRssi = null }) {
  return (
    <div className="panel" style={{ borderColor: warning ? 'var(--warning)' : 'var(--panel-border)' }}>
      <div className="panel-header" style={{ color: warning ? 'var(--warning)' : 'var(--text-muted)' }}>
        {title}
        {warning && <Activity size={14} className="animate-pulse" />}
      </div>
      
      <div className="telemetry-grid">
        <div className="stat-box">
          <div className="stat-label">Battery</div>
          <div className="stat-value" style={{ color: battery < 50 ? 'var(--warning)' : 'var(--success)' }}>
            {battery < 50 ? <BatteryWarning size={18} /> : <Battery size={18} />}
            {battery}%
          </div>
        </div>
        <div className="stat-box">
          <div className="stat-label">Drone → RC</div>
          <div className="stat-value" style={{ color: signalColor(linkDown) }}>
            <Wifi size={18} /> {linkDown === null ? '--' : `${linkDown}%`}
          </div>
        </div>
        <div className="stat-box" style={{ gridColumn: '1 / 3' }}>
           <div className="stat-label">RC → Trạm (WiFi)</div>
           <div className="stat-value" style={{ color: signalColor(wifiPercent) }}>
             <Radio size={18} /> {wifiPercent === null ? '--' : `${wifiPercent}%`}
             {wifiRssi !== null && (
               <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginLeft: 6 }}>
                 {wifiRssi} dBm
               </span>
             )}
           </div>
        </div>
        <div className="stat-box" style={{ gridColumn: '1 / 3' }}>
           <div className="stat-label">Orientation (Pitch | Roll)</div>
           <div className="stat-value">
             <Crosshair size={18} color="var(--text-muted)" /> {pitch} | {roll}
           </div>
        </div>
        <div className="stat-box" style={{ gridColumn: '1 / 3' }}>
           <div className="stat-label">Altitude</div>
           <div className="stat-value">{alt}</div>
        </div>
      </div>
    </div>
  );
}

export default App;
