package com.air.advantage.aaservice.ui.usb

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.service.RebootNotificationService
import com.air.advantage.aaservice.util.ServiceHelper
import java.lang.ref.WeakReference

class UsbConnectActivity : Activity() {

    companion object {
        private const val TAG = "AAService2/UsbConnect"
        private var instance: WeakReference<Activity>? = null

        fun finishIfShowing() {
            Log.d(TAG, "finishIfShowing: finishing instance=${instance?.get() != null}")
            instance?.get()?.finish()
        }
    }

    public override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        Log.d(TAG, "onCreate")
        setTheme(R.style.Theme_Transparent)
        overridePendingTransition(0, 0)
        instance = WeakReference(this)

        if (RebootNotificationService.rebootRequired.get()) {
            Log.d(TAG, "onCreate: reboot required, showing reboot screen")
            setContentView(R.layout.reboot_now)
            ServiceHelper.setVersionText(this)
        } else {
            Log.i(TAG, "onCreate: starting UART service")
            ServiceHelper.startUartService(this)
            finish()
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        if (instance?.get() == this) {
            instance = null
        }
    }
}