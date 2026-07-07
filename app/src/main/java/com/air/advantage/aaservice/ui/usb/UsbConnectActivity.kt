package com.air.advantage.aaservice.ui.usb

import android.app.Activity
import android.os.Bundle
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.service.RebootNotificationService
import com.air.advantage.aaservice.util.ServiceHelper
import java.lang.ref.WeakReference

class UsbConnectActivity : Activity() {

    companion object {
        private const val TAG = "UsbConnectActivity"
        private var instance: WeakReference<Activity>? = null

        fun finishIfShowing() {
            instance?.get()?.finish()
        }
    }

    public override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        setTheme(R.style.Theme_Transparent)
        overridePendingTransition(0, 0)
        instance = WeakReference(this)

        if (RebootNotificationService.rebootRequired.get()) {
            setContentView(R.layout.reboot_now)
            ServiceHelper.setVersionText(this)
        } else {
            ServiceHelper.startUartService(this)
            finish()
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        if (instance?.get() == this) {
            instance = null
        }
    }
}