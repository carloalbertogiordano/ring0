package ring.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(val address: String, val name: String, val rssi: Int)

/** BLE scan of named devices (the ring advertises as "COLMI Ring ..."). */
@SuppressLint("MissingPermission")
fun scanDevices(context: Context): Flow<FoundDevice> = callbackFlow {
    val scanner = context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner
    if (scanner == null) {
        close(IllegalStateException("Bluetooth spento"))
        return@callbackFlow
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: return
            trySend(FoundDevice(result.device.address, name, result.rssi))
        }
    }
    scanner.startScan(callback)
    awaitClose { scanner.stopScan(callback) }
}
