package com.djidrone.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import dji.v5.manager.SDKManager

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        checkRegistration()
    }

    private fun checkRegistration() {
        if (SDKManager.getInstance().isRegistered) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            Handler(Looper.getMainLooper()).postDelayed({ checkRegistration() }, 1000)
        }
    }
}
