package com.dg.playscreen

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etIpAddress: EditText
    private lateinit var tvStatus: TextView

    private var tcpSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentIp: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIpAddress = findViewById(R.id.etIpAddress)
        tvStatus = findViewById(R.id.tvStatus)

        val prefs = getSharedPreferences("DGPlayPrefs", Context.MODE_PRIVATE)
        etIpAddress.setText(prefs.getString("last_ip", ""))

        findViewById<Button>(R.id.btnScan).setOnClickListener { scanForReceivers() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val ip = etIpAddress.text.toString().trim()
            if (ip.isNotEmpty()) {
                prefs.edit().putString("last_ip", ip).apply()
                connectAndFetch(ip)
            }
        }
    }

    private fun scanForReceivers() {
        updateStatus("Scanning Wi-Fi & Hotspot for STB devices...")
        scope.launch {
            val subnets = mutableSetOf<String>()
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                if (iface.isUp && !iface.isLoopback) iface.inetAddresses?.toList()?.filterIsInstance<java.net.Inet4Address>()?.forEach { addr ->
                    val ip = addr.hostAddress ?: ""
                    if (ip.contains(".") && !addr.isLoopbackAddress) subnets.add(ip.substringBeforeLast(".") + ".")
                }
            }
            if (subnets.isEmpty()) subnets.addAll(listOf("192.168.1.", "192.168.43."))
            val foundDevices = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            for (sub in subnets) {
                (1..254).map { i ->
                    async(Dispatchers.IO) {
                        val testIp = "$sub$i"
                        for (port in listOf(20000, 8000, 8888)) {
                            try {
                                Socket().use { s ->
                                    s.connect(InetSocketAddress(testIp, port), 220); s.soTimeout = 1200
                                    foundDevices.add(Pair("STB Receiver ($testIp:$port)", "$testIp:$port"))
                                }
                                break
                            } catch (_: Exception) {}
                        }
                    }
                }.awaitAll()
            }
            withContext(Dispatchers.Main) {
                if (foundDevices.isNotEmpty()) {
                    updateStatus("Found ${foundDevices.size} STB device(s)")
                    AlertDialog.Builder(this@MainActivity).setTitle("Sélectionner un appareil STB").setItems(foundDevices.map { it.first }.toTypedArray()) { _, which ->
                        val chosenIp = foundDevices[which].second
                        etIpAddress.setText(chosenIp)
                        getSharedPreferences("DGPlayPrefs", Context.MODE_PRIVATE).edit().putString("last_ip", chosenIp).apply()
                        connectAndFetch(chosenIp)
                    }.show()
                } else updateStatus("No ALi/GX receiver found")
            }
        }
    }

    private fun connectAndFetch(rawIp: String) {
        val parts = rawIp.trim().split(":")
        val ip = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 20000
        currentIp = ip
        updateStatus("Connecting to $ip:$port...")
        scope.launch {
            try {
                tcpSocket?.close()
                val s = Socket(); s.connect(InetSocketAddress(ip, port), 5000); s.soTimeout = 5000
                tcpSocket = s; outputStream = s.getOutputStream(); inputStream = s.getInputStream()
                val out = outputStream!!; val inStr = inputStream!!
                
                out.write(wrapGmScreenPacket("{\"request\":\"998\",\"ClientType\":\"0\",\"MobileType\":\"Android\",\"AppVersion\":\"1.0\"}"))
                out.flush(); delay(300); drainSocketBuffer(inStr)
                out.write(wrapGmScreenPacket("{\"request\":\"15\"}")); out.flush()
                val handshakeReply = readGmScreenResponse(inStr); drainSocketBuffer(inStr)
                val deviceName = Regex("\"ProductName\"\\s*:\\s*\"([^\"]+)\"").find(handshakeReply)?.groupValues?.get(1) ?: "ALi STB"
                
                withContext(Dispatchers.Main) {
                    updateStatus("Connected to $deviceName • Redirecting...")
                    val intent = Intent(this@MainActivity, ChannelsActivity::class.java)
                    intent.putExtra("IP", currentIp)
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { updateStatus("Connection Error: ${e.message}") }
            }
        }
    }

    private fun wrapGmScreenPacket(payloadStr: String): ByteArray {
        val payloadBytes = payloadStr.toByteArray(Charsets.UTF_8)
        val header = String.format(Locale.US, "Start%07dEnd", payloadBytes.size).toByteArray(Charsets.US_ASCII)
        return header + payloadBytes
    }

    private fun drainSocketBuffer(inStr: InputStream) {
        try { val dump = ByteArray(8192); while (inStr.available() > 0) { inStr.read(dump); Thread.sleep(30) } } catch (_: Exception) {}
    }

    private fun readGmScreenResponse(inStream: InputStream): String {
        val allBytes = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var expectedPayloadSize = -1
        var headerEndOffset = -1
        var idleAttempts = 0
        var lastReadTime = System.currentTimeMillis()

        while (idleAttempts < 20) {
            try {
                if (inStream.available() > 0) {
                    val r = inStream.read(buf); if (r <= 0) break
                    allBytes.write(buf, 0, r); idleAttempts = 0; lastReadTime = System.currentTimeMillis()
                    val currentData = allBytes.toByteArray()
                    if (expectedPayloadSize == -1) {
                        val strCheck = String(currentData, 0, minOf(currentData.size, 128), Charsets.ISO_8859_1)
                        val match = Regex("Start(\\d{7})End").find(strCheck)
                        if (match != null) { expectedPayloadSize = match.groupValues[1].toIntOrNull() ?: -1; headerEndOffset = match.range.last + 1 }
                    }
                    if (expectedPayloadSize > 0 && headerEndOffset > 0 && currentData.size >= headerEndOffset + expectedPayloadSize) break
                } else {
                    if (System.currentTimeMillis() - lastReadTime > 1500) break
                    Thread.sleep(50); idleAttempts++
                }
            } catch (_: Exception) { break }
        }

        val raw = allBytes.toByteArray()
        if (raw.isEmpty()) return ""

        val searchStart = if (headerEndOffset > 0) headerEndOffset else 0

        for (i in searchStart until raw.size - 1) {
            if (raw[i] == 0x78.toByte() && (raw[i + 1] == 0x9C.toByte() || raw[i + 1] == 0xDA.toByte() || raw[i + 1] == 0x01.toByte() || raw[i + 1] == 0x5E.toByte())) {
                try {
                    val inflater = java.util.zip.Inflater(); inflater.setInput(raw, i, raw.size - i)
                    val out = java.io.ByteArrayOutputStream(); val tmp = ByteArray(8192)
                    while (!inflater.finished()) {
                        val count = inflater.inflate(tmp)
                        if (count == 0) { if (inflater.needsInput()) break }
                        out.write(tmp, 0, count)
                    }
                    inflater.end()
                    val unzipped = out.toString("UTF-8")
                    if (unzipped.isNotEmpty()) return unzipped
                } catch (_: Exception) {}
            }
        }
        return if (searchStart < raw.size) String(raw, searchStart, raw.size - searchStart, Charsets.UTF_8) else String(raw, Charsets.UTF_8)
    }

    private fun updateStatus(msg: String) { runOnUiThread { tvStatus.text = msg } }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { tcpSocket?.close() } catch (_: Throwable) {}
    }
}
