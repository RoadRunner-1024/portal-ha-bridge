package com.aeonos.portalha

/**
 * Where the kiosk WebView points. Plain string logic, no Android types, so it can be checked
 * off-device.
 *
 * `haUrl` stays the Home Assistant ORIGIN (http://host:port): MaControl and AssistantToolProvider
 * build their REST calls on it, so it must not carry a dashboard path. The page the kiosk opens on
 * lives separately in `dashboardPath` ("/dashboard-kitchen", "/lovelace/cameras", ...), and pages
 * asked for by a navigate command are always resolved against the same origin.
 */
object DashboardUrls {
    private val ORIGIN = Regex("^(https?://[^/?#]+)", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private const val MAX_PATH = 1024

    /** "192.168.1.5:8123" -> "http://192.168.1.5:8123", as the dashboard always did. "" stays "". */
    fun normalise(url: String): String {
        val u = url.trim()
        return when {
            u.isEmpty() -> ""
            u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true) -> u
            else -> "http://$u"
        }
    }

    /** scheme://host[:port] of [url] (normalised first), or "" if there isn't one. */
    fun origin(url: String): String = ORIGIN.find(normalise(url))?.groupValues?.get(1) ?: ""

    /**
     * A path as typed into Home Assistant or sent in a command, cleaned to "/something"; "" for
     * none; null if it isn't a path at all. A pasted full http(s) URL is cut down to its path -
     * the kiosk only ever shows pages of its own Home Assistant - and any other scheme
     * (javascript:, intent:, file:, ...) is refused. Exactly one leading slash, so "//host" can't
     * turn into a protocol-relative link to somewhere else.
     */
    fun cleanPath(raw: String): String? {
        var s = raw.filter { it >= ' ' && it != '\u007f' }.trim()   // no control characters
        if (s.isEmpty()) return ""
        val o = ORIGIN.find(s)
        if (o != null) s = s.substring(o.range.last + 1)
        else if (SCHEME.containsMatchIn(s)) return null
        s = "/" + s.trimStart('/')
        return if (s.length > MAX_PATH) null else s
    }

    /**
     * The kiosk's home page: <origin><dashboardPath>, or haUrl exactly as before when no path is
     * set (so nothing changes for anyone who doesn't use the feature). "" when HA isn't set up.
     */
    fun home(haUrl: String, dashboardPath: String): String {
        val base = normalise(haUrl)
        if (base.isEmpty()) return ""
        val path = cleanPath(dashboardPath)
        if (path.isNullOrEmpty()) return base
        val o = origin(base)
        return if (o.isEmpty()) base else o + path
    }

    /** A page of the same Home Assistant: <origin><path>. "" if HA isn't set up or [path] isn't one. */
    fun page(haUrl: String, path: String): String {
        val o = origin(haUrl)
        if (o.isEmpty()) return ""
        val p = cleanPath(path) ?: return ""
        return o + p.ifEmpty { "/" }
    }

    /** [url] is on [origin] - not merely sharing a prefix (http://ha:80 vs http://ha:8080). */
    fun sameOrigin(url: String, origin: String): Boolean {
        if (origin.isEmpty() || !url.startsWith(origin, ignoreCase = true)) return false
        return url.length == origin.length || url[origin.length] in "/?#"
    }

    /**
     * Is the WebView at [current] still on [home] or a view under it? The same prefix test the
     * dashboard always used against haUrl, with home's query/fragment ignored (Home Assistant
     * drops those as it routes, and comparing them would reload the page on every resume).
     */
    fun isAtHome(current: String, home: String): Boolean {
        val h = home.substringBefore('#').substringBefore('?').trimEnd('/')
        return h.isNotEmpty() && current.startsWith(h)
    }
}
