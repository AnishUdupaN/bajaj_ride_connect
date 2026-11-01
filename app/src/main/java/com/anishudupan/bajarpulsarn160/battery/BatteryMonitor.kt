package com.anishudupan.bajarpulsarn160.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryInfo(
    val percentage: Int = 100,
    val batteryBars: Int = 3
)

class BatteryMonitor(private val context: Context) {

    private val _batteryInfo = MutableStateFlow(BatteryInfo())
    val batteryInfo: StateFlow<BatteryInfo> = _batteryInfo.asStateFlow()

    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                updateBatteryFromIntent(intent)
            }
        }
    }

    fun start() {
        if (isRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = context.registerReceiver(receiver, filter)
        if (stickyIntent != null) {
            updateBatteryFromIntent(stickyIntent)
        }
        isRegistered = true
    }

    fun stop() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
            // Ignore if already unregistered
        }
        isRegistered = false
    }

    private fun updateBatteryFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            val pct = ((level.toFloat() / scale.toFloat()) * 100).toInt().coerceIn(0, 100)
            val bars = calculateBatteryBars(pct)
            _batteryInfo.value = BatteryInfo(percentage = pct, batteryBars = bars)
        }
    }

    companion object {
        fun calculateBatteryBars(percentage: Int): Int {
            return when {
                percentage >= 75 -> 3
                percentage >= 50 -> 2
                percentage >= 20 -> 1
                else -> 0
            }
        }
    }
}
