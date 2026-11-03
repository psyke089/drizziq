package app.drizziq

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/** Instrumentation-only installation; never selected by an Intent or persisted preference. */
internal object AppPlatform {
    @Volatile var clock: (() -> Long)? = null
    @Volatile var contexts: ((Context) -> Context)? = null
    @Volatile var connections: ((URL) -> HttpURLConnection)? = null
    @Volatile var connected: Boolean? = null

    fun now(): Long = clock?.invoke() ?: System.currentTimeMillis()

    fun context(base: Context): Context = contexts?.invoke(base) ?: base

    fun open(url: URL): HttpURLConnection =
        connections?.invoke(url) ?: url.openConnection() as HttpURLConnection

    fun online(context: Context): Boolean {
        connected?.let {
            return it
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun reset() {
        clock = null
        contexts = null
        connections = null
        connected = null
    }
}
