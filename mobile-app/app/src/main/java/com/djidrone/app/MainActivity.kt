package com.djidrone.app

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout


/**
 * Kế thừa trực tiếp từ DefaultLayoutActivity của DJI UX SDK.
 * Toàn bộ giao diện (Top Bar, Camera Controls, Telemetry, Map, Settings...)
 * được xử lý bởi DefaultLayoutActivity - đảm bảo y hệt app DJI Fly.
 *
 * Chỉ thêm: Nút RTMP Stream.
 */
class MainActivity : CustomLayoutActivity() {

    private lateinit var streamManager: StreamManager
    private var btnStartStream: Button? = null
    /** Gửi telemetry lên MQTT của trạm, chỉ chạy khi đang stream. */
    private var telemetryService: TelemetryService? = null
    /** Luồng đang phát, giữ lại để đổi quality rồi phát lại đúng luồng đó. */
    private var currentStreamName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        streamManager = StreamManager()

        // Khu vực góc dưới-trái giống DJI Fly, có 3 trạng thái:
        // - Bản đồ (mặc định)  - Radar/la bàn  - Thu gọn thành icon
        // Nút X = thu gọn; icon Google Maps = mở lại; nút la bàn = đổi Map <-> Radar.
        val btnToggleMap = findViewById<FrameLayout>(R.id.btn_toggle_map)
        val btnSwitchView = findViewById<FrameLayout>(R.id.btn_switch_map_view)
        val iconSwitchView = findViewById<ImageView>(R.id.icon_switch_map_view)
        val widgetMap = findViewById<dji.v5.ux.map.MapWidget>(R.id.widget_map)
        val widgetCompass = findViewById<View>(R.id.widget_compass)
        val btnMapCollapsed = findViewById<View>(R.id.btn_map_collapsed)

        var isCollapsed = false
        var isCompassMode = false

        fun applyMapState() {
            if (isCollapsed) {
                widgetMap?.visibility = View.GONE
                widgetCompass?.visibility = View.GONE
                btnToggleMap?.visibility = View.GONE
                btnSwitchView?.visibility = View.GONE
                btnMapCollapsed?.visibility = View.VISIBLE
            } else {
                widgetMap?.visibility = if (isCompassMode) View.GONE else View.VISIBLE
                widgetCompass?.visibility = if (isCompassMode) View.VISIBLE else View.GONE
                btnToggleMap?.visibility = View.VISIBLE
                btnSwitchView?.visibility = View.VISIBLE
                btnMapCollapsed?.visibility = View.GONE
                // Icon thể hiện chế độ sẽ chuyển sang khi bấm
                iconSwitchView?.setImageResource(
                    if (isCompassMode) android.R.drawable.ic_menu_mapmode
                    else android.R.drawable.ic_menu_compass
                )
            }
        }

        btnToggleMap?.setOnClickListener {
            isCollapsed = true
            applyMapState()
        }
        btnMapCollapsed?.setOnClickListener {
            isCollapsed = false
            applyMapState()
        }
        btnSwitchView?.setOnClickListener {
            isCompassMode = !isCompassMode
            applyMapState()
        }
        applyMapState()

        // Thêm nút STREAM vào góc trên trái (bên dưới top bar)
        addStreamButton()
    }

    private fun addStreamButton() {
        val rootConstraint = findViewById<ConstraintLayout>(dji.v5.ux.R.id.fpv_holder)?.parent
        if (rootConstraint is ConstraintLayout) {
            btnStartStream = Button(this).apply {
                id = View.generateViewId()
                text = "STREAM"
                setTextColor(Color.parseColor("#FF5252"))
                textSize = 10f
                setBackgroundResource(android.R.color.transparent)
                setPadding(24, 8, 24, 8)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
            }

            val params = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToBottom = dji.v5.ux.R.id.panel_top_bar
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = 8
                marginStart = 12
            }

            rootConstraint.addView(btnStartStream, params)

            btnStartStream?.setOnClickListener {
                if (streamManager.isStreaming()) {
                    stopStreaming()
                } else {
                    scanAndPickStream()
                }
            }
        }
    }

    /** Quét danh sách luồng đã tạo trên Pi rồi cho người dùng chọn luồng để phát. */
    private fun scanAndPickStream() {
        btnStartStream?.text = "Đang quét..."
        PiStreamApi.fetchStreamNames { names ->
            when {
                names == null -> {
                    btnStartStream?.text = "STREAM"
                    Toast.makeText(
                        this,
                        "Không kết nối được trạm ${PiStreamApi.STATION_HOST}:8080",
                        Toast.LENGTH_LONG
                    ).show()
                }
                names.isEmpty() -> {
                    btnStartStream?.text = "STREAM"
                    Toast.makeText(
                        this,
                        "Trạm chưa có luồng nào. Vào http://${PiStreamApi.STATION_HOST}:8080 để tạo.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                else -> {
                    btnStartStream?.text = "STREAM"
                    showStreamPicker(names)
                }
            }
        }
    }

    private fun showStreamPicker(names: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Chọn luồng để phát")
            .setItems(names.toTypedArray()) { _, which -> startStreaming(names[which]) }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    /** Phát video RTMP lên luồng đã chọn, đồng thời âm thầm gửi telemetry theo tên luồng đó. */
    private fun startStreaming(streamName: String) {
        currentStreamName = streamName
        streamManager.startStream(PiStreamApi.rtmpUrlFor(streamName))

        telemetryService?.stop()
        telemetryService = TelemetryService(this, streamName).apply { start() }

        btnStartStream?.text = "⏹ $streamName"
        btnStartStream?.setTextColor(Color.parseColor("#4CAF50"))
        Toast.makeText(this, "Đang phát tới '$streamName'", Toast.LENGTH_SHORT).show()
    }

    private fun stopStreaming() {
        streamManager.stopStream()
        telemetryService?.stop()
        telemetryService = null
        currentStreamName = null

        btnStartStream?.text = "STREAM"
        btnStartStream?.setTextColor(Color.parseColor("#FF5252"))
    }

    override fun onDestroy() {
        telemetryService?.stop()
        telemetryService = null
        super.onDestroy()
    }
}
