import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Battery, BatteryWarning, Wifi, Activity, Radio, Crosshair, Users, Flame } from 'lucide-react';
import mqtt from 'mqtt';
import './index.css';

// Đã BỎ Pi: MediaMTX + MQTT giờ chạy CHUNG máy với dashboard. Trỏ theo host đang phục vụ
// trang (localhost khi mở ở trạm, IP LAN khi người khác xem) -> không hardcode IP, đổi IP vẫn chạy.
const MEDIA_HOST = window.location.hostname;
const WHEP_BASE = `http://${MEDIA_HOST}:8889`;
const DETECT_BASE = `http://${window.location.hostname}:8091`;
const STREAM = new URLSearchParams(window.location.search).get('stream') || 'drone-1';
// Xem nhanh "-web" chu khong phai luong goc: DJI Fly ban H.264 High profile 1920x1088,
// trong khi WebRTC quang cao SDP la Constrained Baseline 3.1 -> trinh duyet tu choi giai ma
// (do duoc: 0 fps, PLI 5 lan/giay, mat goi 0). MediaMTX tu transcode sang nhanh "-web" dung
// chuan (runOnReady trong mediamtx.yml). Luong goc van giu nguyen 1080p cho AI va MQTT.
const WEBRTC_PATH = `${STREAM}-web`;
const STALE_MS = 8000;

const FOV_DEG = 78;                       // camera lắp phía TRƯỚC drone
const VN_LABEL = { person: 'Người', fire: 'Lửa', smoke: 'Khói' };
const clamp01 = (v) => Math.min(1, Math.max(0, v));

const signalColor = (pct) => {
  if (pct === null || pct === undefined) return 'var(--text-muted)';
  if (pct >= 60) return 'var(--success)';
  if (pct >= 30) return 'var(--warning)';
  return 'var(--danger, #ff3366)';
};

/**
 * Tỉ lệ khung hình phải LẤY TỪ LUỒNG THẬT, không đoán 16:9: drone đổi chất lượng là đổi
 * độ phân giải (đo thật: 1920x1088 -> 960x544). Khung cha mang đúng tỉ lệ này thì
 * `object-fit: contain` lấp vừa khít — không cắt, không méo, không thừa dải đen.
 * Kẹp trong [1.2, 2.4] để một luồng dị thường không kéo sập bố cục.
 */
const clampAR = (ar) => (Number.isFinite(ar) && ar > 0 ? Math.min(2.4, Math.max(1.2, ar)) : 16 / 9);

