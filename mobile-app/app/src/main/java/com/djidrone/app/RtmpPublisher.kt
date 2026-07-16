package com.djidrone.app

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Bộ đẩy RTMP tự viết, thay cho LiveStreamManager của DJI.
 *
 * VÌ SAO PHẢI TỰ VIẾT (đã chứng minh bằng tcpdump byte RTMP thô, không qua ffmpeg):
 * muxer RTMP nội bộ của DJI tách NAL `SEI` thành một FLV video tag RIÊNG, cùng timestamp
 * với P-slice:
 *     ts=27 frameType=2 len=61   -> SEI(52B)        <- tag riêng
 *     ts=27 frameType=2 len=2101 -> P-slice(2092B)  <- tag khác, CÙNG ts
 * Đúng chuẩn thì SEI + slice phải nằm chung MỘT tag (một access unit). MediaMTX coi mỗi
 * tag là một access unit hoàn chỉnh -> đẩy ra WebRTC một AU rác chỉ chứa SEI -> decoder
 * của trình duyệt loạn -> mất sạch P-frame, chỉ còn keyframe -> đúng 1 fps.
 * Keyframe không dính vì DJI gửi nó thành một tag duy nhất.
 * DJI không expose API nào chạm tới chỗ tách NAL đó, nên cách duy nhất là bỏ hẳn
 * LiveStreamManager, lấy H.264 thô rồi tự đóng gói.
 *
 * Ở đây gom TẤT CẢ NAL của một access unit vào MỘT tag -> MediaMTX xử lý đúng.
 */
class RtmpPublisher(private val url: String) {

    companion object {
        private const val TAG = "DJIRtmp"
        private const val CHUNK_SIZE = 4096
        /** csid 4: quy ước dùng cho video, giống các publisher khác. */
        private const val CSID_VIDEO = 4
        private const val CSID_CMD = 3
        private const val MSG_VIDEO = 9
        private const val MSG_SET_CHUNK_SIZE = 1
        private const val MSG_AMF0_CMD = 20
        /** ~24fps. Dung khi phai dich moc timestamp luc drone khoi dong lai. */
        private const val FRAME_STEP_MS = 40L
    }

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var inp: DataInputStream? = null
    /**
     * RTMP quy dinh chunk size mac dinh = 128. Chi duoc cat theo CHUNK_SIZE lon hon
     * SAU KHI da gui message "set chunk size". Cat 4096 ngay tu `connect` thi server
     * doc 128 byte roi tuong byte ke tiep la header chunk -> "received type 1 chunk
     * without previous chunk" -> dong ket noi.
     */
    private var outChunkSize = 128
    @Volatile private var running = false
    @Volatile var isConnected = false
        private set

    private val queue = LinkedBlockingQueue<Frame>(60)
    private var sender: Thread? = null
    private var startMs = 0L
    private var sentSeqHeader = false
    /** Timestamp cuối đã gửi, để giữ tăng đơn điệu khi drone khởi động lại. -1 = chưa gửi gì. */
    private var lastOutTs = -1L

    private class Frame(val data: ByteArray, val isKey: Boolean, val ptsMs: Long)

    // ---------- API ----------

    /**
     * Bắt đầu phát. Việc mở socket chạy trong thread nền (Android cấm I/O mạng trên main
     * thread), nên hàm này trả về ngay; khung nào tới trước khi kết nối xong thì bị bỏ.
     * Rớt mạng thì tự nối lại cho tới khi {@link #stop()} - drone bay xa, wifi chập chờn
     * là chuyện thường, không thể bắt người dùng bấm lại nút.
     */
    fun start(): Boolean {
        if (running) return true
        running = true
        sender = Thread({ connectLoop() }, "rtmp-sender").apply { start() }
        return true
    }

