package com.namastecast.tv

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.InputStream

/** What the HTTP server needs from the TV side. Activity implements it; tests fake it. */
interface RemoteHost {
    /** Called on the server thread. Implementations must hop to the UI thread. */
    fun execute(cmd: Command)
    /** Latest snapshot as JSON text. Must be cheap and thread-safe. */
    fun stateJson(): String
    fun tvName(): String
}

/**
 * Tiny LAN web server running inside the TV app.
 *
 *   GET  /              -> remote control web page (phone / laptop)
 *   GET  /api/ping      -> {"ok":true,"name":...}            (no PIN, lets the page find the TV)
 *   GET  /api/state     -> now-playing snapshot              (PIN)
 *   POST /api/cmd       -> run one Command, JSON body        (PIN)
 *   GET  /open?url=&pin -> "send this page to TV" for the laptop bookmarklet
 *
 * PIN arrives as X-Pin header or ?pin=. No CORS headers are sent, so other
 * websites in your browser cannot read state; the PIN stops blind POSTs.
 */
class RemoteServer(
    port: Int,
    private val host: RemoteHost,
    private val pin: () -> String,
    private val asset: (String) -> InputStream?,
    private val clock: () -> Long = System::currentTimeMillis,
) : NanoHTTPD(port) {

    private val failures = ArrayDeque<Long>()
    private val lock = Any()

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, """{"ok":false,"error":"server"}""")
        }
    }

    private fun route(s: IHTTPSession): Response {
        val path = s.uri.trimEnd('/').ifEmpty { "/" }
        return when {
            path == "/" || path == "/index.html" -> page("remote.html")
            path == "/api/ping" -> json(
                Response.Status.OK,
                JSONObject().put("ok", true).put("name", host.tvName()).put("app", "NamasteCast").toString()
            )
            path == "/api/state" && s.method == Method.GET ->
                authed(s) { json(Response.Status.OK, host.stateJson()) }
            path == "/api/cmd" && s.method == Method.POST -> authed(s) {
                val body = readBody(s)
                val cmd = CommandParser.parse(body)
                if (cmd == null) {
                    json(Response.Status.BAD_REQUEST, """{"ok":false,"error":"bad command"}""")
                } else {
                    host.execute(cmd)
                    json(Response.Status.OK, """{"ok":true}""")
                }
            }
            path == "/open" && s.method == Method.GET -> authed(s, htmlErrors = true) {
                val url = CommandParser.normalizeUrl(s.parameters["url"]?.firstOrNull())
                if (url == null) {
                    html(Response.Status.BAD_REQUEST, sentPage("That link can't be opened on the TV."))
                } else {
                    host.execute(Command.Open(url))
                    html(Response.Status.OK, sentPage("Sent to your TV", autoClose = true))
                }
            }
            else -> json(Response.Status.NOT_FOUND, """{"ok":false,"error":"not found"}""")
        }
    }

    private fun authed(s: IHTTPSession, htmlErrors: Boolean = false, block: () -> Response): Response {
        if (lockedOut()) {
            return if (htmlErrors) html(Response.Status.FORBIDDEN, sentPage("Too many wrong PINs. Wait a minute."))
            else json(Response.Status.FORBIDDEN, """{"ok":false,"error":"locked"}""")
        }
        val given = s.headers["x-pin"] ?: s.parameters["pin"]?.firstOrNull()
        if (given == null || given != pin()) {
            recordFailure()
            return if (htmlErrors) html(Response.Status.UNAUTHORIZED, sentPage("Wrong PIN. Re-copy the bookmarklet from the remote page."))
            else json(Response.Status.UNAUTHORIZED, """{"ok":false,"error":"pin"}""")
        }
        return block()
    }

    /** More than 10 bad PINs in 60s locks the API for the rest of that window. */
    private fun lockedOut(): Boolean = synchronized(lock) {
        val cutoff = clock() - 60_000
        while (failures.isNotEmpty() && failures.first() < cutoff) failures.removeFirst()
        failures.size >= 10
    }

    private fun recordFailure() = synchronized(lock) { failures.addLast(clock()) }

    private fun readBody(s: IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0 || len > 8192) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun page(name: String): Response {
        val stream = asset(name) ?: return json(Response.Status.NOT_FOUND, """{"ok":false}""")
        val bytes = stream.use { it.readBytes() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", bytes.inputStream(), bytes.size.toLong())
            .apply { addHeader("Cache-Control", "no-cache") }
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)
            .apply { addHeader("Cache-Control", "no-store") }

    private fun html(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", body)

    private fun sentPage(msg: String, autoClose: Boolean = false): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>NamasteCast</title>
        <style>body{margin:0;height:100vh;display:grid;place-items:center;background:#0f0f12;color:#f5f5f7;
        font:600 18px system-ui,sans-serif}div{text-align:center}b{color:#ff7a1a;font-size:42px;display:block}</style>
        </head><body><div><b>${if (autoClose) "&#10003;" else "!"}</b>${escape(msg)}</div>
        ${if (autoClose) "<script>setTimeout(function(){window.close()},1400)</script>" else ""}
        </body></html>
    """.trimIndent()

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
