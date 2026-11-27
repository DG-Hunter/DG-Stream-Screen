package com.dg.playscreen

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.Locale

data class DvbChannel(
    var index: Int,
    val rawIdent: String,
    val provider: String,
    val serviceId: String,
    val satName: String,
    val freq: String,
    val pol: String,
    val symRate: String,
    val dvbSystem: String,
    val modulation: String,
    val fec: String,
    val isScrambled: Boolean,
    val isHd: Boolean,
    val vPid: String,
    val aPid: String,
    val isFav: Boolean = false,
    val isLocked: Boolean = false
)

class ChannelAdapter(
    private val items: MutableList<DvbChannel>,
    private val onPlayClick: (DvbChannel) -> Unit,
    private val onOptionsClick: (DvbChannel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvChNum: TextView = view.findViewById(R.id.tvChNum)
        val tvChIdent: TextView = view.findViewById(R.id.tvChIdent)
        val tvChIcons: TextView = view.findViewById(R.id.tvChIcons)
        val tvChMeta: TextView = view.findViewById(R.id.tvChMeta)
        val btnChOptions: Button = view.findViewById(R.id.btnChOptions)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ch = items[position]
        holder.tvChNum.text = String.format(Locale.US, "%04d", ch.index)
        holder.tvChIdent.text = ch.rawIdent

        val icons = buildString {
            if (ch.isScrambled) append("💲 ")
            if (ch.isFav) append("❤️ ")
            if (ch.isLocked) append("🔒 ")
        }.trim()
        holder.tvChIcons.text = if (icons.isNotEmpty()) icons else "FTA"

        val satPart = if (ch.satName.isNotEmpty()) "${ch.satName} • " else ""
        val freqPart = if (ch.freq.isNotEmpty()) "${ch.freq} ${ch.pol} ${ch.symRate}" else ch.dvbSystem
        holder.tvChMeta.text = "$satPart$freqPart"

        holder.itemView.setOnClickListener { onPlayClick(ch) }
        holder.btnChOptions.setOnClickListener { onOptionsClick(ch) }
    }

    override fun getItemCount(): Int = items.size
}

class MainActivity : AppCompatActivity() {

    private lateinit var etIpAddress: EditText
    private lateinit var tvStatus: TextView
    private lateinit var rvChannels: RecyclerView
    private lateinit var playerContainer: FrameLayout
    private lateinit var vlcVideoLayout: FrameLayout

    private lateinit var layoutInfoBar: LinearLayout
    private lateinit var tvInfoChannelName: TextView
    private lateinit var tvBadgeRes: TextView
    private lateinit var tvBadgeCrypt: TextView
    private lateinit var tvInfoDb: TextView
    private lateinit var tvInfoSatFreq: TextView
    private lateinit var tvInfoModulation: TextView
    private lateinit var tvInfoBitrate: TextView
    private lateinit var tvInfoPids: TextView
    private lateinit var pbStrength: ProgressBar
    private lateinit var tvStrengthVal: TextView
    private lateinit var pbQuality: ProgressBar
    private lateinit var tvQualityVal: TextView

    private lateinit var tvSatFinderFreq: TextView
    private lateinit var tvSatFinderDb: TextView
    private lateinit var pbSatSignal: ProgressBar
    private lateinit var pbSatQuality: ProgressBar
    private lateinit var tvSatFinderSatName: TextView
    private lateinit var tvSatStrengthVal: TextView
    private lateinit var tvSatQualityVal: TextView

    private var isBeepActive = false
    private var toneGenerator: ToneGenerator? = null

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isFullscreen = false

    private var tcpSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    private val allChannels = mutableListOf<DvbChannel>()
    private val filteredChannels = mutableListOf<DvbChannel>()
    private val discoveredSatellites = mutableListOf<String>()
    private var selectedSatFilter: String = "ALL"
    private var isRadioMode: Boolean = false