    /** Nối -> phát -> rớt -> chờ (backoff) -> nối lại. Thoát khi stop(). */
    private fun connectLoop() {
        var backoffMs = 1000L
        while (running) {
            try {
                connect()
                Log.i(TAG, "da ket noi va publish: $url")
                backoffMs = 1000L          // noi duoc roi thi reset backoff
                senderLoop()               // chi tra ve khi rot hoac stop
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "ket noi that bai: $e")
            }
            if (!running) break
            closeSocket()
            Log.i(TAG, "thu noi lai sau ${backoffMs}ms")
            try {
                Thread.sleep(backoffMs)
            } catch (e: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(15000L)
        }
        closeSocket()
        Log.i(TAG, "sender dung han")
    }

    fun stop() {
        running = false
        sender?.interrupt()
        sender = null
        close()
    }

    /**
     * Đẩy một access unit H.264 (Annex-B, có thể chứa nhiều NAL: SEI + slice).
     * Hàng đợi có hạn: đầy thì bỏ khung cũ nhất, thà rớt hình còn hơn dồn ứ trễ tăng dần.
     */
    fun sendAccessUnit(annexB: ByteArray, isKey: Boolean, ptsMs: Long) {
        if (!running) return
        if (!queue.offer(Frame(annexB, isKey, ptsMs))) {
            queue.poll()
            queue.offer(Frame(annexB, isKey, ptsMs))
        }
    }

    // ---------- Vòng gửi ----------

    /** Trả về khi rớt kết nối (để connectLoop nối lại) hoặc khi stop(). */
    private fun senderLoop() {
        while (running) {
            try {
                val f = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                writeFrame(f)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.e(TAG, "mat ket noi khi gui: $e")
                isConnected = false
                return
            }
        }
    }

