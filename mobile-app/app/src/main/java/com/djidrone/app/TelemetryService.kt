package com.djidrone.app

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.manager.KeyManager
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

/**
 * Âm thầm thu thập telemetry của drone rồi gửi lên MQTT của Pi.
 *
 * Topic đặt theo tên luồng đang stream: `drone/telemetry/<streamName>`
 * (vd luồng "drone-1" -> topic "drone/telemetry/drone-1"), để dashboard
 * ghép đúng video với thông số của chính drone đó.
 *
 * LƯU Ý KỸ THUẬT: dùng POLLING bằng getValue() thay vì KeyManager.listen().
 * Đã kiểm chứng bằng log: KeyAircraftLocation/KeyAircraftLocation3D KHÔNG phát
 * sự kiện qua listen() (chính MapWidget của DJI cũng đọc bằng getValue()).
 */
class TelemetryService(private val context: Context, private val streamName: String) {

    companion object {
        private const val TAG = "Telemetry"
        private const val BROKER = "tcp://10.10.10.2:1883"
        /**
         * 2 Hz - mắt người không phân biệt được với 5 Hz trên bảng số liệu,
         * nhưng giảm rõ số lần re-render React ở dashboard.
         */
        private const val POLL_INTERVAL_MS = 500L
    }

    private val topic = "drone/telemetry/$streamName"
    private val clientId = "DJI_Android_${streamName}_${System.currentTimeMillis()}"
    private var mqttClient: MqttClient? = null

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    /** Dùng applicationContext để tránh giữ tham chiếu Activity gây rò rỉ bộ nhớ. */
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * GPS của điện thoại = vị trí NGƯỜI LÁI. Trạm mặt đất cần cái này để vẽ radar:
     * mỗi drone có một người lái riêng nên không suy ra được từ vị trí drone.
     */
    private val locationManager: LocationManager? =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            collectAndPublish()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        connectMqtt()
        handler.post(pollRunnable)
        Log.i(TAG, "Started, topic=$topic")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
        val client = mqttClient
        mqttClient = null
        Thread {
            try {
                client?.disconnect()
                client?.close()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect error: $e")
            }
        }.start()
        Log.i(TAG, "Stopped")
    }

    fun isRunning(): Boolean = running

    private fun connectMqtt() {
        Thread {
            try {
                mqttClient = MqttClient(BROKER, clientId, MemoryPersistence()).apply {
                    connect(MqttConnectOptions().apply {
                        isCleanSession = true
                        connectionTimeout = 10
                        keepAliveInterval = 20
                        isAutomaticReconnect = true
                    })
                }
                Log.i(TAG, "Connected to MQTT $BROKER")
            } catch (e: Exception) {
                Log.e(TAG, "MQTT connect failed: $e")
            }
        }.start()
    }

    /** Đọc trực tiếp các key của DJI rồi publish. Field nào chưa có dữ liệu thì bỏ qua. */
    private fun collectAndPublish() {
        val payload = JSONObject()
        var hasData = false

        try {
            val loc = KeyManager.getInstance()
                .getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D))
            if (loc is LocationCoordinate3D && isValidLatLng(loc.latitude, loc.longitude)) {
                payload.put("latitude", loc.latitude)
                payload.put("longitude", loc.longitude)
                payload.put("altitude", loc.altitude)
                hasData = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "read location error: $e")
        }

        try {
            val att = KeyManager.getInstance()
                .getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude))
            if (att is Attitude) {
                payload.put("pitch", att.pitch)
                payload.put("roll", att.roll)
                payload.put("yaw", att.yaw)
                hasData = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "read attitude error: $e")
        }

        try {
            val batt = KeyManager.getInstance()
                .getValue(KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent))
            if (batt is Int) {
                payload.put("battery", batt)
                hasData = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "read battery error: $e")
        }

        // Chặng 1: sóng DRONE <-> RC (OcuSync), 0-100.
        // downlink = drone->RC (đường video), uplink = RC->drone (đường điều khiển).
        try {
            val down = KeyManager.getInstance()
                .getValue(KeyTools.createKey(AirLinkKey.KeyDownLinkQualityRaw))
            if (down is Int) {
                payload.put("linkDown", down)
                hasData = true
            }
            val up = KeyManager.getInstance()
                .getValue(KeyTools.createKey(AirLinkKey.KeyUpLinkQualityRaw))
            if (up is Int) {
                payload.put("linkUp", up)
                hasData = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "read airlink error: $e")
        }

        // Chặng 2: sóng ĐIỆN THOẠI(RC) -> TRẠM, đi qua WiFi nên SDK không biết,
        // phải hỏi Android. rssi tính bằng dBm (càng gần 0 càng mạnh, vd -45 tốt, -85 yếu).
        try {
            val info = wifiManager?.connectionInfo
            val rssi = info?.rssi
            if (rssi != null && rssi != 0 && rssi > -127) {
                payload.put("wifiRssi", rssi)
                // Quy về 0-100 cho dashboard dễ hiển thị
                payload.put("wifiPercent", WifiManager.calculateSignalLevel(rssi, 101))
                hasData = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "read wifi error: $e")
        }

        // Vị trí người lái: đọc bản ghi cuối của Android thay vì tự đăng ký cập nhật -
        // CustomLayoutActivity đã đăng ký sẵn để vẽ marker trên map, nên ở đây chỉ việc
        // lấy kết quả, không tốn thêm pin.
        try {
            val p = lastKnownPilotLocation()
            if (p != null) {
                payload.put("pilotLat", p.latitude)
                payload.put("pilotLon", p.longitude)
                payload.put("pilotAcc", p.accuracy)
                hasData = true
            }
        } catch (e: SecurityException) {
            // chưa cấp quyền vị trí -> bỏ qua, các trường khác vẫn gửi
        } catch (e: Exception) {
            Log.w(TAG, "doc vi tri nguoi lai loi: $e")
        }

        if (hasData) {
            publish(payload)
        }
    }

    /** Lấy bản ghi vị trí mới nhất trong các nguồn khả dụng (GPS ưu tiên hơn network). */
    private fun lastKnownPilotLocation(): Location? {
        val lm = locationManager ?: return null
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        // Quá cũ thì thà không gửi còn hơn gửi vị trí sai (vd đã đi chỗ khác).
        return if (best != null && System.currentTimeMillis() - best!!.time < 120_000) best else null
    }

    /** Drone trả toạ độ rác khi chưa có GPS (lat==lon, ngoài dải ±90/±180) -> lọc bỏ. */
    private fun isValidLatLng(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0 &&
                !(Math.abs(lat) < 1e-6 && Math.abs(lng) < 1e-6)

    private fun publish(payload: JSONObject) {
        val client = mqttClient ?: return
        if (!client.isConnected) return
        try {
            client.publish(topic, MqttMessage(payload.toString().toByteArray()).apply { qos = 0 })
        } catch (e: Exception) {
            Log.w(TAG, "publish error: $e")
        }
    }
}