/** WebRTC (WHEP) từ MediaMTX, tự nối lại khi phiên chết. */
function WebRTCPlayer({ streamName, children, onStatus, onAspect }) {
  const videoRef = useRef(null);
  const [status, setStatus] = useState('idle');

  // `resize` bắn khi độ phân giải luồng đổi giữa chừng (đổi chất lượng trên DJI Fly).
  useEffect(() => {
    const v = videoRef.current;
    if (!v || !onAspect) return;
    const report = () => { if (v.videoWidth && v.videoHeight) onAspect(v.videoWidth / v.videoHeight); };
    v.addEventListener('loadedmetadata', report);
    v.addEventListener('resize', report);
    report();
    return () => { v.removeEventListener('loadedmetadata', report); v.removeEventListener('resize', report); };
  }, [onAspect]);
  useEffect(() => { onStatus?.(status); }, [status, onStatus]);
  // Mất tín hiệu -> XOÁ khung cuối. Thẻ <video> vốn giữ frame cuối mãi mãi, nếu không
  // xoá thì LIVE treo ảnh cũ y hệt lỗi detector giữ frame cũ (đã sửa phía kia).
  useEffect(() => { if (status !== 'live' && videoRef.current) videoRef.current.srcObject = null; }, [status]);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!streamName) { setStatus('idle'); return; }
    let cancelled = false, pc = null, resourceUrl = null, retryTimer = null, stallTimer = null;
    const scheduleRetry = (why, delayMs = 2000) => {
      if (cancelled || retryTimer) return;
      setStatus('error');
      retryTimer = setTimeout(() => { if (!cancelled) setAttempt((n) => n + 1); }, delayMs);
      console.warn('[WebRTC]', streamName, why);
    };
    const teardown = () => {
      if (stallTimer) { clearInterval(stallTimer); stallTimer = null; }
      try { pc?.close(); } catch { /* ignore */ }
      if (resourceUrl) { fetch(resourceUrl, { method: 'DELETE' }).catch(() => {}); resourceUrl = null; }
    };
    const timer = setTimeout(async () => {
      if (cancelled) return;
      try {
        setStatus('connecting');
        pc = new RTCPeerConnection({ iceServers: [] });
        pc.addTransceiver('video', { direction: 'recvonly' });
        pc.addTransceiver('audio', { direction: 'recvonly' });
        pc.ontrack = (e) => { if (videoRef.current) videoRef.current.srcObject = e.streams[0]; };
        pc.onconnectionstatechange = () => {
          if (cancelled || !pc) return;
          if (['failed', 'disconnected', 'closed'].includes(pc.connectionState)) scheduleRetry('state=' + pc.connectionState);
        };
        let lastT = -1, stallCount = 0;
        stallTimer = setInterval(() => {
          const v = videoRef.current;
          if (cancelled || !v || v.readyState < 2) return;
          if (v.currentTime === lastT) { if (++stallCount >= 5) scheduleRetry('video dừng ~5s'); }
          else { stallCount = 0; lastT = v.currentTime; }
        }, 1000);
        await pc.setLocalDescription(await pc.createOffer());
        await new Promise((resolve) => {
          if (pc.iceGatheringState === 'complete') return resolve();
          const onChange = () => { if (pc.iceGatheringState === 'complete') { pc.removeEventListener('icegatheringstatechange', onChange); resolve(); } };
          pc.addEventListener('icegatheringstatechange', onChange);
          setTimeout(resolve, 1000);
        });
        if (cancelled) { teardown(); return; }
        const whepUrl = `${WHEP_BASE}/${streamName}/whep`;
        const resp = await fetch(whepUrl, { method: 'POST', headers: { 'Content-Type': 'application/sdp' }, body: pc.localDescription.sdp });
        if (!resp.ok) throw new Error(`WHEP HTTP ${resp.status}`);
        const loc = resp.headers.get('Location');
        if (loc) resourceUrl = new URL(loc, whepUrl).href;
        const answer = await resp.text();
        if (cancelled) { teardown(); return; }
        await pc.setRemoteDescription({ type: 'answer', sdp: answer });
        setStatus('live');
      } catch (err) {
        console.error(`WHEP '${streamName}':`, err);
        teardown();
        scheduleRetry('WHEP lỗi: ' + err);
      }
    }, 150);
    return () => { cancelled = true; clearTimeout(timer); if (retryTimer) clearTimeout(retryTimer); teardown(); };
  }, [streamName, attempt]);

  return (
    <div className="video-container">
      {/* KHONG dat objectFit inline: de CSS `.vid-panel video { object-fit: contain }` lo,
          neu khong inline se de len va cat hinh khi ti le lech du chi mot chut. */}
      <video ref={videoRef} autoPlay muted playsInline style={{ width: '100%', height: '100%' }} />
      <div className="cam-overlay"></div>
      {children}
      {status !== 'live' && (
        <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#05070a', color: 'var(--text-muted)', fontSize: '0.75rem', letterSpacing: '0.05em', textTransform: 'uppercase', pointerEvents: 'none' }}>
          {status === 'idle' ? 'Chưa chọn nguồn' : status === 'connecting' ? 'Đang kết nối...' : 'Không có tín hiệu'}
        </div>
      )}
    </div>
  );
}

