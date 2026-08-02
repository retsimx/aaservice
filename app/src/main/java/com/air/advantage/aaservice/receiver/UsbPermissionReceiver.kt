package com.air.advantage.aaservice.receiver

import android.content.Context
import android.content.Intent
import com.air.advantage.aaservice.service.UartForegroundService
import com.air.advantage.aaservice.util.ServiceHelper

class UsbPermissionReceiver : BaseReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.hardware.usb.action.USB_ACCESSORY_DETACHED") {
            UartForegroundService.instance?.onAccessoryDetached()
            return
        }
        if (intent.getBooleanExtra("permission", false)) {
            ServiceHelper.scheduleServiceStart(context, ServiceHelper.ACTION_OPEN_DEVICE, 0)
        } else {
            ServiceHelper.scheduleServiceStart(context, ServiceHelper.ACTION_REQUEST_PERMISSION, 200)
        }
    }
}