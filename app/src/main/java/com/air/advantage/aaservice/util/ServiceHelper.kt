package com.air.advantage.aaservice.util

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.widget.TextView
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.service.UartForegroundService

object ServiceHelper {

    const val ACTION_REBOOT_DEVICE = "com.air.advantage.REBOOT_DEVICE"

    fun getUsbAccessory(context: Context): UsbAccessory? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val accessories = usbManager.accessoryList
        return accessories?.firstOrNull()
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val devicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        return devicePolicyManager.isAdminActive(
            android.content.ComponentName(context, com.air.advantage.aaservice.receiver.DeviceAdminReceiver::class.java)
        )
    }

    @JvmStatic
    fun scheduleServiceStart(context: Context, action: String, delayMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, UartForegroundService::class.java).apply { setAction(action) }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, R.string.app_name, intent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            @Suppress("DEPRECATION")
            PendingIntent.getService(context, R.string.app_name, intent, PendingIntent.FLAG_IMMUTABLE)
        }
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + delayMs,
            pendingIntent
        )
    }

    fun cancelScheduledServiceStart(context: Context, action: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, UartForegroundService::class.java).apply { setAction(action) }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, R.string.app_name, intent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            @Suppress("DEPRECATION")
            PendingIntent.getService(context, R.string.app_name, intent, PendingIntent.FLAG_IMMUTABLE)
        }
        alarmManager.cancel(pendingIntent)
    }

    fun startUartService(context: Context, action: String? = null) {
        val intent = Intent(context, UartForegroundService::class.java).apply {
            if (action != null) setAction(action)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(intent)
        }
    }

    fun stopUartService(context: Context, action: String? = null) {
        val intent = Intent(context, UartForegroundService::class.java).apply {
            if (action != null) setAction(action)
        }
        context.stopService(intent)
    }

    fun setVersionText(activity: Activity) {
        val textView = activity.findViewById<TextView>(R.id.version_number)
        val version = try {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
        val variant = Build.BRAND + " " + Build.MODEL
        textView.text = "Version $version : $variant"
    }
}