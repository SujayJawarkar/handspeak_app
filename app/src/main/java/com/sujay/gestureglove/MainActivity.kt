package com.sujay.gestureglove

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sujay.gestureglove.ui.theme.GestureGloveAppTheme
import kotlinx.coroutines.*
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "GestureGlove"
private const val PREFS_NAME = "HandSpeakPrefs"

// Custom Colors based on the designs
val BgBeige = Color(0xFFF5F0E1)
val MutedGreen = Color(0xFFADC1B1)
val MutedBlue = Color(0xFFB8D0EB)
val DarkBlue = Color(0xFF1B263B)
val MutedRed = Color(0xFFE57373)
val SoftRed = Color(0xFFEF9A9A)
val LightRed = Color(0xFFFFCDD2)

data class HistoryItem(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val time: String
)

enum class Screen { Home, History, Settings }

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var textToSpeech: TextToSpeech? = null
    private var receivingJob: Job? = null
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    private val deviceName = "GestureGlove"
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var gestureMap: Map<String, String> = emptyMap()

    // App State
    private var currentScreen by mutableStateOf(Screen.Home)
    private var connectionStatus by mutableStateOf("Disconnected")
    private var currentGesture by mutableStateOf("Recognized text will appear here")
    private var isConnected by mutableStateOf(false)
    private var isConnecting by mutableStateOf(false)
    
    // History State
    private val historyList = mutableStateListOf<HistoryItem>()

    // Settings State
    private var volume by mutableStateOf(0.8f)
    private var speechSpeed by mutableStateOf(1.0f)
    private var language by mutableStateOf("English")
    private var voiceType by mutableStateOf("Male")
    private var fontSize by mutableStateOf("Large")
    private var isDarkTheme by mutableStateOf(false)

    // Speech automation states
    private var lastSpokenGesture: String? = null
    private var lastSpeechTime: Long = 0
    private val speechCooldown = 2000L // 2 seconds cooldown

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPersistentData()

        loadGestureMap()
        initializeBluetooth()
        initializeTextToSpeech()
        checkPermissions()

        setContent {
            GestureGloveAppTheme(darkTheme = isDarkTheme) {
                BackHandler(enabled = currentScreen != Screen.Home) {
                    currentScreen = Screen.Home
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        Screen.Home -> HandSpeakScreen(
                            status = connectionStatus,
                            gesture = currentGesture,
                            connected = isConnected,
                            connecting = isConnecting,
                            volume = volume,
                            speechSpeed = speechSpeed,
                            onVolumeChange = { volume = it; saveSettings() },
                            onSpeechSpeedChange = { speechSpeed = it; saveSettings() },
                            onConnectClick = { if (!isConnected) connectToDevice() else disconnect() },
                            onRepeatClick = { if (currentGesture != "Recognized text will appear here") speakText(currentGesture) },
                            onNavigate = { currentScreen = it }
                        )
                        Screen.History -> HistoryScreen(
                            historyItems = historyList,
                            onBack = { currentScreen = Screen.Home },
                            onDelete = { historyList.remove(it); saveHistory() },
                            onSpeak = { speakText(it.text) },
                            onClearAll = { historyList.clear(); saveHistory() }
                        )
                        Screen.Settings -> SettingsScreen(
                            language = language,
                            voiceType = voiceType,
                            fontSize = fontSize,
                            theme = isDarkTheme,
                            onLanguageChange = { language = it; saveSettings() },
                            onVoiceTypeChange = { voiceType = it; saveSettings() },
                            onFontSizeChange = { fontSize = it; saveSettings() },
                            onThemeChange = { isDarkTheme = it; saveSettings() },
                            onReset = {
                                language = "English"
                                voiceType = "Male"
                                fontSize = "Large"
                                isDarkTheme = false
                                volume = 0.8f
                                speechSpeed = 1.0f
                                saveSettings()
                            },
                            onBack = { currentScreen = Screen.Home }
                        )
                    }
                }
            }
        }
    }

    private fun loadPersistentData() {
        // Load Settings
        volume = prefs.getFloat("volume", 0.8f)
        speechSpeed = prefs.getFloat("speechSpeed", 1.0f)
        language = prefs.getString("language", "English") ?: "English"
        voiceType = prefs.getString("voiceType", "Male") ?: "Male"
        fontSize = prefs.getString("fontSize", "Large") ?: "Large"
        isDarkTheme = prefs.getBoolean("isDarkTheme", false)

        // Load History
        val historyJson = prefs.getString("history", null)
        if (historyJson != null) {
            val type = object : TypeToken<List<HistoryItem>>() {}.type
            val items: List<HistoryItem> = gson.fromJson(historyJson, type)
            historyList.clear()
            historyList.addAll(items)
        }
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putFloat("volume", volume)
            putFloat("speechSpeed", speechSpeed)
            putString("language", language)
            putString("voiceType", voiceType)
            putString("fontSize", fontSize)
            putBoolean("isDarkTheme", isDarkTheme)
            apply()
        }
    }

    private fun saveHistory() {
        val historyJson = gson.toJson(historyList.toList())
        prefs.edit().putString("history", historyJson).apply()
    }

    private fun loadGestureMap() {
        try {
            val keys = resources.getStringArray(R.array.gesture_keys)
            val values = resources.getStringArray(R.array.gesture_values)
            gestureMap = keys.zip(values).toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading gesture map", e)
        }
    }

    private fun initializeBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this, this)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice() {
        if (bluetoothAdapter?.isEnabled != true) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            checkPermissions()
            return
        }

        val device = bluetoothAdapter?.bondedDevices?.find { it.name == deviceName }
        if (device != null) {
            performConnection(device)
        } else {
            Toast.makeText(this, "Device $deviceName not paired", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun performConnection(device: BluetoothDevice) {
        lifecycleScope.launch {
            isConnecting = true
            connectionStatus = "Connecting..."

            val success = withContext(Dispatchers.IO) {
                try {
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                    bluetoothSocket?.connect()
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Connection failed", e)
                    false
                }
            }

            isConnecting = false
            if (success) {
                isConnected = true
                connectionStatus = "Connected"
                startDataReceiving()
                Toast.makeText(this@MainActivity, "Connected to $deviceName", Toast.LENGTH_SHORT).show()
            } else {
                connectionStatus = "Failed to connect"
                cleanup()
            }
        }
    }

    private fun startDataReceiving() {
        receivingJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (isActive && isConnected) {
                try {
                    val socket = bluetoothSocket
                    if (socket == null || !socket.isConnected) {
                        throw IOException("Socket disconnected")
                    }
                    val bytes = socket.inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val rawData = String(buffer, 0, bytes).trim()
                        if (rawData.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                processGestureData(rawData)
                            }
                        }
                    } else if (bytes == -1) {
                        throw IOException("End of stream reached")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { 
                        connectionStatus = "Connection Lost"
                        disconnect() 
                    }
                    break
                }
                delay(100)
            }
        }
    }

    private fun processGestureData(rawData: String) {
        val cleanData = rawData.uppercase()
        val gesture = gestureMap[cleanData] ?: "Unknown: $rawData"
        currentGesture = gesture

        val currentTime = System.currentTimeMillis()
        if (gesture != lastSpokenGesture || (currentTime - lastSpeechTime > speechCooldown)) {
            speakText(gesture)
            addToHistory(gesture)
            lastSpokenGesture = gesture
            lastSpeechTime = currentTime
        }
    }

    private fun addToHistory(text: String) {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        historyList.add(0, HistoryItem(text = text, time = time))
        saveHistory() // Auto-save when new item added
    }

    private fun disconnect() {
        isConnected = false
        receivingJob?.cancel()
        cleanup()
        if (connectionStatus != "Connection Lost") {
            connectionStatus = "Disconnected"
        }
        currentGesture = "Recognized text will appear here"
        lastSpokenGesture = null
    }

    private fun cleanup() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing socket", e)
        } finally {
            bluetoothSocket = null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.getDefault()
        }
    }

    private fun speakText(text: String) {
        textToSpeech?.let { tts ->
            tts.setSpeechRate(speechSpeed)
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            tts.speak(text, TextToSpeech.QUEUE_ADD, params, "GestureID")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        textToSpeech?.shutdown()
    }
}

