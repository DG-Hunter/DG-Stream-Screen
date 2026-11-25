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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    val aPid: String
)

class ChannelAdapter(
    private val items: MutableList<DvbChannel>,
    private val onPlayClick: (DvbChannel) -> Unit,
    private val onCopyClick: (DvbChannel) -> Unit,
    private val onMoveUpClick: (Int) -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvChNum: TextView = view.findViewById(R.id.tvChNum)
        val tvChIdent: TextView = view.findViewById(R.id.tvChIdent)
        val tvChMeta: TextView = view.findViewById(R.id.tvChMeta)
        val btnCopyIdent: Button = view.findViewById(R.id.btnCopyIdent)
        val btnMoveUp: Button = view.findViewById(R.id.btnMoveUp)
        val btnDeleteCh: Button = view.findViewById(R.id.btnDeleteCh)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ch = items[position]
        holder.tvChNum.text = String.format(Locale.US, "%04d", ch.index)
        holder.tvChIdent.text = ch.rawIdent
        val prov = if (ch.provider.isNotEmpty()) "${ch.provider} • " else ""
        val freq = if (ch.freq.isNotEmpty()) "${ch.freq} ${ch.pol} ${ch.symRate} • " else ""
        holder.tvChMeta.text = "$prov$freq${ch.dvbSystem} ${ch.modulation}"

        holder.itemView.setOnClickListener { onPlayClick(ch) }
        holder.btnCopyIdent.setOnClickListener { onCopyClick(ch) }
        holder.btnMoveUp.setOnClickListener { onMoveUpClick(holder.bindingAdapterPosition) }
        holder.btnDeleteCh.setOnClickListener { onDeleteClick(holder.bindingAdapterPosition) }
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
    private lateinit var adapter: ChannelAdapter
    private var currentIp: String = ""
    private var videoResInfo: String = "HEVC/H.264"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
                try {
            setContentView(R.layout.activity_main)
        } catch (t: Throwable) {
            val tv = TextView(this).apply {
                text = "CRASH ERROR:\n${t.message}\n\n${t.cause?.message}\n\n${t.stackTraceToString().take(800)}"
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

        try { toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (_: Throwable) {}
        initLibVlcPlayer()

        val prefs = getSharedPreferences("DGPlayPrefs", Context.MODE_PRIVATE)
        etIpAddress.setText(prefs.getString("last_ip", ""))

        adapter = ChannelAdapter(
            filteredChannels,
            onPlayClick = { ch -> showPlayerDialog(ch) },
            onCopyClick = { ch -> copyText(ch.rawIdent, "Copied: ${ch.rawIdent}") },
            onMoveUpClick = { pos -> moveChannelUp(pos) },
            onDeleteClick = { pos -> deleteChannel(pos) }
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

        val btnToggleBeep = findViewById<Button>(R.id.btnToggleBeep)
        btnToggleBeep.setOnClickListener {
            isBeepActive = !isBeepActive
            btnToggleBeep.text = if (isBeepActive) "Disable Audio Beep" else "Enable Audio Beep"
            btnToggleBeep.setBackgroundColor(if (isBeepActive) 0xFFDC2626.toInt() else 0xFF0284C7.toInt())
        }

        findViewById<EditText>(R.id.etSearchChannel).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().lowercase()
                filteredChannels.clear()
                filteredChannels.addAll(
                    allChannels.filter {
                        it.rawIdent.lowercase().contains(q) ||
                        it.provider.lowercase().contains(q) ||
                        it.freq.contains(q)
                    }
                )
                adapter.notifyDataSetChanged()
            }
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

        bindKey(R.id.btnPower, 10); bindKey(R.id.btnMute, 11)
        bindKey(R.id.btnUp, 19); bindKey(R.id.btnDown, 20)
        bindKey(R.id.btnLeft, 21); bindKey(R.id.btnRight, 22)
        bindKey(R.id.btnOk, 23); bindKey(R.id.btnMenu, 24)
        bindKey(R.id.btnInfo, 26); bindKey(R.id.btnExit, 25)
        bindKey(R.id.btnVolUp, 27); bindKey(R.id.btnVolDown, 28)
        bindKey(R.id.btnChUp, 29); bindKey(R.id.btnChDown, 30)
        bindKey(R.id.btnRed, 31); bindKey(R.id.btnGreen, 32)
        bindKey(R.id.btnYellow, 33); bindKey(R.id.btnBlue, 34)
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

    private fun moveChannelUp(pos: Int) {
        if (pos > 0 && pos < filteredChannels.size) {
            Collections.swap(filteredChannels, pos, pos - 1)
            filteredChannels[pos].index = pos + 1
            filteredChannels[pos - 1].index = pos
            adapter.notifyItemMoved(pos, pos - 1)
            adapter.notifyItemRangeChanged(pos - 1, 2)
            vibrateClick()
        }
    }

    private fun deleteChannel(pos: Int) {
        if (pos in filteredChannels.indices) {
            val removed = filteredChannels.removeAt(pos)
            allChannels.remove(removed)
            adapter.notifyItemRemoved(pos)
            Toast.makeText(this, "Deleted: ${removed.rawIdent}", Toast.LENGTH_SHORT).show()
        }
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
        updateStatus("Scanning Wi-Fi & Hotspot for ALi / GX...")
        scope.launch {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val lock = wifiManager?.createMulticastLock("dg_lock")?.apply { setReferenceCounted(true); acquire() }
            try {
                // Detect actual subnet (Works for both Wi-Fi 192.168.1.x and Hotspot 192.168.43.x)
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

                var found: String? = null

                // 1. ALi UDP Discovery (25860)
                try {
                    val udp = DatagramSocket().apply { broadcast = true; soTimeout = 1000 }
                    val msg = "smart_client_search".toByteArray()
                    subnets.forEach { sub ->
                        udp.send(DatagramPacket(msg, msg.size, InetAddress.getByName("${sub}255"), 25860))
                    }
                    val pkt = DatagramPacket(ByteArray(1024), 1024)
                    udp.receive(pkt)
                    found = pkt.address.hostAddress
                    udp.close()
                } catch (_: Exception) {}

                // 2. Fast Port Scan for ALi (20000) & GX (8000, 8888)
                if (found == null) {
                    for (sub in subnets) {
                        found = (1..254).map { i ->
                            async(Dispatchers.IO) {
                                val testIp = "$sub$i"
                                for (port in listOf(20000, 8000, 8888)) {
                                    try {
                                        Socket().use { it.connect(java.net.InetSocketAddress(testIp, port), 200) }
                                        return@async "$testIp:$port"
                                    } catch (_: Exception) {}
                                }
                                null
                            }
                        }.awaitAll().firstOrNull { it != null }
                        if (found != null) break
                    }
                }

                withContext(Dispatchers.Main) {
                    if (found != null) {
                        etIpAddress.setText(found)
                        updateStatus("Found: $found (Click CONNECT)")
                    } else {
                        updateStatus("No ALi/GX receiver found on ${subnets.joinToString()}")
                    }
                }
            } finally {
                if (lock?.isHeld == true) lock.release()
            }
        }
    }

    private fun connectAndFetchChannels(rawIp: String) {
        val parts = rawIp.trim().split(":")
        val ip = parts[0]
        val customPort = parts.getOrNull(1)?.toIntOrNull()
        currentIp = ip
        updateStatus("Connecting to $ip...")

        scope.launch {
            val ports = if (customPort != null) listOf(customPort) else listOf(20000, 8000, 8888)
            for (port in ports) {
                try {
                    tcpSocket?.close()
                    val s = Socket()
                    s.connect(java.net.InetSocketAddress(ip, port), 3000)
                    s.soTimeout = 5000
                    tcpSocket = s
                    outputStream = s.getOutputStream()
                    inputStream = s.getInputStream()

                    updateStatus("Connected to $ip:$port! Fetching channels...")

                                        if (port == 20000) {
                        // Standard ALi / G-MScreen Protocol Packet (GCDH Header + XML)
                        fun buildAliPacket(xml: String): ByteArray {
                            val payload = xml.toByteArray(Charsets.UTF_8)
                            val buf = java.nio.ByteBuffer.allocate(16 + payload.size)
                            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            buf.put("GCDH".toByteArray(Charsets.US_ASCII)) // Magic Start Code
                            buf.putInt(payload.size)                       // XML Length
                            buf.putInt(0)                                  // Flags
                            buf.putInt(0)                                  // Reserved
                            buf.put(payload)
                            return buf.array()
                        }

                        // 1. Login / Handshake
                        outputStream?.write(buildAliPacket("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><Command request=\"0\" />"))
                        outputStream?.flush()
                        delay(400)

                        // 2. Request Channels (0 to 200 first, as many ALi receivers reject >250 at once)
                        outputStream?.write(buildAliPacket("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><Command request=\"1\"><FromIndex>0</FromIndex><ToIndex>200</ToIndex></Command>"))
                        outputStream?.flush()
                    } else {
                        sendRawXml("<Command request=\"0\" />")
                        delay(300)
                        sendRawXml("<Command request=\"1\"><FromIndex>0</FromIndex><ToIndex>200</ToIndex></Command>")
                    }

                    val sb = StringBuilder()
                    val buf = ByteArray(8192)
                    repeat(20) {
                        try {
                            val r = inputStream?.read(buf) ?: -1
                            if (r <= 0) return@repeat
                            // Strip binary header bytes and extract clean XML text
                            val chunk = String(buf, 0, r, Charsets.UTF_8)
                            sb.append(chunk)
                            if (chunk.contains("</Command>") || chunk.contains("End")) return@repeat
                        } catch (_: Exception) { return@repeat }
                    }

                    val rawReply = sb.toString()
                    if (rawReply.contains("<")) {
                        parseChannelsXml(rawReply)
                    } else {
                        updateStatus("Connected ($ip:$port) - No XML reply (Bytes: ${rawReply.length})")
                    }
                    return@launch

                } catch (_: Exception) {}
            }
            updateStatus("Failed to connect to $ip (Tried ports 20000, 8000, 8888)")
        }
    }

    private fun parseChannelsXml(rawResponse: String) {
        val startIdx = rawResponse.indexOf("<?xml")
        val endIdx = rawResponse.lastIndexOf("End")
        if (startIdx == -1) { updateStatus("Connected, no channel list returned."); return }
        val xmlContent = if (endIdx > startIdx) rawResponse.substring(startIdx, endIdx) else rawResponse.substring(startIdx)

        val parsed = mutableListOf<DvbChannel>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xmlContent))
            var eventType = parser.eventType
            var rawName = ""; var provider = ""; var serviceId = ""
            var satName = "Satellite"; var freq = ""; var pol = ""; var sym = ""
            var dvbSys = "DVB-S2"; var mod = "8PSK"; var fec = "3/4"
            var scramble = false; var hd = true; var vPid = "---"; var aPid = "---"
            var idx = 1

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when {
                            tag.equals("ProgramName", true) || tag.equals("Name", true) -> {
                                rawName = parser.nextText().trim()
                                hd = rawName.contains("HD", true) || rawName.contains("4K", true)
                            }
                            tag.equals("ProviderName", true) || tag.equals("Provider", true) -> provider = parser.nextText().trim()
                            tag.equals("ServiceID", true) || tag.equals("ProgramIndex", true) -> serviceId = parser.nextText().trim()
                            tag.equals("SatName", true) -> satName = parser.nextText().trim()
                            tag.equals("Freq", true) || tag.equals("Frequency", true) -> freq = parser.nextText().trim()
                            tag.equals("Polar", true) || tag.equals("Pol", true) -> {
                                val p = parser.nextText().trim()
                                pol = if (p == "0" || p.equals("H", true)) "H" else if (p == "1" || p.equals("V", true)) "V" else p
                            }
                            tag.equals("SymbolRate", true) || tag.equals("Sym", true) -> sym = parser.nextText().trim()
                            tag.equals("System", true) || tag.equals("DvbType", true) -> dvbSys = when (parser.nextText().trim()) { "0" -> "DVB-S"; "1" -> "DVB-S2"; "2" -> "DVB-S2X"; else -> "DVB-S2" }
                            tag.equals("Modulation", true) || tag.equals("Mod", true) -> mod = when (parser.nextText().trim()) { "0" -> "QPSK"; "1" -> "8PSK"; "2" -> "16APSK"; "3" -> "32APSK"; else -> "8PSK" }
                            tag.equals("FEC", true) -> fec = parser.nextText().trim().ifEmpty { "3/4" }
                            tag.equals("Scramble", true) || tag.equals("Crypt", true) -> scramble = parser.nextText().trim() == "1"
                            tag.equals("VideoPID", true) || tag.equals("VPID", true) -> vPid = parser.nextText().trim()
                            tag.equals("AudioPID", true) || tag.equals("APID", true) -> aPid = parser.nextText().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag.equals("Program", true) || tag.equals("Channel", true)) {
                            if (rawName.isNotEmpty()) {
                                parsed.add(DvbChannel(idx++, rawName, provider, serviceId, satName, freq, pol, sym, dvbSys, mod, fec, scramble, hd, vPid, aPid))
                                rawName = ""; provider = ""; serviceId = ""; freq = ""; pol = ""; sym = ""
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Throwable) {}

        scope.launch(Dispatchers.Main) {
            allChannels.clear(); allChannels.addAll(parsed)
            filteredChannels.clear(); filteredChannels.addAll(parsed)
            adapter.notifyDataSetChanged()
            tvStatus.text = "Connected ($currentIp) • Loaded ${parsed.size} channels ✓"
        }
    }

    private fun showPlayerDialog(channel: DvbChannel) {
        val options = arrayOf(
            "Internal Player (H.265 / 4K) + Pro Info Bar",
            "Open with VLC Player",
            "Open with MX Player",
            "Copy Full Ident"
        )
        AlertDialog.Builder(this)
            .setTitle(channel.rawIdent)
            .setItems(options) { _, which ->
                if (which == 3) copyText(channel.rawIdent, "Copied: ${channel.rawIdent}")
                else startStream(channel, which)
            }
            .show()
    }

    private fun startStream(channel: DvbChannel, mode: Int) {
        updateStatus("Streaming: ${channel.rawIdent}...")
        layoutInfoBar.visibility = View.VISIBLE
        tvInfoChannelName.text = String.format(Locale.US, "%04d. %s", channel.index, channel.rawIdent)
        tvBadgeRes.text = if (channel.isHd) "HD" else "SD"
        tvBadgeCrypt.text = if (channel.isScrambled) "CRYPT $" else "FTA"
        tvBadgeCrypt.setTextColor(if (channel.isScrambled) 0xFFEF4444.toInt() else 0xFF4ADE80.toInt())

        val freqText = if (channel.freq.isNotEmpty()) "${channel.satName} • ${channel.freq} ${channel.pol} ${channel.symRate}" else channel.satName
        tvInfoSatFreq.text = freqText
        tvSatFinderFreq.text = freqText
        tvInfoModulation.text = "${channel.dvbSystem} • ${channel.modulation} • ${channel.fec}"
        tvInfoPids.text = "V:${channel.vPid} A:${channel.aPid}"

        startTelemetryMonitor()

        scope.launch {
            sendRawXml("<Command request=\"1010\"><ServiceID>${channel.serviceId}</ServiceID><ProgramIndex>${channel.index - 1}</ProgramIndex></Command>")
            delay(500)
            val streamUrl = "http://$currentIp:8080/live"
            withContext(Dispatchers.Main) {
                when (mode) {
                    0 -> {
                        if (libVlc != null && mediaPlayer != null) {
                            try {
                                playerContainer.visibility = View.VISIBLE
                                val media = Media(libVlc, Uri.parse(streamUrl))
                                media.setHWDecoderEnabled(true, false)
                                mediaPlayer?.media = media
                                media.release()
                                mediaPlayer?.play()
                            } catch (_: Throwable) {
                                openExternal(streamUrl, "org.videolan.vlc", channel.rawIdent)
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "Opening external player...", Toast.LENGTH_SHORT).show()
                            openExternal(streamUrl, "org.videolan.vlc", channel.rawIdent)
                        }
                    }
                    1 -> openExternal(streamUrl, "org.videolan.vlc", channel.rawIdent)
                    2 -> openExternal(streamUrl, "com.mxtech.videoplayer.ad", channel.rawIdent)
                }
            }
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

                    sendRawXml("<Command request=\"1030\" />")
                    delay(350)

                    var strength = 85; var quality = 80
                    val available = inputStream?.available() ?: 0
                    if (available > 0) {
                        val buffer = ByteArray(2048)
                        val read = inputStream?.read(buffer) ?: 0
                        val xml = String(buffer, 0, read, Charsets.UTF_8)
                        strength = extractTagInt(xml, "SignalStrength") ?: extractTagInt(xml, "Strength") ?: 85
                        quality = extractTagInt(xml, "SignalQuality") ?: extractTagInt(xml, "Quality") ?: 80
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
                        tvSatFinderDb.text = dbStr; pbSatSignal.progress = strength; pbSatQuality.progress = quality

                        val qColor = when {
                            quality >= 70 -> 0xFF22C55E.toInt()
                            quality >= 45 -> 0xFFEAB308.toInt()
                            else -> 0xFFEF4444.toInt()
                        }
                        pbQuality.progressTintList = ColorStateList.valueOf(qColor)
                        tvQualityVal.setTextColor(qColor); tvInfoDb.setTextColor(qColor); tvSatFinderDb.setTextColor(qColor)
                    }
                } catch (_: Throwable) {}
                delay(1200)
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

        private fun sendRawXml(xml: String) {
        try {
            val out = outputStream ?: return
            val fullXml = if (xml.startsWith("<?xml")) {
                xml
            } else {
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>$xml"
            }
            val payload = fullXml.toByteArray(Charsets.UTF_8)

            if (tcpSocket?.port == 20000) {
                val buf = java.nio.ByteBuffer.allocate(16 + payload.size)
                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.put("GCDH".toByteArray(Charsets.US_ASCII))
                buf.putInt(payload.size)
                buf.putInt(0)
                buf.putInt(0)
                buf.put(payload)
                out.write(buf.array())
            } else {
                out.write(payload)
                out.write("\n".toByteArray(Charsets.UTF_8))
            }
            out.flush()
        } catch (_: Throwable) {}
    }
        
    private fun updateStatus(msg: String) {
        runOnUiThread {
            tvStatus.text = msg
        }
    }
}
