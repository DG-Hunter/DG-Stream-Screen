package com.dg.playscreen

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

class MainActivity : Activity() {

    data class StbDevice(
        val name: String,
        val ip: String,
        val port: Int,
        val model: String
    )

    data class SatItem(
        val index: Int,
        val name: String,
        val angle: String
    )

    data class ChannelItem(
        val index: Int,
        val serviceId: String,
        val name: String,
        val satIndex: Int,
        val satName: String,
        val isRadio: Boolean,
        val isScrambled: Boolean,
        val isFav: Boolean,
        val isLocked: Boolean,
        val rawObj: JSONObject
    )

    private lateinit var etIpPort: EditText
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView

    private lateinit var tabChannels: Button
    private lateinit var tabRemote: Button
    private lateinit var tabSatfinder: Button

    private lateinit var layoutChannelsTab: LinearLayout
    private lateinit var layoutRemoteTab: View
    private lateinit var layoutSatfinderTab: View

    private lateinit var btnTvRadioToggle: Button
    private lateinit var btnSatDropdown: Button
    private lateinit var etSearchChannel: EditText
    private lateinit var lvChannels: ListView

    private lateinit var pbStrength: ProgressBar
    private lateinit var pbQuality: ProgressBar
    private lateinit var tvStrengthPct: TextView
    private lateinit var tvQualityPct: TextView
    private lateinit var tvSatSignalInfo: TextView

    private val executor = Executors.newCachedThreadPool()
    private val socketLock = Any()

    private var currentIp = ""
    private var currentPort = 20000
    private var stbModel = "STB"
    private var isConnected = false

    private val satList = mutableListOf<SatItem>()
    private val allChannels = mutableListOf<ChannelItem>()
    private val filteredChannels = mutableListOf<ChannelItem>()
    private lateinit var channelAdapter: ChannelAdapter

    private var showRadio = false
    private var selectedSatIndex = -1
    private var selectedSatLabel = "Toutes les chaînes"
    private var expandedChannelIndex = -1

