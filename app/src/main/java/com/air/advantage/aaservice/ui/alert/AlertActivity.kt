package com.air.advantage.aaservice.ui.alert

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.receiver.AlertDialogReceiver

class AlertActivity : Activity() {

    private val hideWarningReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    public override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        setContentView(R.layout.layout_alert)
    }

    public override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.air.advantage.HIDE_WARNING")
        LocalBroadcastManager.getInstance(this).registerReceiver(hideWarningReceiver, filter)
        if (!AlertDialogReceiver.alertActive.get()) {
            finish()
        }
    }

    public override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(hideWarningReceiver)
        super.onPause()
        if (AlertDialogReceiver.alertActive.get()) {
            AlertDialogReceiver().setAlert(this, true, 1200000)
        }
    }
}