package ring.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import ring.protocol.BigDataClient
import ring.protocol.RingClient
import ring.protocol.Transport
import ring.protocol.toHex
import java.util.UUID

private val SERVICE_V1 = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
private val RX_V1 = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
private val TX_V1 = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
private val SERVICE_V2 = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
private val WRITE_V2 = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
private val NOTIFY_V2 = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")

/** GATT connection to the ring: V1 command channel (16-byte packets) and V2 big-data channel. */
class RingBleManager(context: Context) : BleManager(context) {
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var writeV2: BluetoothGattCharacteristic? = null
    private var notifyV2: BluetoothGattCharacteristic? = null

    @Volatile var v1Handler: (ByteArray) -> Unit = {}

    @Volatile var bigHandler: (ByteArray) -> Unit = {}

    override fun getGattCallback(): BleManagerGattCallback = Callback()

    private inner class Callback : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val v1 = gatt.getService(SERVICE_V1)
            val v2 = gatt.getService(SERVICE_V2)
            rx = v1?.getCharacteristic(RX_V1)
            tx = v1?.getCharacteristic(TX_V1)
            writeV2 = v2?.getCharacteristic(WRITE_V2)
            notifyV2 = v2?.getCharacteristic(NOTIFY_V2)
            return rx != null && tx != null && writeV2 != null && notifyV2 != null
        }

        override fun initialize() {
            setNotificationCallback(tx).with { _, data -> v1Handler(data.value ?: ByteArray(0)) }
            setNotificationCallback(notifyV2).with { _, data -> bigHandler(data.value ?: ByteArray(0)) }
            enableNotifications(tx).enqueue()
            enableNotifications(notifyV2).enqueue()
        }

        override fun onServicesInvalidated() {
            rx = null
            tx = null
            writeV2 = null
            notifyV2 = null
        }
    }

    suspend fun connectTo(device: BluetoothDevice) {
        connect(device).retry(CONNECT_RETRIES, CONNECT_RETRY_DELAY_MS).useAutoConnect(false).timeout(CONNECT_TIMEOUT_MS).suspend()
    }

    suspend fun writeV1(data: ByteArray) {
        writeCharacteristic(rx, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).suspend()
    }

    suspend fun writeBig(data: ByteArray) {
        writeCharacteristic(writeV2, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).suspend()
    }
}

private const val TAG = "RingBle"

private class V1Transport(private val manager: RingBleManager) : Transport {
    override suspend fun write(data: ByteArray) {
        Log.d(TAG, "v1 tx ${data.toHex()}")
        manager.writeV1(data)
    }

    override fun setNotifyHandler(handler: (ByteArray) -> Unit) {
        manager.v1Handler = { data ->
            Log.d(TAG, "v1 rx ${data.toHex()}")
            handler(data)
        }
    }
}

private class BigTransport(private val manager: RingBleManager) : Transport {
    override suspend fun write(data: ByteArray) {
        Log.d(TAG, "big tx ${data.toHex()}")
        manager.writeBig(data)
    }

    override fun setNotifyHandler(handler: (ByteArray) -> Unit) {
        manager.bigHandler = { data ->
            Log.d(TAG, "big rx ${data.toHex()}")
            handler(data)
        }
    }
}

/** The two protocol clients on top of the same BLE connection. */
class RingSession(manager: RingBleManager) {
    val v1 = RingClient(V1Transport(manager))
    val big = BigDataClient(BigTransport(manager))
}

private const val CONNECT_RETRIES = 3
private const val CONNECT_RETRY_DELAY_MS = 300
private const val CONNECT_TIMEOUT_MS = 20_000L