    private fun writeFrame(f: Frame) {
        val nals = splitAnnexB(f.data)
        if (nals.isEmpty()) return

        if (!sentSeqHeader) {
            val sps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 7 }
            val pps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 8 }
            if (sps == null || pps == null) return  // chờ tới keyframe đầu có SPS/PPS
            writeVideoMessage(avcSequenceHeader(sps, pps), 0, true, true)
            sentSeqHeader = true
            startMs = f.ptsMs
            Log.i(TAG, "da gui sequence header (SPS ${sps.size}B, PPS ${pps.size}B)")
        }

        // Gom MOI NAL cua access unit vao MOT tag (tru SPS/PPS da nam o seq header).
        // Day chinh la cho DJI lam sai: no tach SEI ra tag rieng.
        val body = ArrayList<ByteArray>()
        for (n in nals) {
            val t = n[0].toInt() and 0x1F
            if (t == 7 || t == 8) continue
            body.add(n)
        }
        if (body.isEmpty()) return

        writeVideoMessage(avcNalu(body), nextTimestamp(f.ptsMs), f.isKey, false)
    }

    /**
     * Đổi presentationTimeMs của SDK thành timestamp RTMP, GIỮ TĂNG ĐƠN ĐIỆU.
     *
     * Vì sao cần: tắt drone thay pin (app không tắt) thì phiên camera mới bắt đầu lại từ
     * đầu -> presentationTimeMs NHẢY LÙI (thường về ~0). Bản đầu tôi tính
     * `ts = ptsMs - startMs` rồi `coerceAtLeast(0)`: mọi khung sau đó đều thành ts=0
     * -> timestamp không tăng -> MediaMTX/WebRTC đứng hình ở khung cuối. Cái coerce đó
     * CHE lỗi chứ không xử lý.
     * Ở đây phát hiện gián đoạn (lùi, hoặc nhảy vọt > 5s) rồi dịch mốc để timestamp ra
     * vẫn tăng đều, đồng thời gửi lại SPS/PPS vì phiên mới có thể đổi thông số.
     */
    private fun nextTimestamp(ptsMs: Long): Long {
        val raw = ptsMs - startMs
        val jumpedBack = raw < lastOutTs
        val jumpedFar = raw - lastOutTs > 5000
        if (lastOutTs >= 0 && (jumpedBack || jumpedFar)) {
            Log.w(TAG, "pts gian doan (${lastOutTs} -> $raw) - drone khoi dong lai? " +
                    "dich moc va gui lai sequence header")
            startMs = ptsMs - (lastOutTs + FRAME_STEP_MS)
            sentSeqHeader = false   // phien moi: gui lai SPS/PPS o keyframe ke tiep
        }
        val ts = (ptsMs - startMs).coerceAtLeast(lastOutTs.coerceAtLeast(0))
        lastOutTs = ts
        return ts
    }

    /** FLV VIDEODATA: [frameType|codecId][AVCPacketType][cts 3 byte][payload] */
    private fun avcNalu(nals: List<ByteArray>): ByteArray {
        var size = 5
        for (n in nals) size += 4 + n.size
        val b = ByteArray(size)
        b[0] = 0 // se dien o writeVideoMessage
        b[1] = 1 // AVCPacketType = 1 (NALU)
        b[2] = 0; b[3] = 0; b[4] = 0 // composition time = 0 (khong co B-frame)
        var o = 5
        for (n in nals) {
            b[o] = (n.size ushr 24).toByte(); b[o+1] = (n.size ushr 16).toByte()
            b[o+2] = (n.size ushr 8).toByte(); b[o+3] = n.size.toByte()
            System.arraycopy(n, 0, b, o + 4, n.size)
            o += 4 + n.size
        }
        return b
    }

    /** AVCDecoderConfigurationRecord (AVCC) - gửi một lần trước khi phát NALU. */
    private fun avcSequenceHeader(sps: ByteArray, pps: ByteArray): ByteArray {
        val b = ByteArray(16 + sps.size + pps.size)
        var o = 0
        b[o++] = 0; b[o++] = 0 // frameType dien sau; AVCPacketType = 0
        b[o++] = 0; b[o++] = 0; b[o++] = 0 // cts
        b[o++] = 1                    // configurationVersion
        b[o++] = sps[1]               // AVCProfileIndication
        b[o++] = sps[2]               // profile_compatibility
        b[o++] = sps[3]               // AVCLevelIndication
        b[o++] = 0xFF.toByte()        // 6 bit reserved + lengthSizeMinusOne = 3
        b[o++] = 0xE1.toByte()        // 3 bit reserved + numOfSPS = 1
        b[o++] = (sps.size ushr 8).toByte(); b[o++] = sps.size.toByte()
        System.arraycopy(sps, 0, b, o, sps.size); o += sps.size
        b[o++] = 1                    // numOfPPS
        b[o++] = (pps.size ushr 8).toByte(); b[o++] = pps.size.toByte()
        System.arraycopy(pps, 0, b, o, pps.size); o += pps.size
        return b.copyOf(o)
    }

    private fun writeVideoMessage(payload: ByteArray, ts: Long, isKey: Boolean, isSeqHdr: Boolean) {
        payload[0] = (((if (isKey || isSeqHdr) 1 else 2) shl 4) or 7).toByte() // frameType|AVC
        if (isSeqHdr) payload[1] = 0
        writeMessage(CSID_VIDEO, MSG_VIDEO, ts, payload)
    }

    /** Cắt Annex-B (00 00 01 hoặc 00 00 00 01) thành từng NAL. */
    private fun splitAnnexB(d: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(4)
        var i = 0
        var start = -1
        while (i + 2 < d.size) {
            val sc3 = d[i].toInt() == 0 && d[i+1].toInt() == 0 && d[i+2].toInt() == 1
            val sc4 = i + 3 < d.size && d[i].toInt() == 0 && d[i+1].toInt() == 0 &&
                    d[i+2].toInt() == 0 && d[i+3].toInt() == 1
            if (sc3 || sc4) {
                val scLen = if (sc4) 4 else 3
                if (start >= 0 && i > start) out.add(d.copyOfRange(start, i))
                i += scLen
                start = i
            } else i++
        }
        if (start in 0 until d.size) out.add(d.copyOfRange(start, d.size))
        return out.filter { it.isNotEmpty() }
    }

    // ---------- Tầng RTMP ----------

    private fun connect() {
        val u = URI(url)
        val host = u.host
        val port = if (u.port > 0) u.port else 1935
        // MediaMTX ghep path = "<app>/<publish name>" roi bo dau '/' thua.
        // Voi rtmp://host/drone-1 phai la app="" + publish("drone-1") -> path "drone-1".
        // Neu gui app="drone-1" + publish("drone-1") thi ra path "drone-1/drone-1" (sai,
        // roi vao all_others chu khong vao dung luong da khai).
        val path = u.path.trimStart('/')
        val appName = path.substringBeforeLast('/', "")
        val playName = path.substringAfterLast('/')

        val s = Socket(host, port).apply { tcpNoDelay = true; soTimeout = 10000 }
        socket = s
        out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        inp = DataInputStream(s.getInputStream())

        handshake()
        // Bao chunk size moi TRUOC connect: message nay chi 4 byte nen lot qua duoc
        // chunk size mac dinh 128, va tu sau day moi duoc cat theo CHUNK_SIZE.
        writeMessage(CSID_CMD, MSG_SET_CHUNK_SIZE, 0,
            byteArrayOf((CHUNK_SIZE ushr 24).toByte(), (CHUNK_SIZE ushr 16).toByte(),
                (CHUNK_SIZE ushr 8).toByte(), CHUNK_SIZE.toByte()))
        out?.flush()
        outChunkSize = CHUNK_SIZE
        sendConnect(appName, "rtmp://$host:$port/$appName")
        drainUntilResult()
        sendCommand("releaseStream", 2.0, playName)
        sendCommand("FCPublish", 3.0, playName)
        sendCreateStream(4.0)
        drainUntilResult()
        sendPublish(playName)
        out?.flush()
        isConnected = true
    }

    private fun handshake() {
        val o = out!!
        val i = inp!!
        o.write(3) // C0
        val c1 = ByteArray(1536)
        Random.nextBytes(c1)
        // 4 byte time + 4 byte zero roi moi den random
        for (k in 0..7) c1[k] = 0
        o.write(c1)
        o.flush()
        val s0 = i.readUnsignedByte()
        if (s0 != 3) throw IllegalStateException("S0 sai: $s0")
        val s1 = ByteArray(1536); i.readFully(s1)
        o.write(s1); o.flush()      // C2 = echo S1
        val s2 = ByteArray(1536); i.readFully(s2)
    }

    private fun drainUntilResult(timeoutMs: Long = 3000) {
        // Doc qua loa cho het _result/onStatus, khong parse ky - MediaMTX khong bat buoc.
        val end = System.currentTimeMillis() + timeoutMs
        val i = inp ?: return
        try {
            while (System.currentTimeMillis() < end) {
                if (i.available() <= 0) { Thread.sleep(30); continue }
                val buf = ByteArray(i.available().coerceAtMost(8192))
                i.read(buf)
                if (String(buf, Charsets.ISO_8859_1).contains("_result")) return
            }
        } catch (e: Exception) {
            Log.w(TAG, "drain: $e")
        }
    }

    // ---- AMF0 ----
    private fun amfStr(sb: java.io.ByteArrayOutputStream, s: String) {
        sb.write(2); val b = s.toByteArray()
        sb.write(b.size ushr 8); sb.write(b.size and 0xFF); sb.write(b)
    }
    private fun amfNum(sb: java.io.ByteArrayOutputStream, d: Double) {
        sb.write(0); val bits = java.lang.Double.doubleToLongBits(d)
        for (k in 7 downTo 0) sb.write(((bits ushr (k * 8)) and 0xFF).toInt())
    }
    private fun amfNull(sb: java.io.ByteArrayOutputStream) = sb.write(5)
    private fun amfPropStr(sb: java.io.ByteArrayOutputStream, k: String, v: String) {
        val b = k.toByteArray(); sb.write(b.size ushr 8); sb.write(b.size and 0xFF); sb.write(b)
        amfStr(sb, v)
    }
    private fun amfPropNum(sb: java.io.ByteArrayOutputStream, k: String, v: Double) {
        val b = k.toByteArray(); sb.write(b.size ushr 8); sb.write(b.size and 0xFF); sb.write(b)
        amfNum(sb, v)
    }

    private fun sendConnect(app: String, tcUrl: String) {
        val sb = java.io.ByteArrayOutputStream()
        amfStr(sb, "connect"); amfNum(sb, 1.0)
        sb.write(3) // object
        amfPropStr(sb, "app", app)
        amfPropStr(sb, "type", "nonprivate")
        amfPropStr(sb, "flashVer", "FMLE/3.0 (compatible; DJIDrone)")
        amfPropStr(sb, "tcUrl", tcUrl)
        sb.write(0); sb.write(0); sb.write(9) // object end
        writeMessage(CSID_CMD, MSG_AMF0_CMD, 0, sb.toByteArray())
        out?.flush()
    }

    private fun sendCommand(name: String, tid: Double, arg: String) {
        val sb = java.io.ByteArrayOutputStream()
        amfStr(sb, name); amfNum(sb, tid); amfNull(sb); amfStr(sb, arg)
        writeMessage(CSID_CMD, MSG_AMF0_CMD, 0, sb.toByteArray())
        out?.flush()
    }

    private fun sendCreateStream(tid: Double) {
        val sb = java.io.ByteArrayOutputStream()
        amfStr(sb, "createStream"); amfNum(sb, tid); amfNull(sb)
        writeMessage(CSID_CMD, MSG_AMF0_CMD, 0, sb.toByteArray())
        out?.flush()
    }

    private fun sendPublish(name: String) {
        val sb = java.io.ByteArrayOutputStream()
        amfStr(sb, "publish"); amfNum(sb, 5.0); amfNull(sb)
        amfStr(sb, name); amfStr(sb, "live")
        writeMessage(CSID_CMD, MSG_AMF0_CMD, 0, sb.toByteArray(), streamId = 1)
        out?.flush()
    }

    /** Ghi một RTMP message, tự cắt thành chunk theo CHUNK_SIZE. */
    @Synchronized
    private fun writeMessage(csid: Int, type: Int, ts: Long, payload: ByteArray, streamId: Int = 0) {
        val o = out ?: return
        val t = ts.coerceAtMost(0xFFFFFF).toInt()
        // fmt0: header day du
        o.write((0 shl 6) or csid)
        o.write(t ushr 16); o.write(t ushr 8); o.write(t)
        o.write(payload.size ushr 16); o.write(payload.size ushr 8); o.write(payload.size)
        o.write(type)
        o.write(streamId); o.write(streamId ushr 8); o.write(streamId ushr 16); o.write(streamId ushr 24)
        var off = 0
        while (off < payload.size) {
            val n = minOf(outChunkSize, payload.size - off)
            o.write(payload, off, n)
            off += n
            if (off < payload.size) o.write((3 shl 6) or csid) // fmt3: chunk tiep theo
        }
        if (type == MSG_VIDEO) o.flush()
    }

    /**
     * Đóng socket và reset về trạng thái sẵn sàng nối lại.
     * Quan trọng: xoá hàng đợi (khung cũ vô dụng, gửi lên chỉ tăng trễ) và hạ
     * sentSeqHeader để phiên mới gửi lại SPS/PPS rồi mới phát - phiên RTMP mới phải
     * bắt đầu bằng sequence header + keyframe, không thì server không dựng được track.
     */
    private fun closeSocket() {
        isConnected = false
        try { out?.flush() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null; inp = null
        sentSeqHeader = false
        lastOutTs = -1L
        outChunkSize = 128
        queue.clear()
    }

    private fun close() = closeSocket()
}
