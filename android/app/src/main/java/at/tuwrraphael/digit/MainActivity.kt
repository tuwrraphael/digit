package at.tuwrraphael.digit

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import at.tuwrraphael.digit.ui.theme.DigitTheme
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.regex.Pattern
import java.io.File
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import android.provider.Settings

private const val SELECT_DEVICE_REQUEST_CODE = 0
class MainActivity : ComponentActivity() {
    private val lastCtsWrite = mutableStateOf(0L)
    private val batteryHistory = mutableStateOf("")

    private val companionHistory = mutableStateOf("")

    private val associatedMac = mutableStateOf<String?>(null)

    private val calendarPermissionGranted = mutableStateOf(false)

    private val notificationAccessGranted = mutableStateOf(false)

    private val prefsUpdatesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Handle the preference updates here
            if (intent?.action == "PREFS_UPDATED") {
                updateFromPrefs()
                updateBatteryHistory()
                updateCompanionHistory()
            }
        }
    }

    private fun updateAssociatedMac() {
        val deviceManager = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        val associations = deviceManager.associations
        associatedMac.value = associations.firstOrNull()
    }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarPermissionGranted.value = granted
    }

    private fun checkCalendarPermission() {
        calendarPermissionGranted.value =
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkNotificationAccess() {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val packageName = packageName
        notificationAccessGranted.value = !enabledListeners.isNullOrEmpty() && enabledListeners.contains(packageName)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(prefsUpdatesReceiver)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerReceiver(prefsUpdatesReceiver, IntentFilter("PREFS_UPDATED"), Context.RECEIVER_NOT_EXPORTED)
        updateFromPrefs()
        updateBatteryHistory()
        updateCompanionHistory()
        updateAssociatedMac()
        checkCalendarPermission()
        startObservingCompanionPresence()
        setContent {
            DigitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding),
                        onDiscoverClick = { startDeviceDiscovery(true) },
                        onStartForegroundService = { syncDevice() },
                        lastCtsWrite = lastCtsWrite.value,
                        batteryHistory = batteryHistory.value,
                        companionHistory = companionHistory.value,
                        associatedMac = associatedMac.value,
                        onUnassociate = { unassociateDevice() },
                        calendarPermissionGranted = calendarPermissionGranted.value,
                        onRequestCalendarPermission = { calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
                        onCopyBatteryHistory = { copyBatteryHistoryToClipboard() },
                        onCopyCompanionHistory = { copyCompanionHistoryToClipboard() },
                        onSaveHomeAssistantConfig = { url, webhookId -> saveHomeAssistantConfig(url, webhookId) },
                        homeAssistantConfig = loadHomeAssistantConfig(),
                        onClearCompanionHistory = { clearCompanionHistory() },
                        onClearBatteryHistory = { clearBatteryHistory() },
                        onRequestNotificationAccess = {
                            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                        },
                        notificationAccessGranted = notificationAccessGranted.value
                    )
                }
            }
        }

        // AlarmManager für periodische Synchronisation setzen
        setSyncAlarm()
    }

    private fun setSyncAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(SyncForegroundService.SYNC_ALARM_ACTION)
        intent.setClass(this, SyncForegroundService.SyncAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val intervalMillis = 4 * 60 * 60 * 1000L // 4 Stunden
        //val triggerAtMillis = System.currentTimeMillis() + intervalMillis
        alarmManager.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + intervalMillis, intervalMillis, pendingIntent)
    }

    override fun onResume() {
        super.onResume()
        updateAssociatedMac()
        checkNotificationAccess()
    }

    private fun unassociateDevice() {
        val deviceManager = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        for (association in deviceManager.associations) {
            deviceManager.disassociate(association)
            associatedMac.value = null
        }
    }

    private fun updateFromPrefs(){
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        lastCtsWrite.value = prefs.getLong("last_cts_write", 0L)
    }

    private fun updateBatteryHistory() {
        val file = File(filesDir, "battery_history.txt")
        batteryHistory.value = if (file.exists()) file.readText() else "Keine Batteriehistorie vorhanden."
    }

    private fun updateCompanionHistory() {
        val file = File(filesDir, "companion_device_history.txt")
        companionHistory.value = if (file.exists()) file.readText() else "Keine Companion-Historie vorhanden."
    }

    private fun clearCompanionHistory() {
        val file = File(filesDir, "companion_device_history.txt")
        if (file.exists()) file.writeText("")
        updateCompanionHistory()
        Toast.makeText(this, "Companion-Historie gelöscht", Toast.LENGTH_SHORT).show()
    }

    private fun clearBatteryHistory() {
        val file = File(filesDir, "battery_history.txt")
        if (file.exists()) file.writeText("")
        updateBatteryHistory()
        Toast.makeText(this, "Batteriehistorie gelöscht", Toast.LENGTH_SHORT).show()
    }

    private fun copyBatteryHistoryToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Batteriehistorie", batteryHistory.value)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Batteriehistorie kopiert", Toast.LENGTH_SHORT).show()
    }

    private fun copyCompanionHistoryToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("CompanionHistorie", companionHistory.value)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Companion-Historie kopiert", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private val deviceSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // The user chose to pair the app with a Bluetooth device.
            val deviceToPair: ScanResult? =
                result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
            deviceToPair?.device?.createBond();
            // Nach erfolgreichem Bonding Präsenzüberwachung starten
            val mac = deviceToPair?.device?.address
            if (!mac.isNullOrBlank()) {
                val deviceManager = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
                try {
                    deviceManager.startObservingDevicePresence(mac)
                    Log.d("MainActivity", "startObservingDevicePresence für $mac aufgerufen (nach Pairing)")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Fehler beim Starten der Präsenzüberwachung nach Pairing", e)
                }
            }
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startDeviceDiscovery(false)
        }
    }

    private fun startDeviceDiscovery(b: Boolean) {
        if (b) {
            val requiredPermissions = mutableListOf(
                Manifest.permission.BLUETOOTH_CONNECT
            )

            /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_SCAN,

            ))
        }*/


            requestPermissions.launch(requiredPermissions.toTypedArray())
            return
        }

        val digitServiceUUID = java.util.UUID.fromString("00001523-1212-efde-1523-785fef13d123")


        val scanFilter = android.bluetooth.le.ScanFilter.Builder()
            //.setServiceUuid(ParcelUuid(digitServiceUUID))
            .setDeviceName("Digit")
            .build()

        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            //.setNamePattern(Pattern.compile("Digit"))
            .setScanFilter(scanFilter)
            .build()

        val pairingRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)

            //.setSingleDevice(true)
            //.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
            .build()

        val deviceManager : CompanionDeviceManager = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager

        deviceManager.associate(pairingRequest,
            object : CompanionDeviceManager.Callback() {
                // Called when a device is found. Launch the IntentSender so the user
                // can select the device they want to pair with.
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    deviceSelectionLauncher.launch(
                        IntentSenderRequest.Builder(chooserLauncher).build()
                    )
                }

                override fun onFailure(error: CharSequence?) {
                    val x = 4;
                }
            }, null)
    }

    private fun syncDevice() {
        val serviceIntent = Intent(this, SyncForegroundService::class.java)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(serviceIntent)
        } else {
            requestPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
    }

    private fun saveHomeAssistantConfig(url: String, webhookId: String) {
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("ha_url", url)
            .putString("ha_webhook_id", webhookId)
            .apply()
        Toast.makeText(this, "Home Assistant Konfiguration gespeichert", Toast.LENGTH_SHORT).show()
    }

    private fun loadHomeAssistantConfig(): Pair<String, String> {
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val url = prefs.getString("ha_url", "") ?: ""
        val webhookId = prefs.getString("ha_webhook_id", "") ?: ""
        return Pair(url, webhookId)
    }

    private fun startObservingCompanionPresence() {
        val deviceManager = getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        val associations = deviceManager.associations
        val mac = associations.firstOrNull()
        if (!mac.isNullOrBlank()) {
            try {
                deviceManager.startObservingDevicePresence(mac)
                Log.d("MainActivity", "startObservingDevicePresence für $mac aufgerufen")
            } catch (e: Exception) {
                Log.e("MainActivity", "Fehler beim Starten der Präsenzüberwachung", e)
            }
        }
    }
}

