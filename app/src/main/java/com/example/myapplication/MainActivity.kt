package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID

@Suppress("SetTextI18n")
class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var tvStatus: TextView
    private lateinit var tvBpm: TextView
    private lateinit var tvBpmAvg: TextView
    private lateinit var tvAi: TextView
    private lateinit var pbConf: ProgressBar
    private lateinit var tvConf: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnToggle: Button
    private lateinit var chart: LineChart

    // state
    private var isConnected = false
    private var isStreaming = false
    private var collecting = false
    private var leadOff = false

    // ===== Chart (aproximativ clinic-ish) =====
    private val fs = 250f                 // rata reala (ESP32)
    private val displayDecim = 2          // afiseaza 1 din 2 => ~125Hz pe chart
    private val displayFs = fs / displayDecim
    private val windowSeconds = 3f        // fereastra vizibila (sec) - mai aerisit
    private val maxPoints = (displayFs * windowSeconds).toInt()

    private var xSec = 0f
    private val dt = 1f / displayFs
    private var decimCounter = 0

    // smoothing doar pentru display (nu afecteaza BPM/AI)
    private var smooth = 0f
    private val smoothA = 0.20f           // 0.1..0.3

    // BLE
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    private val serviceUUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c3319140")
    private val waveUUID    = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c3319141")
    private val bpmUUID     = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c3319142")
    private val statusUUID  = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c3319143")
    private val cccdUUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // CCCD queue
    private val notifyQueue: ArrayDeque<BluetoothGattCharacteristic> = ArrayDeque()
    private var enablingNotifies = false

    // Chart batching
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pendingEntries = mutableListOf<Entry>()

    // AI
    private lateinit var ai: TfliteClassifier
    private val N = 30
    private val bpmNormRing = FloatArray(N)
    private val bpmRawRing = IntArray(N)
    private var bpmPos = 0
    private var bpmFilled = false
    private var lastBpmRxMs = 0L

    // Permissions
    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startScan() }

    private val uiRunnable = object : Runnable {
        override fun run() {
            synchronized(pendingEntries) {
                if (pendingEntries.isNotEmpty()) {
                    addEntriesToChart(pendingEntries.toList())
                    pendingEntries.clear()
                }
            }

            // watchdog: daca BPM nu mai vine, re-enable notify (si nu mai colecta AI)
            val now = System.currentTimeMillis()
            if (isConnected && now - lastBpmRxMs > 3500) {
                runOnUiThread { tvStatus.text = "BPM timeout → re-enable notify..." }
                reEnableBpmNotify()
                collecting = false
                resetAiWindow("AI: aștept BPM...")
            }

            if (isStreaming) uiHandler.postDelayed(this, 40)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvBpm = findViewById(R.id.tvBpm)
        tvBpmAvg = findViewById(R.id.tvBpmAvg)
        tvAi = findViewById(R.id.tvAi)
        pbConf = findViewById(R.id.pbConf)
        tvConf = findViewById(R.id.tvConf)
        btnConnect = findViewById(R.id.btnConnect)
        btnToggle = findViewById(R.id.btnToggle)
        chart = findViewById(R.id.chart)

        setupChart()
        setupButtons()

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        ai = TfliteClassifier(this)

        // initial UI
        tvStatus.text = "Status: Idle"
        tvBpm.text = "BPM: -"
        tvBpmAvg.text = "Avg (30s): -"
        tvAi.text = "AI: -"
        pbConf.progress = 0
        tvConf.text = "Confidence: 0%"
    }

    private fun setupButtons() {
        btnConnect.setOnClickListener {
            if (isConnected) disconnectClean("Disconnected manually.")
            else askPermissionsAndStart()
        }

        btnToggle.setOnClickListener {
            isStreaming = !isStreaming
            btnToggle.text = if (isStreaming) "Stop" else "Start"

            if (isStreaming) {
                uiHandler.removeCallbacks(uiRunnable)
                uiHandler.post(uiRunnable)

                collecting = true
                resetAiWindow("AI: colectez date... (0/$N)")
                resetChart()
            } else {
                collecting = false
                uiHandler.removeCallbacks(uiRunnable)

                tvAi.text = "AI: -"
                pbConf.progress = 0
                tvConf.text = "Confidence: 0%"
                tvBpmAvg.text = "Avg (30s): -"
            }
        }
    }

    private fun resetChart() {
        xSec = 0f
        decimCounter = 0
        smooth = 0f
        synchronized(pendingEntries) { pendingEntries.clear() }

        val data = chart.data
        val set = data?.getDataSetByIndex(0) as? LineDataSet
        set?.clear()
        data?.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    private fun resetAiWindow(statusText: String) {
        bpmPos = 0
        bpmFilled = false
        runOnUiThread {
            tvAi.text = statusText
            tvBpmAvg.text = "Avg (30s): -"
            pbConf.progress = 0
            tvConf.text = "Confidence: 0%"
            tvAi.setBackgroundColor(0xFF444444.toInt())
        }
    }

    private fun askPermissionsAndStart() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val need = perms.any {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need) requestPerms.launch(perms) else startScan()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectClean(msg: String) {
        isStreaming = false
        collecting = false
        uiHandler.removeCallbacks(uiRunnable)

        runOnUiThread {
            tvStatus.text = msg
            btnConnect.text = "Connect"
            btnToggle.visibility = Button.GONE
            btnToggle.text = "Start"

            tvBpm.text = "BPM: -"
            tvBpmAvg.text = "Avg (30s): -"
            tvAi.text = "AI: -"
            pbConf.progress = 0
            tvConf.text = "Confidence: 0%"
        }

        try { gatt?.disconnect() } catch (_: SecurityException) {}
        try { gatt?.close() } catch (_: SecurityException) {}
        gatt = null

        isConnected = false
        enablingNotifies = false
        notifyQueue.clear()

        leadOff = false
        bpmPos = 0
        bpmFilled = false
        lastBpmRxMs = 0L
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            tvStatus.text = "Bluetooth is OFF."
            return
        }

        tvStatus.text = "Status: Scanning..."

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filter = ScanFilter.Builder()
            .setDeviceName("ESP32_EKG")
            .build()

        scanner?.startScan(listOf(filter), settings, scanCb)

        uiHandler.postDelayed({
            try { scanner?.stopScan(scanCb) } catch (_: Exception) {}
            if (!isConnected) tvStatus.text = "Scan timeout. Press Connect."
        }, 12_000)
    }

    @SuppressLint("MissingPermission")
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: ""
            tvStatus.text = "Found: $name RSSI=${result.rssi}"

            if (name.equals("ESP32_EKG", ignoreCase = true)) {
                try { scanner?.stopScan(this) } catch (_: Exception) {}
                gatt = device.connectGatt(this@MainActivity, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            tvStatus.text = "Scan failed: $errorCode"
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    tvStatus.text = "Connected. Discovering services..."
                    btnToggle.visibility = Button.VISIBLE
                    btnConnect.text = "Disconnect"
                    isConnected = true
                }
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.requestMtu(185)
                } catch (_: Exception) {}
                g.discoverServices()
            } else {
                disconnectClean("Disconnected.")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(serviceUUID)
            if (svc == null) {
                disconnectClean("Service not found.")
                return
            }

            enqueueEnableNotify(g, svc.getCharacteristic(waveUUID))
            enqueueEnableNotify(g, svc.getCharacteristic(bpmUUID))
            enqueueEnableNotify(g, svc.getCharacteristic(statusUUID))

            runOnUiThread { tvStatus.text = "Enabling notifications..." }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            enableNextNotify(g)
        }

        // Android 13+ (nou)
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristic(ch, value)
        }

        // fallback vechi
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val data = ch.value ?: return
            handleCharacteristic(ch, data)
        }
    }

    private fun handleCharacteristic(ch: BluetoothGattCharacteristic, data: ByteArray) {
        when (ch.uuid) {
            waveUUID -> if (data.size == 20) parseWave(data)
            bpmUUID -> if (data.size >= 2) parseBpmAndRunAi(data)
            statusUUID -> if (data.isNotEmpty()) applyStatusFlags(data[0].toInt() and 0xFF)
        }
    }

    private fun enqueueEnableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic?) {
        if (ch == null) return
        notifyQueue.add(ch)
        if (!enablingNotifies) {
            enablingNotifies = true
            enableNextNotify(g)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun enableNextNotify(g: BluetoothGatt) {
        val ch = notifyQueue.pollFirst()
        if (ch == null) {
            enablingNotifies = false
            runOnUiThread { tvStatus.text = "Notifications active." }
            return
        }

        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(cccdUUID)
        if (cccd == null) {
            enableNextNotify(g)
            return
        }

        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = g.writeDescriptor(cccd)

        if (!ok) {
            enableNextNotify(g)
            return
        }

        uiHandler.postDelayed({
            if (enablingNotifies) enableNextNotify(g)
        }, 500)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun reEnableBpmNotify() {
        val g = gatt ?: return
        val svc = g.getService(serviceUUID) ?: return
        val ch = svc.getCharacteristic(bpmUUID) ?: return
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(cccdUUID) ?: return
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(cccd)
    }

    private fun applyStatusFlags(flags: Int) {
        leadOff = (flags and 0x01) != 0
        runOnUiThread {
            if (leadOff) {
                tvStatus.text = "Electrozi deconectați"
                collecting = false
                tvAi.text = "AI: -"
                pbConf.progress = 0
                tvConf.text = "Confidence: 0%"
                tvBpmAvg.text = "Avg (30s): -"
            }
        }
    }

    private fun parseWave(data: ByteArray) {
        val flags = data[2].toInt() and 0xFF
        if ((flags and 0x01) != 0) return

        val bb = ByteBuffer.wrap(data, 4, 16).order(ByteOrder.LITTLE_ENDIAN)
        val samples = IntArray(8) { bb.short.toInt() }

        if (isStreaming) handleSamples(samples)
    }

    private fun parseBpmAndRunAi(data: ByteArray) {
        lastBpmRxMs = System.currentTimeMillis()

        val bpm = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)

        // bpm=0 => invalid (timeout / leadOff)
        if (bpm == 0) {
            runOnUiThread {
                tvBpm.text = "BPM: -"
                tvBpm.setTextColor(0xFFAAB3C8.toInt())
            }
            collecting = false
            resetAiWindow("AI: aștept semnal valid...")
            return
        }

        runOnUiThread {
            tvBpm.text = "BPM: $bpm"
            tvBpm.setTextColor(0xFF4CAF50.toInt())
        }

        if (leadOff) return
        if (!collecting) return

        bpmRawRing[bpmPos] = bpm
        bpmNormRing[bpmPos] = (bpm.toFloat() / 200f).coerceIn(0f, 1.5f)

        bpmPos = (bpmPos + 1) % N
        if (bpmPos == 0) bpmFilled = true

        if (!bpmFilled) {
            runOnUiThread {
                tvAi.text = "AI: colectez date... ($bpmPos/$N)"
                pbConf.progress = 0
                tvConf.text = "Confidence: 0%"
            }
            return
        }

        // avg rolling
        var sum = 0
        for (i in 0 until N) sum += bpmRawRing[i]
        val avg = (sum.toFloat() / N).toInt()
        runOnUiThread { tvBpmAvg.text = "Avg (30s): $avg" }

        // chronological window
        val window = FloatArray(N)
        val tail = N - bpmPos
        System.arraycopy(bpmNormRing, bpmPos, window, 0, tail)
        System.arraycopy(bpmNormRing, 0, window, tail, bpmPos)

        val probs = ai.predict(window)
        val cls = probs.indices.maxBy { probs[it] }
        val conf = probs[cls]

        runOnUiThread {
            pbConf.progress = (conf * 100).toInt()
            tvConf.text = "Confidence: ${(conf * 100).toInt()}%"
        }

        if (conf < 0.70f) return

        val verdict = when (cls) {
            1 -> "Tahicardie detectată"
            2 -> "Bradicardie detectată"
            else -> "Ritm normal"
        }

        runOnUiThread {
            tvAi.text = "AI: $verdict"
            tvAi.setBackgroundColor(
                when (cls) {
                    1 -> 0xFFFF9800.toInt()
                    2 -> 0xFF2196F3.toInt()
                    else -> 0xFF4CAF50.toInt()
                }
            )
        }
    }

//    decimare + smoothing + X in
    private fun handleSamples(samples: IntArray) {
        for (s in samples) {
            decimCounter++
            if (decimCounter % displayDecim != 0) continue

            val rawV = s.toFloat().coerceIn(-5000f, 5000f)
            smooth = (1f - smoothA) * smooth + smoothA * rawV
            val v = smooth

            synchronized(pendingEntries) {
                pendingEntries.add(Entry(xSec, v))
            }
            xSec += dt
        }
    }

    private fun setupChart() {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setPinchZoom(true)
        chart.setScaleEnabled(true)

        // ca ScrollView sa nu fure gesture-urile
        chart.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }

        // fundal "monitor-like"
        chart.setBackgroundColor(Color.WHITE)

        val set = LineDataSet(mutableListOf(), "ECG").apply {
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 2f
            color = Color.BLACK
        }
        chart.data = LineData(set)

        chart.axisRight.isEnabled = false

        // zoom vertical (aproximativ clinic)
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.rgb(255, 210, 210)
            gridLineWidth = 0.6f
            textColor = Color.DKGRAY

            // ajusteaza daca vrei mai mare/mic
            axisMinimum = -300f
            axisMaximum = 1100f
        }

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            gridColor = Color.rgb(255, 210, 210)
            gridLineWidth = 0.6f

            // grid la 0.04s (aprox “hartie ECG” la 25mm/s)
            granularity = 0.04f

            // afiseaza secunde (0.0, 0.5, 1.0...) - simplu
            setDrawLabels(true)
            textColor = Color.DKGRAY
        }
    }

    private fun addEntriesToChart(entries: List<Entry>) {
        val data = chart.data ?: return
        val set = data.getDataSetByIndex(0) as LineDataSet

        for (e in entries) set.addEntry(e)

        // pastreaza doar fereastra vizibila
        while (set.entryCount > maxPoints) {
            set.removeFirst()
        }

        data.notifyDataChanged()
        chart.notifyDataSetChanged()

        chart.setVisibleXRangeMaximum(windowSeconds)
        chart.moveViewToX(xSec)
        chart.invalidate()
    }

    override fun onStop() {
        super.onStop()
        isStreaming = false
        uiHandler.removeCallbacks(uiRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ai.close() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: SecurityException) {}
    }
}
