package com.beiqi.counter

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class BleManager(
    private val context: Context,
    private val listener: BleEventListener
) {
    companion object {
        private const val TAG = "DailyJapaBleManager"
        const val TARGET_NAME = "BEIQI-ST"
        const val TARGET_MAC = "7A:EC:97:9F:C2:B8"
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }

    interface BleEventListener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onMiddleButtonClick()
        fun onDeviceCountDetected(count: Int)
        fun onBatteryLevelDetected(batteryPercent: Int)
        fun onDiagnosticPacket(charUuid: String, hexString: String, isCountButton: Boolean)
        fun onError(message: String)
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        READY
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val descriptorWriteQueue = ConcurrentLinkedQueue<BluetoothGattDescriptor>()
    private var isWritingDescriptor = false

    private var lastClickTimestamp = 0L

    var isCalibrating = false
    var calibratedCharUuid: String? = null
    var calibratedSignature: String? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            listener.onError("Please turn on Bluetooth on your device.")
            return
        }

        disconnect()
        listener.onConnectionStateChanged(ConnectionState.CONNECTING)

        try {
            val device = bluetoothAdapter.getRemoteDevice(TARGET_MAC)
            Log.d(TAG, "Connecting to $TARGET_NAME ($TARGET_MAC)...")

            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
            listener.onError("Connection failed: ${e.message}")
            listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error", e)
        } finally {
            bluetoothGatt = null
            descriptorWriteQueue.clear()
            isWritingDescriptor = false
            listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to $TARGET_NAME. Discovering services...")
                        listener.onConnectionStateChanged(ConnectionState.CONNECTED)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from $TARGET_NAME.")
                        listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Services discovery failed: $status")
                return
            }

            Log.d(TAG, "Services discovered. Subscribing to notifications...")
            var notifyFound = false

            for (service in gatt.services) {
                for (characteristic in service.characteristics) {
                    val props = characteristic.properties
                    val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

                    if (canNotify || canIndicate) {
                        gatt.setCharacteristicNotification(characteristic, true)

                        val descriptor = characteristic.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            val value = if (canNotify) {
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            } else {
                                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            }
                            descriptor.value = value
                            queueDescriptorWrite(descriptor)
                            notifyFound = true
                        }
                    }

                    if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        try {
                            gatt.readCharacteristic(characteristic)
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                }
            }

            mainHandler.post {
                if (notifyFound) {
                    listener.onConnectionStateChanged(ConnectionState.READY)
                } else {
                    listener.onError("Connected, but could not open notification stream.")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            processNotification(characteristic.uuid.toString(), data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processNotification(characteristic.uuid.toString(), value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                handleCharacteristicData(characteristic.uuid.toString(), data)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicData(characteristic.uuid.toString(), value)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            isWritingDescriptor = false
            processNextDescriptor()
        }
    }

    private fun queueDescriptorWrite(descriptor: BluetoothGattDescriptor) {
        descriptorWriteQueue.add(descriptor)
        if (!isWritingDescriptor) {
            processNextDescriptor()
        }
    }

    @SuppressLint("MissingPermission")
    private fun processNextDescriptor() {
        val next = descriptorWriteQueue.poll() ?: return
        isWritingDescriptor = true
        bluetoothGatt?.writeDescriptor(next)
    }

    private fun handleCharacteristicData(charUuid: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return

        // Standard BLE Battery Level
        if (charUuid.contains("2a19", ignoreCase = true)) {
            val level = bytes[0].toInt() and 0xFF
            if (level in 1..100) {
                mainHandler.post { listener.onBatteryLevelDetected(level) }
            }
            return
        }

        extractHardwareCount(bytes)
    }

    private fun processNotification(charUuid: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val hexString = bytes.joinToString(" ") { "%02X".format(it) }

        // Check for BEIQI battery report packet: [0xAA, 0x05, batteryLevel, ...]
        if (bytes.size >= 3) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if ((b0 == 0xAA || b0 == 0x55) && b1 == 0x05) {
                val battery = bytes[2].toInt() and 0xFF
                if (battery in 1..100) {
                    mainHandler.post { listener.onBatteryLevelDetected(battery) }
                }
                mainHandler.post {
                    listener.onDiagnosticPacket(charUuid, hexString, false)
                }
                return
            }
        }

        // Check if in Calibration mode: user presses physical middle button to lock it
        if (isCalibrating) {
            isCalibrating = false
            mainHandler.post {
                listener.onDiagnosticPacket(charUuid, hexString, true)
            }
            return
        }

        // Determine if packet is a middle button count click
        val isMiddleButton: Boolean
        val savedSig = calibratedSignature
        val savedUuid = calibratedCharUuid

        if (!savedSig.isNullOrEmpty()) {
            // User has calibrated middle button: enforce strict signature & characteristic match
            val uuidMatches = savedUuid.isNullOrEmpty() || charUuid.equals(savedUuid, ignoreCase = true)
            val prefix = savedSig.trim().split(" ").take(2).joinToString(" ")
            val sigMatches = hexString.startsWith(prefix, ignoreCase = true) ||
                             hexString.equals(savedSig.trim(), ignoreCase = true)

            isMiddleButton = uuidMatches && sigMatches
            if (!isMiddleButton) {
                Log.d(TAG, "Blocked side button / non-matching packet: $hexString")
            }
        } else {
            // Default Smart Whitelist Filter for BEIQI rings
            isMiddleButton = isMiddleButtonClickPacket(bytes)
            if (!isMiddleButton) {
                Log.d(TAG, "Blocked side button / non-tally packet: $hexString")
            }
        }

        // Send to live diagnostic listener (for interactive testing / confirmation in UI)
        mainHandler.post {
            listener.onDiagnosticPacket(charUuid, hexString, isMiddleButton)
        }

        if (!isMiddleButton) {
            // Explicitly ignore side buttons and system packets
            return
        }

        // Debounce clicks within 90ms to prevent electrical micro-chatter
        val now = System.currentTimeMillis()
        if (now - lastClickTimestamp < 90) {
            return
        }
        lastClickTimestamp = now

        // Check if packet carries the ring's display count
        extractHardwareCount(bytes)

        // Fire middle button click event
        mainHandler.post {
            listener.onMiddleButtonClick()
        }
    }

    /**
     * Strict Whitelist: Only verified middle-button tally events return TRUE.
     * Side buttons (wake screen, flip display, mode switch, settings) return FALSE.
     */
    private fun isMiddleButtonClickPacket(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val b0 = bytes[0].toInt() and 0xFF

        // 1. Standard BEIQI / Smart Ring Frame (AA or 55 header)
        if (b0 == 0xAA || b0 == 0x55) {
            if (bytes.size >= 2) {
                val b1 = bytes[1].toInt() and 0xFF
                // Command 0x01 = Tally Count Button Click
                // Command 0x20 = Tally Count Sync Frame
                // ALL other commands (0x02=screen wake, 0x03=flip, 0x04=mode, 0x05=battery, 0x08=sleep, 0x80+=ack) are side buttons or system!
                return (b1 == 0x01 || b1 == 0x20)
            }
            return false
        }

        // 2. Direct KeyCode format: [KeyId, State]
        // Key 0x01 is the middle tally button. Key 0x02, 0x03, 0x04 are side buttons.
        if (bytes.size in 1..3) {
            return (b0 == 0x01)
        }

        // 3. Any packet starting with 0x02..0x0F or 0xAB is definitely side button or control
        if (b0 in 0x02..0x0F || b0 == 0xAB) {
            return false
        }

        // Safe by default: do not count unknown packets without calibration
        return false
    }

    private fun extractHardwareCount(bytes: ByteArray) {
        if (bytes.size < 4) return
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        if ((b0 == 0xAA || b0 == 0x55) && (b1 == 0x01 || b1 == 0x20)) {
            val high = bytes[2].toInt() and 0xFF
            val low = bytes[3].toInt() and 0xFF
            val count = (high shl 8) or low
            if (count in 1..99999) {
                mainHandler.post {
                    listener.onDeviceCountDetected(count)
                }
            }
        }
    }
}
