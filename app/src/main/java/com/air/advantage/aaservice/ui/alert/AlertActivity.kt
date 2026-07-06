package com.air.advantage.aaservice.ui.alert

import android.app.Activity
import android.os.Bundle
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.receiver.AlertDialogReceiver

class AlertActivity : Activity() {

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        setContentView(R.layout.layout_alert)
    }

    override fun onResume() {
        super.onResume()
        if (!AlertDialogReceiver.alertActive.get()) {
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (AlertDialogReceiver.alertActive.get()) {
            AlertDialogReceiver().setAlert(this, true, 1200000)
        }
    }
}