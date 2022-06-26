package org.dash.wallet.integration.liquid.ui

import android.app.Activity
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.os.Bundle
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class LiquidAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceBundle: Bundle?) {
        super.onCreate(savedInstanceBundle)

        val liquidIntent = Intent(this, LiquidSplashActivity::class.java).apply {
            data = intent.data
            action = ACTION_VIEW
        }
        liquidIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        liquidIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (isTaskRoot) {

            liquidIntent.action = LiquidSplashActivity.FINISH_ACTION

            //I'm in my own task and not the main task
             LocalBroadcastManager.getInstance(this).sendBroadcast(liquidIntent)
        } else {
            startActivity(liquidIntent)
        }
        finish()
    }
}