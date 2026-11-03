package app.drizziq

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/** Production platform inputs; release builds have no fixture override. */
internal object AppPlatform {
    fun now(): Long = System.currentTimeMillis()

    fun context(base: Context): Context = base

    fun open(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
