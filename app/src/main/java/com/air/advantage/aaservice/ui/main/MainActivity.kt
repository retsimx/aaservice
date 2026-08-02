package com.air.advantage.aaservice.ui.main

import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.air.advantage.aaservice.R
import com.air.advantage.aaservice.receiver.DeviceAdminReceiver
import com.air.advantage.aaservice.service.RebootNotificationService
import com.air.advantage.aaservice.service.TransportStatusStore
import com.air.advantage.aaservice.util.PreferencesManager
import com.air.advantage.aaservice.util.ServiceHelper
import com.air.advantage.aaservice.util.TransportMode
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity(), View.OnClickListener {

    companion object {
        private const val TAG = "AAService2/Main"
        val isVisible = AtomicBoolean(false)
    }

    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private lateinit var componentName: ComponentName
    lateinit var devicePolicyManager: DevicePolicyManager

    /** Suppress RadioGroup callbacks while syncing controls from prefs. */
    private var suppressTransportModeCallback = false

    private var transportStatusJob: Job? = null

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(this, DeviceAdminReceiver::class.java)
        Log.d(TAG, "onCreate")
    }

    public override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        isVisible.set(true)
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

    public override fun onPause() {
        super.onPause()
        isVisible.set(false)
        transportStatusJob?.cancel()
        transportStatusJob = null
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

        bindTransportControls()
    }

    private fun bindTransportControls() {
        val modeGroup = findViewById<RadioGroup>(R.id.transport_mode_group)
        val urlField = findViewById<EditText>(R.id.transport_daemon_url)
        val saveButton = findViewById<View>(R.id.transport_url_save)
        val statusView = findViewById<TextView>(R.id.transport_connection_status)

        suppressTransportModeCallback = true
        when (preferencesManager.transportMode) {
            TransportMode.Usb -> modeGroup.check(R.id.transport_mode_usb)
            TransportMode.Ws -> modeGroup.check(R.id.transport_mode_ws)
        }
        suppressTransportModeCallback = false

        urlField.setText(preferencesManager.daemonWsUrl)
        applyTransportStatusToView(statusView, viewModel.transportConnectionStatus.value)

        transportStatusJob?.cancel()
        transportStatusJob = lifecycleScope.launch {
            TransportStatusStore.status.collect { modeStatus ->
                val ui = modeStatus.toTransportConnectionStatus()
                viewModel.setTransportConnectionStatus(ui)
                applyTransportStatusToView(statusView, ui)
            }
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressTransportModeCallback) return@setOnCheckedChangeListener
            val selected = when (checkedId) {
                R.id.transport_mode_usb -> TransportMode.Usb
                R.id.transport_mode_ws -> TransportMode.Ws
                else -> return@setOnCheckedChangeListener
            }
            if (selected == preferencesManager.transportMode) return@setOnCheckedChangeListener

            preferencesManager.transportMode = selected
            val extras = Bundle().apply {
                putString(ServiceHelper.EXTRA_TRANSPORT_MODE, selected.value)
            }
            ServiceHelper.startUartService(
                this,
                ServiceHelper.ACTION_TRANSPORT_MODE_CHANGED,
                extras,
            )
        }

        saveButton.setOnClickListener {
            preferencesManager.daemonWsUrl = urlField.text?.toString().orEmpty()
            // Reflect any default applied by PreferencesManager (e.g. blank → default URL).
            urlField.setText(preferencesManager.daemonWsUrl)
        }
    }

    private fun applyTransportStatusToView(
        statusView: TextView,
        status: TransportConnectionStatus,
    ) {
        statusView.setText(statusStringRes(status))
    }

    private fun statusStringRes(status: TransportConnectionStatus): Int = when (status) {
        TransportConnectionStatus.Idle -> R.string.transport_status_idle
        TransportConnectionStatus.Connecting -> R.string.transport_status_connecting
        TransportConnectionStatus.Connected -> R.string.transport_status_connected
        TransportConnectionStatus.Error -> R.string.transport_status_error
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
                Log.d(TAG, "onClick: requesting device admin activation")
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

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 12345) {
            if (resultCode != RESULT_OK) {
                Log.d(TAG, "Admin not enabled")
                updateUI()
            } else {
                Log.d(TAG, "Admin enabled")
                ServiceHelper.startUartService(this, null)
                updateUI()
            }
        }
    }

    private fun isOnAAHardware(): Boolean {
        return true
    }
}
