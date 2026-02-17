package com.sujay.gestureglove

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var gestureText: TextView
    private lateinit var connectButton: Button
    private lateinit var speakButton: Button

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var textToSpeech: TextToSpeech? = null
    private var isConnected = false

    // ESP32 Bluetooth device name
    private val deviceName = "GestureGlove"
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Gesture mapping
    private val gestureMap = mapOf(
        "HELLO" to "Hello",
        "THANK" to "Thank you",
        "YES" to "Yes",
        "NO" to "No",
        "HELP" to "Help me",
        "WATER" to "I need water"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        initializeBluetooth()
        initializeTextToSpeech()
        setupClickListeners()
        checkPermissions()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        gestureText = findViewById(R.id.gestureText)
        connectButton = findViewById(R.id.connectButton)
        speakButton = findViewById(R.id.speakButton)
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

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            if (!isConnected) {
                connectToDevice()
            } else {
                disconnect()
            }
        }

        speakButton.setOnClickListener {
            val text = gestureText.text.toString()
            if (text != "No gesture detected") {
                speakText(text)
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val permissionsNeeded = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), 1)
        }
    }

    private fun connectToDevice() {

        if (bluetoothAdapter?.isEnabled != true) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, 2)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
                return
            }
        }


        val pairedDevices = bluetoothAdapter?.bondedDevices
        val device = pairedDevices?.find { it.name == deviceName }

        if (device != null) {
            ConnectTask().execute(device)
        } else {
            Toast.makeText(this, "Device $deviceName not paired", Toast.LENGTH_LONG).show()
        }
    }

    private inner class ConnectTask : AsyncTask<BluetoothDevice, Void, Boolean>() {
        override fun doInBackground(vararg devices: BluetoothDevice): Boolean {
            return try {
                val device = devices[0]
                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothSocket?.connect()
                true
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
        }

        override fun onPostExecute(success: Boolean) {
            if (success) {
                isConnected = true
                statusText.text = "Status: Connected"
                connectButton.text = "Disconnect"
                speakButton.isEnabled = true
                startDataReceiving()
                Toast.makeText(this@MainActivity, "Connected to glove", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startDataReceiving() {
        Thread {
            val buffer = ByteArray(1024)
            var bytes: Int

            while (isConnected) {
                try {
                    val inputStream: InputStream = bluetoothSocket?.inputStream ?: break
                    bytes = inputStream.read(buffer)
                    val receivedData = String(buffer, 0, bytes).trim()

                    runOnUiThread {
                        processGestureData(receivedData)
                    }
                } catch (e: IOException) {
                    runOnUiThread {
                        disconnect()
                    }
                    break
                }
            }
        }.start()
    }

    private fun processGestureData(rawData: String) {
        val gesture = gestureMap[rawData] ?: "Unknown: $rawData"
        gestureText.text = gesture

        // Auto-speak recognized gestures
        if (gestureMap.containsKey(rawData)) {
            speakText(gesture)
        }
    }

    private fun disconnect() {
        try {
            isConnected = false
            bluetoothSocket?.close()
            statusText.text = "Status: Disconnected"
            connectButton.text = "Connect to Glove"
            speakButton.isEnabled = false
            gestureText.text = "No gesture detected"
            Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // TextToSpeech Implementation
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.getDefault()
        } else {
            Toast.makeText(this, "TTS initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speakText(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        textToSpeech?.shutdown()
    }
}
