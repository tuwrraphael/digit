package at.tuwrraphael.digit

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.S)
class DigitCompanionDeviceService : CompanionDeviceService() {

    private val instanceId :String = java.util.UUID.randomUUID().toString()
    private var activiateButtonNotification = false;

    private val bluetoothManager: BluetoothManager by lazy {
        applicationContext.getSystemService(BluetoothManager::class.java)
    }

    override fun onDeviceAppeared(address: String) {
        appendAppearanceHistory(instanceId, "appeared")
        connectToDevice(address)
    }

    override fun onDeviceDisappeared(address: String) {
        appendAppearanceHistory(instanceId, "disappeared")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        appendAppearanceHistory(instanceId, "service unbound")
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        appendAppearanceHistory(instanceId, "service created")
        super.onCreate()
    }

    override fun onDestroy() {
        appendAppearanceHistory(instanceId, "service destroyed")
        super.onDestroy()
    }

    private fun appendAppearanceHistory(address: String, status: String) {
        //val file = File(filesDir, "companion_device_history.txt")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(System.currentTimeMillis())
        //file.appendText("$timestamp - $address $status\n")
        sendBroadcast(Intent("PREFS_UPDATED"))
    }

    private fun _convertToVoltage(g: Int): Double {
        val y = (179 * g) / 100 + 711
        val x = 9.0 * y / 2560.0
        return x
    }

    private fun logBatteryLevel(address: String, level: Int) {
        //val file = File(filesDir, "companion_device_history.txt")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(System.currentTimeMillis())
        //file.appendText("$timestamp - $address battery level: $level\n")
        sendBroadcast(Intent("PREFS_UPDATED"))
        val voltage = _convertToVoltage(level)

        // Home Assistant POST
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val haUrl = prefs.getString("ha_url", null)
        val webhookId = prefs.getString("ha_webhook_id", null)
        if (!haUrl.isNullOrBlank() && !webhookId.isNullOrBlank()) {
            try {
                val url = URL("${haUrl.trimEnd('/')}/api/webhook/${webhookId.trim()}")
                val json = JSONObject()
                json.put("batteryRaw", level)
                val voltageRounded = Math.round(voltage * 1000) / 1000.0
                json.put("batteryVoltage", voltageRounded)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(json.toString().toByteArray()) }
                val code = conn.responseCode
                if (code != 200) {
                    Log.w("DigitCompanionService", "Home Assistant POST failed: $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("DigitCompanionService", "Home Assistant POST error", e)
            }
        }
    }

    private fun sendButtonState(address: String, buttonState: Int) {

        // Home Assistant POST
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val haUrl = prefs.getString("ha_url", null)
        val webhookId = prefs.getString("ha_webhook_id", null)
        if (!haUrl.isNullOrBlank() && !webhookId.isNullOrBlank()) {
            try {
                val url = URL("${haUrl.trimEnd('/')}/api/webhook/${webhookId.trim()}-button")
                val json = JSONObject()
                json.put("state", buttonState)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(json.toString().toByteArray()) }
                val code = conn.responseCode
                if (code != 200) {
                    Log.w("DigitCompanionService", "Home Assistant POST failed: $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("DigitCompanionService", "Home Assistant POST error", e)
            }
        }
    }

    private fun connectToDevice(address: String) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(address)
            device.connectGatt(this, true, object : android.bluetooth.BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
                    try {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            //gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                            appendAppearanceHistory(instanceId, "connected")
                            // Batterie-Service suchen
                            gatt.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            gatt.close()
                            appendAppearanceHistory(instanceId, "disconnected")
                        }
                    } catch (e: SecurityException) {
                        Log.e("CompanionDeviceService", "GATT close SecurityException", e)
                    }
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt?,
                    descriptor: BluetoothGattDescriptor?,
                    status: Int
                ) {
                    if (gatt != null)
                    if (activiateButtonNotification == true)
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS && descriptor?.uuid == java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) {

                        val digitService = gatt.getService(java.util.UUID.fromString("00001523-1212-efde-1523-785fef13d123"))
                        digitService?.let {
                            val buttonChar = it.getCharacteristic(java.util.UUID.fromString("00001527-1212-efde-1523-785fef13d123"))
                            buttonChar?.let { char ->
                                gatt.setCharacteristicNotification(char, true)
                                val descriptor = char.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                descriptor?.let { d ->
                                    d.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    activiateButtonNotification = false
                                    gatt.writeDescriptor(d)
                                }
                            }
                        }
                    }
                    super.onDescriptorWrite(gatt, descriptor, status)
                }

                override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
                    if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {

                        val batteryService = gatt.getService(java.util.UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb"))
                        batteryService?.let {
                            val batteryLevelChar = it.getCharacteristic(java.util.UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"))
                            batteryLevelChar?.let { char ->
                                gatt.setCharacteristicNotification(char, true)
                                val descriptor = char.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                descriptor?.let { d ->
                                    d.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    activiateButtonNotification = true
                                    gatt.writeDescriptor(d)
                                }
                            }
                        }

                    }
                }

                override fun onCharacteristicChanged(gatt: android.bluetooth.BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic) {
                    if (characteristic.uuid == java.util.UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")) {
                        val batteryLevel = characteristic.getIntValue(android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                        logBatteryLevel(address, batteryLevel)
                    } else if (characteristic.uuid == java.util.UUID.fromString("00001527-1212-efde-1523-785fef13d123")) {
                        val buttonState = characteristic.getIntValue(android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                        sendButtonState(address, buttonState)
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e("CompanionDeviceService", "connectGatt SecurityException", e)
        }
    }
}
