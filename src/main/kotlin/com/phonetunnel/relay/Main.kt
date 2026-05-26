package com.phonetunnel.relay

import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val log = LoggerFactory.getLogger("relay")

/**
 * Phone Tunnel signaling + TURN-credential relay (MVP3).
 *
 * Two phones connect via WebSocket and pair by a shared 6-digit code. Once
 * paired, this server hands them short-lived Cloudflare TURN credentials (so
 * the data path can go P2P via WebRTC, or fall back to Cloudflare TURN if the
 * NAT doesn't allow direct), and then forwards opaque text frames between the
 * two peers (those carry WebRTC offer/answer/ICE candidate exchange).
 *
 * The server NEVER sees tunnel traffic — that flows over the WebRTC data
 * channel directly between the phones (or via Cloudflare TURN, which is also
 * end-to-end encrypted at the WebRTC DTLS layer plus our AES-GCM on top).
 *
 * Wire protocol:
 *   1. Connect to /ws
 *   2. First text frame: "HELLO <role> <code>"
 *        role = "server" or "client";  code = 6 ASCII digits
 *   3. When both peers arrive, both receive a text frame:
 *        "PAIRED <iceServersJson>"
 *      where iceServersJson is the body Cloudflare returns from its
 *      credentials/generate endpoint (or a STUN-only fallback if Cloudflare
 *      isn't configured).
 *   4. From there, both peers send/receive text frames that the relay
 *      forwards verbatim (these carry WebRTC offer, answer, ICE candidates).
 *   5. Binary frames are also forwarded blindly (legacy fallback path for
 *      MVP2 APKs that don't use WebRTC yet).
 *
 * Env vars:
 *   CF_APP_ID        Cloudflare Realtime TURN App ID (public, safe to log)
 *   CF_API_TOKEN     Cloudflare Realtime TURN API Token (secret, never log)
 *   PORT             Listen port (default 8080; Render sets this for you)
 *   HOST             Bind host (default 0.0.0.0)
 *
 * If CF_API_TOKEN is missing, the relay still works but only ships STUN
 * servers — peers without direct connectivity will fail to establish.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val turnConfigured = !System.getenv("CF_API_TOKEN").isNullOrBlank() &&
        !System.getenv("CF_APP_ID").isNullOrBlank()
    log.info("Phone Tunnel relay starting on $host:$port (Cloudflare TURN: ${if (turnConfigured) "ON" else "OFF — STUN only"})")

    embeddedServer(Netty, port = port, host = host) {
        install(CallLogging)
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(20)
            timeout = Duration.ofSeconds(60)
            maxFrameSize = 1 * 1024 * 1024   // 1 MB to fit big SDP offers
        }
        configureRouting()
    }.start(wait = true)
}

private data class PendingPeer(
    val session: DefaultWebSocketServerSession,
    val role: Role,
    val partner: CompletableDeferred<DefaultWebSocketServerSession>
)

private enum class Role { SERVER, CLIENT }

private val pendingByCode = ConcurrentHashMap<String, PendingPeer>()
private val pairLock = Mutex()
private val activeSessions = AtomicLong(0)
private val totalSessions = AtomicLong(0)
private val totalSignalingBytes = AtomicLong(0)

private fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText(
                "phonetunnel signaling relay\n" +
                    "active=${activeSessions.get()} total=${totalSessions.get()}\n",
                ContentType.Text.Plain
            )
        }
        get("/health") { call.respondText("ok", ContentType.Text.Plain) }
        get("/stats") {
            val s = "active=${activeSessions.get()} total=${totalSessions.get()} " +
                "signaling_bytes=${totalSignalingBytes.get()} pending=${pendingByCode.size}\n"
            call.respondText(s, ContentType.Text.Plain)
        }
        webSocket("/ws") { handleConnection(this) }
    }
}

// ===========================================================================
// Cloudflare TURN credential fetcher
// ===========================================================================

private val http: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
}

private data class CachedIce(val json: String, val fetchedAtMs: Long)
private val iceCache = AtomicReference<CachedIce?>(null)
private const val ICE_CACHE_TTL_MS = 60L * 60 * 1000   // 1h; Cloudflare creds last 24h, we refresh hourly

private fun fetchIceServersJson(): String {
    val cached = iceCache.get()
    if (cached != null && (System.currentTimeMillis() - cached.fetchedAtMs) < ICE_CACHE_TTL_MS) {
        return cached.json
    }

    val appId = System.getenv("CF_APP_ID")?.takeIf { it.isNotBlank() }
    val token = System.getenv("CF_API_TOKEN")?.takeIf { it.isNotBlank() }
    if (appId == null || token == null) {
        val stunOnly = """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478","stun:stun.l.google.com:19302"]}]}"""
        iceCache.set(CachedIce(stunOnly, System.currentTimeMillis()))
        return stunOnly
    }

    return try {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://rtc.live.cloudflare.com/v1/turn/keys/$appId/credentials/generate"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"ttl":86400}"""))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() in 200..299) {
            // Cloudflare returns: {"iceServers":{"urls":[...],"username":"...","credential":"..."}}
            // WebRTC's RTCConfiguration expects iceServers to be an array. Wrap the single
            // server object into a one-element array so Android's PeerConnection accepts it.
            val body = resp.body()
            val wrapped = wrapSingleIceServerAsArray(body)
            iceCache.set(CachedIce(wrapped, System.currentTimeMillis()))
            log.info("Refreshed Cloudflare TURN credentials")
            wrapped
        } else {
            log.warn("Cloudflare TURN API HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
            stunFallback()
        }
    } catch (t: Throwable) {
        log.warn("Cloudflare TURN call failed: ${t.message}")
        stunFallback()
    }
}

private fun stunFallback(): String =
    """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478","stun:stun.l.google.com:19302"]}]}"""

/**
 * Cloudflare returns iceServers as an object; WebRTC wants an array of one or
 * more such objects. This tiny transformation does that without pulling in a
 * full JSON library — we just locate the `"iceServers":` key and wrap its
 * value in `[ ... ]`.
 */
