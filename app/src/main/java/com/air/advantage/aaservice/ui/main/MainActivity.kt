package com.air.advantage.aaservice.ui.main

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.receiver.DeviceAdminReceiver
import com.air.advantage.aaservice.service.RebootNotificationService
import com.air.advantage.aaservice.util.ServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), View.OnClickListener {

    companion object {
        private const val TAG = "MainActivity"
        val isActive = AtomicBoolean(false)
    }

    private lateinit var componentName: ComponentName
    private lateinit var devicePolicyManager: DevicePolicyManager

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(this, DeviceAdminReceiver::class.java)
        Log.d(TAG, "onCreate")
    }

    override fun onResume() {
        super.onResume()
        isActive.set(true)
        if (RebootNotificationService.rebootRequired.get()) {
            setContentView(R.layout.reboot_now)
            ServiceHelper.setVersionText(this)
        } else {
            if (isOnAAHardware()) {
                setContentView(R.layout.activity_main)
                updateUI()
            } else {
                Log.d(TAG, "Not on AA Hardware")
                Toast.makeText(this, R.string.not_on_aa_hardware_error, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isActive.set(false)
    }

    private fun updateUI() {
        val statusText = findViewById<TextView>(R.id.status_text)
        val permissionDescription = findViewById<TextView>(R.id.permission_description)
        val statusIcon = findViewById<ImageView>(R.id.status_icon)

        if (devicePolicyManager.isAdminActive(componentName)) {
            findViewById<View>(R.id.enable_device_admin).visibility = View.GONE
            permissionDescription.setText(R.string.device_admin_explain_disable)
            statusText.setText(R.string.setup_correctly)
            statusIcon.setImageResource(R.drawable.green_tick)
            findViewById<View>(R.id.disable_device_admin).visibility = View.VISIBLE
        } else {
            findViewById<View>(R.id.enable_device_admin).visibility = View.VISIBLE
            permissionDescription.setText(R.string.device_admin_explain_enable)
            statusText.setText(R.string.setup_incorrectly)
            statusIcon.setImageResource(R.drawable.red_cross)
            findViewById<View>(R.id.disable_device_admin).visibility = View.GONE
        }

        findViewById<View>(R.id.enable_device_admin).setOnClickListener(this)
        findViewById<View>(R.id.disable_device_admin).setOnClickListener(this)
        findViewById<View>(R.id.show_backup).setOnClickListener(this)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.disable_device_admin -> {
                val builder = AlertDialog.Builder(this)
                builder.setPositiveButton("Disable") { dialog, _ ->
                    try {
                        devicePolicyManager.removeActiveAdmin(componentName)
                    } catch (e: Exception) {
                        Log.d(TAG, "Error removing admin", e)
                    }
                    dialog.dismiss()
                }
                builder.setNegativeButton("No  (recommended)") { dialog, _ -> dialog.dismiss() }
                builder.setMessage(R.string.device_admin_warning)
                builder.setTitle("WARNING")
                builder.create().show()
            }
            R.id.enable_device_admin -> {
                val intent = Intent("android.app.action.ADD_DEVICE_ADMIN")
                intent.putExtra("android.app.extra.DEVICE_ADMIN", componentName)
                intent.putExtra("android.app.extra.ADD_EXPLANATION",
                    "Please click 'Activate' for the Air-Conditioning controller to start working.")
                startActivityForResult(intent, 12345)
            }
            R.id.show_backup -> {
                // Handle show backup
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 12345) {
            if (resultCode != RESULT_OK) {
                Log.d(TAG, "Admin not enabled")
                updateUI()
            } else {
                Log.d(TAG, "Admin enabled")
                ServiceHelper.startUartService(this)
                updateUI()
            }
        }
    }

    private fun isOnAAHardware(): Boolean {
        return true
    }
}