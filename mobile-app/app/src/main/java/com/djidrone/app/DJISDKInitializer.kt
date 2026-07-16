package com.djidrone.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback

object DJISDKInitializer {

    /** Dùng chung tag với watchdog để trace cả chuỗi ở một chỗ. */
    private const val TAG = "DJIREWIRE"

    @Volatile
    var isProductConnected = false
        private set

    fun init(context: Context) {
        doInit(context.applicationContext)
    }


    private fun doInit(context: Context) {
        Log.i(TAG, "SDK init... (isRegistered=${SDKManager.getInstance().isRegistered})")
        SDKManager.getInstance().init(context, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                Log.i(TAG, "DJI SDK Registered")
                toast(context, "DJI SDK Registered")
            }

            override fun onRegisterFailure(error: IDJIError) {
                Log.e(TAG, "Register Failed: ${error.description()}")
                toast(context, "Register Failed: ${error.description()}")
            }

            // 3 callback này trước đây để rỗng, nên lúc soi log không biết được máy bay
            // có mặt ở tầng SDK hay không. Log lại để chẩn đoán.
            override fun onProductDisconnect(productId: Int) {
                isProductConnected = false
                Log.w(TAG, "onProductDisconnect: $productId")
            }

            override fun onProductConnect(productId: Int) {
                isProductConnected = true
                Log.i(TAG, "onProductConnect: $productId")
            }

            override fun onProductChanged(productId: Int) {
                Log.i(TAG, "onProductChanged: $productId")
            }

            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                Log.i(TAG, "Init Process: $event, $totalProcess")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
        })
    }

    private fun toast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
