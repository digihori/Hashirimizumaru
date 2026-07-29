package tk.horiuchi.hashirimizumaru

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NavInfo(val distanceMeters: Float, val bearing: Float)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = (app as HashirimizumaruApp).database.dao()
    private val tracker = LocationTracker(app)
    val waypoints = dao.waypoints().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val catches = dao.catches().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tracks = dao.tracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val location = tracker.location
    val destination = MutableStateFlow<Waypoint?>(null)
    val powerSaving = MutableStateFlow(true)
    val recording = MutableStateFlow(false)
    private var bufferJob: Job? = null
    private val trackBuffer = mutableListOf<TrackPoint>()

    val navInfo = combine(location, destination) { here, target ->
        if (here == null || target == null) null else {
            val result = FloatArray(2)
            Location.distanceBetween(here.latitude, here.longitude, target.latitude, target.longitude, result)
            NavInfo(result[0], (result[1] + 360f) % 360f)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startLocation() = tracker.start(powerSaving.value)
    fun restartLocation() { tracker.stop(); tracker.start(powerSaving.value) }
    fun stopLocation() = tracker.stop()
    fun togglePowerSaving() { powerSaving.value = !powerSaving.value; restartLocation() }

    fun saveWaypoint(value: Waypoint) = viewModelScope.launch { dao.saveWaypoint(value) }
    fun deleteWaypoint(value: Waypoint) = viewModelScope.launch {
        if (destination.value?.id == value.id) destination.value = null
        dao.deleteWaypoint(value)
    }
    fun saveCatch(value: CatchRecord) = viewModelScope.launch { dao.saveCatch(value) }

    fun toggleRecording() {
        recording.value = !recording.value
        if (recording.value) {
            bufferJob = viewModelScope.launch {
                var lastSavedAt = 0L
                location.filterNotNull().collect { point ->
                    val now = System.currentTimeMillis()
                    if (now - lastSavedAt >= 15_000) {
                        trackBuffer += TrackPoint(latitude = point.latitude, longitude = point.longitude)
                        lastSavedAt = now
                    }
                    if (trackBuffer.size >= 8) flushTracks()
                }
            }
        } else {
            bufferJob?.cancel()
            viewModelScope.launch { flushTracks() }
        }
    }
    private suspend fun flushTracks() {
        if (trackBuffer.isEmpty()) return
        val copy = trackBuffer.toList()
        trackBuffer.clear()
        dao.saveTracks(copy)
    }
    fun clearTracks() = viewModelScope.launch { trackBuffer.clear(); dao.clearTracks() }
    override fun onCleared() { tracker.stop(); super.onCleared() }
}
