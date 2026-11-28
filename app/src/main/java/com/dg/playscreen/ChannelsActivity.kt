package com.dg.playscreen
import org.json.JSONObject
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
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
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
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
        holder.tvChMeta.text = "$satPart${ch.dvbSystem}"
        holder.itemView.setOnClickListener { onPlayClick(ch) }
        holder.btnChOptions.setOnClickListener { onOptionsClick(ch) }
    }

    override fun getItemCount(): Int = items.size
}

class ChannelsActivity : AppCompatActivity() {
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var rvChannels: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var btnToggleTvRadio: Button
    private lateinit var btnSelectSatFilter: Button

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tcpSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val allChannels = mutableListOf<DvbChannel>()
    private val filteredChannels = mutableListOf<DvbChannel>()
    private val discoveredSatellites = mutableListOf<String>()
    private var selectedSatFilter: String = "ALL"
    private var isRadioMode: Boolean = false
    private var currentIp: String = ""
    private lateinit var adapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channels)

        val ip = intent.getStringExtra("IP") ?: ""
        if (ip.isEmpty()) { finish(); return }
        currentIp = ip.split(":")[0]
        val port = ip.split(":").getOrNull(1)?.toIntOrNull() ?: 20000

        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)
        rvChannels = findViewById(R.id.rvChannels)
        etSearch = findViewById(R.id.etSearchChannel)
        btnToggleTvRadio = findViewById(R.id.btnToggleTvRadio)
        btnSelectSatFilter = findViewById(R.id.btnSelectSatFilter)

        adapter = ChannelAdapter(filteredChannels, onPlayClick = { ch -> showPlayerDialog(ch) }, onOptionsClick = { ch -> showPlayerDialog(ch) })
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = adapter

        btnToggleTvRadio.setOnClickListener {
            isRadioMode = !isRadioMode
            btnToggleTvRadio.text = if (isRadioMode) "RADIO" else "TV"
            btnToggleTvRadio.setBackgroundColor(if (isRadioMode) 0xFFD97706.toInt() else 0xFF0284C7.toInt())
            connectAndFetchChannels(port)
        }

        btnSelectSatFilter.setOnClickListener {
            val options = mutableListOf("Toutes les chaînes (All)")
            options.addAll(discoveredSatellites)
            AlertDialog.Builder(this).setTitle("Sélectionner un Satellite").setItems(options.toTypedArray()) { _, which ->
                selectedSatFilter = if (which == 0) "ALL" else options[which]
                btnSelectSatFilter.text = "$selectedSatFilter ▾"
                applyChannelFilters(etSearch.text.toString())
            }.show()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyChannelFilters(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        connectAndFetchChannels(port)
    }

    private fun applyChannelFilters(query: String) {
        val q = query.lowercase().trim()
        filteredChannels.clear()
        filteredChannels.addAll(allChannels.filter { ch ->
            val matchesSat = when (selectedSatFilter) {
                "ALL" -> true
                else -> ch.satName == selectedSatFilter
            }
            val matchesText = q.isEmpty() || ch.rawIdent.lowercase().contains(q) || ch.satName.lowercase().contains(q)
            matchesSat && matchesText
        })
        adapter.notifyDataSetChanged()
    }

    private fun connectAndFetchChannels(port: Int) {
        updateStatus("Connecting to $currentIp:$port...")
        scope.launch {
            try {
                tcpSocket?.close()
                val s = Socket(); s.connect(InetSocketAddress(currentIp, port), 5000); s.soTimeout = 5000
                tcpSocket = s; outputStream = s.getOutputStream(); inputStream = s.getInputStream()
                val out = outputStream!!; val inStr = inputStream!!

                out.write(wrapGmScreenPacket("{\"request\":\"998\",\"ClientType\":\"0\",\"MobileType\":\"Android\",\"AppVersion\":\"1.0\"}"))
                out.flush(); delay(300); drainSocketBuffer(inStr)

                out.write(wrapGmScreenPacket("{\"request\":\"15\"}")); out.flush()
                val handshakeReply = readGmScreenResponse(inStr); drainSocketBuffer(inStr)
                val deviceName = Regex("\"ProductName\"\\s*:\\s*\"([^\"]+)\"").find(handshakeReply)?.groupValues?.get(1) ?: "ALi STB"
                withContext(Dispatchers.Main) { tvTitle.text = deviceName }
                
                val satMap = mutableMapOf<String, String>()
                out.write(wrapGmScreenPacket("{\"request\":\"22\"}")); out.flush(); delay(400)
                val satReply = readGmScreenResponse(inStr); drainSocketBuffer(inStr)
                val satRegex = Regex("\\{[^{}]*\"SatName\"[^{}]*\\}")
                for (m in satRegex.findAll(satReply)) {
                    val sObj = m.value
                    val sIdx = Regex("\"SatIndex\"\\s*:\\s*\"?([^\",}]+)\"?").find(sObj)?.groupValues?.get(1)?.trim() ?: ""
                    val sName = Regex("\"SatName\"\\s*:\\s*\"([^\"]+)\"").find(sObj)?.groupValues?.get(1)?.trim() ?: ""
                    if (sIdx.isNotEmpty() && sName.isNotEmpty()) satMap[sIdx] = sName
                }
                discoveredSatellites.clear(); discoveredSatellites.addAll(satMap.values.distinct().sorted())

                val fetchedList = mutableListOf<DvbChannel>()
                var fromIndex = 0; val step = 500; var hasMore = true
                val tvStateVal = if (isRadioMode) 1 else 0

                while (hasMore && fromIndex < 6000) {
                    drainSocketBuffer(inStr)
                    val cmd = "{\"request\":\"0\",\"TvState\":$tvStateVal,\"FromIndex\":$fromIndex,\"ToIndex\":${fromIndex + step - 1}}"
                    out.write(wrapGmScreenPacket(cmd)); out.flush(); delay(400)
                    val chReply = readGmScreenResponse(inStr)
                    if (chReply.isEmpty()) break
                    val batch = parseGmScreenChannels(chReply, fetchedList.size, satMap)
                    if (batch.isEmpty()) hasMore = false else {
                        fetchedList.addAll(batch)
                        withContext(Dispatchers.Main) { updateStatus("Loaded ${fetchedList.size} Channels...") }
                        if (batch.size < step) hasMore = false else fromIndex += step
                    }
                }

                withContext(Dispatchers.Main) {
                    allChannels.clear(); allChannels.addAll(fetchedList)
                    applyChannelFilters("")
                    updateStatus("Ready: ${fetchedList.size} Channels • ${satMap.size} Satellites")
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { updateStatus("Error: ${e.message}") } }
        }
    }

    private fun parseGmScreenChannels(data: String, startOffset: Int, satMap: Map<String, String> = emptyMap()): List<DvbChannel> {
        val list = mutableListOf<DvbChannel>()
        var idx = startOffset + 1
        try {
            val nameRegex = Regex("\"ServiceName\"\\s*:\\s*\"([^\"]+)\"")
            val idRegex = Regex("\"ServiceID\"\\s*:\\s*\"?([0-9]+)\"?")
            val satIdxRegex = Regex("\"SatIndex\"\\s*:\\s*\"?([0-9]+)\"?")
            val names = nameRegex.findAll(data).map { it.groupValues[1].trim() }.toList()
            val ids = idRegex.findAll(data).map { it.groupValues[1].trim() }.toList()
            val satIdxs = satIdxRegex.findAll(data).map { it.groupValues[1].trim() }.toList()

            for (i in names.indices) {
                val name = names[i]; if (name.isEmpty()) continue
                val sid = if (i < ids.size) ids[i] else "$idx"
                val satIdx = if (i < satIdxs.size) satIdxs[i] else "0"
                val satName = satMap[satIdx] ?: "Satellite $satIdx"
                list.add(DvbChannel(idx++, name, satName, sid, satName, "", "", "", "DVB-S2", "8PSK", "3/4", false, name.contains("HD", true), "0", "0"))
            }
        } catch (_: Exception) {}
        return list
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
        var expectedPayloadSize = -1; var headerEndOffset = -1; var idleAttempts = 0; var lastReadTime = System.currentTimeMillis()
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
        val raw = allBytes.toByteArray(); if (raw.isEmpty()) return ""
        val searchStart = if (headerEndOffset > 0) headerEndOffset else 0
        for (i in searchStart until raw.size - 1) {
            if (raw[i] == 0x78.toByte() && (raw[i + 1] == 0x9C.toByte() || raw[i + 1] == 0xDA.toByte() || raw[i + 1] == 0x01.toByte() || raw[i + 1] == 0x5E.toByte())) {
                try {
                    val inflater = java.util.zip.Inflater(); inflater.setInput(raw, i, raw.size - i)
                    val out = java.io.ByteArrayOutputStream(); val tmp = ByteArray(8192)
                    while (!inflater.finished()) { val count = inflater.inflate(tmp); if (count == 0) { if (inflater.needsInput()) break }; out.write(tmp, 0, count) }
                    inflater.end(); val unzipped = out.toString("UTF-8"); if (unzipped.isNotEmpty()) return unzipped
                } catch (_: Exception) {}
            }
        }
        return if (searchStart < raw.size) String(raw, searchStart, raw.size - searchStart, Charsets.UTF_8) else String(raw, Charsets.UTF_8)
    }

    private fun showPlayerDialog(ch: DvbChannel) {
        Toast.makeText(this, "Selected: ${ch.rawIdent}", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(msg: String) { runOnUiThread { tvStatus.text = msg } }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { tcpSocket?.close() } catch (_: Throwable) {}
    }
}
