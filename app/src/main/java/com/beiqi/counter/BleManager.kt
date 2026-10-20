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
        fun onClickRegistered(currentCount: Int, rawHex: String)
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

    private var clickCount = 0
    private var lastClickTimestamp = 0L
    private var lastKnownDeviceCount: Int? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            listener.onError("Bluetooth is not enabled on this device")
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
            Log.e(TAG, "Failed to initiate GATT connection", e)
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
            Log.e(TAG, "Error closing GATT", e)
        } finally {
            bluetoothGatt = null
            descriptorWriteQueue.clear()
            isWritingDescriptor = false
            listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
        }
    }

    fun resetCount() {
        clickCount = 0
        lastKnownDeviceCount = null
    }

    fun getCurrentCount(): Int = clickCount

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected to GATT server. Discovering services...")
                        listener.onConnectionStateChanged(ConnectionState.CONNECTED)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from GATT server.")
                        listener.onConnectionStateChanged(ConnectionState.DISCONNECTED)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onServicesDiscovered received: $status")
                return
            }

            Log.d(TAG, "Services discovered. Searching for Notify/Indicate characteristics...")
            var notifyFound = false

            for (service in gatt.services) {
                for (characteristic in service.characteristics) {
                    val props = characteristic.properties
                    val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

                    if (canNotify || canIndicate) {
                        Log.d(TAG, "Enabling notifications on: ${characteristic.uuid}")
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
                }
            }

            mainHandler.post {
                if (notifyFound) {
                    listener.onConnectionStateChanged(ConnectionState.READY)
                } else {
                    listener.onError("Connected, but no notify characteristics found.")
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

        // Android 13+ support
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingBytes(value)
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

    private fun handleIncomingBytes(bytes: ByteArray) {
        val now = System.currentTimeMillis()
        // Debounce packets arriving within 60ms to prevent duplicate triggers
        if (now - lastClickTimestamp < 60) {
            return
        }
        lastClickTimestamp = now

        val hexString = bytes.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "Packet received from BEIQI-ST: $hexString")

        // Check if device transmits a 16-bit or 32-bit cumulative count in the payload
        var parsedDeviceCount: Int? = null
        if (bytes.size >= 4) {
            // Check common offsets for counters in BLE rings (e.g., bytes 2..3 or 3..4)
            val potential16BitBig = ((bytes[bytes.size - 2].toInt() and 0xFF) shl 8) or (bytes[bytes.size - 1].toInt() and 0xFF)
            val potential16BitLittle = ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8) or (bytes[bytes.size - 2].toInt() and 0xFF)
            
            if (lastKnownDeviceCount != null && (potential16BitBig == lastKnownDeviceCount!! + 1)) {
                parsedDeviceCount = potential16BitBig
            } else if (lastKnownDeviceCount != null && (potential16BitLittle == lastKnownDeviceCount!! + 1)) {
                parsedDeviceCount = potential16BitLittle
            }
        }

        if (parsedDeviceCount != null) {
            lastKnownDeviceCount = parsedDeviceCount
            clickCount++
        } else {
            // Every notification packet sent upon clicking the button registers 1 click
            clickCount++
        }

        mainHandler.post {
            listener.onClickRegistered(clickCount, hexString)
        }
    }
}
