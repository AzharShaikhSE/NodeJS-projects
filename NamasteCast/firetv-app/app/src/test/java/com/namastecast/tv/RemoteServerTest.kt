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
    }

    @Before fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = RemoteServer(port, host, { "1234" }, { name ->
            if (name == "remote.html") "<html>remote</html>".byteInputStream() else null
        }, clock = { now })
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
}
