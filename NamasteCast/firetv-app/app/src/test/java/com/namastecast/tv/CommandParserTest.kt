package com.namastecast.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {

    @Test fun `open adds https when scheme missing`() {
        assertEquals(Command.Open("https://namastedev.com/learn"), CommandParser.parse("""{"type":"open","url":"namastedev.com/learn"}"""))
    }

    @Test fun `open keeps full https url`() {
        val u = "https://namastedev.com/learn/namaste-react/episode-1?x=1#t"
        assertEquals(Command.Open(u), CommandParser.parse("""{"type":"open","url":"$u"}"""))
    }

    @Test fun `dangerous schemes rejected`() {
        listOf("javascript:alert(1)", "file:///etc/hosts", "intent://x#Intent;end", "data:text/html,hi",
            "ftp://x.com", "about:blank", "", "   ", "not a url", "localhostx")
            .forEach { assertNull(it, CommandParser.normalizeUrl(it)) }
    }

    @Test fun `credential trick host still needs a dot`() {
        assertNull(CommandParser.normalizeUrl("https://namastedev.com@evil"))
        assertEquals("https://user@evil.com/x", CommandParser.normalizeUrl("https://user@evil.com/x"))
    }

    @Test fun `video values are clamped`() {
        assertEquals(Command.Video(VideoAction.RATE, 4.0), CommandParser.parse("""{"type":"video","action":"rate","value":99}"""))
        assertEquals(Command.Video(VideoAction.SEEKTO, 1.0), CommandParser.parse("""{"type":"video","action":"seekto","value":3}"""))
        assertEquals(Command.Video(VideoAction.SEEK, -10.0), CommandParser.parse("""{"type":"video","action":"SEEK","value":-10}"""))
        assertEquals(Command.Video(VideoAction.TOGGLE, 0.0), CommandParser.parse("""{"type":"video","action":"toggle"}"""))
    }

    @Test fun `keys only from allow list`() {
        assertEquals(Command.Key(RemoteKey.SPACE), CommandParser.parse("""{"type":"key","key":"space"}"""))
        assertNull(CommandParser.parse("""{"type":"key","key":"power"}"""))
    }

    @Test fun `move and scroll`() {
        assertEquals(Command.Move(5f, -2000f), CommandParser.parse("""{"type":"move","dx":5,"dy":-99999}"""))
        assertEquals(Command.Scroll(420), CommandParser.parse("""{"type":"scroll","dy":420}"""))
        assertEquals(Command.MoveTo(1f, 0f), CommandParser.parse("""{"type":"moveto","fx":7,"fy":-1}"""))
    }

    @Test fun `text is capped`() {
        val c = CommandParser.parse("""{"type":"text","text":"${"a".repeat(900)}"}""") as Command.Text
        assertEquals(500, c.text.length)
        assertNull(CommandParser.parse("""{"type":"text","text":""}"""))
    }

    @Test fun `misc commands`() {
        assertEquals(Command.Click, CommandParser.parse("""{"type":"click"}"""))
        assertEquals(Command.TogglePanel, CommandParser.parse("""{"type":"panel"}"""))
        assertEquals(Command.Nav(NavAction.SETHOME), CommandParser.parse("""{"type":"nav","action":"sethome"}"""))
        assertEquals(Command.Mode(false), CommandParser.parse("""{"type":"mode","desktop":false}"""))
        assertEquals(Command.Zoom(1), CommandParser.parse("""{"type":"zoom","delta":5}"""))
    }

    @Test fun `garbage is null`() {
        listOf(null, "", "{", "[]", """{"type":"nope"}""", """{"type":"nav","action":"explode"}""")
            .forEach { assertNull(it, CommandParser.parse(it)) }
    }
}
