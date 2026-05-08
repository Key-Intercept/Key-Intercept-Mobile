package com.github.supersliser.supabase

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
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

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }
    private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val refCounter = AtomicInteger(1)

    @Volatile
    private var session: WebSocketSession? = null

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
        val uri = URI(wsUrl)

        runCatching {
            runBlocking {
                client.webSocket(
                    method = HttpMethod.Get,
                    host = uri.host,
                    port = if (uri.port == -1) 443 else uri.port,
                    path = uri.rawPath,
                    request = {
                        url {
                            uri.rawQuery?.takeIf { it.isNotEmpty() }?.split('&')?.forEach { pair ->
                                val idx = pair.indexOf('=')
                                if (idx > 0) {
                                    parameters.append(pair.substring(0, idx), pair.substring(idx + 1))
                                }
                            }
                        }
                    }
                ) {
                    session = this
                    joinAllChannels(this, subscriptions)
                    startHeartbeat(this)
                    onConnected?.invoke()

                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> handleMessage(frame.readText())
                            }
                        }
                    } finally {
                        session = null
                        runCatching { heartbeatFuture?.cancel(true) }
                        heartbeatFuture = null
                    }
                }
            }
        }.onFailure { error ->
            onError?.invoke(error)
            scheduleReconnect(onConnected, onError)
        }
    }

    fun close() {
        closedByUser = true
        session = null
        runCatching { heartbeatFuture?.cancel(true) }
        heartbeatFuture = null
        runCatching { heartbeatExecutor.shutdownNow() }
        runCatching { reconnectExecutor.shutdownNow() }
    }

    private fun buildWebSocketUrl(): String {
        val base = SupabaseClient.SUPABASE_URL.removePrefix("https://").removePrefix("http://")
        return "wss://$base/realtime/v1/websocket?apikey=${SupabaseClient.SUPABASE_KEY}&vsn=1.0.0"
    }

    private fun joinAllChannels(session: WebSocketSession, subscriptions: List<RealtimeSubscription>) {
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

            session.outgoing.trySend(Frame.Text(joinMsg.toString()))
        }
    }

    private fun startHeartbeat(session: WebSocketSession) {
        heartbeatFuture?.cancel(true)
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate({
            val heartbeat = JSONObject()
                .put("topic", "phoenix")
                .put("event", "heartbeat")
                .put("payload", JSONObject())
                .put("ref", nextRef())
            session.outgoing.trySend(Frame.Text(heartbeat.toString()))
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
