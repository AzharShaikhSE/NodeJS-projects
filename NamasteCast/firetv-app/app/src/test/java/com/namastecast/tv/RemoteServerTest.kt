package com.namastecast.tv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

class RemoteServerTest {

    private val received = CopyOnWriteArrayList<Command>()
    private var now = 0L
    private lateinit var server: RemoteServer
    private var port = 0

    private val host = object : RemoteHost {
        override fun execute(cmd: Command) { received += cmd }
        override fun stateJson() = """{"ok":true,"title":"Episode 1"}"""
        override fun tvName() = "AFTKA"
        override fun startMirror(id: String) { mirrorEvents += "start:$id" }
        override fun stopMirror() { mirrorEvents += "stop" }
    }
    private val mirrorEvents = CopyOnWriteArrayList<String>()

    @Before fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = RemoteServer(port, host, { "1234" }, { name ->
            when (name) {
                "remote.html" -> "<html>remote</html>".byteInputStream()
                "receiver.html" -> "<html>receiver</html>".byteInputStream()
                else -> null
            }
        }, clock = { now }, receiverToken = "tok", newId = { "m1" })
        server.start(5000, false)
    }

    @After fun stop() = server.stop()

    private data class Res(val code: Int, val body: String)

    private fun call(path: String, method: String = "GET", pin: String? = null, body: String? = null): Res {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        pin?.let { c.setRequestProperty("X-Pin", it) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        return Res(code, stream?.bufferedReader()?.readText() ?: "")
    }

    @Test fun `serves remote page without pin`() {
        val r = call("/")
        assertEquals(200, r.code)
        assertTrue(r.body.contains("remote"))
    }

    @Test fun `ping is open and names the tv`() {
        val r = call("/api/ping")
        assertEquals(200, r.code)
        assertTrue(r.body.contains("AFTKA"))
    }

    @Test fun `state needs pin`() {
        assertEquals(401, call("/api/state").code)
        assertEquals(401, call("/api/state", pin = "0000").code)
        val ok = call("/api/state", pin = "1234")
        assertEquals(200, ok.code)
        assertTrue(ok.body.contains("Episode 1"))
        assertEquals(200, call("/api/state?pin=1234").code)
    }

    @Test fun `cmd executes with pin and rejects junk`() {
        assertEquals(200, call("/api/cmd", "POST", "1234", """{"type":"video","action":"toggle"}""").code)
        assertEquals(400, call("/api/cmd", "POST", "1234", """{"type":"open","url":"javascript:alert(1)"}""").code)
        assertEquals(401, call("/api/cmd", "POST", "9999", """{"type":"click"}""").code)
        assertEquals(listOf<Command>(Command.Video(VideoAction.TOGGLE)), received.toList())
    }

    @Test fun `bookmarklet open endpoint`() {
        val u = URLEncoder.encode("https://namastedev.com/learn/x", "UTF-8")
        val r = call("/open?pin=1234&url=$u")
        assertEquals(200, r.code)
        assertTrue(r.body.contains("Sent to your TV"))
        assertEquals(listOf<Command>(Command.Open("https://namastedev.com/learn/x")), received.toList())
        assertEquals(401, call("/open?pin=1&url=$u").code)
    }

    @Test fun `locks out after 10 bad pins for a minute`() {
        repeat(10) { assertEquals(401, call("/api/state", pin = "0000").code) }
        assertEquals(403, call("/api/state", pin = "1234").code)
        now += 61_000
        assertEquals(200, call("/api/state", pin = "1234").code)
    }

    @Test fun `unknown path 404`() = assertEquals(404, call("/nope").code)

    private val offer = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\n"
    private fun sdpJson(sdp: String, id: String? = null) =
        org.json.JSONObject().put("sdp", sdp).apply { id?.let { put("id", it) } }.toString()

    @Test fun `mirror handshake laptop to tv receiver`() {
        // laptop posts offer -> TV opens receiver
        val o = call("/api/mirror/offer", "POST", "1234", sdpJson(offer))
        assertEquals(200, o.code)
        assertTrue(o.body.contains("\"id\":\"m1\""))
        assertEquals(listOf("start:m1"), mirrorEvents.toList())
        // no answer yet
        assertTrue(call("/api/mirror/answer?id=m1", pin = "1234").body.contains("\"sdp\":null"))
        // receiver page needs no PIN, but its data needs the token
        assertEquals(200, call("/receiver").code)
        assertEquals(403, call("/api/rx/offer").code)
        assertEquals(403, call("/api/rx/offer?t=wrong").code)
        val rx = call("/api/rx/offer?t=tok")
        assertEquals(200, rx.code)
        assertTrue(rx.body.contains("v=0"))
        // stale id or junk answer rejected
        assertEquals(400, call("/api/rx/answer?t=tok", "POST", body = sdpJson("v=0 x", "old")).code)
        assertEquals(400, call("/api/rx/answer?t=tok", "POST", body = sdpJson("not sdp", "m1")).code)
        // real answer reaches the laptop
        assertEquals(200, call("/api/rx/answer?t=tok", "POST", body = sdpJson("v=0 answer", "m1")).code)
        assertTrue(call("/api/mirror/answer?id=m1", pin = "1234").body.contains("v=0 answer"))
        assertEquals(410, call("/api/mirror/answer?id=zzz", pin = "1234").code)
        // stop
        assertEquals(200, call("/api/mirror/stop", "POST", "1234", "{}").code)
        assertEquals(listOf("start:m1", "stop"), mirrorEvents.toList())
        assertEquals(410, call("/api/rx/offer?t=tok").code)
    }

    @Test fun `mirror endpoints need pin and valid sdp`() {
        assertEquals(401, call("/api/mirror/offer", "POST", "0000", sdpJson(offer)).code)
        assertEquals(400, call("/api/mirror/offer", "POST", "1234", sdpJson("hello")).code)
        assertEquals(401, call("/api/mirror/stop", "POST", null, "{}").code)
        assertTrue(mirrorEvents.isEmpty())
    }

    @Test fun `rejected POST does not poison the next request on a kept-alive connection`() {
        repeat(3) {
            assertEquals(401, call("/api/cmd", "POST", "0000", """{"type":"click"}""").code)
            assertEquals(200, call("/api/state", pin = "1234").code)
        }
    }
}
