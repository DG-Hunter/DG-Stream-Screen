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
    private lateinit var vlcVideoLayout: VLCVideoLayout

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
