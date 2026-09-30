package com.zyd.browser

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Direct, serverless text/file transfer over Wi‑Fi Direct. */
@SuppressLint("MissingPermission")
class TransferActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var fileButton: Button

    private val worker = Executors.newCachedThreadPool()
    private val peers = mutableListOf<WifiP2pDevice>()
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var serverSocket: ServerSocket? = null
    private var receiverRegistered = false

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    status(if (enabled) "Wi‑Fi Direct 已开启，请搜索附近设备" else "请先打开手机 Wi‑Fi")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "设备互传"
        buildUi()
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager?.initialize(this, mainLooper, null)
        requestTransferPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION") registerReceiver(receiver, intentFilter)
            }
            receiverRegistered = true
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.WHITE)
        }
        toolbar.addView(Button(this).apply {
            text = "‹"
            textSize = 28f
            background = null
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(TextView(this).apply {
            text = "设备互传"
            textSize = 20f
            setTextColor(Color.rgb(24, 33, 47))
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(60)))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }
        content.addView(TextView(this).apply {
            text = "无需服务器、路由器或互联网。两部手机通过 Wi‑Fi Direct 直接连接后即可互传文字和文件。"
            textSize = 15f
            setTextColor(Color.rgb(75, 85, 102))
            setPadding(0, 0, 0, dp(12))
        })
        val actionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actionRow.addView(Button(this).apply {
            text = "搜索附近设备"
            isAllCaps = false
            setOnClickListener { discoverPeers() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        actionRow.addView(Button(this).apply {
            text = "等待连接"
            isAllCaps = false
            setOnClickListener { startServer() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(actionRow)
        statusView = TextView(this).apply {
            text = "正在初始化 Wi‑Fi Direct…"
            textSize = 14f
            setTextColor(Color.rgb(8, 127, 79))
            setPadding(0, dp(14), 0, dp(10))
        }
        content.addView(statusView)
        content.addView(TextView(this).apply {
            text = "附近设备"
            textSize = 17f
            setTextColor(Color.rgb(24, 33, 47))
        })
        deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(12))
        }
        content.addView(deviceList)
        val divider = View(this).apply { setBackgroundColor(Color.rgb(224, 229, 238)) }
        content.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
        content.addView(TextView(this).apply {
            text = "发送文字"
            textSize = 17f
            setTextColor(Color.rgb(24, 33, 47))
            setPadding(0, dp(16), 0, dp(6))
        })
        messageInput = EditText(this).apply {
            hint = "输入要发送的文字"
            minLines = 2
            gravity = Gravity.TOP
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(messageInput, LinearLayout.LayoutParams(-1, dp(92)))
        sendButton = Button(this).apply {
            text = "发送文字"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { sendText() }
        }
        content.addView(sendButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        fileButton = Button(this).apply {
            text = "选择文件并发送"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { chooseFile() }
        }
        content.addView(fileButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun requestTransferPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (Build.VERSION.SDK_INT < 33 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST)
    }

    private fun discoverPeers() {
        if (!hasWifiPermission()) {
            requestTransferPermissions()
            return
        }
        status("正在搜索附近设备…")
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = status("搜索已开始，请等待另一部手机出现")
            override fun onFailure(reason: Int) = status("搜索失败：${reasonText(reason)}")
        })
    }

    private fun requestPeers() {
        manager?.requestPeers(channel) { result: WifiP2pDeviceList ->
            peers.clear()
            peers.addAll(result.deviceList)
            renderPeers()
        }
    }

    private fun renderPeers() {
        deviceList.removeAllViews()
        if (peers.isEmpty()) {
            deviceList.addView(TextView(this).apply {
                text = "暂未发现设备。请让另一部手机打开本页面并点击“等待连接”。"
                textSize = 14f
                setTextColor(Color.rgb(104, 115, 134))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        peers.forEach { device ->
            deviceList.addView(Button(this).apply {
                text = "${device.deviceName.ifBlank { "未知设备" }}\n${device.deviceAddress}"
                textSize = 14f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isAllCaps = false
                setOnClickListener { connectTo(device) }
            }, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(5) })
        }
    }

    private fun connectTo(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        status("正在连接 ${device.deviceName.ifBlank { "设备" }}…")
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = status("连接请求已发送")
            override fun onFailure(reason: Int) = status("连接失败：${reasonText(reason)}")
        })
    }

    private fun requestConnectionInfo() {
        manager?.requestConnectionInfo(channel) { info: WifiP2pInfo ->
            if (!info.groupFormed) return@requestConnectionInfo
            if (info.isGroupOwner) {
                status("已连接，正在等待对方建立传输通道…")
                startServer()
            } else {
                val host = info.groupOwnerAddress?.hostAddress
                if (host != null) connectSocket(host)
            }
        }
    }

    private fun startServer() {
        if (serverSocket != null) return
        status("等待另一部手机连接…")
        worker.execute {
            try {
                serverSocket = ServerSocket(PORT)
                val accepted = serverSocket!!.accept()
                setupSocket(accepted)
            } catch (error: Exception) {
                status("等待连接失败：${error.localizedMessage ?: "未知错误"}")
            }
        }
    }

    private fun connectSocket(host: String) {
        if (socket?.isConnected == true) return
        status("设备已连接，正在建立传输通道…")
        worker.execute {
            try {
                val connected = Socket()
                connected.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                setupSocket(connected)
            } catch (error: Exception) {
                status("传输通道建立失败：${error.localizedMessage ?: "未知错误"}")
            }
        }
    }

    @Synchronized
    private fun setupSocket(connected: Socket) {
        socket?.close()
        socket = connected
        val stream = DataOutputStream(BufferedOutputStream(connected.getOutputStream()))
        output = stream
        stream.writeUTF(PROTOCOL)
        stream.flush()
        sendButtonState(true)
        status("已连接，可以互传文字和文件")
        worker.execute { readLoop(connected) }
    }

    private fun readLoop(connected: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(connected.getInputStream()))
            if (input.readUTF() != PROTOCOL) throw IOException("协议不匹配")
            while (!connected.isClosed) {
                when (input.readByte().toInt()) {
                    TYPE_TEXT -> {
                        val text = input.readUTF()
                        status("收到文字：$text")
                    }
                    TYPE_FILE -> receiveFile(input)
                    else -> throw IOException("未知数据类型")
                }
            }
        } catch (_: Exception) {
            if (socket === connected) {
                socket = null
                output = null
                sendButtonState(false)
                status("连接已断开")
            }
        }
    }

    @SuppressLint("NewApi")
    private fun receiveFile(input: DataInputStream) {
        val name = input.readUTF().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "received_file" }
        val length = input.readLong()
        if (length < 0 || length > MAX_FILE_SIZE) throw IOException("文件大小不受支持")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/绿萝工具箱")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建下载文件")
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var remaining = length
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw IOException("文件传输中断")
                    outputStream.write(buffer, 0, count)
                    remaining -= count
                }
            } ?: throw IOException("无法写入下载文件")
            status("已收到文件：$name，保存在下载/绿萝工具箱")
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun sendText() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        worker.execute {
            try {
                synchronized(this) {
                    output?.apply {
                        writeByte(TYPE_TEXT)
                        writeUTF(text)
                        flush()
                    } ?: throw IOException("尚未连接设备")
                }
                status("文字已发送")
                runOnUiThread { messageInput.text.clear() }
            } catch (error: Exception) {
                status("发送失败：${error.localizedMessage ?: "未知错误"}")
            }
        }
    }

    private fun chooseFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, FILE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_REQUEST || resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        worker.execute {
            try {
                val name = queryFileName(uri)
                val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (size < 0 || size > MAX_FILE_SIZE) throw IOException("文件超过 2 GB 限制")
                synchronized(this) {
                    val stream = output ?: throw IOException("尚未连接设备")
                    stream.writeByte(TYPE_FILE)
                    stream.writeUTF(name)
                    stream.writeLong(size)
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var count: Int
                        while (input.read(buffer).also { count = it } >= 0) {
                            if (count > 0) stream.write(buffer, 0, count)
                        }
                    } ?: throw IOException("无法读取文件")
                    stream.flush()
                }
                status("文件已发送：$name")
            } catch (error: Exception) {
                status("发送失败：${error.localizedMessage ?: "未知错误"}")
            }
        }
    }

    private fun queryFileName(uri: android.net.Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return "sent_file"
    }

    private fun hasWifiPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持 Wi‑Fi Direct"
        WifiP2pManager.BUSY -> "系统忙，请稍后重试"
        WifiP2pManager.ERROR -> "系统错误"
        else -> "错误码 $reason"
    }

    private fun status(message: String) {
        runOnUiThread { if (::statusView.isInitialized) statusView.text = message }
    }

    private fun sendButtonState(connected: Boolean) {
        runOnUiThread {
            sendButton.isEnabled = connected
            fileButton.isEnabled = connected
        }
    }

    override fun onDestroy() {
        try { serverSocket?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PORT = 39_871
        private const val PROTOCOL = "LVLUO_TRANSFER_1"
        private const val TYPE_TEXT = 1
        private const val TYPE_FILE = 2
        private const val BUFFER_SIZE = 32 * 1024
        private const val MAX_FILE_SIZE = 2L * 1024L * 1024L * 1024L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val PERMISSION_REQUEST = 7_201
        private const val FILE_REQUEST = 7_202
    }
}
