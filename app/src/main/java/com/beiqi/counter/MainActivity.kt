package com.beiqi.counter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.animation.ScaleAnimation
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.beiqi.counter.databinding.ActivityMainBinding
import java.util.*

class MainActivity : AppCompatActivity(), BleManager.BleEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var repository: CounterRepository
    private var vibrator: Vibrator? = null

    private var latestHardwareCount: Int? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleManager.connect()
        } else {
            Toast.makeText(this, "Bluetooth permission needed to connect to BEIQI-ST", Toast.LENGTH_LONG).show()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // Non-fatal if URI does not support persistable grant
            }
            val active = repository.getActiveCounter()
            active.imageUri = uri.toString()
            repository.saveActiveCounter(active)
            updateActiveCounterView(active)
            Toast.makeText(this, "Counter photo updated", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = CounterRepository(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        bleManager = BleManager(this, this)

        applyTheme(repository.currentTheme)
        setupUI()
        refreshDashboard()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
        // Auto-reconnect and sync when phone screen opens / app resumes
        checkPermissionsAndConnect()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            checkPermissionsAndConnect()
        }

        binding.btnTheme.setOnClickListener {
            showThemeSelector()
        }

        binding.btnSwitchCounter.setOnClickListener {
            showSwitchCounterDialog()
        }

        binding.btnAddCounter.setOnClickListener {
            showAddCounterDialog()
        }

        binding.btnEditCounter.setOnClickListener {
            showEditCounterDialog()
        }

        binding.imageContainer.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnSyncDevice.setOnClickListener {
            showSyncDialog()
        }

        binding.btnResetMala.setOnClickListener {
            showResetMalaDialog()
        }

        binding.switchClickVibration.isChecked = repository.vibrateOnClick
        binding.switchClickVibration.setOnCheckedChangeListener { _, isChecked ->
            repository.vibrateOnClick = isChecked
        }

        binding.switchMalaVibration.isChecked = repository.vibrateOnMala
        binding.switchMalaVibration.setOnCheckedChangeListener { _, isChecked ->
            repository.vibrateOnMala = isChecked
        }

        binding.cardLifetimeCount.setOnLongClickListener {
            showResetLifetimeDialog()
            true
        }
    }

    private fun refreshDashboard() {
        val active = repository.getActiveCounter()
        updateActiveCounterView(active)
    }

    private fun updateActiveCounterView(counter: CounterProfile) {
        binding.tvCounterName.text = counter.name
        binding.btnSwitchCounter.text = "📿 ${counter.name} ▾"
        binding.tvCurrentBeads.text = counter.currentBeads.toString()
        binding.malaProgressBar.progress = counter.currentBeads

        // Exact 4 Dashboard Metrics
        binding.tvDailyCount.text = counter.dailyCount.toString()
        binding.tvDailyMala.text = counter.dailyMala.toString()
        binding.tvLifetimeCount.text = "%,d".format(counter.lifetimeCount)
        binding.tvLifetimeMala.text = "%,d".format(counter.lifetimeMala)

        // Custom Picture Display
        if (!counter.imageUri.isNullOrEmpty()) {
            try {
                binding.counterImage.setImageURI(Uri.parse(counter.imageUri))
            } catch (e: Exception) {
                binding.counterImage.setImageResource(R.drawable.ic_launcher)
            }
        } else {
            binding.counterImage.setImageResource(R.drawable.ic_launcher)
        }
    }

    private fun showSyncDialog() {
        // If ring recently broadcasted its count, offer to apply it directly
        val detected = latestHardwareCount

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (detected != null && detected > 0) {
                setText(detected.toString())
            } else {
                val active = repository.getActiveCounter()
                setText(active.dailyCount.toString())
            }
            hint = "Count currently on ring screen"
        }

        AlertDialog.Builder(this)
            .setTitle("Sync with BEIQI-ST Ring")
            .setMessage("Confirm the count shown on your ring's display to synchronize:")
            .setView(input)
            .setPositiveButton("Sync Now") { _, _ ->
                val ringCount = input.text.toString().toIntOrNull()
                if (ringCount != null && ringCount >= 0) {
                    val updated = repository.syncActiveCounterWithRing(ringCount)
                    updateActiveCounterView(updated)
                    binding.syncStatusText.text = "Synced: $ringCount"
                    Toast.makeText(this, "Synchronized with ring count: $ringCount", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSwitchCounterDialog() {
        val list = repository.getAllCounters()
        val names = list.map { it.name }.toTypedArray()
        val active = repository.getActiveCounter()
        val selectedIndex = list.indexOfFirst { it.id == active.id }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Switch Active Counter")
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val chosen = list[which]
                repository.setActiveCounterId(chosen.id)
                refreshDashboard()
                dialog.dismiss()
                Toast.makeText(this, "Active: ${chosen.name}", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showAddCounterDialog() {
        val input = EditText(this).apply {
            hint = "Name (e.g. Sewing, Meditation)"
        }

        AlertDialog.Builder(this)
            .setTitle("Create Purpose Counter")
            .setMessage("Add a new dedicated counter:")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val created = repository.addCounter(name)
                    refreshDashboard()
                    Toast.makeText(this, "Created: ${created.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditCounterDialog() {
        val active = repository.getActiveCounter()
        val input = EditText(this).apply {
            setText(active.name)
            hint = "Counter Name"
        }

        AlertDialog.Builder(this)
            .setTitle("Edit: ${active.name}")
            .setView(input)
            .setPositiveButton("Save Name") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    active.name = newName
                    repository.saveActiveCounter(active)
                    refreshDashboard()
                }
            }
            .setNeutralButton("Delete Counter") { _, _ ->
                val remaining = repository.deleteCounter(active.id)
                if (remaining != null) {
                    refreshDashboard()
                    Toast.makeText(this, "Counter deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Cannot delete the default counter", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResetMalaDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Current Mala?")
            .setMessage("Reset the active 0-108 bead count back to 0? Daily and Lifetime stats will remain untouched.")
            .setPositiveButton("Reset Mala") { _, _ ->
                val active = repository.getActiveCounter()
                active.resetMalaOnly()
                repository.saveActiveCounter(active)
                refreshDashboard()
                Toast.makeText(this, "Mala bead progress reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResetLifetimeDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Lifetime Stats?")
            .setMessage("Do you want to reset all lifetime counts and malas for this counter?")
            .setPositiveButton("Reset") { _, _ ->
                val active = repository.getActiveCounter()
                active.lifetimeCount = 0L
                active.lifetimeMala = 0L
                repository.saveActiveCounter(active)
                refreshDashboard()
                Toast.makeText(this, "Lifetime stats cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showThemeSelector() {
        val themes = arrayOf(
            CounterRepository.THEME_GOLD,
            CounterRepository.THEME_OBSIDIAN,
            CounterRepository.THEME_EMERALD,
            CounterRepository.THEME_LOTUS
        )

        AlertDialog.Builder(this)
            .setTitle("Select Color Theme")
            .setItems(themes) { _, which ->
                val chosen = themes[which]
                repository.currentTheme = chosen
                applyTheme(chosen)
            }
            .show()
    }

    private fun applyTheme(themeName: String) {
        val (bgColor, cardColor, strokeColor, accentColor, textColor) = when (themeName) {
            CounterRepository.THEME_OBSIDIAN -> ThemeColors(
                R.color.obsidian_bg, R.color.obsidian_card, R.color.obsidian_card_stroke,
                R.color.obsidian_accent, R.color.obsidian_text
            )
            CounterRepository.THEME_EMERALD -> ThemeColors(
                R.color.emerald_bg, R.color.emerald_card, R.color.emerald_card_stroke,
                R.color.emerald_accent, R.color.emerald_text
            )
            CounterRepository.THEME_LOTUS -> ThemeColors(
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

        listOf(
            binding.statusCard, binding.counterHeroCard, binding.cardDailyCount,
            binding.cardDailyMala, binding.cardLifetimeCount, binding.cardLifetimeMala,
            binding.settingsCard
        ).forEach {
            it.setCardBackgroundColor(cardResolved)
            it.strokeColor = strokeResolved
        }

        binding.appTitle.setTextColor(textResolved)
        binding.tvCounterName.setTextColor(textResolved)
        binding.tvCurrentBeads.setTextColor(accentResolved)
        binding.tvDailyCount.setTextColor(textResolved)
        binding.tvDailyMala.setTextColor(accentResolved)
        binding.tvLifetimeCount.setTextColor(textResolved)
        binding.tvLifetimeMala.setTextColor(accentResolved)
        binding.statusText.setTextColor(textResolved)
        binding.tvMalaVibrateTitle.setTextColor(textResolved)
        binding.tvClickVibrateTitle.setTextColor(textResolved)

        binding.btnTheme.setTextColor(accentResolved)
        binding.btnTheme.strokeColor = ColorStateList.valueOf(strokeResolved)
        binding.btnConnect.setTextColor(accentResolved)
        binding.btnSwitchCounter.setTextColor(textResolved)
        binding.btnSwitchCounter.strokeColor = ColorStateList.valueOf(strokeResolved)
        binding.btnAddCounter.setTextColor(accentResolved)
        binding.btnAddCounter.strokeColor = ColorStateList.valueOf(strokeResolved)
        binding.btnEditCounter.setTextColor(textResolved)
        binding.btnEditCounter.strokeColor = ColorStateList.valueOf(strokeResolved)
        binding.btnSyncDevice.setTextColor(accentResolved)
        binding.btnSyncDevice.strokeColor = ColorStateList.valueOf(strokeResolved)

        binding.malaProgressBar.setIndicatorColor(accentResolved)
        binding.malaProgressBar.trackColor = strokeResolved

        binding.divider1.setBackgroundColor(strokeResolved)
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
                binding.syncStatusText.text = "Ring Ready"
                binding.btnConnect.text = "Disconnect"
                Toast.makeText(this, "Daily Japa Ready! Click your ring", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Middle Button Click from the ring.
     * Side buttons are ignored by BleManager.
     */
    override fun onMiddleButtonClick() {
        val (counter, malaCompleted) = repository.recordClick()
        updateActiveCounterView(counter)

        // Animate counter bump
        val anim = ScaleAnimation(
            1.0f, 1.15f, 1.0f, 1.15f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 80
            repeatMode = ScaleAnimation.REVERSE
            repeatCount = 1
        }
        binding.tvCurrentBeads.startAnimation(anim)

        // Vibration handling
        if (malaCompleted && repository.vibrateOnMala) {
            triggerMalaCelebrationVibration()
            Toast.makeText(this, "📿 Mala Completed! (108 Beads)", Toast.LENGTH_LONG).show()
        } else if (repository.vibrateOnClick) {
            triggerClickVibration()
        }
    }

    override fun onDeviceCountDetected(count: Int) {
        latestHardwareCount = count
        val active = repository.getActiveCounter()
        if (count > active.dailyCount) {
            val updated = repository.syncActiveCounterWithRing(count)
            updateActiveCounterView(updated)
            binding.syncStatusText.text = "Auto-Synced: $count"
        }
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
