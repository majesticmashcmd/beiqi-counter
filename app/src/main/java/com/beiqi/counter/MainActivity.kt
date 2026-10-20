package com.beiqi.counter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.animation.ScaleAnimation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.beiqi.counter.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), BleManager.BleEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var prefs: CounterPreferences
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

        prefs = CounterPreferences(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        bleManager = BleManager(this, this)

        applyTheme(prefs.currentTheme)
        setupUI()
        updateDashboard()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            checkPermissionsAndConnect()
        }

        binding.btnResetToday.setOnClickListener {
            showResetConfirmation()
        }

        binding.btnSyncDevice.setOnClickListener {
            showManualSyncDialog()
        }

        binding.btnTheme.setOnClickListener {
            showThemeSelector()
        }

        binding.switchClickVibration.isChecked = prefs.vibrateOnClick
        binding.switchClickVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.vibrateOnClick = isChecked
        }

        binding.switchMalaVibration.isChecked = prefs.vibrateOnMala
        binding.switchMalaVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.vibrateOnMala = isChecked
        }

        // Long press on lifetime to reset if desired
        binding.cardLifetime.setOnLongClickListener {
            showResetLifetimeDialog()
            true
        }
    }

    private fun updateDashboard() {
        val currentMala = prefs.getCurrentMalaProgress()
        val totalMalas = prefs.getTotalMalas()
        val today = prefs.getTodayCount()
        val lifetime = prefs.getLifetimeCount()

        binding.tvMalaProgress.text = currentMala.toString()
        binding.malaProgressBar.progress = currentMala
        binding.tvTotalMalas.text = totalMalas.toString()
        binding.tvTodayCount.text = today.toString()
        binding.tvLifetimeCount.text = "%,d".format(lifetime)
    }

    private fun showResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Reset Current Mala?")
            .setMessage("This will reset the current Mala progress back to 0. Lifetime count will be preserved.")
            .setPositiveButton("Reset") { _, _ ->
                prefs.resetTodayAndMala()
                updateDashboard()
                Toast.makeText(this, "Mala reset to 0", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResetLifetimeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Lifetime Count?")
            .setMessage("Do you want to clear your lifetime tally count back to 0?")
            .setPositiveButton("Reset Everything") { _, _ ->
                prefs.resetAllLifetime()
                updateDashboard()
                Toast.makeText(this, "All counts reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualSyncDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Enter count currently on ring"
        }

        AlertDialog.Builder(this)
            .setTitle("Sync Count with Ring")
            .setMessage("Enter the number shown on your BEIQI-ST ring screen to synchronize the app:")
            .setView(input)
            .setPositiveButton("Sync") { _, _ ->
                val count = input.text.toString().toIntOrNull()
                if (count != null && count >= 0) {
                    prefs.syncFromDeviceCount(count, 108)
                    updateDashboard()
                    Toast.makeText(this, "Synced count: $count", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showThemeSelector() {
        val themes = arrayOf(
            CounterPreferences.THEME_GOLD,
            CounterPreferences.THEME_OBSIDIAN,
            CounterPreferences.THEME_EMERALD,
            CounterPreferences.THEME_LOTUS
        )

        AlertDialog.Builder(this)
            .setTitle("Select App Theme")
            .setItems(themes) { _, which ->
                val chosen = themes[which]
                prefs.currentTheme = chosen
                applyTheme(chosen)
            }
            .show()
    }

    private fun applyTheme(themeName: String) {
        val (bgColor, cardColor, strokeColor, accentColor, textColor) = when (themeName) {
            CounterPreferences.THEME_OBSIDIAN -> ThemeColors(
                R.color.obsidian_bg, R.color.obsidian_card, R.color.obsidian_card_stroke,
                R.color.obsidian_accent, R.color.obsidian_text
            )
            CounterPreferences.THEME_EMERALD -> ThemeColors(
                R.color.emerald_bg, R.color.emerald_card, R.color.emerald_card_stroke,
                R.color.emerald_accent, R.color.emerald_text
            )
            CounterPreferences.THEME_LOTUS -> ThemeColors(
                R.color.lotus_bg, R.color.lotus_card, R.color.lotus_card_stroke,
                R.color.lotus_accent, R.color.lotus_text
            )
            else -> ThemeColors(
                R.color.gold_bg, R.color.gold_card, R.color.gold_card_stroke,
                R.color.gold_accent, R.color.gold_text
            )
        }

        val bgResolved = ContextCompat.getColor(this, bgColor)
        val cardResolved = ContextCompat.getColor(this, cardColor)
        val strokeResolved = ContextCompat.getColor(this, strokeColor)
        val accentResolved = ContextCompat.getColor(this, accentColor)
        val textResolved = ContextCompat.getColor(this, textColor)

        binding.mainScrollView.setBackgroundColor(bgResolved)
        window.statusBarColor = bgResolved

        // Card backgrounds & strokes
        listOf(
            binding.statusCard, binding.cardMalas, binding.cardToday,
            binding.cardLifetime, binding.counterHeroCard, binding.settingsCard
        ).forEach {
            it.setCardBackgroundColor(cardResolved)
            it.strokeColor = strokeResolved
        }

        // Texts
        binding.appTitle.setTextColor(textResolved)
        binding.tvMalaProgress.setTextColor(accentResolved)
        binding.tvTotalMalas.setTextColor(accentResolved)
        binding.tvTodayCount.setTextColor(textResolved)
        binding.tvLifetimeCount.setTextColor(textResolved)
        binding.statusText.setTextColor(textResolved)
        binding.tvMalaVibrateTitle.setTextColor(textResolved)
        binding.tvClickVibrateTitle.setTextColor(textResolved)
        binding.lastPacketText.setTextColor(accentResolved)

        // Buttons & Indicators
        binding.btnTheme.setTextColor(accentResolved)
        binding.btnTheme.strokeColor = ColorStateList.valueOf(strokeResolved)
        binding.btnConnect.setTextColor(accentResolved)
        binding.btnSyncDevice.setTextColor(accentResolved)
        binding.btnSyncDevice.strokeColor = ColorStateList.valueOf(strokeResolved)
        binding.malaProgressBar.setIndicatorColor(accentResolved)
        binding.malaProgressBar.trackColor = strokeResolved

        binding.divider1.setBackgroundColor(strokeResolved)
        binding.divider2.setBackgroundColor(strokeResolved)
    }

    private data class ThemeColors(
        val bg: Int,
        val card: Int,
        val stroke: Int,
        val accent: Int,
        val text: Int
    )

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
                binding.syncStatusText.text = "Tap Connect"
                binding.btnConnect.text = "Connect"
            }
            BleManager.ConnectionState.CONNECTING -> {
                binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.status_yellow))
                binding.statusText.text = "Connecting..."
                binding.syncStatusText.text = "Pairing with BEIQI-ST"
                binding.btnConnect.text = "Connecting"
            }
            BleManager.ConnectionState.CONNECTED -> {
                binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.status_yellow))
                binding.statusText.text = "Connected"
                binding.syncStatusText.text = "Syncing services"
                binding.btnConnect.text = "Disconnect"
            }
            BleManager.ConnectionState.READY -> {
                binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))
                binding.statusText.text = "Connected & Active"
                binding.syncStatusText.text = "Live Sync Ready"
                binding.btnConnect.text = "Disconnect"
                Toast.makeText(this, "BEIQI-ST Connected! Ready to count", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onClickRegistered(rawHex: String) {
        val result = prefs.registerClick(malaTarget = 108)
        updateDashboard()

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.lastPacketText.text = "[$timeStr] $rawHex"

        // Animate counter
        val anim = ScaleAnimation(
            1.0f, 1.14f, 1.0f, 1.14f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 80
            repeatMode = ScaleAnimation.REVERSE
            repeatCount = 1
        }
        binding.tvMalaProgress.startAnimation(anim)

        // Handle Vibration
        if (result.malaCompleted && prefs.vibrateOnMala) {
            triggerMalaCelebrationVibration()
            Toast.makeText(this, "📿 Mala Completed! (108 Clicks)", Toast.LENGTH_LONG).show()
        } else if (prefs.vibrateOnClick) {
            triggerClickVibration()
        }
    }

    override fun onDeviceCountDetected(deviceCount: Int, rawHex: String) {
        prefs.syncFromDeviceCount(deviceCount, 108)
        updateDashboard()

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.lastPacketText.text = "[$timeStr Device Sync: $deviceCount] $rawHex"
        binding.syncStatusText.text = "Synced: $deviceCount"
    }

    private fun triggerClickVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(35)
        }
    }

    private fun triggerMalaCelebrationVibration() {
        // Triple distinct pulsing celebration pattern for 108 Mala
        val pattern = longArrayOf(0, 180, 100, 220, 100, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
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