    @Volatile
    private var satfinderRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupTabs()
        setupChannelControls()
        setupRemoteButtons()
        setupConnectionButtons()
    }

    private fun bindViews() {
        etIpPort = findViewById(R.id.etIpPort)
        btnScan = findViewById(R.id.btnScan)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)

        tabChannels = findViewById(R.id.tabChannels)
        tabRemote = findViewById(R.id.tabRemote)
        tabSatfinder = findViewById(R.id.tabSatfinder)

        layoutChannelsTab = findViewById(R.id.layoutChannelsTab)
        layoutRemoteTab = findViewById(R.id.layoutRemoteTab)
        layoutSatfinderTab = findViewById(R.id.layoutSatfinderTab)

        btnTvRadioToggle = findViewById(R.id.btnTvRadioToggle)
        btnSatDropdown = findViewById(R.id.btnSatDropdown)
        etSearchChannel = findViewById(R.id.etSearchChannel)
        lvChannels = findViewById(R.id.lvChannels)

        pbStrength = findViewById(R.id.pbStrength)
        pbQuality = findViewById(R.id.pbQuality)
        tvStrengthPct = findViewById(R.id.tvStrengthPct)
        tvQualityPct = findViewById(R.id.tvQualityPct)
        tvSatSignalInfo = findViewById(R.id.tvSatSignalInfo)

        channelAdapter = ChannelAdapter()
        lvChannels.adapter = channelAdapter
    }

    private fun setupTabs() {
        tabChannels.setOnClickListener { switchTab(0) }
        tabRemote.setOnClickListener { switchTab(1) }
        tabSatfinder.setOnClickListener { switchTab(2) }
        switchTab(0)
    }

    private fun switchTab(index: Int) {
        satfinderRunning = (index == 2)

        layoutChannelsTab.visibility = if (index == 0) View.VISIBLE else View.GONE
        layoutRemoteTab.visibility = if (index == 1) View.VISIBLE else View.GONE
        layoutSatfinderTab.visibility = if (index == 2) View.VISIBLE else View.GONE

        tabChannels.setBackgroundColor(if (index == 0) Color.parseColor("#0284C7") else Color.parseColor("#1E293B"))
        tabRemote.setBackgroundColor(if (index == 1) Color.parseColor("#0284C7") else Color.parseColor("#1E293B"))
        tabSatfinder.setBackgroundColor(if (index == 2) Color.parseColor("#0284C7") else Color.parseColor("#1E293B"))

        if (index == 2 && isConnected) {
            startSatfinderPolling()
        }
    }

    private fun setupChannelControls() {
        btnTvRadioToggle.setOnClickListener {
            showRadio = !showRadio
            btnTvRadioToggle.text = if (showRadio) "RADIO" else "TV"
            btnTvRadioToggle.setBackgroundColor(
                if (showRadio) Color.parseColor("#EA580C") else Color.parseColor("#0284C7")
            )
            applyChannelFilter()
        }

        btnSatDropdown.setOnClickListener {
            showSatSelectionDialog()
        }

        etSearchChannel.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyChannelFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupConnectionButtons() {
        btnScan.setOnClickListener {
            discoverStbDevices()
        }

        btnConnect.setOnClickListener {
            val raw = etIpPort.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(this, "Enter STB IP or press SCAN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val parts = raw.split(":")
            currentIp = parts[0].trim()
            currentPort = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: 20000 else 20000
            connectAndLoadData()
        }
    }
    // ==================== 1. STB Network Discovery ====================
    private fun discoverStbDevices() {
        tvStatus.text = "Scanning network for Géant / StarSat STB..."
        executor.execute {
            val found = mutableListOf<StbDevice>()
            try {
                val udpSocket = DatagramSocket()
                udpSocket.broadcast = true
                udpSocket.soTimeout = 1800

                val probes = listOf(
                    "{\"request\":\"998\"}",
                    "GM_SEARCH_STB",
                    "{\"cmd\":\"discover\"}"
                )
                val ports = listOf(20000, 19000, 8888)
                for (p in ports) {
                    for (msg in probes) {
                        try {
                            val data = msg.toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(
                                data, data.size,
                                InetAddress.getByName("255.255.255.255"), p
                            )
                            udpSocket.send(packet)
                        } catch (_: Exception) {
                        }
                    }
                }

                val buf = ByteArray(2048)
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 1800) {
                    try {
                        val resp = DatagramPacket(buf, buf.size)
                        udpSocket.receive(resp)
                        val text = String(resp.data, 0, resp.length, Charsets.UTF_8).trim()
                        val ip = resp.address.hostAddress ?: continue
                        var model = "STB Receiver"
                        var port = 20000
                        try {
                            val json = JSONObject(text)
                            model = json.optString("ProductName", json.optString("model", model))
                            port = json.optInt("Port", 20000)
                        } catch (_: Exception) {
                            if (text.isNotEmpty() && text.length < 40) model = text
                        }
                        if (found.none { it.ip == ip }) {
                            found.add(StbDevice(model, ip, port, model))
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
                udpSocket.close()
            } catch (_: Exception) {
            }

            if (found.isEmpty()) {
                val prefix = getLocalSubnetPrefix()
                if (prefix != null) {
                    val latch = CountDownLatch(254)
                    for (i in 1..254) {
                        val testIp = "$prefix.$i"
                        executor.execute {
                            try {
                                val s = Socket()
                                s.connect(InetSocketAddress(testIp, 20000), 220)
                                s.close()
                                synchronized(found) {
                                    if (found.none { it.ip == testIp }) {
                                        found.add(StbDevice("Géant / StarSat ($testIp)", testIp, 20000, "STB"))
                                    }
                                }
                            } catch (_: Exception) {
                            } finally {
                                latch.countDown()
                            }
                        }
                    }
                    try {
                        latch.await(2500, TimeUnit.MILLISECONDS)
                    } catch (_: Exception) {
                    }
                }
            }

            runOnUiThread {
                if (found.isEmpty()) {
                    tvStatus.text = "No STB found automatically. Enter IP manually."
                    Toast.makeText(this, "No STB found automatically", Toast.LENGTH_SHORT).show()
                } else {
                    tvStatus.text = "Found ${found.size} STB device(s)"
                    showDeviceSelectionDialog(found)
                }
            }
        }
    }

    private fun getLocalSubnetPrefix(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        val lastDot = ip.lastIndexOf('.')
                        if (lastDot > 0) return ip.substring(0, lastDot)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun showDeviceSelectionDialog(devices: List<StbDevice>) {
        val labels = devices.map { "${it.name}\n${it.ip}:${it.port}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Sélectionner un appareil STB")
            .setItems(labels) { _, which ->
                val chosen = devices[which]
                currentIp = chosen.ip
                currentPort = chosen.port
                etIpPort.setText("${chosen.ip}:${chosen.port}")
                connectAndLoadData()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ==================== 2. Connect & Fetch Sats + Channels ====================
    private fun connectAndLoadData() {
        tvStatus.text = "Connecting to $currentIp:$currentPort ..."
        executor.execute {
            synchronized(socketLock) {
                var socket: Socket? = null
                try {
                    socket = Socket()
                    socket.connect(InetSocketAddress(currentIp, currentPort), 4000)
                    socket.soTimeout = 3500
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()

                    drainIncomingPackets(inp, 250)

                    sendGmCommand(out, "{\"request\":\"998\"}")
                    val handshakeJson = readMatchingResponse(inp, 2500) { raw ->
                        raw.contains("ProductName") || raw.contains("SoftwareVersion") || raw.contains("Platform") || raw.contains("{")
                    }
                    if (handshakeJson != null) {
                        try {
                            val obj = extractFirstJsonObject(handshakeJson)
                            if (obj != null) {
                                val pName = obj.optString("ProductName", "")
                                if (pName.isNotEmpty()) stbModel = pName
                            }
                        } catch (_: Exception) {
                        }
                    }

                    isConnected = true
                    runOnUiThread {
                        tvStatus.text = "Connected ($stbModel) • Loading Sats & Channels..."
                    }

                    val fetchedSats = fetchSatellites(out, inp)
                    satList.clear()
                    satList.addAll(fetchedSats)

                    runOnUiThread {
                        tvStatus.text = "Connected ($stbModel) • ${satList.size} Sats • Loading Channels..."
                    }

                    val fetchedChannels = fetchAllChannelsPaginated(out, inp)
                    allChannels.clear()
                    allChannels.addAll(fetchedChannels)

                    runOnUiThread {
                        tvStatus.text = "Connected ($stbModel) • ${satList.size} Sats • ${allChannels.size} Ch."
                        applyChannelFilter()
                    }
                } catch (e: Exception) {
                    isConnected = false
                    runOnUiThread {
                        tvStatus.text = "Connection error: ${e.message}"
                    }
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun fetchSatellites(out: OutputStream, inp: InputStream): List<SatItem> {
        val result = mutableListOf<SatItem>()
        val satCmds = listOf(
            "{\"request\":\"1001\"}",
            "{\"request\":\"2\"}",
            "{\"request\":\"1001\",\"FromIndex\":\"0\",\"ToIndex\":\"100\"}"
        )

        for (cmd in satCmds) {
            drainIncomingPackets(inp, 150)
            sendGmCommand(out, cmd)
            val raw = readMatchingResponse(inp, 2500) { text ->
                !isSignalPacket(text) && (text.contains("SatName") || text.contains("SatelliteName") || text.contains("Name"))
            } ?: continue

            val arr = extractJsonArray(raw) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("SatName",
                    obj.optString("SatelliteName",
                        obj.optString("Name", "")))
                if (name.isNotEmpty()) {
                    val idx = obj.optInt("SatIndex", obj.optInt("Index", i))
                    val angle = obj.optString("SatAngle", obj.optString("Angle", ""))
                    result.add(SatItem(idx, name, angle))
                }
            }
            if (result.isNotEmpty()) break
        }
        return result
    }
    private fun fetchAllChannelsPaginated(out: OutputStream, inp: InputStream): List<ChannelItem> {
        val result = mutableListOf<ChannelItem>()
        val seenIds = HashSet<String>()

        val requestModes = listOf(
            Pair("1000", true),
            Pair("0", true),
            Pair("1000", false),
            Pair("0", false)
        )

        for ((reqCode, usePaging) in requestModes) {
            var fromIdx = 0
            val step = 150
            var emptyPages = 0

            while (fromIdx < 6000 && emptyPages < 2) {
                val toIdx = fromIdx + step - 1
                val cmd = if (usePaging) {
                    "{\"request\":\"$reqCode\",\"FromIndex\":\"$fromIdx\",\"ToIndex\":\"$toIdx\"}"
                } else {
                    "{\"request\":\"$reqCode\"}"
                }

                drainIncomingPackets(inp, 120)
                sendGmCommand(out, cmd)

                val raw = readMatchingResponse(inp, 3200) { text ->
                    !isSignalPacket(text) && (
                        text.contains("ServiceName") ||
                        text.contains("ProgramName") ||
                        text.contains("ChannelName") ||
                        text.contains("ServiceID") ||
                        text.contains("ProgramId") ||
                        text.contains("\"Name\"")
                    )
                }

                if (raw == null) {
                    emptyPages++
                    if (!usePaging) break
                    fromIdx += step
                    continue
                }

                val arr = extractJsonArray(raw)
                if (arr == null || arr.length() == 0) {
                    emptyPages++
                    if (!usePaging) break
                    fromIdx += step
                    continue
                }

                var addedInBatch = 0
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val chName = obj.optString("ServiceName",
                        obj.optString("ProgramName",
                            obj.optString("ChannelName",
                                obj.optString("Name", "")))).trim()
                    if (chName.isEmpty()) continue

                    val srvId = obj.optString("ServiceID",
                        obj.optString("ProgramId",
                            obj.optString("ChannelID", "${fromIdx + i}")))

                    val chIndex = obj.optInt("ProgramIndex",
                        obj.optInt("ServiceIndex",
                            obj.optInt("Index", result.size + 1)))

                    val satIdx = obj.optInt("SatIndex",
                        obj.optInt("SatelliteIndex",
                            obj.optInt("SatID", -1)))

                    val satName = satList.firstOrNull { it.index == satIdx }?.name
                        ?: obj.optString("SatName", "")

                    val radioFlag = obj.optInt("Radio", obj.optInt("IsRadio", 0)) == 1 ||
                            obj.optString("ServiceType", "").equals("Radio", true)

                    val scrambleFlag = obj.optInt("Scramble", obj.optInt("IsScrambled", obj.optInt("CA", 0))) == 1
                    val favFlag = obj.optInt("Fav", obj.optInt("IsFav", obj.optInt("Favorite", 0))) > 0
                    val lockFlag = obj.optInt("Lock", obj.optInt("IsLocked", 0)) == 1

                    val uniqueKey = "$srvId|$chName|$radioFlag"
                    if (seenIds.add(uniqueKey)) {
                        result.add(
                            ChannelItem(
                                index = if (chIndex > 0) chIndex else result.size + 1,
                                serviceId = srvId,
                                name = chName,
                                satIndex = satIdx,
                                satName = satName,
                                isRadio = radioFlag,
                                isScrambled = scrambleFlag,
                                isFav = favFlag,
                                isLocked = lockFlag,
                                rawObj = obj
                            )
                        )
                        addedInBatch++
                    }
                }

                if (!usePaging) break
                if (addedInBatch == 0 || arr.length() < step / 2) {
                    break
                }
                fromIdx += step
            }

            if (result.isNotEmpty()) break
        }

        return result
    }

    // ==================== 3. Smart Socket Protocol & Zlib ====================
    private fun sendGmCommand(out: OutputStream, jsonPayload: String) {
        val body = jsonPayload.toByteArray(Charsets.UTF_8)
        val header = String.format("Start%07d", body.size).toByteArray(Charsets.UTF_8)
        out.write(header)
        out.write(body)
        out.flush()
    }

    private fun drainIncomingPackets(inp: InputStream, durationMs: Long) {
        val end = System.currentTimeMillis() + durationMs
        val buf = ByteArray(4096)
        while (System.currentTimeMillis() < end) {
            try {
                if (inp.available() > 0) {
                    inp.read(buf)
                } else {
                    Thread.sleep(20)
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun isSignalPacket(text: String): Boolean {
        val clean = text.trim()
        return clean.contains("\"strength\"") && clean.contains("\"quality\"") &&
                !clean.contains("ServiceName") && !clean.contains("ProgramName") && !clean.contains("SatName")
    }

    private fun readMatchingResponse(
        inp: InputStream,
        timeoutMs: Long,
        predicate: (String) -> Boolean
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArrayOutputStream()
        val temp = ByteArray(8192)

        while (System.currentTimeMillis() < deadline) {
            try {
                val avail = inp.available()
                if (avail > 0) {
                    val read = inp.read(temp, 0, minOf(temp.size, avail))
                    if (read > 0) {
                        buffer.write(temp, 0, read)
                        val decoded = decodeGmStreamBytes(buffer.toByteArray())
                        for (chunk in decoded) {
                            if (predicate(chunk)) {
                                return chunk
                            }
                        }
                    }
                } else {
                    Thread.sleep(30)
                }
            } catch (_: Exception) {
                break
            }
        }
        val finalChunks = decodeGmStreamBytes(buffer.toByteArray())
        return finalChunks.firstOrNull { predicate(it) }
    }

    private fun decodeGmStreamBytes(rawBytes: ByteArray): List<String> {
        val chunks = mutableListOf<String>()
        if (rawBytes.isEmpty()) return chunks

        val decompressed = tryZlibDecompress(rawBytes)
        val text = String(decompressed ?: rawBytes, Charset.forName("UTF-8"))

        val parts = text.split(Regex("Start\\d{7}"))
        for (p in parts) {
            val trimmed = p.trim()
            if (trimmed.isNotEmpty()) {
                chunks.add(trimmed)
            }
        }
        return chunks
    }

    private fun tryZlibDecompress(data: ByteArray): ByteArray? {
        for (offset in 0 until minOf(data.size - 2, 32)) {
            if (data[offset] == 0x78.toByte() &&
                (data[offset + 1] == 0x9C.toByte() || data[offset + 1] == 0xDA.toByte() || data[offset + 1] == 0x01.toByte())
            ) {
                try {
                    val inflater = Inflater()
                    inflater.setInput(data, offset, data.size - offset)
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (!inflater.finished()) {
                        val count = inflater.inflate(buf)
                        if (count == 0) break
                        out.write(buf, 0, count)
                    }
                    inflater.end()
                    if (out.size() > 0) return out.toByteArray()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun extractJsonArray(raw: String): JSONArray? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start != -1 && end > start) {
            try {
                return JSONArray(raw.substring(start, end + 1))
            } catch (_: Exception) {
            }
        }
        val obj = extractFirstJsonObject(raw) ?: return null
        val keys = listOf("list", "List", "data", "Data", "channels", "Channels", "programs", "Programs", "sats", "SatList")
        for (k in keys) {
            val arr = obj.optJSONArray(k)
            if (arr != null) return arr
        }
        return null
    }

    private fun extractFirstJsonObject(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start != -1 && end > start) {
            try {
                return JSONObject(raw.substring(start, end + 1))
            } catch (_: Exception) {
            }
        }
        return null
    }
    // ==================== 4. Satellite Filter & Channel List ====================
    private fun showSatSelectionDialog() {
        val items = mutableListOf<String>()
        items.add("Toutes les chaînes (${allChannels.count { it.isRadio == showRadio }})")
        items.add("★ Favoris (${allChannels.count { it.isRadio == showRadio && it.isFav }})")

        for (sat in satList) {
            val count = allChannels.count { it.isRadio == showRadio && it.satIndex == sat.index }
            items.add("${sat.name} ($count)")
        }

        AlertDialog.Builder(this)
            .setTitle("Sélectionner un Satellite")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        selectedSatIndex = -1
                        selectedSatLabel = "Toutes les chaînes"
                    }
                    1 -> {
                        selectedSatIndex = -2
                        selectedSatLabel = "★ Favoris"
                    }
                    else -> {
                        val sat = satList[which - 2]
                        selectedSatIndex = sat.index
                        selectedSatLabel = sat.name
                    }
                }
                btnSatDropdown.text = "$selectedSatLabel ▾"
                applyChannelFilter()
            }
            .show()
    }

    private fun applyChannelFilter() {
        val query = etSearchChannel.text.toString().trim().lowercase()
        filteredChannels.clear()

        for (ch in allChannels) {
            if (ch.isRadio != showRadio) continue
            if (selectedSatIndex == -2 && !ch.isFav) continue
            if (selectedSatIndex >= 0 && ch.satIndex != selectedSatIndex) continue
            if (query.isNotEmpty() && !ch.name.lowercase().contains(query) && !ch.index.toString().contains(query)) {
                continue
            }
            filteredChannels.add(ch)
        }
        expandedChannelIndex = -1
        channelAdapter.notifyDataSetChanged()
    }

    // ==================== 5. Channel Adapter & GM Screen Actions (•••) ====================
    inner class ChannelAdapter : BaseAdapter() {
        override fun getCount(): Int = filteredChannels.size
        override fun getItem(position: Int): Any = filteredChannels[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_channel, parent, false)

            val ch = filteredChannels[position]
            val rowMain = view.findViewById<LinearLayout>(R.id.rowChannelMain)
            val tvNum = view.findViewById<TextView>(R.id.tvChannelNum)
            val tvName = view.findViewById<TextView>(R.id.tvChannelName)
            val tvScramble = view.findViewById<TextView>(R.id.tvBadgeScramble)
            val tvFav = view.findViewById<TextView>(R.id.tvBadgeFav)
            val tvLock = view.findViewById<TextView>(R.id.tvBadgeLock)
            val tvSat = view.findViewById<TextView>(R.id.tvChannelSat)
            val btnMore = view.findViewById<TextView>(R.id.btnChannelMore)
            val layoutActions = view.findViewById<LinearLayout>(R.id.layoutChannelActions)

            tvNum.text = ch.index.toString()
            tvName.text = ch.name
            tvSat.text = ch.satName

            tvScramble.setTextColor(if (ch.isScrambled) Color.parseColor("#FACC15") else Color.parseColor("#334155"))
            tvFav.setTextColor(if (ch.isFav) Color.parseColor("#F43F5E") else Color.parseColor("#334155"))
            tvLock.setTextColor(if (ch.isLocked) Color.parseColor("#38BDF8") else Color.parseColor("#334155"))

            val isExpanded = (expandedChannelIndex == position)
            rowMain.setBackgroundColor(if (isExpanded) Color.parseColor("#0284C7") else Color.parseColor("#1E293B"))
            layoutActions.visibility = if (isExpanded) View.VISIBLE else View.GONE

            rowMain.setOnClickListener {
                switchAndPlayChannel(ch)
            }

            btnMore.setOnClickListener {
                expandedChannelIndex = if (expandedChannelIndex == position) -1 else position
                notifyDataSetChanged()
            }

            view.findViewById<Button>(R.id.btnActPlay).setOnClickListener {
                switchAndPlayChannel(ch)
            }

            view.findViewById<Button>(R.id.btnActFav).setOnClickListener {
                sendQuickStbCommand("{\"request\":\"1007\",\"ServiceID\":\"${ch.serviceId}\"}")
                Toast.makeText(this@MainActivity, "Favori updated: ${ch.name}", Toast.LENGTH_SHORT).show()
            }

            view.findViewById<Button>(R.id.btnActRename).setOnClickListener {
                showRenameChannelDialog(ch)
            }

            view.findViewById<Button>(R.id.btnActDelete).setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Effacer la chaîne")
                    .setMessage("Supprimer ${ch.name} ?")
                    .setPositiveButton("Oui") { _, _ ->
                        sendQuickStbCommand("{\"request\":\"1005\",\"ServiceID\":\"${ch.serviceId}\"}")
                        allChannels.remove(ch)
                        applyChannelFilter()
                    }
                    .setNegativeButton("Non", null)
                    .show()
            }

            view.findViewById<Button>(R.id.btnActLock).setOnClickListener {
                sendQuickStbCommand("{\"request\":\"1006\",\"ServiceID\":\"${ch.serviceId}\"}")
                Toast.makeText(this@MainActivity, "Verrouillage updated: ${ch.name}", Toast.LENGTH_SHORT).show()
            }

            return view
        }
    }

    private fun switchAndPlayChannel(ch: ChannelItem) {
        Toast.makeText(this, "Zapping: ${ch.name}", Toast.LENGTH_SHORT).show()
        executor.execute {
            sendQuickStbCommandSync("{\"request\":\"1002\",\"ServiceID\":\"${ch.serviceId}\"}")
            val streamUrl = "http://$currentIp:8080/stream?ServiceID=${ch.serviceId}"
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setDataAndType(Uri.parse(streamUrl), "video/*")
                    startActivity(Intent.createChooser(intent, "Play ${ch.name} with..."))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun showRenameChannelDialog(ch: ChannelItem) {
        val input = EditText(this)
        input.setText(ch.name)
        AlertDialog.Builder(this)
            .setTitle("Renommer la chaîne")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    sendQuickStbCommand("{\"request\":\"1004\",\"ServiceID\":\"${ch.serviceId}\",\"ServiceName\":\"$newName\"}")
                    val idx = allChannels.indexOf(ch)
                    if (idx != -1) {
                        allChannels[idx] = ch.copy(name = newName)
                        applyChannelFilter()
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ==================== 6. Remote & Satfinder ====================
    private fun setupRemoteButtons() {
        val keyMap = mapOf(
            R.id.btnPower to 10,
            R.id.btnMute to 11,
            R.id.btnMenu to 12,
            R.id.btnExit to 13,
            R.id.btnUp to 14,
            R.id.btnDown to 15,
            R.id.btnLeft to 16,
            R.id.btnRight to 17,
            R.id.btnOk to 18,
            R.id.btnVolUp to 19,
            R.id.btnVolDown to 20,
            R.id.btnChUp to 21,
            R.id.btnChDown to 22,
            R.id.btnInfo to 23,
            R.id.btnAudio to 24,
            R.id.btnFavRemote to 25
        )
        for ((viewId, keyCode) in keyMap) {
            findViewById<View>(viewId)?.setOnClickListener {
                sendQuickStbCommand("{\"request\":\"1003\",\"KeyCode\":\"$keyCode\"}")
            }
        }
    }

    private fun startSatfinderPolling() {
        executor.execute {
            while (satfinderRunning && isConnected) {
                synchronized(socketLock) {
                    var s: Socket? = null
                    try {
                        s = Socket()
                        s.connect(InetSocketAddress(currentIp, currentPort), 2000)
                        s.soTimeout = 1500
                        val out = s.getOutputStream()
                        val inp = s.getInputStream()
                        sendGmCommand(out, "{\"request\":\"1010\"}")
                        val raw = readMatchingResponse(inp, 1200) { it.contains("strength") || it.contains("quality") }
                        if (raw != null) {
                            val arr = extractJsonArray(raw)
                            val obj = arr?.optJSONObject(0) ?: extractFirstJsonObject(raw)
                            if (obj != null) {
                                val str = obj.optInt("strength", 0)
                                val qua = obj.optInt("quality", 0)
                                runOnUiThread {
                                    pbStrength.progress = str
                                    pbQuality.progress = qua
                                    tvStrengthPct.text = "$str%"
                                    tvQualityPct.text = "$qua%"
                                    tvSatSignalInfo.text = "Signal: Strength $str% | Quality $qua%"
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try {
                            s?.close()
                        } catch (_: Exception) {
                        }
                    }
                }
                try {
                    Thread.sleep(900)
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun sendQuickStbCommand(jsonCmd: String) {
        if (!isConnected) return
        executor.execute {
            sendQuickStbCommandSync(jsonCmd)
        }
    }

    private fun sendQuickStbCommandSync(jsonCmd: String) {
        synchronized(socketLock) {
            var s: Socket? = null
            try {
                s = Socket()
                s.connect(InetSocketAddress(currentIp, currentPort), 2500)
                sendGmCommand(s.getOutputStream(), jsonCmd)
            } catch (_: Exception) {
            } finally {
                try {
                    s?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        satfinderRunning = false
        executor.shutdownNow()
    }
}