@Composable
fun HandSpeakScreen(
    status: String,
    gesture: String,
    connected: Boolean,
    connecting: Boolean,
    volume: Float,
    speechSpeed: Float,
    onVolumeChange: (Float) -> Unit,
    onSpeechSpeedChange: (Float) -> Unit,
    onConnectClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onNavigate: (Screen) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "HandSpeak",
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onConnectClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (connected) LightRed else MutedGreen
            ),
            shape = RoundedCornerShape(50),
            modifier = Modifier.width(180.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            Icon(Icons.Default.PowerSettingsNew, null, tint = Color.Black, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(if (connecting) "Connecting..." else if (connected) "Disconnect" else "Connect", color = Color.Black)
        }

        Spacer(modifier = Modifier.height(40.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Mic, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onBackground)
                Spacer(modifier = Modifier.height(24.dp))
                Text(gesture, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MutedGreen)
                .clickable(enabled = connected, onClick = onRepeatClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Replay, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onBackground)
        }

        Spacer(modifier = Modifier.height(32.dp))

        ControlSlider("Volume", volume, onVolumeChange)
        ControlSlider("Speech Speed", speechSpeed, onSpeechSpeedChange, 0.5f..2.0f)

        Spacer(modifier = Modifier.height(40.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            BottomCircleButton(Icons.Default.History) { onNavigate(Screen.History) }
            BottomCircleButton(Icons.Default.Settings) { onNavigate(Screen.Settings) }
        }
    }
}