@Composable
fun Greeting(
    name: String,
    modifier: Modifier = Modifier,
    onDiscoverClick: () -> Unit,
    onStartForegroundService: () -> Unit = {},
    lastCtsWrite: Long = 0L,
    batteryHistory: String = "",
    companionHistory: String = "",
    associatedMac: String? = null,
    onUnassociate: () -> Unit = {},
    calendarPermissionGranted: Boolean = false,
    onRequestCalendarPermission: () -> Unit = {},
    onCopyBatteryHistory: () -> Unit = {},
    onCopyCompanionHistory: () -> Unit = {},
    onSaveHomeAssistantConfig: (String, String) -> Unit = { _, _ -> },
    homeAssistantConfig: Pair<String, String> = Pair("", ""),
    onClearCompanionHistory: () -> Unit = {},
    onClearBatteryHistory: () -> Unit = {},
    onRequestNotificationAccess: () -> Unit = {},
    notificationAccessGranted: Boolean = true,
) {
    // Formatieren des Zeitstempels
    val formattedTime = if (lastCtsWrite > 0) {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(lastCtsWrite))
    } else {
        "Noch nie synchronisiert"
    }

    val scrollState = rememberScrollState()
    val batteryScrollState = rememberScrollState()
    val companionScrollState = rememberScrollState()

    var haUrl by remember { mutableStateOf(homeAssistantConfig.first) }
    var haWebhookId by remember { mutableStateOf(homeAssistantConfig.second) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (associatedMac != null) {
            Text("Verbundenes Gerät: $associatedMac")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onUnassociate) {
                Text("Trennen")
            }
        } else {
            Text("Press to discover and save your Digit device")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onDiscoverClick) {
                Text("Verbinden")
            }
        }
        Button(onClick = onStartForegroundService) {
            Text("Jetzt synchronisieren")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Letzte CTS-Synchronisation: $formattedTime")
        Spacer(modifier = Modifier.height(16.dp))
        Text("Batteriehistorie:")
        Box(
            modifier = Modifier
                .heightIn(max = 200.dp)
                .verticalScroll(batteryScrollState)
                .background(Color(0xFFF5F5F5))
        ) {
            Text(
                batteryHistory,
                modifier = Modifier.padding(8.dp),
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Visible
            )
        }
        Button(onClick = onCopyBatteryHistory) {
            Text("Batteriehistorie kopieren")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onClearBatteryHistory) {
            Text("Batteriehistorie löschen")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Companion-Historie:")
        Box(
            modifier = Modifier
                .heightIn(max = 200.dp)
                .verticalScroll(companionScrollState)
                .background(Color(0xFFF5F5F5))
        ) {
            Text(
                companionHistory,
                modifier = Modifier.padding(8.dp),
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Visible
            )
        }
        Button(onClick = onCopyCompanionHistory) {
            Text("Companion-Historie kopieren")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onClearCompanionHistory) {
            Text("Companion-Historie löschen")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (!calendarPermissionGranted) {
            Button(onClick = onRequestCalendarPermission) {
                Text("Kalender-Berechtigung erlauben")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Home Assistant Konfiguration:")
        OutlinedTextField(
            value = haUrl,
            onValueChange = { haUrl = it },
            label = { Text("Home Assistant URL") }
        )
        OutlinedTextField(
            value = haWebhookId,
            onValueChange = { haWebhookId = it },
            label = { Text("Webhook ID") }
        )
        Button(onClick = { onSaveHomeAssistantConfig(haUrl, haWebhookId) }) {
            Text("Speichern")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (!notificationAccessGranted) {
            Button(onClick = onRequestNotificationAccess) {
                Text("Benachrichtigungszugriff erlauben")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}



@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    DigitTheme {
        Greeting("Android", onDiscoverClick = {})
    }
}