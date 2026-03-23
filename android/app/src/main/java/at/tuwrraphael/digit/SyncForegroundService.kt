package at.tuwrraphael.digit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.annotation.RequiresPermission
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SyncForegroundService : Service() {
    companion object {
        val DIGIT_SERVICE_UUID = java.util.UUID.fromString("00001523-1212-efde-1523-785fef13d123")
        val CTS_CHARACTERISTIC_UUID = java.util.UUID.fromString("00001805-1212-efde-1523-785fef13d123")
        val BATTERY_SERVICE_UUID = java.util.UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHARACTERISTIC_UUID = java.util.UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")
        const val SYNC_ALARM_ACTION = "at.tuwrraphael.digit.SYNC_ALARM"

        const val DO_DISCONNECT = false;
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = Notification.Builder(this, "sync_channel")
            .setContentTitle("Digit Synchronisation")
            .setContentText("Wird durchgeführt...")
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .build();
        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        synchronize()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun findDigitService(device: BluetoothDevice, gatt: BluetoothGatt): BluetoothGattService? {
        return gatt.services.firstOrNull { it.uuid == DIGIT_SERVICE_UUID }
    }

    private fun findCtsCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? {
        return service.characteristics.firstOrNull { it.uuid == CTS_CHARACTERISTIC_UUID }
    }

    private fun findBatteryService(gatt: BluetoothGatt): BluetoothGattService? {
        return gatt.services.firstOrNull { it.uuid == BATTERY_SERVICE_UUID }
    }

    private fun findBatteryLevelCharacteristic(service: BluetoothGattService): BluetoothGattCharacteristic? {
        return service.characteristics.firstOrNull { it.uuid == BATTERY_LEVEL_CHARACTERISTIC_UUID }
    }

    private fun logLastSuccessfulCtsWrite() {
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_cts_write", System.currentTimeMillis()).apply()
        sendBroadcast(Intent("PREFS_UPDATED"))
    }

    private fun _convertToVoltage(g: Int): Double {
        val y = (179 * g) / 100 + 711
        val x = 9.0 * y / 2560.0
        return x
    }

    private fun appendBatteryHistory(level: Int) {
        //val file = File(filesDir, "battery_history.txt")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(System.currentTimeMillis())
        val voltage = _convertToVoltage(level)
        //file.appendText("$timestamp: $level (${String.format("%.3f", voltage)} V)\n")
        sendBroadcast(Intent("PREFS_UPDATED"))

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
                        Log.w("SyncForegroundService", "Home Assistant POST failed: $code")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.e("SyncForegroundService", "Home Assistant POST error", e)
                }
        }
    }

    private fun synchronize() {
        val deviceManager = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager;
        val associatedDevices = deviceManager.associations;
        if (associatedDevices.size != 1) {
            stopSelf()
            return
        }
        val bleMacAddress = associatedDevices.first();
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager;
        if (bluetoothManager.adapter == null || !bluetoothManager.adapter.isEnabled) {
            stopSelf()
            return
        }
        val device = bluetoothManager.adapter.getRemoteDevice(bleMacAddress);
        if (device == null) {
            stopSelf()
            return
        }
        // check permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                stopSelf()
                return
            }
        }


        device.connectGatt(this, DO_DISCONNECT == false, object : BluetoothGattCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                    stopSelf()
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = findDigitService(device, gatt) ?: return
                val characteristic = findCtsCharacteristic(service) ?: return
                val now = Calendar.getInstance()
                val cts = dateToCts(now)
                gatt.writeCharacteristic(characteristic.apply {
                    value = cts
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                })
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (characteristic.uuid == CTS_CHARACTERISTIC_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                    logLastSuccessfulCtsWrite()
                    // Battery auslesen
                    val batteryService = findBatteryService(gatt)
                    if (batteryService == null) {
                        if (DO_DISCONNECT) {
                            gatt.disconnect()
                        } else {
                            gatt.close()
                        }
                        stopSelf()
                        return
                    }
                    val batteryLevelChar = findBatteryLevelCharacteristic(batteryService)
                    if (batteryLevelChar != null) {
                        if (!gatt.readCharacteristic(batteryLevelChar)== true) {
                            if (DO_DISCONNECT) {
                                gatt.disconnect()
                            } else {
                                gatt.close()
                            }
                            stopSelf()
                        }
                    } else {
                        if (DO_DISCONNECT) {
                            gatt.disconnect()
                        } else {
                            gatt.close()
                        }
                        stopSelf()
                    }
                } else {
                    if (DO_DISCONNECT) {
                        gatt.disconnect()
                    } else {
                        gatt.close()
                    }
                    stopSelf()
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            @Suppress("DEPRECATION")
            @Deprecated(
                "Used natively in Android 12 and lower",
                ReplaceWith("onCharacteristicRead(gatt, characteristic, characteristic.value, status)")
            )
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) = onCharacteristicRead(gatt, characteristic, characteristic.value, status)

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (characteristic.uuid == BATTERY_LEVEL_CHARACTERISTIC_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                    val batteryLevel = value?.get(0)?.toInt() ?: 0
                    appendBatteryHistory(batteryLevel)
                }
                if (DO_DISCONNECT) {
                    gatt.disconnect()
                } else {
                    gatt.close()
                }
                stopSelf()
            }
        })

    }

    private fun dateToCts(date: Calendar): ByteArray {
        val yearValue = date.get(Calendar.YEAR) + 1900
        return byteArrayOf(
            (yearValue and 0xff).toByte(),
            (yearValue shr 8).toByte(),
            (date.get(Calendar.MONTH) + 1).toByte(),
            date.get(Calendar.DAY_OF_MONTH).toByte(),
            date.get(Calendar.HOUR_OF_DAY).toByte(),
            date.get(Calendar.MINUTE).toByte(),
            date.get(Calendar.SECOND).toByte()
        )
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sync_channel",
                "Synchronisation",
                NotificationManager.IMPORTANCE_LOW // Silent
            )
            channel.setSound(null, null)
            channel.enableVibration(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    class SyncAlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == SYNC_ALARM_ACTION) {
                val serviceIntent = Intent(context, SyncForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}