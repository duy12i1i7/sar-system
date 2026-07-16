package com.djidrone.app

import android.app.Application
import android.content.Context
import android.util.Log

class MainApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        
        Log.i("MainApplication", "Starting SDK Initialization...")
        DJISDKInitializer.init(this)
    }
}
