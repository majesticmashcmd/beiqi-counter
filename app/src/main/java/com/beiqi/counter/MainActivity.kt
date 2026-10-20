package com.beiqi.counter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.animation.ScaleAnimation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.beiqi.counter.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), BleManager.BleEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private var vibrator: Vibrator? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleManager.connect()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to connect", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        bleManager = BleManager(this, this)

        setupUI()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            checkPermissionsAndConnect()
        }

        binding.btnReset.setOnClickListener {
            bleManager.resetCount()
            binding.counterNumber.text = "0"
            binding.lastPacketText.text = "Counter reset to 0"
        }
    }

    private fun checkPermissionsAndConnect() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            bleManager.connect()
        }
    }

    override fun onConnectionStateChanged(state: BleManager.ConnectionState) {
        when (state) {
            BleManager.ConnectionState.DISCONNECTED -> {
                binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
                binding.statusText.text = "Disconnected"
                binding.btnConnect.text = "Connect"
            }
            BleManager.ConnectionState.CONNECTING -> {
                binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.status_yellow))
                binding.statusText.text = "Connecting..."
                binding.btnConnect.text = "Connecting"
            }
            BleManager.ConnectionState.CONNECTED -> {
                binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.status_yellow))
                binding.statusText.text = "Discovering..."
                binding.btnConnect.text = "Disconnect"
            }
            BleManager.ConnectionState.READY -> {
                binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))
                binding.statusText.text = "Connected & Ready"
                binding.btnConnect.text = "Disconnect"
                Toast.makeText(this, "Ready! Click your BEIQI-ST counter", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onClickRegistered(currentCount: Int, rawHex: String) {
        binding.counterNumber.text = currentCount.toString()

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.lastPacketText.text = "[$timeStr] $rawHex"

        // Animate count bump
        val scaleAnim = ScaleAnimation(
            1.0f, 1.15f, 1.0f, 1.15f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 90
            repeatMode = ScaleAnimation.REVERSE
            repeatCount = 1
        }
        binding.counterNumber.startAnimation(scaleAnim)

        // Haptic feedback
        if (binding.switchVibration.isChecked) {
            triggerVibration()
        }
    }

    private fun triggerVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(35)
        }
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}
