import React, { useState, useEffect, useRef, useMemo } from 'react';
import { Battery, BatteryWarning, Wifi, Navigation, Cpu, Activity, Server, Radio, Crosshair, Users, Flame, ShieldCheck } from 'lucide-react';
import mqtt from 'mqtt';
import './index.css';

const PI_HOST = '10.10.10.2';
const WHEP_BASE = `http://${PI_HOST}:8889`;
const DETECT_BASE = `http://${window.location.hostname}:8091`;
const STREAM = new URLSearchParams(window.location.search).get('stream') || 'drone-1';
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

/** WebRTC (WHEP) từ MediaMTX, tự nối lại khi phiên chết. */
function WebRTCPlayer({ streamName, children, onStatus }) {
  const videoRef = useRef(null);
  const [status, setStatus] = useState('idle');
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
      <video ref={videoRef} autoPlay muted playsInline style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
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
function DetectFeed({ streamName, det }) {
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
        onLoad={() => { setAlive(true); next(150); }}
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

function SysRow({ icon, label, ok, text }) {
  return (
    <div className="sys-item">
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>{icon} {label}</div>
      <span className={ok ? 'badge ok' : 'badge'} style={ok ? undefined : { background: 'rgba(255,255,255,0.12)', color: 'var(--text-muted)' }}>{text}</span>
    </div>
  );
}

/** Ô cảnh báo (đẹp): số người cần cứu hộ / trạng thái cháy. */
function AlertCard({ kind, det }) {
  const fresh = det && Date.now() - det.ts < STALE_MS;
  const live = fresh && det.live !== false;
  if (kind === 'people') {
    const n = live ? (det.people || 0) : 0;
    return (
      <div className="panel alert-card">
        <div className="panel-header"><span><Users size={14} style={{ verticalAlign: 'middle', marginRight: 6 }} />NGƯỜI CẦN CỨU HỘ</span></div>
        <div className="alert-body">
          <div className="alert-icon"><Users size={30} /></div>
          <div>
            <div className="alert-bignum">{n}</div>
            <div className="alert-subtext">{!fresh ? 'Chưa có dữ liệu' : det.live === false ? 'Mất tín hiệu nguồn' : `Đã nhận diện ${n} người cần cứu hộ`}</div>
          </div>
        </div>
      </div>
    );
  }
  const on = live && (det.fire?.on || det.smoke?.on);
  const conf = live ? Math.max(det.fire?.conf || 0, det.smoke?.conf || 0) : 0;
  const smokeOnly = live && !det.fire?.on && det.smoke?.on;
  return (
    <div className={`panel alert-card${on ? ' fire-on' : ''}`}>
      <div className="panel-header"><span><Flame size={14} style={{ verticalAlign: 'middle', marginRight: 6 }} />CẢNH BÁO CHÁY</span></div>
      <div className="alert-body">
        <div className={`alert-icon ${on ? 'fire' : 'safe'}`}><Flame size={30} /></div>
        <div>
          {on ? (
            <>
              <div className="alert-status fire">PHÁT HIỆN {smokeOnly ? 'KHÓI' : 'ĐÁM CHÁY'}</div>
              <div className="alert-subtext" style={{ color: '#ffb3b3' }}>Độ tin cậy {(conf * 100).toFixed(0)}%</div>
            </>
          ) : (
            <>
              <div className="alert-status safe">AN TOÀN</div>
              <div className="alert-subtext">{det?.live === false ? 'Mất tín hiệu nguồn' : 'Không phát hiện cháy/khói'}</div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function App() {
  const [time, setTime] = useState(new Date().toLocaleTimeString());
  const [detections, setDetections] = useState({});
  const [sys, setSys] = useState(null);
  const [videoStatus, setVideoStatus] = useState({});
  const onVideoStatus = useMemo(() => ({ feed1: (st) => setVideoStatus((p) => (p.feed1 === st ? p : { ...p, feed1: st })) }), []);

  useEffect(() => { const t = setInterval(() => setTime(new Date().toLocaleTimeString()), 1000); return () => clearInterval(t); }, []);

  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try { const r = await fetch(`${DETECT_BASE}/status`, { cache: 'no-store' }); const d = await r.json(); if (alive) setSys(d); }
      catch { if (alive) setSys(null); }
    };
    tick();
    const t = setInterval(tick, 5000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  useEffect(() => {
    const client = mqtt.connect(`ws://${PI_HOST}:9001`);
    client.on('connect', () => client.subscribe('drone/detect/#'));
    client.on('message', (topic, message) => {
      const name = topic.split('/').pop();
      try { if (topic.startsWith('drone/detect/')) setDetections((prev) => ({ ...prev, [name]: JSON.parse(message.toString()) })); }
      catch (e) { console.error('detect parse', e); }
    });
    return () => { try { client.end(); } catch { /* noop */ } };
  }, []);

  const det = detections[STREAM];
  const videoReady = videoStatus.feed1 === 'live' ? 1 : 0;
  const fireOn = det && Date.now() - det.ts < STALE_MS && det.live !== false && (det.fire?.on || det.smoke?.on);

  return (
    <div className={`dash2${fireOn ? ' alarm' : ''}`}>
      {/* HÀNG 1: LIVE · RADAR · DETECT */}
      <div className="dash2-row top">
        <div className="panel" style={{ padding: 0 }}>
          <div className="panel-header" style={{ padding: '12px 12px 0', position: 'absolute', zIndex: 20 }}>LIVE FEED — DRONE 1</div>
          <WebRTCPlayer streamName={STREAM} onStatus={onVideoStatus.feed1}>
            <div className="crosshair"></div>
          </WebRTCPlayer>
        </div>
        <div className="panel" style={{ padding: 0, background: '#05070a' }}>
          <DemoRadar det={det} />
        </div>
        <div className="panel" style={{ padding: 0 }}>
          <div className="panel-header" style={{ padding: '12px 12px 0', position: 'absolute', zIndex: 20, color: 'var(--accent)' }}>PHÁT HIỆN AI — DRONE 1</div>
          <DetectFeed streamName={STREAM} det={det} />
        </div>
      </div>

      {/* HÀNG 2: DRONE · SYSTEM · NGƯỜI · CHÁY */}
      <div className="dash2-row bot">
        <FakeDronePanel label="DRONE 1" streamName={STREAM} />
        <div className="panel">
          <div className="panel-header">SYSTEM STATUS <span style={{ color: 'var(--accent)' }}>{time}</span></div>
          <div className="sys-list">
            <SysRow icon={<Server size={14} color="var(--text-muted)" />} label="Internet" ok={!!sys?.internet} text={sys ? (sys.internet ? 'OK' : 'NO NET') : '- -'} />
            <SysRow icon={<Radio size={14} color="var(--text-muted)" />} label="Access Point" ok={!!sys?.ap} text={sys ? (sys.ap ? 'OK' : 'OFFLINE') : '- -'} />
            <SysRow icon={<Cpu size={14} color="var(--text-muted)" />} label="AI Available" ok={!!sys?.ai?.ok} text={!sys ? '- -' : sys.ai?.ok ? (sys.ai.device === 'cuda' ? 'GPU' : 'CPU') : 'NO'} />
            <SysRow icon={<Flame size={14} color="var(--text-muted)" />} label="Nhận diện lửa" ok={!!sys?.ai?.fireModel} text={sys?.ai?.fireModel ? 'BẬT' : '- -'} />
            <SysRow icon={<Activity size={14} color="var(--text-muted)" />} label="Video Ready" ok={videoReady > 0} text={`${videoReady}/1`} />
            <SysRow icon={<Navigation size={14} color="var(--text-muted)" />} label="GPS" ok={!!sys?.gps} text={sys?.gps ? `${sys.gps.lat.toFixed(3)}, ${sys.gps.lon.toFixed(3)}` : '- -'} />
          </div>
        </div>
        <AlertCard kind="people" det={det} />
        <AlertCard kind="fire" det={det} />
      </div>
    </div>
  );
}

/** DRONE 1 — telemetry FAKE (DJI Fly không gửi telemetry). */
function FakeDronePanel({ label, streamName }) {
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
    />
  );
}

/** Bảng thông số drone GỐC. */
function DronePanel({ title, battery, alt, pitch, roll, warning = false, linkDown = null, wifiPercent = null, wifiRssi = null }) {
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
    </div>
  );
}

export default App;
