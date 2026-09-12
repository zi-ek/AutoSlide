package com.ziek.autoslide.ec

/**
 * 直连兜底 · 被控端 agent。
 *
 * 常驻一条 WebSocket 连到中转的**控制通道** `/relay/control`；控制端直连本机失败时会请求中转，
 * 中转顺这条控制通道推来 `{"type":"open","session":...}`，本 agent 就开一条**数据 WebSocket**
 * `/relay/data?side=agent` 并桥到本机 adbd(`127.0.0.1:5555`)，让控制端经中转连过来。
 *
 * 关键点：
 * - **只在直连兜底时才有数据流**。直连能通，控制端根本不请求中转，这里平时只是挂一条轻量 WS。
 * - 中转只搬字节，端到端的 ADB RSA 鉴权照常成立——中转看不懂也控不了 adb。
 * - 只在被 Easycontrol 部署过（有序列号）时启用，见 [EcRegistry.isEnabled]；没序列号完全不联网。
 * - 走 wss（和 [SERVER_BASE_URL] 同源），穿 Cloudflare 隧道；OkHttp 负责 TLS + WS 帧 + ping 保活。
 */

import android.content.Context
import com.ziek.autoslide.LogX
import com.ziek.autoslide.SERVER_BASE_URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object RelayAgent {

    private const val TAG = "RelayAgent"
    private const val ADBD_HOST = "127.0.0.1"
    private const val ADBD_PORT = 5555
    private const val RECONNECT_MS = 10_000L

    /** https://host → wss://host（穿 Cloudflare 隧道；控制/数据都走它） */
    private val WS_BASE = SERVER_BASE_URL
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)   // 保活，穿 Cloudflare/NAT 的空闲长连
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS 长连不设读超时
        .build()

    @Volatile
    private var control: WebSocket? = null

    /**
     * 启动。可重入；没有序列号时什么都不做，等 [EcSerialActivity] 收到序列号后再调一次即可。
     */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!EcRegistry.isEnabled(app)) return
        if (!started.compareAndSet(false, true)) return
        connectControl(app)
        LogX.i(TAG, "已启动中转 agent")
    }

    private fun connectControl(context: Context) {
        val serial = EcRegistry.serial(context)
        if (serial.isEmpty()) return
        val url = "$WS_BASE/relay/control?serial=${enc(serial)}&token=${enc(EcRegistry.TOKEN)}"
        control = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                LogX.i(TAG, "控制通道已连接")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    if (json.optString("type") == "open") openTunnel(json.optString("session"))
                }.onFailure { LogX.w(TAG, "解析开隧道指令失败", it) }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                scheduleReconnect(context)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                LogX.w(TAG, "控制通道断开：${t.message}")
                scheduleReconnect(context)
            }
        })
    }

    private fun scheduleReconnect(context: Context) {
        control = null
        scope.launch {
            delay(RECONNECT_MS)
            if (started.get() && control == null) connectControl(context)
        }
    }

    /**
     * 收到开隧道：连一条数据 WS + 连本机 adbd，两个方向各自搬字节。
     * 任一方向结束就把两边都关掉。每个会话相互独立，可并发。
     */
    private fun openTunnel(session: String) {
        if (session.isEmpty()) return
        scope.launch {
            val adbd = runCatching {
                Socket().apply { connect(InetSocketAddress(ADBD_HOST, ADBD_PORT), 3000) }
            }.getOrElse {
                LogX.w(TAG, "连本机 adbd 失败：${it.message}")
                return@launch
            }
            val url = "$WS_BASE/relay/data?session=${enc(session)}&side=agent"
            client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    // adbd → WS 读循环
                    scope.launch {
                        val buf = ByteArray(16 * 1024)
                        try {
                            val ins = adbd.getInputStream()
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                if (!ws.send(buf.toByteString(0, n))) break
                            }
                        } catch (_: Exception) {
                        } finally {
                            runCatching { ws.close(1000, null) }
                            runCatching { adbd.close() }
                        }
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    // WS → adbd
                    try {
                        adbd.getOutputStream().apply { write(bytes.toByteArray()); flush() }
                    } catch (_: Exception) {
                        runCatching { ws.close(1000, null) }
                        runCatching { adbd.close() }
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    runCatching { adbd.close() }
                    runCatching { ws.close(1000, null) }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    runCatching { adbd.close() }
                }
            })
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