/** Màn PHÁT HIỆN AI: ảnh /snap đã vẽ bbox, lấp đầy ô + thanh nhãn (đồng bộ live). */
function DetectFeed({ streamName, det, onAspect }) {
  const [alive, setAlive] = useState(false);
  const [tick, setTick] = useState(0);
  const timer = useRef(null);
  const next = (d) => { clearTimeout(timer.current); timer.current = setTimeout(() => setTick((n) => n + 1), d); };
  useEffect(() => () => clearTimeout(timer.current), []);

  const fresh = det && Date.now() - det.ts < STALE_MS;
  const live = fresh && det.live !== false;
  const people = live ? (det.people || 0) : 0;
  const fire = live && (det.fire?.on || det.smoke?.on);
  const bar = !fresh ? 'KHÔNG CÓ DỮ LIỆU'
    : det.live === false ? 'MẤT TÍN HIỆU'
    : fire ? '⚠ PHÁT HIỆN CHÁY'
    : people > 0 ? `${people} NGƯỜI CẦN CỨU HỘ`
    : 'KHÔNG PHÁT HIỆN';
  const barBg = fire ? 'rgba(255,51,102,0.92)' : people > 0 ? 'rgba(0,190,120,0.88)' : 'rgba(255,255,255,0.12)';

  return (
    <div style={{ position: 'absolute', inset: 0, background: '#000' }}>
      <img
        src={streamName ? `${DETECT_BASE}/snap/${streamName}?t=${tick}` : undefined}
        onLoad={(e) => {
          setAlive(true); next(150);
          const im = e.currentTarget;
          if (im.naturalWidth && im.naturalHeight) onAspect?.(im.naturalWidth / im.naturalHeight);
        }}
        onError={() => { setAlive(false); next(1500); }}
        style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }}
        alt="detect"
      />
      {!alive && (
        <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-muted)', fontSize: '0.8rem' }}>ĐANG CHỜ AI…</div>
      )}
      <div style={{ position: 'absolute', bottom: 0, left: 0, right: 0, background: barBg, color: '#fff', fontSize: '0.72rem', fontWeight: 700, textAlign: 'center', padding: '5px', letterSpacing: '0.04em' }}>
        {bar} — DRONE 1
      </div>
    </div>
  );
}

