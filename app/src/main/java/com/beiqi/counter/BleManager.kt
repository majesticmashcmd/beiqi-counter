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
        fun onSingleClickRegistered(rawHex: String)
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
            listener.onError("Bluetooth is turned off. Please turn on Bluetooth.")
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
                Log.w(TAG, "onServicesDiscovered failed: $status")
                return
            }

            Log.d(TAG, "Services discovered. Subscribing to click notification stream...")
            var notifyFound = false

            for (service in gatt.services) {
                for (characteristic in service.characteristics) {
                    val props = characteristic.properties
                    val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

                    if (canNotify || canIndicate) {
                        Log.d(TAG, "Subscribing to: ${characteristic.uuid}")
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
                    listener.onError("Connected, but no notification channel found.")
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
            handleNotification(data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(value)
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
     * Strict 1 Click = 1 Count.
     * Whenever the counter ring button is clicked, it sends a BLE notification.
     * We debounce by 90ms to ensure 1 press = 1 registration.
     */
    private fun handleNotification(bytes: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastClickTimestamp < 90) {
            return // Ignore duplicate bounce within 90ms
        }
        lastClickTimestamp = now

        val hexString = bytes.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "Ring Click Registered: $hexString")

        mainHandler.post {
            listener.onSingleClickRegistered(hexString)
        }
    }
}
