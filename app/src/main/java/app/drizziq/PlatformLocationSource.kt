package app.drizziq

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** Uses only permission-eligible enabled platform providers; never changes settings. */
class PlatformLocationSource(private val context: Context) : PositionSource {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun enabledProviders(): List<String> {
        val source = manager ?: return emptyList()
        val enabled =
            source.allProviders
                .filter {
                    it in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER) &&
                        source.isProviderEnabled(it)
                }
                .toSet()
        return FixPolicy.providers(
            granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            granted(Manifest.permission.ACCESS_FINE_LOCATION),
            enabled,
        )
    }

    private fun Location.fix(provider: String) =
        PositionFix(
            latitude,
            longitude,
            if (hasAccuracy()) accuracy.toDouble() else null,
            elapsedRealtimeNanos,
            provider,
        )

    // Eligibility is rechecked immediately before each permission-protected platform call.
    @SuppressLint("MissingPermission")
    override fun lastKnown(provider: String): PositionFix? {
        if (provider !in enabledProviders()) throw SecurityException("Location access changed")
        return manager?.getLastKnownLocation(provider)?.fix(provider)
    }

    @SuppressLint("MissingPermission")
    override fun listen(
        provider: String,
        onFix: (PositionFix) -> Unit,
        onUnavailable: () -> Unit,
    ): Cancel {
        val source = manager ?: error("Location unavailable")
        if (provider !in enabledProviders()) throw SecurityException("Location access changed")
        val listener =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onFix(location.fix(provider))
                }

                override fun onProviderDisabled(name: String) {
                    onUnavailable()
                }

                override fun onProviderEnabled(name: String) {}

                @Deprecated("Platform compatibility callback")
                override fun onStatusChanged(name: String?, status: Int, extras: Bundle?) {}
            }
        try {
            source.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (error: Exception) {
            runCatching { source.removeUpdates(listener) }
            throw error
        }
        return Cancel { source.removeUpdates(listener) }
    }
}
