package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.air.advantage.aaservice.util.ServiceHelper

class UsbPermissionReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.air.advantage.USB_PERMISSION") return

        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        if (granted) {
            ServiceHelper.scheduleServiceStart(context, ServiceHelper.ACTION_OPEN_DEVICE, 0)
        } else {
            ServiceHelper.scheduleServiceStart(context, ServiceHelper.ACTION_REQUEST_PERMISSION, 200)
        }
    }
}