/** RADAR: chrome gốc + tâm=drone, nón FOV trước, chấm=vật detect có nhãn. */
function DemoRadar({ det }) {
  const fresh = det && Date.now() - det.ts < STALE_MS;
  const objs = (fresh && det.live !== false) ? (det.objects || []) : [];
  return (
    <>
      <div className="panel-header" style={{ display: 'flex', justifyContent: 'space-between' }}>
        <span>RADAR</span>
        <span style={{ color: 'var(--accent)', fontSize: '0.7rem' }}>◎ DRONE 1 · FOV trước · {objs.length} vật</span>
      </div>
      <div className="radar-grid">
        <div className="radar-circle"></div>
        <div className="radar-circle"></div>
        <div className="radar-circle"></div>
        <div className="radar-cross v"></div>
        <div className="radar-cross h"></div>
        <div className="radar-sweep"></div>
        <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', background: `conic-gradient(from ${-FOV_DEG / 2}deg, rgba(0,240,255,0.14), rgba(0,240,255,0.03) ${FOV_DEG}deg, transparent ${FOV_DEG}deg 360deg)` }} />
        <div style={{ position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%,-50%)', width: 0, height: 0, borderLeft: '6px solid transparent', borderRight: '6px solid transparent', borderBottom: '13px solid var(--accent)', filter: 'drop-shadow(0 0 6px var(--accent))', zIndex: 4 }} />
        {objs.map((o, i) => {
          const ang = (o.cx - 0.5) * FOV_DEG * Math.PI / 180;
          const frac = clamp01(((o.by ?? 0.8) - 0.5) / 0.47);
          const r = 0.9 - frac * 0.68;
          const left = 50 + Math.sin(ang) * r * 46;
          const top = 50 - Math.cos(ang) * r * 46;
          const isFire = o.label === 'fire' || o.label === 'smoke';
          const col = isFire ? 'var(--danger)' : 'var(--accent)';
          return <div key={i} className="radar-dot" data-label={VN_LABEL[o.label] || o.label} style={{ left: `${left}%`, top: `${top}%`, background: col, boxShadow: `0 0 10px ${col}` }} />;
        })}
        {objs.length === 0 && (
          <div style={{ position: 'absolute', bottom: 10, width: '100%', textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.68rem' }}>Chưa phát hiện vật thể</div>
        )}
      </div>
    </>
  );
}

/** Ô cảnh báo nhỏ gọn. variant: grey (chưa) | accent (có người) | safe (an toàn) | danger (cháy). */
/** Một dòng cảnh báo gọn, dùng bên trong khung trạng thái chung (không còn là ô riêng). */
function AlertRow({ bodyIcon, variant, statusText, subText }) {
  return (
    <div className={`alert-row r-${variant}`}>
      <div className={`alert-icon a-${variant}`}>{bodyIcon}</div>
      <div>
        <div className={`alert-status s-${variant}`}>{statusText}</div>
        <div className="alert-subtext">{subText}</div>
      </div>
    </div>
  );
}

function App() {
  const [detections, setDetections] = useState({});

  useEffect(() => {
    const client = mqtt.connect(`ws://${MEDIA_HOST}:9001`);
    client.on('connect', () => client.subscribe('drone/detect/#'));
    client.on('message', (topic, message) => {
      const name = topic.split('/').pop();
      try { if (topic.startsWith('drone/detect/')) setDetections((prev) => ({ ...prev, [name]: JSON.parse(message.toString()) })); }
      catch (e) { console.error('detect parse', e); }
    });
    return () => { try { client.end(); } catch { /* noop */ } };
  }, []);

  const det = detections[STREAM];

  // Cảnh báo LATCH: một khi phát hiện thì GIỮ, KHÔNG quay lại trạng thái an toàn (SAR).
  // Ghi lai THOI DIEM phat hien dau tien. Do tin cay cua model khong noi len dieu gi
  // cho nguoi dieu hanh (49% hay 82% thi van phai di kiem tra), con "phat hien luc may gio"
  // thi dung duoc ngay de doi chieu voi duong bay.
  const [victim, setVictim] = useState({ ever: false, at: 0 });
  const [fire, setFire] = useState({ ever: false, flame: false, smoke: false, at: 0 });
  useEffect(() => {
    if (!det || Date.now() - det.ts >= STALE_MS || det.live === false) return;
    if ((det.people || 0) > 0) setVictim((p) => (p.ever ? p : { ever: true, at: Date.now() }));
    if (det.fire?.on || det.smoke?.on) {
      setFire((p) => ({
        ever: true,
        flame: p.flame || !!det.fire?.on,
        smoke: p.smoke || !!det.smoke?.on,
        at: p.ever ? p.at : Date.now(),
      }));
    }
  }, [det]);
  const hhmm = (t) => new Date(t).toLocaleTimeString('vi-VN');

  // Quầng đỏ toàn màn: chỉ khi cháy ĐANG trong khung (để không nhấp nháy mãi, giữ LIVE/DETECT nổi bật).
  const fireNow = det && Date.now() - det.ts < STALE_MS && det.live !== false && (det.fire?.on || det.smoke?.on);

  // Tỉ lệ khung của từng ô video, lấy từ chính luồng. useCallback để identity ổn định,
  // không thì effect gắn listener trong WebRTCPlayer chạy lại mỗi lần render.
  const [liveAR, setLiveAR] = useState(16 / 9);
  const [detAR, setDetAR] = useState(16 / 9);
  const onLiveAspect = useCallback((ar) => {
    const v = clampAR(ar);
    setLiveAR((p) => (Math.abs(p - v) < 0.005 ? p : v));
  }, []);
  const onDetAspect = useCallback((ar) => {
    const v = clampAR(ar);
    setDetAR((p) => (Math.abs(p - v) < 0.005 ? p : v));
  }, []);
  const [live2AR, setLive2AR] = useState(16 / 9);
  const onLive2Aspect = useCallback((ar) => {
    const v = clampAR(ar);
    setLive2AR((p) => (Math.abs(p - v) < 0.005 ? p : v));
  }, []);

  return (
    <div className={`dash2${fireNow ? ' alarm' : ''}`}>
      {/* HÀNG 1 — hai khung video. Khung mang ĐÚNG tỉ lệ của luồng (lấy động từ video/ảnh),
          nên contain lấp vừa khít: không cắt cụt, không méo, không thừa dải đen. */}
      <div className="videos">
        <div className="panel vid-panel" style={{ padding: 0, aspectRatio: liveAR }}>
          <div className="panel-header" style={{ padding: '12px 12px 0', position: 'absolute', zIndex: 20 }}>LIVE FEED — DRONE 1</div>
          <WebRTCPlayer streamName={WEBRTC_PATH} onAspect={onLiveAspect}>
            <div className="crosshair"></div>
          </WebRTCPlayer>
        </div>
        <div className="panel vid-panel" style={{ padding: 0, aspectRatio: detAR }}>
          <div className="panel-header" style={{ padding: '12px 12px 0', position: 'absolute', zIndex: 20, color: 'var(--accent)' }}>PHÁT HIỆN AI — DRONE 1</div>
          <DetectFeed streamName={STREAM} det={det} onAspect={onDetAspect} />
        </div>
      </div>

      {/* HÀNG 2 — radar · khung trạng thái gộp (thông số + 2 cảnh báo) · live feed drone-2 */}
      <div className="bottom">
        <div className="panel" style={{ background: '#05070a' }}>
          <DemoRadar det={det} />
        </div>

        <FakeDronePanel label="DRONE 1" streamName={STREAM}>
          <div className="alert-rows">
            <AlertRow
              bodyIcon={<Users size={22} />}
              variant={victim.ever ? 'accent' : 'grey'}
              statusText={victim.ever ? 'CÓ NGƯỜI BỊ NẠN' : 'CHƯA PHÁT HIỆN'}
              subText={victim.ever ? `Phát hiện lúc ${hhmm(victim.at)}` : 'Chưa thấy người bị nạn'}
            />
            <AlertRow
              bodyIcon={<Flame size={22} />}
              variant={fire.ever ? 'danger' : 'safe'}
              statusText={fire.ever ? `PHÁT HIỆN ${fire.flame ? 'ĐÁM CHÁY' : 'KHÓI'}` : 'AN TOÀN'}
              subText={fire.ever ? `Phát hiện lúc ${hhmm(fire.at)}` : 'Không phát hiện cháy/khói'}
            />
          </div>
        </FakeDronePanel>

        {/* Khung live thứ hai, CỐ ĐỊNH drone-2. Cao bằng hàng, rộng theo tỉ lệ luồng
            (ngược với hàng trên: trên rộng cố định -> cao suy ra). */}
        <div className="panel vid-panel vid-h" style={{ padding: 0, aspectRatio: live2AR }}>
          <div className="panel-header" style={{ padding: '12px 12px 0', position: 'absolute', zIndex: 20 }}>LIVE FEED — DRONE 2</div>
          <WebRTCPlayer streamName="drone-2-web" onAspect={onLive2Aspect}>
            <div className="crosshair"></div>
          </WebRTCPlayer>
        </div>
      </div>
    </div>
  );
}

/** DRONE 1 — telemetry FAKE (DJI Fly không gửi telemetry). */
function FakeDronePanel({ label, streamName, children }) {
  const [t, setT] = useState(0);
  useEffect(() => { const id = setInterval(() => setT((x) => x + 1), 1000); return () => clearInterval(id); }, []);
  const wob = (b, a, ph) => b + Math.sin((t + ph) / 3) * a;
  return (
    <DronePanel
      title={`${label} (${streamName})`}
      battery={Math.max(64, 86 - Math.floor(t / 45))}
      alt={`${wob(42.6, 0.8, 0).toFixed(1)} m`}
      pitch={`${wob(-2, 3, 1).toFixed(1)}°`}
      roll={`${wob(0, 2, 4).toFixed(1)}°`}
      linkDown={Math.round(wob(94, 4, 2))}
      wifiPercent={Math.round(wob(88, 5, 6))}
      wifiRssi={Math.round(wob(-47, 4, 3))}
    >
      {children}
    </DronePanel>
  );
}

/** Bảng thông số drone GỐC. */
function DronePanel({ title, battery, alt, pitch, roll, warning = false, linkDown = null, wifiPercent = null, wifiRssi = null, children }) {
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
            {battery < 50 ? <BatteryWarning size={18} /> : <Battery size={18} />}{battery}%
          </div>
        </div>
        <div className="stat-box">
          <div className="stat-label">Drone → RC</div>
          <div className="stat-value" style={{ color: signalColor(linkDown) }}><Wifi size={18} /> {linkDown === null ? '--' : `${linkDown}%`}</div>
        </div>
        <div className="stat-box" style={{ gridColumn: '1 / 3' }}>
          <div className="stat-label">RC → Trạm (WiFi)</div>
          <div className="stat-value" style={{ color: signalColor(wifiPercent) }}>
            <Radio size={18} /> {wifiPercent === null ? '--' : `${wifiPercent}%`}
            {wifiRssi !== null && <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginLeft: 6 }}>{wifiRssi} dBm</span>}
          </div>
        </div>
        <div className="stat-box" style={{ gridColumn: '1 / 3' }}>
          <div className="stat-label">Orientation (Pitch | Roll)</div>
          <div className="stat-value"><Crosshair size={18} color="var(--text-muted)" /> {pitch} | {roll}</div>
        </div>
        <div className="stat-box" style={{ gridColumn: '1 / 3' }}>
          <div className="stat-label">Altitude</div>
          <div className="stat-value">{alt}</div>
        </div>
      </div>
      {children}
    </div>
  );
}

export default App;
