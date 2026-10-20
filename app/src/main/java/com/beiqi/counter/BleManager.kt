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
    }

    interface BleEventListener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onMiddleButtonClick()
        fun onDeviceCountDetected(count: Int)
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

    @SuppressLint("MissingPermission")
    fun connect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            listener.onError("Please enable Bluetooth on your phone.")
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
            Log.e(TAG, "Connection error", e)
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
                Log.w(TAG, "Service discovery failed with status: $status")
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
                        Log.d(TAG, "Enabling notifications on ${characteristic.uuid}")
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

                    // Attempt to read readable characteristics that might hold the current device count
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
                    listener.onError("Connected, but notification stream could not be opened.")
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
            processPacket(data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processPacket(value)
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
                extractAndNotifyHardwareCount(data)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                extractAndNotifyHardwareCount(value)
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

    /**
     * Filters packets to ensure ONLY the middle count button registers clicks,
     * ignoring side buttons (which send mode switch / screen wake commands).
     */
    private fun processPacket(bytes: ByteArray) {
        if (bytes.isEmpty()) return

        // Check if packet represents a side-button action
        if (isSideButtonEvent(bytes)) {
            Log.d(TAG, "Ignored side button event: ${bytes.joinToString(" ") { "%02X".format(it) }}")
            return
        }

        // Debounce clicks within 90ms to ensure 1 press = 1 count
        val now = System.currentTimeMillis()
        if (now - lastClickTimestamp < 90) {
            return
        }
        lastClickTimestamp = now

        // Check if the packet carries the hardware screen count
        extractAndNotifyHardwareCount(bytes)

        // Trigger middle button click
        mainHandler.post {
            listener.onMiddleButtonClick()
        }
    }

    /**
     * In BEIQI and smart tasbeeh rings:
     * - Middle Button (Tally click): command ID 0x01 or 0x20, or key code 0x01.
     * - Side Buttons (Screen on / mode switch / settings): command ID 0x02, 0x03, 0x04, or 0x80+.
     */
    private fun isSideButtonEvent(bytes: ByteArray): Boolean {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF

            // If header is AA or 55 or AB
            if (b0 == 0xAA || b0 == 0x55 || b0 == 0xAB) {
                // Command 0x02 = side key, 0x03 = screen wake, 0x04 = menu, 0x05 = long press
                if (b1 in listOf(0x02, 0x03, 0x04, 0x05, 0x08, 0x80, 0x81, 0x82)) {
                    return true
                }
            }

            // Single keycode format [KeyId, State]
            if (bytes.size == 2 && b0 != 0x01 && b0 != 0xAA && b0 != 0x55) {
                // Key IDs other than 0x01 are side keys
                if (b0 in 0x02..0x0A) {
                    return true
                }
            }
        }
        return false
    }

    private fun extractAndNotifyHardwareCount(bytes: ByteArray) {
        if (bytes.size < 4) return
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // Standard BEIQI frame: [0xAA, 0x01, CountHigh, CountLow, ...]
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
