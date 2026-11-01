package dev.personalterminal.lan

import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.automation.Commands
import dev.personalterminal.domain.AppClock
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * B16 `sshd` — a token-protected text UI over LAN (http://phone:7331). Same command shell as
 * the Today screen (`done`, `wear`, `stats`, …) plus a read-only heatmap, so a laptop keyboard
 * can drive the phone. Pure platform sockets: no dependencies, one accept loop, one thread
 * per connection, `Connection: close` everywhere (a phone-sized workload).
 */
object LanServer {

    const val PORT = 7331

    data class State(
        val running: Boolean = false,
        val token: String = "",
        val ip: String = "",
        val error: String? = null,
    ) {
        val url: String get() = if (running) "http://$ip:$PORT/?t=$token" else ""
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "lan-http").apply { isDaemon = true } }
    private val output = ArrayDeque<String>()
    private val outputLock = Any()

    // token is swapped into hrefs/forms at render time; constant for the lifetime of a run
    private var renderToken: String = ""

    fun start(app: PersonalTerminalApp): String {
        if (_state.value.running) return "lan already up → ${_state.value.url}"
        return try {
            val token = newToken()
            renderToken = token
            val ss = ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
            socket = ss
            _state.value = State(running = true, token = token, ip = localIp())
            acceptThread = Thread({
                runCatching {
                    while (!ss.isClosed) {
                        val client = runCatching { ss.accept() }.getOrNull() ?: break
                        pool.execute { handle(app, client, token) }
                    }
                }
            }, "lan-accept").apply { isDaemon = true; start() }
            push("# lan up · token ${token.take(8)}… · `lan off` stops it")
            "lan up → ${_state.value.url}"
        } catch (e: Exception) {
            _state.value = State(running = false, error = e.message)
            "lan failed: ${e.message}"
        }
    }

    fun stop(): String {
        val was = _state.value
        runCatching { socket?.close() }
        socket = null
        acceptThread = null
        _state.value = State()
        return if (was.running) "lan stopped" else "lan was not running"
    }

    // ------------------------------------------------------------------ http

    private fun handle(app: PersonalTerminalApp, client: java.net.Socket, token: String) {
        client.use { sock ->
            runCatching {
                sock.soTimeout = 15_000
                val input = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = input.readLine() ?: return
                var contentLength = 0
                while (true) {
                    val h = input.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = h.substringAfter(':').trim().toIntOrNull()?.coerceAtMost(64 * 1024) ?: 0
                    }
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val rawTarget = parts.getOrNull(1) ?: "/"
                val body = if (method == "POST" && contentLength > 0) {
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(chars, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(chars, 0, read)
                } else ""

                val params = parseParams(rawTarget.substringBefore('#'), body)
                val provided = params["t"] ?: ""
                if (!auth(provided, token)) {
                    write(sock, 401, html(page401()))
                    return
                }
                val line = params["line"]
                when {
                    method == "POST" && !line.isNullOrBlank() -> {
                        runBlocking {
                            runCatching {
                                withTimeout(10_000) { Commands.run(app, app, line, AppClock.today()) }
                            }.fold(
                                onSuccess = { r ->
                                    push("\$ $line")
                                    if (r.message.isNotBlank()) push(r.message)
                                },
                                onFailure = { e ->
                                    push("\$ $line")
                                    push("error: ${e.message ?: "command failed"}")
                                },
                            )
                        }
                        write(sock, 200, html(pageMain(app)))
                    }
                    params["v"] == "heatmap" -> write(sock, 200, html(pageHeatmap(app)))
                    else -> write(sock, 200, html(pageMain(app)))
                }
            }.onFailure {
                runCatching { write(sock, 500, html("<pre>server error</pre>")) }
            }
        }
    }

    private fun auth(provided: String, expected: String): Boolean {
        if (provided.isEmpty() || expected.isEmpty()) return false
        return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    private fun parseParams(target: String, body: String): Map<String, String> {
        val all = target.substringAfter('?', "") + "&" + body
        return all.split('&').mapNotNull { kv ->
            if ('=' !in kv) return@mapNotNull null
            val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(kv.substringAfter('='), "UTF-8")
            k to v
        }.toMap()
    }

    private fun write(sock: java.net.Socket, code: Int, content: String) {
        val status = when (code) {
            200 -> "200 OK"; 401 -> "401 Unauthorized"; else -> "500 Internal Server Error"
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        sock.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    // ------------------------------------------------------------------ pages

    private fun push(line: String) {
        synchronized(outputLock) {
            output.addLast(line)
            while (output.size > 60) output.removeFirst()
        }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun shell(): String = synchronized(outputLock) {
        output.joinToString("\n") { esc(it) }
    }

    private fun css() = """
        body{background:#060606;color:#E5E5E5;font:14px/1.5 ui-monospace,Menlo,Consolas,monospace;margin:0;padding:16px}
        a{color:#C5FF4A}
        .wrap{max-width:760px;margin:0 auto}
        .hdr{color:#C5FF4A;font-weight:700;letter-spacing:.04em;margin-bottom:2px}
        .dim{color:#7A7A7A}
        .status{border:1px solid #313131;background:#1F1F1F;padding:8px 10px;margin:10px 0}
        pre.out{white-space:pre-wrap;word-break:break-word;background:#0B0B0B;border:1px solid #313131;padding:10px;min-height:120px;color:#E5E5E5;margin:10px 0}
        form{display:flex;gap:8px;margin:8px 0}
        input[name=line]{flex:1;background:#1F1F1F;color:#E5E5E5;border:1px solid #313131;padding:9px 10px;font:inherit;outline:none}
        input[name=line]:focus{border-color:#C5FF4A}
        button{background:#C5FF4A;color:#060606;border:0;padding:9px 16px;font:inherit;font-weight:700;cursor:pointer}
        .err{color:#E5484D}
        .grid{display:grid;grid-template-rows:repeat(7,12px);grid-auto-flow:column;grid-auto-columns:12px;gap:3px}
        .cell{width:12px;height:12px;background:#161616}
        .legend{display:flex;gap:6px;align-items:center;margin-top:10px;color:#7A7A7A}
        .lg{width:12px;height:12px;display:inline-block}
    """.trimIndent()

    private fun tok(): String = esc(renderToken)

    private fun page401() = """
        <div class=wrap><div class=hdr>personal terminal</div>
        <p class=err>token required</p>
        <p class=dim>open the URL printed by <b>lan on</b> in the app (&hellip;/?t=token).</p></div>
    """.trimIndent()

    private fun pageMain(app: PersonalTerminalApp): String {
        val today = AppClock.today()
        val summary = runCatching { runBlocking { withTimeout(5_000) { app.habits.daySummary(today) } } }.getOrNull()
        val status = if (summary != null) {
            "${summary.done}/${summary.active.size} done · ${summary.totalXp} xp · shields ${summary.shieldsAvailable}"
        } else "…"
        return """
            <div class=wrap>
            <div class=hdr>personal terminal <span class=dim>· lan</span></div>
            <div class=dim>$today &nbsp; <a href="/?t=${tok()}">shell</a> · <a href="/?t=${tok()}&v=heatmap">heatmap</a></div>
            <div class=status>$status</div>
            <pre class=out>${shell()}</pre>
            <form method=post action="/">
                <input type=hidden name=t value="${tok()}">
                <input name=line placeholder="done read · wear speedy · stats · lan off" autocomplete=off autofocus>
                <button type=submit>↵</button>
            </form>
            <div class=dim>same shell as the phone · read-only heatmap · token rotates every start</div>
            </div>
        """.trimIndent()
    }

    private fun pageHeatmap(app: PersonalTerminalApp): String {
        val today = AppClock.today()
        val from = today.minusDays(363)
        val counts = runCatching {
            runBlocking { withTimeout(5_000) { app.habits.observeCompletionCounts(from, today).first() } }
        }.getOrDefault(emptyList())
        val active = runCatching {
            runBlocking { withTimeout(5_000) { app.habits.allHabits() } }
        }.getOrDefault(emptyList()).count { !it.archived }.coerceAtLeast(1)
        val byDay = counts.associate { it.day to (it.count.toFloat() / active).coerceIn(0f, 1f) }

        val cells = StringBuilder()
        // columns are Monday-first weeks, oldest → newest, like the profile grid
        val first = from.with(java.time.DayOfWeek.MONDAY)
        var day = first
        while (!day.isAfter(today)) {
            val v = byDay[day.toEpochDay()] ?: -1f
            val bg = when {
                day.isBefore(from) -> "#0B0B0B"
                v < 0f -> "#161616"
                else -> {
                    val a = 0.15f + v * 0.85f
                    val alpha = (a * 255).toInt().toString(16).padStart(2, '0')
                    "#597321$alpha"
                }
            }
            cells.append("<div class=cell style=\"background:$bg\"></div>")
            day = day.plusDays(1)
        }
        return """
            <div class=wrap>
            <div class=hdr>personal terminal <span class=dim>· heatmap (read-only)</span></div>
            <div class=dim>$from → $today · completions / active habits</div>
            <div class=grid style="margin-top:12px">$cells</div>
            <div class=legend>less <span class=lg style="background:#161616"></span>
              <span class=lg style="background:#59732140"></span><span class=lg style="background:#59732199"></span>
              <span class=lg style="background:#597321FF"></span> more</div>
            <p><a href="/?t=${tok()}">← shell</a></p>
            </div>
        """.trimIndent()
    }

    private fun html(body: String): String = """
        <!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>personal terminal</title><style>${css()}</style></head><body>$body</body></html>
    """.trimIndent()

    private fun newToken(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun localIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && !it.isLinkLocalAddress }
            ?.hostAddress ?: "phone"
    }.getOrDefault("phone")
}
