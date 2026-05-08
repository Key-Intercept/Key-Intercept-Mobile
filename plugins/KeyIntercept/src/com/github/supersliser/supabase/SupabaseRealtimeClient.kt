package com.github.supersliser.supabase

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SupabaseRealtimeClient {
    data class RealtimeSubscription(
        val channel: String,
        val schema: String,
        val table: String,
        val onChange: () -> Unit
    )

    private val client = OkHttpClient.Builder().build()
    private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val refCounter = AtomicInteger(1)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closedByUser = false

    @Volatile
    private var subscriptions: List<RealtimeSubscription> = emptyList()

    @Volatile
    private var heartbeatFuture: ScheduledFuture<*>? = null

    fun connectAndSubscribe(
        subscriptions: List<RealtimeSubscription>,
        onConnected: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null
    ) {
        this.subscriptions = subscriptions
        this.closedByUser = false

        val wsUrl = buildWebSocketUrl()
        val request = Request.Builder().url(wsUrl).build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                joinAllChannels(webSocket, subscriptions)
                startHeartbeat()
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError?.invoke(t)
                scheduleReconnect(onConnected, onError)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedByUser) {
                    scheduleReconnect(onConnected, onError)
                }
            }
        })
    }

    fun close() {
        closedByUser = true
        runCatching { socket?.close(1000, "Plugin stopped") }
        socket = null
        runCatching { heartbeatFuture?.cancel(true) }
        heartbeatFuture = null
        runCatching { heartbeatExecutor.shutdownNow() }
        runCatching { reconnectExecutor.shutdownNow() }
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private fun buildWebSocketUrl(): String {
        val base = SupabaseClient.SUPABASE_URL.removePrefix("https://").removePrefix("http://")
        return "wss://$base/realtime/v1/websocket?apikey=${SupabaseClient.SUPABASE_KEY}&vsn=1.0.0"
    }

    private fun joinAllChannels(webSocket: WebSocket, subscriptions: List<RealtimeSubscription>) {
        subscriptions.forEach { sub ->
            val topic = topicForChannel(sub.channel)
            val payload = JSONObject()
                .put("config", JSONObject()
                    .put("broadcast", JSONObject().put("self", false))
                    .put("postgres_changes", JSONArray().put(
                        JSONObject()
                            .put("event", "*")
                            .put("schema", sub.schema)
                            .put("table", sub.table)
                    ))
                )
                .put("access_token", SupabaseClient.SUPABASE_KEY)

            val joinMsg = JSONObject()
                .put("topic", topic)
                .put("event", "phx_join")
                .put("payload", payload)
                .put("ref", nextRef())

            webSocket.send(joinMsg.toString())
        }
    }

    private fun startHeartbeat() {
        heartbeatFuture?.cancel(true)
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate({
            val ws = socket ?: return@scheduleAtFixedRate
            val heartbeat = JSONObject()
                .put("topic", "phoenix")
                .put("event", "heartbeat")
                .put("payload", JSONObject())
                .put("ref", nextRef())
            ws.send(heartbeat.toString())
        }, 30L, 30L, TimeUnit.SECONDS)
    }

    private fun handleMessage(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val event = obj.optString("event", "")
        if (event != "postgres_changes") return

        val topic = obj.optString("topic", "")
        if (topic.isEmpty()) return

        val mapped = subscriptions.filter { topicForChannel(it.channel) == topic }
        mapped.forEach { sub ->
            runCatching { sub.onChange() }
        }
    }

    private fun scheduleReconnect(
        onConnected: (() -> Unit)?,
        onError: ((Throwable) -> Unit)?
    ) {
        if (closedByUser) return

        reconnectExecutor.schedule({
            if (!closedByUser) {
                connectAndSubscribe(subscriptions, onConnected, onError)
            }
        }, 3L, TimeUnit.SECONDS)
    }

    private fun topicForChannel(channel: String): String {
        return "realtime:$channel"
    }

    private fun nextRef(): String = refCounter.getAndIncrement().toString()
}