private fun wrapSingleIceServerAsArray(body: String): String {
    val key = "\"iceServers\""
    val keyIdx = body.indexOf(key)
    if (keyIdx < 0) return body
    val colonIdx = body.indexOf(':', keyIdx + key.length)
    if (colonIdx < 0) return body
    // Find the first '{' after the colon — that's the start of the single server object.
    var i = colonIdx + 1
    while (i < body.length && body[i].isWhitespace()) i++
    if (i >= body.length || body[i] != '{') return body   // already an array, or unexpected shape
    // Walk matching braces to find the end of the object.
    var depth = 0
    var j = i
    while (j < body.length) {
        when (body[j]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) break }
        }
        j++
    }
    if (depth != 0) return body
    return body.substring(0, i) + "[" + body.substring(i, j + 1) + "]" + body.substring(j + 1)
}

// ===========================================================================
// WebSocket connection handling
// ===========================================================================

private suspend fun handleConnection(session: DefaultWebSocketServerSession) {
    // --- 1. Read HELLO ---
    val helloFrame = try {
        withTimeoutOrNull(30_000) { session.incoming.receive() }
    } catch (_: ClosedReceiveChannelException) { null }

    if (helloFrame == null || helloFrame !is Frame.Text) {
        runCatching { session.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "expected HELLO text frame")) }
        return
    }
    val parts = helloFrame.readText().trim().split(' ')
    if (parts.size != 3 || parts[0] != "HELLO") {
        runCatching { session.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "bad HELLO")) }
        return
    }
    val role = when (parts[1]) {
        "server" -> Role.SERVER
        "client" -> Role.CLIENT
        else -> {
            runCatching { session.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "bad role")) }
            return
        }
    }
    val code = parts[2]
    if (code.length != 6 || !code.all { it.isDigit() }) {
        runCatching { session.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "bad code")) }
        return
    }
    log.info("HELLO role=$role code=$code")

    // --- 2. Pair with the opposite role ---
    var myDeferred: CompletableDeferred<DefaultWebSocketServerSession>? = null
    val matchedNow: DefaultWebSocketServerSession? = pairLock.withLock {
        val existing = pendingByCode[code]
        when {
            existing == null -> {
                val def = CompletableDeferred<DefaultWebSocketServerSession>()
                myDeferred = def
                pendingByCode[code] = PendingPeer(session, role, def)
                null
            }
            existing.role == role -> {
                runCatching { existing.session.close(CloseReason(CloseReason.Codes.NORMAL, "superseded")) }
                existing.partner.cancel()
                val def = CompletableDeferred<DefaultWebSocketServerSession>()
                myDeferred = def
                pendingByCode[code] = PendingPeer(session, role, def)
                null
            }
            else -> {
                pendingByCode.remove(code)
                existing.partner.complete(session)
                existing.session
            }
        }
    }

    val partnerSession: DefaultWebSocketServerSession = if (matchedNow != null) {
        matchedNow
    } else {
        val def = myDeferred
            ?: run {
                runCatching { session.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "no deferred")) }
                return
            }
        val result = withTimeoutOrNull(5 * 60 * 1000) { def.await() }
        if (result == null) {
            pairLock.withLock {
                val cur = pendingByCode[code]
                if (cur?.session === session) pendingByCode.remove(code)
            }
            runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "pairing timeout")) }
            return
        }
        result
    }

    // --- 3. Bridge ---
    activeSessions.incrementAndGet()
    totalSessions.incrementAndGet()
    log.info("PAIRED code=$code role=$role")

    try {
        // Send PAIRED with ICE servers JSON so the phones can start WebRTC negotiation.
        // We block briefly on the Cloudflare API call — this happens on the worker thread
        // for this connection, but Cloudflare returns within ~100-200 ms.
        val iceJson = withTimeoutOrNull(5_000) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchIceServersJson() }
        } ?: stunFallback()
        runCatching { session.send(Frame.Text("PAIRED $iceJson")) }

        pump(session, partnerSession)
    } catch (t: Throwable) {
        log.debug("bridge terminated: ${t.message}")
    } finally {
        runCatching { partnerSession.close(CloseReason(CloseReason.Codes.NORMAL, "peer disconnected")) }
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "done")) }
        activeSessions.decrementAndGet()
        log.info("CLOSED code=$code role=$role")
    }
}

/** Reads frames from [from] and forwards them verbatim to [to]. */
private suspend fun pump(from: DefaultWebSocketServerSession, to: DefaultWebSocketServerSession) {
    try {
        for (frame in from.incoming) {
            if (to.outgoing.isClosedForSend) return
            when (frame) {
                is Frame.Text -> {
                    val txt = frame.readText()
                    totalSignalingBytes.addAndGet(txt.length.toLong())
                    runCatching { to.send(Frame.Text(txt)) }
                }
                is Frame.Binary -> {
                    val bytes = frame.data
                    totalSignalingBytes.addAndGet(bytes.size.toLong())
                    runCatching { to.send(Frame.Binary(true, bytes)) }
                }
                is Frame.Close -> return
                else -> { /* ping/pong handled by Ktor */ }
            }
        }
    } catch (_: ClosedReceiveChannelException) {
        // peer closed cleanly
    }
}
