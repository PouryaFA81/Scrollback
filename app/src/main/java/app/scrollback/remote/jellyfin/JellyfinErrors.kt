package app.scrollback.remote.jellyfin

import app.scrollback.remote.HttpException

/** Turns network/HTTP failures into short, human messages. Raw exception text is never shown. */
object JellyfinErrors {
    fun friendly(e: Throwable): String = when (e) {
        is HttpException -> when (e.code) {
            401 -> "Your Jellyfin sign-in expired. Sign in again under Settings → Servers."
            403 -> "This Jellyfin account isn't allowed to open that."
            404 -> e.message?.substringAfter(": ", "")?.takeIf { it.isNotBlank() && it.startsWith("Jellyfin").not() }
                ?: "Jellyfin couldn't find that. It may have been removed."
            in 500..599 -> "Jellyfin had a problem answering. Try again in a moment."
            else -> "Jellyfin answered with an error (${e.code}). Try again in a moment."
        }
        is java.net.UnknownHostException -> "Can't find your Jellyfin server. Check the address or your connection."
        is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "Can't reach your Jellyfin server. Make sure it's running and this phone is on the same network."
        is java.net.SocketTimeoutException -> "Your Jellyfin server is taking too long to answer. Try again in a moment."
        is javax.net.ssl.SSLException -> "Couldn't make a secure connection to Jellyfin (certificate problem)."
        is java.io.IOException -> "Lost the connection to your Jellyfin server. Make sure it's running, then try again."
        is org.json.JSONException -> "Jellyfin sent something Scrollback didn't understand. Is the address really a Jellyfin server?"
        else -> "Something went wrong while loading from Jellyfin. Try again in a moment."
    }
}
