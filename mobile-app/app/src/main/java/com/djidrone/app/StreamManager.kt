package com.djidrone.app

import android.util.Log
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.camera.StreamInfo
import dji.v5.manager.interfaces.ICameraStreamManager

/**
 * Phát video drone lên RTMP.
 *
 * KHÔNG dùng LiveStreamManager của DJI nữa. Lý do (đã chứng minh bằng tcpdump byte RTMP thô):
 * muxer RTMP nội bộ của DJI tách NAL `SEI` thành một FLV video tag RIÊNG, cùng timestamp
 * với P-slice. MediaMTX coi mỗi tag là một access unit hoàn chỉnh -> đẩy ra WebRTC một AU
 * rác chỉ chứa SEI -> decoder trình duyệt mất sạch P-frame, chỉ còn keyframe -> đúng 1 fps.
 * DJI không expose API nào chạm tới chỗ đó (đã thử hết quality/bitrate/channel/cameraIndex).
 *
 * Nên: lấy H.264 thô qua addReceiveStreamListener (trước khi muxer của DJI đụng vào),
 * gom cả access unit vào MỘT tag rồi tự đẩy qua {@link RtmpPublisher}.
 */
class StreamManager {

    companion object {
        private const val TAG = "DJIStream"
    }

    private val cameraStreamManager: ICameraStreamManager =
        MediaDataCenter.getInstance().cameraStreamManager

    private var publisher: RtmpPublisher? = null
    private var listener: ICameraStreamManager.ReceiveStreamListener? = null
    private var cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    @Volatile private var streaming = false

    private var frameCount = 0L
    private var keyCount = 0L

    fun startStream(rtmpUrl: String, camera: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN) {
        if (streaming) stopStream()

        // start() tra ve ngay; ket noi chay o thread nen (Android cam I/O mang tren main thread).
        val pub = RtmpPublisher(rtmpUrl)
        pub.start()
        publisher = pub
        cameraIndex = camera
        frameCount = 0; keyCount = 0

        val l = object : ICameraStreamManager.ReceiveStreamListener {
            override fun onReceiveStream(
                data: ByteArray, offset: Int, length: Int, info: StreamInfo
            ) {
                val p = publisher ?: return
                if (!p.isConnected) return
                val au = if (offset == 0 && length == data.size) data
                         else data.copyOfRange(offset, offset + length)
                p.sendAccessUnit(au, info.isKeyFrame, info.presentationTimeMs)
                frameCount++
                if (info.isKeyFrame) keyCount++
                if (frameCount % 240L == 0L) {
                    Log.i(TAG, "da gui $frameCount khung ($keyCount keyframe) " +
                            "${info.width}x${info.height}@${info.frameRate} ${info.mimeType}")
                }
            }
        }
        listener = l
        cameraStreamManager.addReceiveStreamListener(camera, l)
        streaming = true
        Log.i(TAG, "bat dau phat $camera -> $rtmpUrl")
    }

    fun stopStream() {
        streaming = false
        listener?.let { cameraStreamManager.removeReceiveStreamListener(it) }
        listener = null
        publisher?.stop()
        publisher = null
        Log.i(TAG, "da dung phat (tong $frameCount khung)")
    }

    /** Dựa vào cờ của mình, không đợi isConnected - kết nối RTMP lên bất đồng bộ. */
    fun isStreaming(): Boolean = streaming
}
