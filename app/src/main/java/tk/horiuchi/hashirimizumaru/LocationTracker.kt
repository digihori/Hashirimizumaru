package tk.horiuchi.hashirimizumaru

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationTracker(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val _location = MutableStateFlow<Location?>(null)
    val location = _location.asStateFlow()
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun start(powerSaving: Boolean) {
        if (callback != null) return
        val request = LocationRequest.Builder(
            if (powerSaving) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY,
            if (powerSaving) 15_000 else 5_000
        ).setMinUpdateDistanceMeters(if (powerSaving) 30f else 5f)
            .setMinUpdateIntervalMillis(if (powerSaving) 10_000 else 2_000)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { _location.value = it }
            }
        }
        client.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
    }

    fun stop() {
        callback?.let(client::removeLocationUpdates)
        callback = null
    }
}
