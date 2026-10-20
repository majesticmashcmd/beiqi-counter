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
        private const val TAG = "BeiQiBleManager"
        const val TARGET_NAME = "BEIQI-ST"
        const val TARGET_MAC = "7A:EC:97:9F:C2:B8"
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    interface BleEventListener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onClickRegistered(rawHex: String)
        fun onDeviceCountDetected(deviceCount: Int, rawHex: String)
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
    private var lastKnownDeviceCount: Int? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            listener.onError("Bluetooth is disabled. Please turn on Bluetooth.")
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
            Log.e(TAG, "Connection failed", e)
            listener.onError("Connection error: ${e.message}")
            listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
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
                Log.w(TAG, "onServicesDiscovered failed with status: $status")
                return
            }

            Log.d(TAG, "Services discovered. Enrolling notifications and reading readable attributes...")
            var notifyFound = false

            for (service in gatt.services) {
                for (characteristic in service.characteristics) {
                    val props = characteristic.properties
                    val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

                    if (canNotify || canIndicate) {
                        Log.d(TAG, "Enabling notifications on characteristic: ${characteristic.uuid}")
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

                    // Attempt to read readable characteristics that might contain the counter state
                    if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        try {
                            gatt.readCharacteristic(characteristic)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not read characteristic ${characteristic.uuid}: ${e.message}")
                        }
                    }
                }
            }

            mainHandler.post {
                if (notifyFound) {
                    listener.onConnectionStateChanged(ConnectionState.READY)
                } else {
                    listener.onError("Connected, but no notify characteristics discovered.")
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
            handleIncomingBytes(data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingBytes(value)
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
                inspectReadPayload(data)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                inspectReadPayload(value)
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

    private fun inspectReadPayload(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val hexString = bytes.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "Characteristic Read: $hexString")
        val parsedCount = extractCountFromPayload(bytes)
        if (parsedCount != null && parsedCount > 0) {
            mainHandler.post {
                listener.onDeviceCountDetected(parsedCount, hexString)
            }
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastClickTimestamp < 60) {
            return // Debounce 60ms
        }
        lastClickTimestamp = now

        val hexString = bytes.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "Notification received from BEIQI-ST: $hexString")

        val detectedCount = extractCountFromPayload(bytes)
        mainHandler.post {
            if (detectedCount != null && detectedCount > 0 && detectedCount != lastKnownDeviceCount) {
                lastKnownDeviceCount = detectedCount
                listener.onDeviceCountDetected(detectedCount, hexString)
            } else {
                listener.onClickRegistered(hexString)
            }
        }
    }

    /**
     * Inspects byte patterns typical for BEIQI / Smart Tasbih rings.
     * Searches for 16-bit or 24-bit embedded integer values.
     */
    private fun extractCountFromPayload(bytes: ByteArray): Int? {
        if (bytes.size < 3) return null

        // Pattern 1: Big-Endian at the end of packet (common: [header, cmd, high, low])
        val last16Big = ((bytes[bytes.size - 2].toInt() and 0xFF) shl 8) or (bytes[bytes.size - 1].toInt() and 0xFF)
        if (last16Big in 1..99999) {
            return last16Big
        }

        // Pattern 2: Little-Endian at the end of packet
        val last16Little = ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8) or (bytes[bytes.size - 2].toInt() and 0xFF)
        if (last16Little in 1..99999) {
            return last16Little
        }

        // Pattern 3: Fixed offset (index 2-3 or 3-4)
        if (bytes.size >= 5) {
            val mid16 = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            if (mid16 in 1..99999) return mid16
        }

        return null
    }
}
