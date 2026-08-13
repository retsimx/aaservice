package com.air.advantage.aaservice.data.mailbox

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject

/**
 * OkHttp-backed [MailboxWsClient].
 *
 * ## Session policy
 * - **Keepalive:** OkHttp `pingInterval` from [MailboxWsConfig.pingIntervalMs]
 *   (default [MailboxWsConfig.DEFAULT_PING_INTERVAL_MS] ≈ 30s).
 * - **Connected** only after the first `snapshot` ([MailboxInbound.Snapshot]);
 *   bare socket open alone leaves the client in [MailboxConnectionState.Connecting].
 * - Unexpected close / failure → [MailboxConnectionState.Disconnected], then
 *   **auto-reconnect** with exponential backoff from
 *   [MailboxWsConfig.reconnectInitialDelayMs] doubling up to
 *   [MailboxWsConfig.reconnectMaxDelayMs]. Backoff resets when the next snapshot
 *   reaches [MailboxConnectionState.Connected].
 *   Transport drops intentionally use **Disconnected**, not
 *   [MailboxConnectionState.Error] (reserved for future client-local failures;
 *   A2 must map drops via Disconnected / reconnect, not Error).
 * - Close **4009** (single-client limit) → [MailboxConnectionState.Rejected];
 *   **no** auto-reconnect (reconnect job cancelled, session ended).
 * - Explicit [disconnect] cancels reconnect, closes the socket →
 *   [MailboxConnectionState.Idle]; **no** auto-reconnect.
 * - **No automatic USB fallback** — never opens USB accessories.
 *
 * [connect] / [disconnect] schedule work on [scope]; collect [connectionState]
 * rather than assuming synchronous transitions.
 *
 * Ack wait: [sendWrite] / [sendCommand] throw [MailboxAckTimeoutException] on timeout
 * (they do not synthesize an error [MailboxInbound.Ack]). [sendRead] returns
 * [ReadOutcome.Value] on a matching `read_result` or [ReadOutcome.Error] on an
 * error ack, and likewise throws [MailboxAckTimeoutException] on timeout.
 *
 * Broker link-state frames ([MailboxInbound.Status]) are additionally re-emitted
 * on [daemonStatus] (replay=1); overflow logs a warning and never fails the socket.
 */
