package com.satstream.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var statusText: TextView
    private lateinit var ipInput: EditText
    private lateinit var channelList: ListView
    private val streams = mutableListOf<Pair<String, String>>()
    private var currentUrl = ""
    private var currentTitle = ""

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        ipInput = findViewById(R.id.ipInput)
        channelList = findViewById(R.id.channelList)
        val playerView = findViewById<PlayerView>(R.id.playerView)

        val renderers = DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER).setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(1500, 15000, 1000, 1500).build()
        player = ExoPlayer.Builder(this, renderers).setLoadControl(loadControl).build()
        playerView.player = player

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val ip = ipInput.text.toString().trim()
            if (ip.isNotEmpty()) loadStreams(ip)
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener { scanNetwork() }
        findViewById<Button>(R.id.btnExternalPlayer).setOnClickListener {
            if (currentUrl.isNotEmpty()) openExternal(currentUrl, currentTitle)
        }
        channelList.setOnItemClickListener { _, _, pos, _ ->
            currentTitle = streams[pos].first; currentUrl = streams[pos].second
            statusText.text = "Playing 4K/H.265: $currentTitle"
            player?.setMediaItem(MediaItem.fromUri(currentUrl)); player?.prepare(); player?.play()
        }
        channelList.setOnItemLongClickListener { _, _, pos, _ ->
            openExternal(streams[pos].second, streams[pos].first); true
        }
    }

    private fun openExternal(url: String, title: String) {
        player?.pause()
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(url), "video/*"); putExtra("title", title) }
        try { startActivity(Intent.createChooser(intent, "Open with VLC / MX Player")) } catch (e: Exception) { Toast.makeText(this, "Install VLC or MX Player", Toast.LENGTH_SHORT).show() }
    }

    private fun scanNetwork() {
        statusText.text = "Scanning Wi-Fi for ALI & GX..."
        thread {
            try {
                val socket = DatagramSocket().apply { soTimeout = 3500 }
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n".toByteArray()
                socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
                val resp = DatagramPacket(ByteArray(2048), 2048); socket.receive(resp)
                val ip = resp.address.hostAddress ?: ""
                runOnUiThread { ipInput.setText(ip); statusText.text = "Found: $ip"; loadStreams(ip) }
                socket.close()
            } catch (e: Exception) { runOnUiThread { statusText.text = "Enter Receiver IP manually" } }
        }
    }

    private fun loadStreams(ip: String) {
        streams.clear()
        streams.add(Pair("[ALI] Sat>IP / G-MScreen (RTSP 554)", "rtsp://$ip:554/"))
        streams.add(Pair("[ALI] HTTP Stream (Port 8080)", "http://$ip:8080/"))
        streams.add(Pair("[ALI] Direct Channel (Port 8001)", "http://$ip:8001/1:0:1:1:1:1:0:0:0:0:"))
        streams.add(Pair("[GX] DVB2IP Stream (Port 8888)", "http://$ip:8888/"))
        streams.add(Pair("[GX] DLNA Media (Port 9000)", "http://$ip:9000/"))
        streams.add(Pair("[GX] MiniDLNA Stream (Port 8200)", "http://$ip:8200/"))
        channelList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, streams.map { it.first })
        statusText.text = "Ready ($ip) - Tap or Long-press for VLC/MX"
    }

    override fun onDestroy() { super.onDestroy(); player?.release() }
}