@Composable
fun HistoryScreen(
    historyItems: List<HistoryItem>,
    onBack: () -> Unit,
    onDelete: (HistoryItem) -> Unit,
    onSpeak: (HistoryItem) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .clickable { onBack() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(24.dp))
                }
            }
            Text(
                "History",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(historyItems) { item ->
                    HistoryCard(item, onSpeak = { onSpeak(item) }, onDelete = { onDelete(item) })
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onClearAll,
            colors = ButtonDefaults.buttonColors(containerColor = SoftRed),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Icon(Icons.Default.Delete, null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear All History", color = Color.White, fontSize = 18.sp)
        }
    }
}

@Composable
fun HistoryCard(item: HistoryItem, onSpeak: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.text, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                Text(item.time, fontSize = 12.sp, color = Color.Gray)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallCircleButton(Icons.AutoMirrored.Filled.VolumeUp, MutedGreen, onSpeak)
                SmallCircleButton(Icons.Default.Delete, SoftRed, onDelete)
            }
        }
    }
}

@Composable
fun SettingsScreen(
    language: String,
    voiceType: String,
    fontSize: String,
    theme: Boolean,
    onLanguageChange: (String) -> Unit,
    onVoiceTypeChange: (String) -> Unit,
    onFontSizeChange: (String) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Settings?") },
            text = { Text("This will revert all settings to their default values.") },
            confirmButton = {
                TextButton(onClick = {
                    onReset()
                    showResetDialog = false
                }) {
                    Text("Yes", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("No")
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .clickable { onBack() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(24.dp))
                }
            }
            Text(
                "Settings",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        
        Spacer(modifier = Modifier.height(40.dp))
        
        SettingsDropdownRow("Language", language, listOf("English", "Hindi", "Marathi", "Tamil", "Telugu"), onLanguageChange)
        SettingsDropdownRow("Voice Type", voiceType, listOf("Male", "Female"), onVoiceTypeChange)
        SettingsDropdownRow("Font Size", fontSize, listOf("Small", "Medium", "Large"), onFontSizeChange)
        
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Theme", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Switch(checked = theme, onCheckedChange = onThemeChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MutedGreen))
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = { showResetDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
        ) {
            Text("Reset", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDropdownRow(label: String, selectedValue: String, options: List<String>, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        
        Box {
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.5f)
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedValue, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                    Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onBackground)
                }
            }
            DropdownMenu(
                expanded = expanded, 
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = MaterialTheme.colorScheme.onBackground) }, 
                        onClick = { onValueChange(option); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
fun ControlSlider(label: String, value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float> = 0f..1f) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(100.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        Slider(
            value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MutedGreen)
        )
    }
}

@Composable
fun BottomCircleButton(icon: ImageVector, onClick: () -> Unit) {
    Surface(modifier = Modifier.size(50.dp).clickable { onClick() }, shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(24.dp)) }
    }
}

@Composable
fun SmallCircleButton(icon: ImageVector, color: Color, onClick: () -> Unit) {
    Surface(modifier = Modifier.size(36.dp).clickable { onClick() }, shape = CircleShape, color = color) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
    }
}