class OkHttpMailboxWsClient(
    private val config: MailboxWsConfig,
    client: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope,
    /**
     * When true, cancels [scope]'s [Job] after disconnect teardown.
     * Use for per-client scopes from [MailboxWsClientFactory.okHttp]; leave false
     * when tests share a long-lived scope across clients.
     */
    private val cancelScopeOnDisconnect: Boolean = false,
) : MailboxWsClient {

    private val httpClient: OkHttpClient = client.newBuilder()
        .pingInterval(config.pingIntervalMs, TimeUnit.MILLISECONDS)
        .build()

    private val _connectionState =
        MutableStateFlow<MailboxConnectionState>(MailboxConnectionState.Idle)
    override val connectionState: StateFlow<MailboxConnectionState> =
        _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<MailboxInbound>(
        // Replay last frame so UartForegroundService's collector, which attaches only
        // after Connected (post-snapshot), still receives the snapshot that
        // carries system_status / zones for :2025.
        extraBufferCapacity = 64,
        replay = 1,
    )
    override val incoming: SharedFlow<MailboxInbound> = _incoming.asSharedFlow()

    private val _daemonStatus = MutableSharedFlow<MailboxInbound.Status>(
        replay = 1,
        extraBufferCapacity = 4,
    )
    override val daemonStatus: SharedFlow<MailboxInbound.Status> =
        _daemonStatus.asSharedFlow()

    private val sessionActive = AtomicBoolean(false)
    private val socketGeneration = AtomicLong(0)
    private val activeSocket = AtomicReference<WebSocket?>(null)
    private val pendingAcks =
        ConcurrentHashMap<String, CompletableDeferred<MailboxInbound.Ack>>()
    private val pendingReads =
        ConcurrentHashMap<String, CompletableDeferred<ReadOutcome>>()

    private val connectMutex = Mutex()
    private var reconnectJob: Job? = null
    private var reconnectDelayMs: Long = config.reconnectInitialDelayMs

    override fun connect() {
        sessionActive.set(true)
        _connectionState.value = MailboxConnectionState.Connecting
        scope.launch {
            connectMutex.withLock {
                if (!sessionActive.get()) return@withLock
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectDelayMs = config.reconnectInitialDelayMs
                openSocketLocked()
            }
        }
    }

    override fun disconnect() {
        sessionActive.set(false)
        scope.launch {
            try {
                connectMutex.withLock {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    closeSocketLocked(code = 1000, reason = "client disconnect")
                    failPendingAcks("disconnected")
                    failPendingReads("disconnected")
                    _connectionState.value = MailboxConnectionState.Idle
                }
            } finally {
                if (cancelScopeOnDisconnect) {
                    scope.coroutineContext[Job]?.cancel()
                }
            }
        }
    }

    override suspend fun sendWrite(
        register: String,
        payload: MailboxPayload,
        zone: Int?,
    ): MailboxInbound.Ack {
        val msgId = newMsgId()
        val frame = MailboxOutbound.Write(
            msgId = msgId,
            register = register,
            payload = payload,
            zone = zone,
        )
        return sendAndAwaitAck(msgId, frame.toJsonString())
    }

    override suspend fun sendRead(register: String, zone: Int?): ReadOutcome {
        val msgId = newMsgId()
        val frame = MailboxOutbound.Read(
            msgId = msgId,
            register = register,
            zone = zone,
        )
        return sendAndAwait(msgId, frame.toJsonString(), pendingReads)
    }

    override suspend fun sendCommand(action: String): MailboxInbound.Ack {
        val msgId = newMsgId()
        val frame = MailboxOutbound.Command(msgId = msgId, action = action)
        return sendAndAwaitAck(msgId, frame.toJsonString())
    }

    private suspend fun <T> sendAndAwait(
        msgId: String,
        json: String,
        pending: ConcurrentHashMap<String, CompletableDeferred<T>>,
    ): T {
        val socket = activeSocket.get()
            ?: throw IllegalStateException("Mailbox WebSocket is not open")
        val deferred = CompletableDeferred<T>()
        pending[msgId] = deferred
        try {
            val enqueued = socket.send(json)
            if (!enqueued) {
                throw IllegalStateException(
                    "Mailbox WebSocket send queue rejected frame msg_id=$msgId",
                )
            }
            return withTimeout(config.ackTimeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw MailboxAckTimeoutException(msgId)
        } finally {
            pending.remove(msgId)
        }
    }

    private suspend fun sendAndAwaitAck(msgId: String, json: String): MailboxInbound.Ack =
        sendAndAwait(msgId, json, pendingAcks)

    private fun openSocketLocked() {
        closeSocketLocked(code = 1000, reason = "reopen")
        if (_connectionState.value !is MailboxConnectionState.Connecting) {
            _connectionState.value = MailboxConnectionState.Connecting
        }

        val generation = socketGeneration.incrementAndGet()
        val request = Request.Builder().url(config.url).build()
        val socket = httpClient.newWebSocket(request, socketListener(generation))
        activeSocket.set(socket)
        Log.i(TAG, "openSocket: connecting to ${config.url} gen=$generation")
    }

    private fun socketListener(generation: Long): WebSocketListener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrentGeneration(generation)) return
                Log.i(TAG, "onOpen: awaiting mailbox_snapshot before Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrentGeneration(generation)) return
                handleTextMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrentGeneration(generation)) return
                Log.d(TAG, "onClosing: code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrentGeneration(generation)) return
                onSocketEnded(
                    webSocket = webSocket,
                    generation = generation,
                    code = code,
                    reason = reason,
                    throwable = null,
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrentGeneration(generation)) return
                Log.w(TAG, "onFailure: ${t.message}", t)
                onSocketEnded(
                    webSocket = webSocket,
                    generation = generation,
                    code = null,
                    reason = t.message,
                    throwable = t,
                )
            }
        }

    private fun handleTextMessage(text: String) {
        val inbound = try {
            MailboxInbound.parse(text)
        } catch (e: JSONException) {
            Log.w(TAG, "Ignoring non-JSON mailbox frame", e)
            return
        }

        when (inbound) {
            is MailboxInbound.Unknown -> {
                Log.d(TAG, "Ignoring unknown mailbox type=${inbound.type}")
                return
            }
            is MailboxInbound.Snapshot -> {
                if (_connectionState.value is MailboxConnectionState.Connecting) {
                    _connectionState.value = MailboxConnectionState.Connected
                    reconnectDelayMs = config.reconnectInitialDelayMs
                    Log.i(TAG, "Connected after mailbox_snapshot")
                }
            }
            is MailboxInbound.Ack -> {
                val msgId = inbound.msgId
                if (msgId != null) {
                    val deferredAck = pendingAcks.remove(msgId)
                    if (deferredAck != null) {
                        deferredAck.complete(inbound)
                    } else {
                        // No write/command wait — maybe an error ack for a read.
                        // No status guard: the daemon only acks reads with ERROR
                        // (success reads come back as read_result), so a SUCCESS
                        // ack here is a stale frame for an already-settled
                        // msg_id and never matches a pending read.
                        pendingReads.remove(msgId)?.complete(ReadOutcome.Error(inbound))
                    }
                }
            }
            is MailboxInbound.Error -> {
                // Recoverable protocol error — emit only; do not fail the socket.
                Log.w(TAG, "Mailbox protocol error: ${inbound.message}")
            }
            is MailboxInbound.Event -> Unit
            is MailboxInbound.ReadResult -> {
                val msgId = inbound.msgId
                if (msgId != null) {
                    pendingReads.remove(msgId)?.complete(ReadOutcome.Value(inbound))
                }
            }
            is MailboxInbound.Status -> {
                if (!_daemonStatus.tryEmit(inbound)) {
                    Log.w(TAG, "daemonStatus overflow; dropped state=${inbound.state}")
                }
            }
        }

        if (!_incoming.tryEmit(inbound)) {
            Log.w(TAG, "incoming buffer overflow; dropped type=${inbound.type}")
        }
    }

    private fun onSocketEnded(
        webSocket: WebSocket,
        generation: Long,
        code: Int?,
        reason: String?,
        throwable: Throwable?,
    ) {
        activeSocket.compareAndSet(webSocket, null)
        scope.launch {
            connectMutex.withLock {
                // Skip if this socket was superseded (disconnect / reopen bumped generation).
                if (socketGeneration.get() != generation) return@withLock
                handleSocketEndedLocked(code = code, reason = reason, throwable = throwable)
            }
        }
    }

    private fun handleSocketEndedLocked(
        code: Int?,
        reason: String?,
        throwable: Throwable?,
    ) {
        failPendingAcks(reason ?: throwable?.message ?: "socket closed")
        failPendingReads(reason ?: throwable?.message ?: "socket closed")

        if (!sessionActive.get()) {
            // Explicit disconnect owns Idle transition.
            return
        }

        if (code == CLOSE_SINGLE_CLIENT) {
            sessionActive.set(false)
            reconnectJob?.cancel()
            reconnectJob = null
            _connectionState.value = MailboxConnectionState.Rejected(
                code = CLOSE_SINGLE_CLIENT,
                reason = reason?.takeIf { it.isNotBlank() } ?: "Single client limit enforced",
            )
            Log.w(TAG, "Rejected: close $CLOSE_SINGLE_CLIENT reason=$reason")
            return
        }

        _connectionState.value = MailboxConnectionState.Disconnected
        scheduleReconnectLocked()
    }

    private fun scheduleReconnectLocked() {
        if (!sessionActive.get()) return
        reconnectJob?.cancel()
        val delayMs = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(config.reconnectMaxDelayMs)
        Log.i(TAG, "scheduleReconnect: delayMs=$delayMs")
        reconnectJob = scope.launch {
            delay(delayMs)
            connectMutex.withLock {
                if (!sessionActive.get()) return@withLock
                openSocketLocked()
            }
        }
    }

    private fun closeSocketLocked(code: Int, reason: String) {
        // Invalidate in-flight listener callbacks before tearing down.
        socketGeneration.incrementAndGet()
        val socket = activeSocket.getAndSet(null) ?: return
        runCatching { socket.close(code, reason) }
        runCatching { socket.cancel() }
    }

    private fun failPendingAcks(reason: String) {
        val msgIds = pendingAcks.keys.toList()
        for (msgId in msgIds) {
            val deferred = pendingAcks.remove(msgId) ?: continue
            deferred.complete(
                MailboxInbound.Ack(
                    msgId = msgId,
                    status = MailboxAckStatus.ERROR,
                    reason = reason,
                    raw = JSONObject()
                        .put("type", MailboxMessageType.ACK)
                        .put("msg_id", msgId)
                        .put("status", MailboxAckStatus.ERROR.toWire())
                        .put("reason", reason),
                ),
            )
        }
    }

    private fun failPendingReads(reason: String) {
        val msgIds = pendingReads.keys.toList()
        for (msgId in msgIds) {
            val deferred = pendingReads.remove(msgId) ?: continue
            deferred.complete(
                ReadOutcome.Error(
                    MailboxInbound.Ack(
                        msgId = msgId,
                        status = MailboxAckStatus.ERROR,
                        reason = reason,
                        raw = JSONObject()
                            .put("type", MailboxMessageType.ACK)
                            .put("msg_id", msgId)
                            .put("status", MailboxAckStatus.ERROR.toWire())
                            .put("reason", reason),
                    ),
                ),
            )
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        socketGeneration.get() == generation

    private fun newMsgId(): String = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "AAService2/MailboxWs"

        /** Daemon close code when another mailbox client already holds the session. */
        const val CLOSE_SINGLE_CLIENT = 4009
    }
}
