/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Scope signing authority to the visible approval host, independently of auto-logout settings. */
internal class ConnectionForegroundObserver(context: Context, private val available: (Boolean) -> Unit) :
    DefaultLifecycleObserver {
    private val context = context.applicationContext
    private val power = this.context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard = this.context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private var registered = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) available(false)
        }
    }

    fun isDeviceAvailable(): Boolean = power.isInteractive && !keyguard.isDeviceLocked

    override fun onCreate(owner: LifecycleOwner) {
        // System-only broadcasts do not require receiver export flags, including on Android 14+.
        context.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registered = true
    }

    override fun onResume(owner: LifecycleOwner) = available(isDeviceAvailable())
    override fun onPause(owner: LifecycleOwner) = available(false)
    override fun onStop(owner: LifecycleOwner) = available(false)
    override fun onDestroy(owner: LifecycleOwner) {
        available(false)
        if (registered) context.unregisterReceiver(screenOff)
        registered = false
    }
}
