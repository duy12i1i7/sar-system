package com.djidrone.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gọi API của "Drone Stream Manager" (Flask) chạy trên Pi.
 *
 * Web manager: http://10.10.10.2:8080  (proxy sang MediaMTX API :9997)
 *   GET /api/streams  -> danh sách path đã định nghĩa
 */
object PiStreamApi {

    private const val TAG = "PiStreamApi"
    const val PI_HOST = "10.10.10.2"
    private const val STREAMS_URL = "http://$PI_HOST:8080/api/streams"

    /** Path catch-all mặc định của MediaMTX, không phải luồng thật -> bỏ khỏi danh sách chọn. */
    private const val CATCH_ALL = "all_others"

    /** URL RTMP để publish vào một luồng. */
    fun rtmpUrlFor(streamName: String) = "rtmp://$PI_HOST:1935/$streamName"

    /**
     * Lấy danh sách tên luồng đã định nghĩa trên Pi (chạy nền, trả kết quả về main thread).
     * onResult(null) nghĩa là lỗi mạng/Pi không phản hồi.
     */
    fun fetchStreamNames(onResult: (List<String>?) -> Unit) {
        Thread {
            val names = try {
                val conn = (URL(STREAMS_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                parseNames(body)
            } catch (e: Exception) {
                Log.e(TAG, "fetch streams failed: $e")
                null
            }
            Handler(Looper.getMainLooper()).post { onResult(names) }
        }.start()
    }

    private fun parseNames(body: String): List<String> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val name = items.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotEmpty() && name != CATCH_ALL) {
                out.add(name)
            }
        }
        return out.sorted()
    }
}