    private lateinit var adapter: ChannelAdapter
    private var currentIp: String = ""
    private var videoResInfo: String = "HEVC/H.264"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
        } catch (t: Throwable) {
            val tv = TextView(this).apply {
                text = "CRASH ERROR:\n${t.message}\n\n${t.stackTraceToString().take(800)}"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF991B1B.toInt())
                setPadding(32, 64, 32, 32)
                textSize = 13f
            }
            setContentView(tv)
            return
        }

        etIpAddress = findViewById(R.id.etIpAddress)
        tvStatus = findViewById(R.id.tvStatus)
        rvChannels = findViewById(R.id.rvChannels)
        playerContainer = findViewById(R.id.playerContainer)
        vlcVideoLayout = findViewById(R.id.vlcVideoLayout)

        layoutInfoBar = findViewById(R.id.layoutInfoBar)
        tvInfoChannelName = findViewById(R.id.tvInfoChannelName)
        tvBadgeRes = findViewById(R.id.tvBadgeRes)
        tvBadgeCrypt = findViewById(R.id.tvBadgeCrypt)
        tvInfoDb = findViewById(R.id.tvInfoDb)
        tvInfoSatFreq = findViewById(R.id.tvInfoSatFreq)
        tvInfoModulation = findViewById(R.id.tvInfoModulation)
        tvInfoBitrate = findViewById(R.id.tvInfoBitrate)
        tvInfoPids = findViewById(R.id.tvInfoPids)
        pbStrength = findViewById(R.id.pbStrength)
        tvStrengthVal = findViewById(R.id.tvStrengthVal)
        pbQuality = findViewById(R.id.pbQuality)
        tvQualityVal = findViewById(R.id.tvQualityVal)

        tvSatFinderFreq = findViewById(R.id.tvSatFinderFreq)
        tvSatFinderDb = findViewById(R.id.tvSatFinderDb)
        pbSatSignal = findViewById(R.id.pbSatSignal)
        pbSatQuality = findViewById(R.id.pbSatQuality)
        tvSatFinderSatName = findViewById(R.id.tvSatFinderSatName)
        tvSatStrengthVal = findViewById(R.id.tvSatStrengthVal)
        tvSatQualityVal = findViewById(R.id.tvSatQualityVal)

        try { toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (_: Throwable) {}
        initLibVlcPlayer()

        val prefs = getSharedPreferences("DGPlayPrefs", Context.MODE_PRIVATE)
        etIpAddress.setText(prefs.getString("last_ip", ""))

        adapter = ChannelAdapter(
            filteredChannels,
            onPlayClick = { ch -> showPlayerDialog(ch) },
            onOptionsClick = { ch -> showPlayerDialog(ch) }
        )
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = adapter

        findViewById<Button>(R.id.btnStopPlayer).setOnClickListener {
            try { mediaPlayer?.stop() } catch (_: Throwable) {}
            playerContainer.visibility = View.GONE
            if (isFullscreen) toggleFullscreen()
        }
        findViewById<Button>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }

        val layoutChannels = findViewById<LinearLayout>(R.id.layoutChannels)
        val layoutRemote = findViewById<ScrollView>(R.id.layoutRemote)
        val layoutSatFinder = findViewById<LinearLayout>(R.id.layoutSatFinder)
        val btnTabChannels = findViewById<Button>(R.id.btnTabChannels)
        val btnTabRemote = findViewById<Button>(R.id.btnTabRemote)
        val btnTabSatFinder = findViewById<Button>(R.id.btnTabSatFinder)

        fun selectTab(tab: Int) {
            layoutChannels.visibility = if (tab == 0) View.VISIBLE else View.GONE
            layoutRemote.visibility = if (tab == 1) View.VISIBLE else View.GONE
            layoutSatFinder.visibility = if (tab == 2) View.VISIBLE else View.GONE
            btnTabChannels.setBackgroundColor(if (tab == 0) 0xFF0284C7.toInt() else 0xFF1E293B.toInt())
            btnTabRemote.setBackgroundColor(if (tab == 1) 0xFF0284C7.toInt() else 0xFF1E293B.toInt())
            btnTabSatFinder.setBackgroundColor(if (tab == 2) 0xFF0284C7.toInt() else 0xFF1E293B.toInt())
            if (tab == 2) startTelemetryMonitor()
        }

        btnTabChannels.setOnClickListener { selectTab(0) }
        btnTabRemote.setOnClickListener { selectTab(1) }
        btnTabSatFinder.setOnClickListener { selectTab(2) }

        val btnToggleTvRadio = findViewById<Button>(R.id.btnToggleTvRadio)
        val btnSelectSatFilter = findViewById<Button>(R.id.btnSelectSatFilter)

        btnToggleTvRadio.setOnClickListener {
            isRadioMode = !isRadioMode
            btnToggleTvRadio.text = if (isRadioMode) "RADIO" else "TV"
            btnToggleTvRadio.setBackgroundColor(if (isRadioMode) 0xFFD97706.toInt() else 0xFF0284C7.toInt())
            val ip = etIpAddress.text.toString().trim()
            if (ip.isNotEmpty()) connectAndFetchChannels(ip)
        }

        btnSelectSatFilter.setOnClickListener {
            val options = mutableListOf("Toutes les chaînes (All)", "★ Favoris (Favorites)")
            options.addAll(discoveredSatellites)
            AlertDialog.Builder(this)
                .setTitle("Sélectionner un Satellite")
                .setItems(options.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> {
                            selectedSatFilter = "ALL"
                            btnSelectSatFilter.text = "Toutes les chaînes ▾"
                        }
                        1 -> {
                            selectedSatFilter = "FAV"
                            btnSelectSatFilter.text = "★ Favoris ▾"
                        }
                        else -> {
                            selectedSatFilter = options[which]
                            btnSelectSatFilter.text = "$selectedSatFilter ▾"
                        }
                    }
                    applyChannelFilters(findViewById<EditText>(R.id.etSearchChannel).text.toString())
                }
                .show()
        }

        val btnToggleBeep = findViewById<Button>(R.id.btnToggleBeep)
        btnToggleBeep.setOnClickListener {
            isBeepActive = !isBeepActive
            btnToggleBeep.text = if (isBeepActive) "Disable Audio Beep" else "Enable Audio Beep"
            btnToggleBeep.setBackgroundColor(if (isBeepActive) 0xFFDC2626.toInt() else 0xFF0284C7.toInt())
        }

        findViewById<EditText>(R.id.etSearchChannel).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyChannelFilters(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        findViewById<Button>(R.id.btnScan).setOnClickListener { scanForReceivers() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val ip = etIpAddress.text.toString().trim()
            if (ip.isNotEmpty()) {
                prefs.edit().putString("last_ip", ip).apply()
                connectAndFetchChannels(ip)
            }
        }

        bindKey(R.id.btnPower, 1); bindKey(R.id.btnMute, 2); bindKey(R.id.btnTvRadio, 31); bindKey(R.id.btnRecall, 32)
        bindKey(R.id.btnSat, 37); bindKey(R.id.btnFav, 30); bindKey(R.id.btnEpg, 29); bindKey(R.id.btnFind, 40)
        bindKey(R.id.btnNum0, 16); bindKey(R.id.btnNum1, 7); bindKey(R.id.btnNum2, 8); bindKey(R.id.btnNum3, 9)
        bindKey(R.id.btnNum4, 10); bindKey(R.id.btnNum5, 11); bindKey(R.id.btnNum6, 12); bindKey(R.id.btnNum7, 13)
        bindKey(R.id.btnNum8, 14); bindKey(R.id.btnNum9, 15); bindKey(R.id.btnAudio, 38); bindKey(R.id.btnSub, 39)
        bindKey(R.id.btnMenu, 26); bindKey(R.id.btnExit, 27); bindKey(R.id.btnInfo, 28); bindKey(R.id.btnPage, 25)
        bindKey(R.id.btnUp, 21); bindKey(R.id.btnDown, 22); bindKey(R.id.btnLeft, 23); bindKey(R.id.btnRight, 24); bindKey(R.id.btnOk, 25)
        bindKey(R.id.btnVolUp, 17); bindKey(R.id.btnVolDown, 18); bindKey(R.id.btnChUp, 19); bindKey(R.id.btnChDown, 20)
        bindKey(R.id.btnRed, 33); bindKey(R.id.btnGreen, 34); bindKey(R.id.btnYellow, 35); bindKey(R.id.btnBlue, 36)
        bindKey(R.id.btnRew, 41); bindKey(R.id.btnPlayPause, 42); bindKey(R.id.btnStopMedia, 43); bindKey(R.id.btnFwd, 44); bindKey(R.id.btnRec, 45)
    }

    private fun applyChannelFilters(query: String) {
        val q = query.lowercase().trim()
        filteredChannels.clear()
        filteredChannels.addAll(
            allChannels.filter { ch ->
                val matchesSat = when (selectedSatFilter) {
                    "ALL" -> true
                    "FAV" -> ch.isFav
                    else -> ch.satName.equals(selectedSatFilter, ignoreCase = true)
                }
                val matchesText = q.isEmpty() ||
                        ch.rawIdent.lowercase().contains(q) ||
                        ch.satName.lowercase().contains(q) ||
                        ch.freq.contains(q)
                matchesSat && matchesText
            }
        )
        adapter.notifyDataSetChanged()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val header = findViewById<LinearLayout>(R.id.headerBar)
        val connect = findViewById<LinearLayout>(R.id.connectBar)
        val tabs = findViewById<LinearLayout>(R.id.tabBar)
        val params = playerContainer.layoutParams as LinearLayout.LayoutParams
        if (isFullscreen) {
            header.visibility = View.GONE; connect.visibility = View.GONE
            tvStatus.visibility = View.GONE; tabs.visibility = View.GONE
            params.height = 0; params.weight = 1f
        } else {
            header.visibility = View.VISIBLE; connect.visibility = View.VISIBLE
            tvStatus.visibility = View.VISIBLE; tabs.visibility = View.VISIBLE
            params.height = (195 * resources.displayMetrics.density).toInt(); params.weight = 0f
        }
        playerContainer.layoutParams = params
    }

    private fun bindKey(buttonId: Int, keyCode: Int) {
        findViewById<Button>(buttonId).setOnClickListener {
            vibrateClick()
            scope.launch { sendRawXml("<Command request=\"1040\"><key>$keyCode</key></Command>") }
        }
    }

    private fun vibrateClick() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            } else { @Suppress("DEPRECATION") v.vibrate(25) }
        } catch (_: Throwable) {}
    }

    private fun initLibVlcPlayer() {
        try {
            val vlcView = VLCVideoLayout(this)
            vlcVideoLayout.addView(vlcView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            val options = arrayListOf("--network-caching=1200", "--clock-jitter=0", "--clock-synchro=0")
            libVlc = LibVLC(this, options)
            mediaPlayer = MediaPlayer(libVlc)
            mediaPlayer?.attachViews(vlcView, null, false, false)
            mediaPlayer?.setEventListener { event ->
                if (event.type == MediaPlayer.Event.Vout) {
                    val track = mediaPlayer?.currentVideoTrack
                    if (track != null && track.width > 0) {
                        val res = if (track.height >= 2160) "4K UHD" else if (track.height >= 1080) "1080p" else if (track.height >= 720) "720p" else "${track.height}i"
                        videoResInfo = "${track.width}x${track.height} ($res)"
                        runOnUiThread { tvBadgeRes.text = if (track.height >= 2160) "4K" else if (track.height >= 720) "HD" else "SD" }
                    }
                }
            }
        } catch (_: Throwable) {
            libVlc = null
            mediaPlayer = null
        }
    }

    private fun copyText(text: String, msg: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DGPlay", text))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun scanForReceivers() {
        updateStatus("Scanning Wi-Fi & Hotspot for STB devices...")
        scope.launch {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val lock = wifiManager?.createMulticastLock("dg_lock")?.apply { setReferenceCounted(true); acquire() }
            try {
                val subnets = mutableSetOf<String>()
                java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                    if (iface.isUp && !iface.isLoopback) {
                        iface.inetAddresses?.toList()?.filterIsInstance<java.net.Inet4Address>()?.forEach { addr ->
                            val ip = addr.hostAddress ?: ""
                            if (ip.contains(".") && !addr.isLoopbackAddress) {
                                subnets.add(ip.substringBeforeLast(".") + ".")
                            }
                        }
                    }
                }
                if (subnets.isEmpty()) subnets.addAll(listOf("192.168.1.", "192.168.43."))

                val foundDevices = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

                try {
                    val udp = DatagramSocket().apply { broadcast = true; soTimeout = 1000 }
                    val msg = "smart_client_search".toByteArray()
                    subnets.forEach { sub ->
                        udp.send(DatagramPacket(msg, msg.size, InetAddress.getByName("${sub}255"), 25860))
                    }
                    val pkt = DatagramPacket(ByteArray(1024), 1024)
                    udp.receive(pkt)
                    val udpIp = pkt.address.hostAddress ?: ""
                    if (udpIp.isNotEmpty()) {
                        foundDevices.add(Pair("ALi Receiver ($udpIp:20000)", "$udpIp:20000"))
                    }
                    udp.close()
                } catch (_: Exception) {}

                if (foundDevices.isEmpty()) {
                    for (sub in subnets) {
                        (1..254).map { i ->
                            async(Dispatchers.IO) {
                                val testIp = "$sub$i"
                                for (port in listOf(20000, 8000, 8888)) {
                                    try {
                                        Socket().use { s ->
                                            s.connect(InetSocketAddress(testIp, port), 220)
                                            s.soTimeout = 1200
                                            var stbName = if (port == 20000) "ALi STB Receiver" else "GX STB Receiver"
                                            if (port == 20000) {
                                                s.getOutputStream().write(wrapGmScreenPacket("{\"request\":\"15\"}"))
                                                s.getOutputStream().flush()
                                                val reply = readGmScreenResponse(s.getInputStream())
                                                val prod = Regex("\"ProductName\"\\s*:\\s*\"([^\"]+)\"").find(reply)?.groupValues?.get(1)
                                                if (!prod.isNullOrEmpty()) stbName = prod
                                            }
                                            foundDevices.add(Pair("$stbName ($testIp:$port)", "$testIp:$port"))
                                        }
                                        break
                                    } catch (_: Exception) {}
                                }
                            }
                        }.awaitAll()
                    }
                }

                withContext(Dispatchers.Main) {
                    if (foundDevices.isNotEmpty()) {
                        updateStatus("Found ${foundDevices.size} STB device(s)")
                        val labels = foundDevices.map { it.first }.toTypedArray()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Sélectionner un appareil STB")
                            .setItems(labels) { _, which ->
                                val chosenIp = foundDevices[which].second
                                etIpAddress.setText(chosenIp)
                                getSharedPreferences("DGPlayPrefs", Context.MODE_PRIVATE).edit().putString("last_ip", chosenIp).apply()
                                connectAndFetchChannels(chosenIp)
                            }
                            .show()
                    } else {
                        updateStatus("No ALi/GX receiver found on ${subnets.joinToString()}")
                    }
                }
            } finally {
                if (lock?.isHeld == true) lock.release()
            }
        }
    }

    private fun wrapGmScreenPacket(payloadStr: String): ByteArray {
        val payloadBytes = payloadStr.toByteArray(Charsets.UTF_8)
        val header = String.format(Locale.US, "Start%07dEnd", payloadBytes.size).toByteArray(Charsets.US_ASCII)
        return header + payloadBytes
    }

    private fun drainSocketBuffer(inStr: InputStream) {
        try {
            val dump = ByteArray(8192)
            while (inStr.available() > 0) {
                inStr.read(dump)
                Thread.sleep(30)
            }
        } catch (_: Exception) {}
    }

    private fun readGmScreenResponse(inStream: InputStream): String {
        val allBytes = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var expectedPayloadSize = -1
        var headerEndOffset = -1
        var idleAttempts = 0

        while (idleAttempts < 40) {
            try {
                if (inStream.available() > 0) {
                    val r = inStream.read(buf)
                    if (r <= 0) break
                    allBytes.write(buf, 0, r)
                    idleAttempts = 0

                    val currentData = allBytes.toByteArray()
                    if (expectedPayloadSize == -1) {
                        val strCheck = String(currentData, 0, minOf(currentData.size, 128), Charsets.ISO_8859_1)
                        val match = Regex("Start(\\d{7})End").find(strCheck)
                        if (match != null) {
                            expectedPayloadSize = match.groupValues[1].toIntOrNull() ?: -1
                            headerEndOffset = match.range.last + 1
                        }
                    }

                    if (expectedPayloadSize > 0 && headerEndOffset > 0) {
                        if (currentData.size >= headerEndOffset + expectedPayloadSize) {
                            break
                        }
                    }
                } else {
                    if (expectedPayloadSize == -1 && allBytes.size() > 32) {
                        val strCheck = String(allBytes.toByteArray(), Charsets.ISO_8859_1)
                        if (!strCheck.contains("Start")) break
                    }
                    Thread.sleep(100)
                    idleAttempts++
                }
            } catch (_: Exception) {
                break
            }
        }

        val raw = allBytes.toByteArray()
        if (raw.isEmpty()) return ""

        val searchStart = if (headerEndOffset > 0) headerEndOffset else 0

        for (i in searchStart until raw.size - 1) {
            if (raw[i] == 0x78.toByte() && (
                        raw[i + 1] == 0x9C.toByte() ||
                        raw[i + 1] == 0xDA.toByte() ||
                        raw[i + 1] == 0x01.toByte() ||
                        raw[i + 1] == 0x5E.toByte()
                        )) {
                try {
                    val inflater = java.util.zip.Inflater()
                    inflater.setInput(raw, i, raw.size - i)
                    val out = java.io.ByteArrayOutputStream()
                    val tmp = ByteArray(8192)
                    while (!inflater.finished()) {
                        val count = inflater.inflate(tmp)
                        if (count == 0) {
                            if (inflater.needsInput()) break
                        }
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

    private fun connectAndFetchChannels(rawIp: String) {
        val parts = rawIp.trim().split(":")
        val ip = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 20000
        currentIp = ip
        updateStatus("Connecting to $ip:$port...")

        scope.launch {
            try {
                tcpSocket?.close()
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 5000)
                s.soTimeout = 5000
                tcpSocket = s
                outputStream = s.getOutputStream()
                inputStream = s.getInputStream()

                val out = outputStream!!
                val inStr = inputStream!!

                out.write(wrapGmScreenPacket("{\"request\":\"998\",\"ClientType\":\"0\",\"MobileType\":\"Android\",\"AppVersion\":\"1.0\"}"))
                out.flush()
                delay(300)
                drainSocketBuffer(inStr)

                out.write(wrapGmScreenPacket("{\"request\":\"15\"}"))
                out.flush()
                val handshakeReply = readGmScreenResponse(inStr)
                drainSocketBuffer(inStr)
                val deviceName = Regex("\"ProductName\"\\s*:\\s*\"([^\"]+)\"").find(handshakeReply)?.groupValues?.get(1) ?: "ALi STB"
                updateStatus("Connected ($deviceName) • Fetching Satellites...")

                val satMap = mutableMapOf<String, String>()
                out.write(wrapGmScreenPacket("{\"request\":\"22\"}"))
                out.flush()
                delay(400)
                val satReply = readGmScreenResponse(inStr)
                drainSocketBuffer(inStr)

                val satRegex = Regex("\\{[^{}]*\"SatName\"[^{}]*\\}")
                for (m in satRegex.findAll(satReply)) {
                    val sObj = m.value
                    val sIdx = Regex("\"SatIndex\"\\s*:\\s*\"?([^\",}]+)\"?").find(sObj)?.groupValues?.get(1)?.trim() ?: ""
                    val sName = Regex("\"SatName\"\\s*:\\s*\"([^\"]+)\"").find(sObj)?.groupValues?.get(1)?.trim() ?: ""
                    if (sIdx.isNotEmpty() && sName.isNotEmpty()) {
                        satMap[sIdx] = sName
                    }
                }
                discoveredSatellites.clear()
                discoveredSatellites.addAll(satMap.values.distinct().sorted())
                updateStatus("Found ${satMap.size} Satellites • Loading Channels...")

                val fetchedList = mutableListOf<DvbChannel>()
                var fromIndex = 0
                val step = 100
                var hasMore = true
                var lastRawDebug = ""
                val tvStateVal = if (isRadioMode) 1 else 0

                while (hasMore && fromIndex < 6000) {
                    drainSocketBuffer(inStr)
                    val cmd = "{\"request\":\"0\",\"TvState\":$tvStateVal,\"FromIndex\":$fromIndex,\"ToIndex\":${fromIndex + step - 1}}"
                    out.write(wrapGmScreenPacket(cmd))
                    out.flush()
                    delay(350)

                    val chReply = readGmScreenResponse(inStr)
                    if (chReply.isEmpty()) break
                    lastRawDebug = chReply

                    val batch = parseGmScreenChannels(chReply, fetchedList.size, satMap)
                    if (batch.isEmpty()) {
                        hasMore = false
                    } else {
                        fetchedList.addAll(batch)
                        updateStatus("Loaded ${fetchedList.size} Channels ($deviceName)...")
                        if (batch.size < step) {
                            hasMore = false
                        } else {
                            fromIndex += step
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    allChannels.clear()
                    allChannels.addAll(fetchedList)
                    applyChannelFilters(findViewById<EditText>(R.id.etSearchChannel).text.toString())

                    if (fetchedList.isNotEmpty()) {
                        updateStatus("Ready: ${fetchedList.size} Channels • ${satMap.size} Satellites ($deviceName)")
                    } else {
                        updateStatus("Connected ($deviceName) • ${satMap.size} Sats • 0 Ch. Raw: ${lastRawDebug.take(140)}")
                    }
                }
            } catch (e: Exception) {
                updateStatus("Connection Error: ${e.message}")
            }
        }
    }

    private fun parseGmScreenChannels(data: String, startOffset: Int, satMap: Map<String, String> = emptyMap()): List<DvbChannel> {
    val list = mutableListOf<DvbChannel>()
    
    // Try XML extraction first (Ali/GX receivers reply with XML inside Zlib)
    try {
        val nameRegex = Regex("<(?:ServiceName|ProgramName|ChannelName|Name)>(.*?)</(?:ServiceName|ProgramName|ChannelName|Name)>", RegexOption.IGNORE_CASE)
        val idRegex = Regex("<(?:ServiceID|ProgramId|ChannelID)>(.*?)</(?:ServiceID|ProgramId|ChannelID)>", RegexOption.IGNORE_CASE)
        val satRegex = Regex("<(?:SatIndex|SatelliteIndex|SatID)>(.*?)</(?:SatIndex|SatelliteIndex|SatID)>", RegexOption.IGNORE_CASE)
        val freqRegex = Regex("<(?:Frequency|Freq|TPFreq)>(.*?)</(?:Frequency|Freq|TPFreq)>", RegexOption.IGNORE_CASE)
        val polRegex = Regex("<(?:Polarization|Pol|Polar)>(.*?)</(?:Polarization|Pol|Polar)>", RegexOption.IGNORE_CASE)
        val symRegex = Regex("<(?:SymbolRate|Sym|SR)>(.*?)</(?:SymbolRate|Sym|SR)>", RegexOption.IGNORE_CASE)
        val vPidRegex = Regex("<(?:VideoPID|VPid)>(.*?)</(?:VideoPID|VPid)>", RegexOption.IGNORE_CASE)
        val aPidRegex = Regex("<(?:AudioPID|APid)>(.*?)</(?:AudioPID|APid)>", RegexOption.IGNORE_CASE)
        val cryptRegex = Regex("<(?:Scramble|Crypt|IsCrypt)>(.*?)</(?:Scramble|Crypt|IsCrypt)>", RegexOption.IGNORE_CASE)
        
        val names = nameRegex.findAll(data).map { it.groupValues[1].trim() }.toList()
        val ids = idRegex.findAll(data).map { it.groupValues[1].trim() }.toList()
        val sats = satRegex.findAll(data).map { it.groupValues[1].trim() }.toList()
        val freqs = freqRegex.findAll(data).map { it.groupValues[1].trim() }.toList()
        val pols = polRegex.findAll(data).map { it.groupValues[1].trim() }.toList()
        val syms = symRegex.findAll(data).map { it.groupValues[1].trim() }.toList()
        val vPids = vPidRegex.findAll(data).map { it.groupValues[1].trim() }.toList()
        val aPids = aPidRegex.findAll(data).map { it.groupValues[1].trim() }.toList()
        val crypts = cryptRegex.findAll(data).map { it.groupValues[1].trim() }.toList()

        if (names.isNotEmpty()) {
            var idx = startOffset + 1
            for (i in names.indices) {
                val name = names[i]
                if (name.isEmpty()) continue
                
                val sid = if (i < ids.size) ids[i] else "$idx"
                val satIdx = if (i < sats.size) sats[i] else ""
                val satName = satMap[satIdx] ?: if (satIdx.isNotEmpty()) "Sat #$satIdx" else "Satellite"
                val freq = if (i < freqs.size) freqs[i] else ""
                val pol = if (i < pols.size) pols[i] else ""
                val sym = if (i < syms.size) syms[i] else ""
                val vPid = if (i < vPids.size) vPids[i] else "0"
                val aPid = if (i < aPids.size) aPids[i] else "0"
                val crypt = if (i < crypts.size) crypts[i] == "1" else false
                val isHd = name.contains("HD", ignoreCase = true)

                list.add(
                    DvbChannel(idx++, name, satName, sid, satName, freq, pol, sym, "DVB-S2", "8PSK", "3/4", crypt, isHd, vPid, aPid)
                )
            }
            return list // If we found XML channels, return them
        }
    } catch (_: Exception) {}

    // Fallback to JSON if XML fails
    try {
        val objRegex = Regex("\\{[^{}]*\"(ServiceName|ProgramName|ChannelName|Name)\"[^{}]*\\}")
        var idx = startOffset + 1
        for (m in objRegex.findAll(data)) {
            val obj = m.value
            fun getJsonVal(vararg keys: String): String {
                for (k in keys) {
                    val v = Regex("\"$k\"\\s*:\\s*\"?([^\",}]+)\"?").find(obj)?.groupValues?.get(1)?.trim()
                    if (!v.isNullOrEmpty()) return v
                }
                return ""
            }
            val name = getJsonVal("ServiceName", "ProgramName", "ChannelName", "Name")
            if (name.isEmpty()) continue
            
            val sid = getJsonVal("ServiceID", "ProgramId", "ServiceIndex", "ProgramIndex").ifEmpty { "$idx" }
            val satIdx = getJsonVal("SatIndex", "SatId")
            val sat = getJsonVal("SatName").ifEmpty { satMap[satIdx] ?: "Satellite" }
            val freq = getJsonVal("Frequency", "Freq", "TPFreq")
            val pol = getJsonVal("Polarization", "Pol", "Polar")
            val sym = getJsonVal("SymbolRate", "Sym", "SR")
            val vPid = getJsonVal("VideoPID", "VPid").ifEmpty { "0" }
            val aPid = getJsonVal("AudioPID", "APid").ifEmpty { "0" }
            val crypt = getJsonVal("Scramble", "Crypt", "IsCrypt") == "1"
            val isHd = getJsonVal("HD", "IsHD") == "1" || name.contains("HD", ignoreCase = true)

            list.add(DvbChannel(idx++, name, sat, sid, sat, freq, pol, sym, "DVB-S2", "8PSK", "3/4", crypt, isHd, vPid, aPid))
        }
    } catch (_: Exception) {}
    
    return list
    }

    private fun showPlayerDialog(ch: DvbChannel) {
        val polLower = if (ch.pol.lowercase().startsWith("v")) "v" else "h"
        val streamUrl = if (ch.freq.isNotEmpty() && ch.vPid != "0") {
            "rtsp://$currentIp:554/?alisatid=1&freq=${ch.freq}&pol=$polLower&msys=dvbs2&mtype=8psk&ro=0.35&plts=on&sr=${ch.symRate.ifEmpty { "27500" }}&fec=1&vpid=${ch.vPid}&apid=${ch.aPid}&pids=${ch.vPid},${ch.aPid}"
        } else {
            "http://$currentIp:8000/stream?sid=${ch.serviceId}"
        }

        val options = arrayOf(
            "▶ Play (Play Inside App)",
            "📱 Open in VLC App",
            "📱 Open in MX Player",
            "✏️ Rename (Rename on STB)",
            "🗑️ Delete (Delete from STB)",
            "🔒 Verrouiller / Déverrouiller (Lock)",
            "📋 Copy Stream URL"
        )
        AlertDialog.Builder(this)
            .setTitle("${ch.index}. ${ch.rawIdent} (${ch.satName})")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playInsideApp(ch, streamUrl)
                    1 -> openExternal(streamUrl, "org.videolan.vlc", ch.rawIdent)
                    2 -> openExternal(streamUrl, "com.mxtech.videoplayer.ad", ch.rawIdent)
                    3 -> showRenameChannelDialog(ch)
                    4 -> {
                        scope.launch {
                            sendRawXml("{\"request\":\"1005\",\"TvState\":\"${if (isRadioMode) 1 else 0}\",\"ProgramId\":\"${ch.serviceId}\"}")
                        }
                        allChannels.remove(ch)
                        filteredChannels.remove(ch)
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this, "Deleted on STB: ${ch.rawIdent}", Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        scope.launch {
                            sendRawXml("{\"request\":\"1006\",\"TvState\":\"${if (isRadioMode) 1 else 0}\",\"ProgramId\":\"${ch.serviceId}\"}")
                        }
                        Toast.makeText(this, "Toggled Lock on STB: ${ch.rawIdent}", Toast.LENGTH_SHORT).show()
                    }
                    6 -> copyText(streamUrl, "Stream URL Copied!")
                }
            }
            .show()
    }

    private fun showRenameChannelDialog(ch: DvbChannel) {
        val input = EditText(this).apply { setText(ch.rawIdent) }
        AlertDialog.Builder(this)
            .setTitle("Renommer la chaîne")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    scope.launch {
                        sendRawXml("{\"request\":\"1004\",\"TvState\":\"${if (isRadioMode) 1 else 0}\",\"ProgramId\":\"${ch.serviceId}\",\"ProgramName\":\"$newName\"}")
                    }
                    val idx = allChannels.indexOf(ch)
                    if (idx >= 0) {
                        allChannels[idx] = ch.copy(rawIdent = newName)
                        applyChannelFilters(findViewById<EditText>(R.id.etSearchChannel).text.toString())
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun playInsideApp(ch: DvbChannel, url: String) {
        playerContainer.visibility = View.VISIBLE
        layoutInfoBar.visibility = View.VISIBLE
        tvInfoChannelName.text = ch.rawIdent
        tvBadgeCrypt.text = if (ch.isScrambled) "CRYPT" else "FTA"
        tvInfoSatFreq.text = "${ch.satName} • ${ch.freq} ${ch.pol}"
        tvInfoPids.text = "V:${ch.vPid} A:${ch.aPid}"

        scope.launch {
            sendRawXml("{\"request\":\"1009\",\"TvState\":\"${if (isRadioMode) 1 else 0}\",\"ProgramId\":\"${ch.serviceId}\"}")
        }

        try {
            mediaPlayer?.stop()
            val media = Media(libVlc, Uri.parse(url))
            mediaPlayer?.media = media
            media.release()
            mediaPlayer?.play()
        } catch (e: Exception) {
            updateStatus("Player Error: ${e.message}")
        }
    }

    private fun startTelemetryMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var lastRxBytes = TrafficStats.getTotalRxBytes()
            var lastTimeMs = System.currentTimeMillis()
            while (isActive) {
                try {
                    val nowRx = TrafficStats.getTotalRxBytes()
                    val nowMs = System.currentTimeMillis()

                    val diffSec = (nowMs - lastTimeMs) / 1000.0
                    val mbps = if (diffSec > 0 && nowRx >= lastRxBytes) ((nowRx - lastRxBytes) * 8.0) / (diffSec * 1_000_000.0) else 0.0
                    lastRxBytes = nowRx; lastTimeMs = nowMs

                    sendRawXml("{\"request\":\"1001\"}")
                    delay(250)
                    var activeSat = ""
                    var activeFreqInfo = ""
                    val inStr = inputStream
                    if (inStr != null && inStr.available() > 0) {
                        val infoReply = readGmScreenResponse(inStr)
                        val chName = Regex("\"(ServiceName|ProgramName)\"\\s*:\\s*\"([^\"]+)\"").find(infoReply)?.groupValues?.get(2) ?: ""
                        val satName = Regex("\"SatName\"\\s*:\\s*\"([^\"]+)\"").find(infoReply)?.groupValues?.get(1) ?: ""
                        val freq = Regex("\"(Frequency|Freq)\"\\s*:\\s*\"?(\\d+)\"?").find(infoReply)?.groupValues?.get(2) ?: ""
                        val pol = Regex("\"(Polarization|Pol)\"\\s*:\\s*\"?([^\"]+)\"?").find(infoReply)?.groupValues?.get(2) ?: ""
                        val sym = Regex("\"(SymbolRate|Sym)\"\\s*:\\s*\"?(\\d+)\"?").find(infoReply)?.groupValues?.get(2) ?: ""

                        if (satName.isNotEmpty() || chName.isNotEmpty()) {
                            activeSat = "Satellite: ${satName.ifEmpty { "Active Tuner" }} (${chName.ifEmpty { "Live" }})"
                        }
                        if (freq.isNotEmpty()) {
                            activeFreqInfo = "TP: $freq MHz • $pol • SR $sym"
                        }
                    }

                    sendRawXml("{\"request\":\"403\"}")
                    delay(300)

                    var strength = 0
                    var quality = 0
                    if (inStr != null && inStr.available() > 0) {
                        val reply = readGmScreenResponse(inStr)
                        strength = Regex("\"Strength\"\\s*:\\s*\"?(\\d+)\"?", RegexOption.IGNORE_CASE).find(reply)?.groupValues?.get(1)?.toIntOrNull()
                            ?: extractTagInt(reply, "SignalStrength") ?: 0
                        quality = Regex("\"Quality\"\\s*:\\s*\"?(\\d+)\"?", RegexOption.IGNORE_CASE).find(reply)?.groupValues?.get(1)?.toIntOrNull()
                            ?: extractTagInt(reply, "SignalQuality") ?: 0
                    }

                    val dbStr = String.format(Locale.US, "%.1f dB", quality * 0.175)
                    val bitrateStr = String.format(Locale.US, "Bitrate: %.2f Mbps • %s", mbps, videoResInfo)

                    if (isBeepActive && quality > 30) {
                        try { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80) } catch (_: Throwable) {}
                    }

                    withContext(Dispatchers.Main) {
                        pbStrength.progress = strength; tvStrengthVal.text = "$strength%"
                        pbQuality.progress = quality; tvQualityVal.text = "$quality%"
                        tvInfoDb.text = dbStr; tvInfoBitrate.text = bitrateStr

                        if (activeSat.isNotEmpty()) tvSatFinderSatName.text = activeSat
                        if (activeFreqInfo.isNotEmpty()) tvSatFinderFreq.text = activeFreqInfo
                        tvSatFinderDb.text = dbStr

                        pbSatSignal.progress = strength
                        pbSatSignal.progressTintList = ColorStateList.valueOf(0xFFEF4444.toInt())
                        tvSatStrengthVal.text = "$strength%"

                        pbSatQuality.progress = quality
                        pbSatQuality.progressTintList = ColorStateList.valueOf(0xFF22C55E.toInt())
                        tvSatQualityVal.text = "$quality%"

                        val dbColor = when {
                            quality >= 65 -> 0xFF22C55E.toInt()
                            quality >= 40 -> 0xFFEAB308.toInt()
                            else -> 0xFFEF4444.toInt()
                        }
                        tvSatFinderDb.setTextColor(dbColor)
                        tvInfoDb.setTextColor(dbColor)
                    }
                } catch (_: Throwable) {}
                delay(1000)
            }
        }
    }

    private fun extractTagInt(xml: String, tag: String): Int? =
        Regex("<$tag>(\\d+)</$tag>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.toIntOrNull()

    private fun openExternal(url: String, pkg: String, title: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), "video/*")
                setPackage(pkg)
                putExtra("title", title)
            }
            startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), "video/*")
                putExtra("title", title)
            }
            startActivity(Intent.createChooser(fallback, "Select Video Player"))
        }
    }

    private fun sendRawXml(cmd: String) {
        try {
            val out = outputStream ?: return
            val finalCmd = if (cmd.contains("request=\"1040\"")) {
                val key = Regex("<key>(\\d+)</key>").find(cmd)?.groupValues?.get(1) ?: "1"
                "{\"request\":\"1040\",\"array\":[{\"KeyValue\":\"$key\"}]}"
            } else {
                cmd
            }
            out.write(wrapGmScreenPacket(finalCmd))
            out.flush()
        } catch (_: Throwable) {}
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { tvStatus.text = msg }
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        scope.cancel()
        try { tcpSocket?.close() } catch (_: Throwable) {}
        try { mediaPlayer?.release(); libVlc?.release() } catch (_: Throwable) {}
        try { toneGenerator?.release() } catch (_: Throwable) {}
    }
}
