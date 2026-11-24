package com.namastecast.tv

import org.json.JSONObject

/**
 * Everything the phone/laptop remote can ask the TV to do.
 * Parsing is pure Kotlin + org.json so it is unit-testable off-device.
 */
sealed class Command {
    data class Open(val url: String) : Command()
    data class Nav(val action: NavAction) : Command()
    data class Key(val key: RemoteKey) : Command()
    data class Move(val dx: Float, val dy: Float) : Command()
    data class MoveTo(val fx: Float, val fy: Float) : Command()
    object Click : Command()
    data class Scroll(val dy: Int) : Command()
    data class Text(val text: String) : Command()
    data class Video(val action: VideoAction, val value: Double = 0.0) : Command()
    data class Mode(val desktop: Boolean) : Command()
    data class Zoom(val delta: Int) : Command()
    object TogglePanel : Command()
}

enum class NavAction { BACK, FORWARD, RELOAD, HOME, SETHOME }

enum class VideoAction { TOGGLE, PLAY, PAUSE, SEEK, SEEKTO, RATE, MUTE, FILL }

/** Keys we forward to the web page. Kept to a fixed list on purpose. */
enum class RemoteKey {
    SPACE, ENTER, BACKSPACE, TAB, ESCAPE, LEFT, RIGHT, UP, DOWN,
    PAGEUP, PAGEDOWN, F, M, K, J, L, N, P
}

object CommandParser {
    private const val MAX_TEXT = 500
    private const val MAX_URL = 2048

    fun parse(raw: String?): Command? {
        if (raw.isNullOrBlank() || raw.length > 8192) return null
        val json = try { JSONObject(raw) } catch (e: Exception) { return null }
        return parse(json)
    }

    fun parse(json: JSONObject): Command? = when (json.optString("type").lowercase()) {
        "open" -> normalizeUrl(json.optString("url"))?.let { Command.Open(it) }
        "nav" -> enumOrNull<NavAction>(json.optString("action"))?.let { Command.Nav(it) }
        "key" -> enumOrNull<RemoteKey>(json.optString("key"))?.let { Command.Key(it) }
        "move" -> Command.Move(
            clamp(json.optDouble("dx", 0.0), -2000.0, 2000.0).toFloat(),
            clamp(json.optDouble("dy", 0.0), -2000.0, 2000.0).toFloat()
        )
        "moveto" -> Command.MoveTo(
            clamp(json.optDouble("fx", 0.5), 0.0, 1.0).toFloat(),
            clamp(json.optDouble("fy", 0.5), 0.0, 1.0).toFloat()
        )
        "click" -> Command.Click
        "scroll" -> Command.Scroll(clamp(json.optDouble("dy", 0.0), -5000.0, 5000.0).toInt())
        "text" -> json.optString("text").takeIf { it.isNotEmpty() }
            ?.let { Command.Text(it.take(MAX_TEXT)) }
        "video" -> enumOrNull<VideoAction>(json.optString("action"))?.let { action ->
            val v = json.optDouble("value", 0.0).let { if (it.isNaN()) 0.0 else it }
            val safe = when (action) {
                VideoAction.RATE -> clamp(v, 0.25, 4.0)
                VideoAction.SEEKTO -> clamp(v, 0.0, 1.0)
                VideoAction.SEEK -> clamp(v, -3600.0, 3600.0)
                else -> v
            }
            Command.Video(action, safe)
        }
        "mode" -> Command.Mode(json.optBoolean("desktop", true))
        "zoom" -> Command.Zoom(json.optInt("delta", 0).coerceIn(-1, 1))
        "panel" -> Command.TogglePanel
        else -> null
    }

    /**
     * Accepts "namastedev.com/x" or full http(s) URLs. Anything else
     * (javascript:, file:, intent:, data:) is rejected.
     */
    fun normalizeUrl(input: String?): String? {
        val s = input?.trim().orEmpty()
        if (s.isEmpty() || s.length > MAX_URL) return null
        if (s.any { it.isWhitespace() }) return null
        val lower = s.lowercase()
        val withScheme = when {
            lower.startsWith("https://") || lower.startsWith("http://") -> s
            lower.contains("://") || lower.startsWith("javascript:") ||
                lower.startsWith("data:") || lower.startsWith("file:") ||
                lower.startsWith("intent:") || lower.startsWith("about:") -> return null
            else -> "https://$s"
        }
        val host = withScheme.substringAfter("://").substringBefore('/')
            .substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (host.isEmpty() || !host.contains('.') && host != "localhost") return null
        return withScheme
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun clamp(v: Double, lo: Double, hi: Double): Double =
        if (v.isNaN()) 0.0 else v.coerceIn(lo, hi)
}
