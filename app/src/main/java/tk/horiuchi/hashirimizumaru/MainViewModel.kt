package tk.horiuchi.hashirimizumaru

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NavInfo(val distanceMeters: Float, val bearing: Float)
data class MapFocus(val waypoint: Waypoint, val requestId: Long)
data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Double)
data class TrackFocus(val sessionId: Long, val requestId: Long)
data class NavigationStart(val latitude: Double, val longitude: Double)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = (app as HashirimizumaruApp).database.dao()
    private val tracker = LocationTracker(app)
    private val contourRepository = ContourRepository(app)
    private val _contours = MutableStateFlow<ContourState>(ContourState.Idle)
    val contours = _contours.asStateFlow()
    val waypoints = dao.waypoints().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val catches = dao.catches().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trackSessions = dao.trackSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeTrackSession = dao.activeTrackSession()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val tracks = dao.tracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val location = tracker.location
    val destination = MutableStateFlow<Waypoint?>(null)
    val pendingDestination = MutableStateFlow<Waypoint?>(null)
    val navigationStart = MutableStateFlow<NavigationStart?>(null)
    val powerSaving = MutableStateFlow(true)
    val recording = MutableStateFlow(false)
    val showContours = MutableStateFlow(false)
    val showSeaMarks = MutableStateFlow(true)
    val showTracks = MutableStateFlow(true)
    val followLocation = MutableStateFlow(true)
    val mapFocus = MutableStateFlow<MapFocus?>(null)
    val mapCamera = MutableStateFlow<MapCamera?>(null)
    val selectedTrackSessionId = MutableStateFlow<Long?>(null)
    val trackFocus = MutableStateFlow<TrackFocus?>(null)
    val interruptedTrackSession = MutableStateFlow<TrackSession?>(null)
    private val bufferedTracks = MutableStateFlow<List<TrackPoint>>(emptyList())
    val allTrackPoints = combine(tracks, bufferedTracks) { saved, buffered ->
        (saved + buffered)
            .distinctBy { it.id.takeIf { id -> id != 0L } ?: -it.time }
            .sortedBy { it.time }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val displayedTracks = combine(
        allTrackPoints,
        activeTrackSession,
        selectedTrackSessionId
    ) { points, active, selected ->
        val visibleSessionIds = setOfNotNull(active?.id, selected)
        points.filter { it.sessionId in visibleSessionIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var mapFocusRequestId = 0L
    private var trackFocusRequestId = 0L
    private var bufferJob: Job? = null
    private var currentRecordingSession: TrackSession? = null
    private var recordingStartedByNavigation = false
    private val trackBuffer = mutableListOf<TrackPoint>()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            contourRepository.cached()?.let { _contours.value = it }
        }
        viewModelScope.launch {
            dao.activeTrackSession().first()?.let { interruptedTrackSession.value = it }
        }
    }

    val navInfo = combine(location, destination, ::navigationInfo)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pendingNavInfo = combine(location, pendingDestination, ::navigationInfo)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startLocation() = tracker.start(powerSaving.value)
    fun restartLocation() { tracker.stop(); tracker.start(powerSaving.value) }
    fun stopLocation() = tracker.stop()
    fun togglePowerSaving() { powerSaving.value = !powerSaving.value; restartLocation() }

    fun saveWaypoint(value: Waypoint) = viewModelScope.launch { dao.saveWaypoint(value) }
    fun deleteWaypoint(value: Waypoint) = viewModelScope.launch {
        if (destination.value?.id == value.id) stopNavigation()
        if (pendingDestination.value?.id == value.id) cancelNavigationPreview()
        dao.deleteWaypoint(value)
    }
    fun saveCatch(value: CatchRecord) = viewModelScope.launch { dao.saveCatch(value) }
    fun focusOnMap(waypoint: Waypoint) {
        mapFocus.value = MapFocus(waypoint, ++mapFocusRequestId)
    }
    fun saveMapCamera(latitude: Double, longitude: Double, zoom: Double) {
        mapCamera.value = MapCamera(latitude, longitude, zoom)
    }
    fun consumeMapFocus(requestId: Long) {
        if (mapFocus.value?.requestId == requestId) mapFocus.value = null
    }

    fun loadContours(forceRefresh: Boolean = false) {
        if (_contours.value is ContourState.Loading) return
        if (!forceRefresh && _contours.value is ContourState.Ready) return
        viewModelScope.launch {
            _contours.value = ContourState.Loading
            _contours.value = runCatching { contourRepository.download() }
                .getOrElse { error ->
                    contourRepository.cached()
                        ?: ContourState.Error(error.message ?: "等深線を取得できませんでした")
                }
        }
    }

    fun toggleRecording() {
        if (recording.value) stopRecording() else startRecording(startedByNavigation = false)
    }

    private fun startRecording(startedByNavigation: Boolean) {
        if (recording.value) return
        recording.value = true
        recordingStartedByNavigation = startedByNavigation
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val newSession = TrackSession(
                name = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
                    .format(Date(startedAt)),
                startedAt = startedAt,
                startedByNavigation = startedByNavigation
            )
            val sessionId = dao.saveTrackSession(newSession)
            currentRecordingSession = newSession.copy(id = sessionId)
            selectedTrackSessionId.value = sessionId
            if (recording.value) {
                collectTrackPoints(sessionId)
            } else {
                dao.updateTrackSession(
                    currentRecordingSession!!.copy(endedAt = System.currentTimeMillis())
                )
                currentRecordingSession = null
            }
        }
    }

    private fun collectTrackPoints(sessionId: Long) {
        bufferJob?.cancel()
        bufferJob = viewModelScope.launch {
            var lastSavedAt = 0L
            location.filterNotNull().collect { point ->
                val now = System.currentTimeMillis()
                if (now - lastSavedAt >= 15_000) {
                    trackBuffer += TrackPoint(
                        sessionId = sessionId,
                        latitude = point.latitude,
                        longitude = point.longitude
                    )
                    bufferedTracks.value = trackBuffer.toList()
                    lastSavedAt = now
                }
                if (trackBuffer.size >= 4) flushTracks()
            }
        }
    }

    fun stopRecording() {
        if (!recording.value) return
        recording.value = false
        bufferJob?.cancel()
        bufferJob = null
        viewModelScope.launch {
            flushTracks()
            (currentRecordingSession ?: activeTrackSession.value)?.let { session ->
                dao.updateTrackSession(session.copy(endedAt = System.currentTimeMillis()))
            }
            currentRecordingSession = null
            recordingStartedByNavigation = false
        }
    }

    fun previewNavigation(waypoint: Waypoint) {
        pendingDestination.value = waypoint
    }

    fun cancelNavigationPreview() {
        pendingDestination.value = null
        mapFocus.value = null
    }

    fun confirmNavigation() {
        val waypoint = pendingDestination.value ?: return
        val distance = pendingNavInfo.value?.distanceMeters
        if (distance != null && distance <= 30f) return
        pendingDestination.value = null
        destination.value = waypoint
        navigationStart.value = location.value?.let {
            NavigationStart(it.latitude, it.longitude)
        }
        if (navigationStart.value == null) {
            viewModelScope.launch {
                val firstLocation = location.filterNotNull().first()
                if (destination.value?.id == waypoint.id) {
                    navigationStart.value =
                        NavigationStart(firstLocation.latitude, firstLocation.longitude)
                }
            }
        }
        if (!recording.value) startRecording(startedByNavigation = true)
        followLocation.value = true
        messages.tryEmit("ポイント「${waypoint.name}」へのナビを開始しました")
    }

    fun stopNavigation() {
        destination.value = null
        pendingDestination.value = null
        navigationStart.value = null
        if (recordingStartedByNavigation) stopRecording()
    }

    fun resumeInterruptedTrack() {
        val session = interruptedTrackSession.value ?: return
        interruptedTrackSession.value = null
        currentRecordingSession = session
        recordingStartedByNavigation = false
        recording.value = true
        selectedTrackSessionId.value = session.id
        collectTrackPoints(session.id)
    }

    fun finishInterruptedTrack() {
        val session = interruptedTrackSession.value ?: return
        interruptedTrackSession.value = null
        viewModelScope.launch {
            dao.updateTrackSession(session.copy(endedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun flushTracks() {
        if (trackBuffer.isEmpty()) return
        val copy = trackBuffer.toList()
        dao.saveTracks(copy)
        trackBuffer.clear()
        bufferedTracks.value = emptyList()
    }

    fun updateTrackSession(value: TrackSession) =
        viewModelScope.launch { dao.updateTrackSession(value) }

    fun deleteTrackSession(value: TrackSession) = viewModelScope.launch {
        if (activeTrackSession.value?.id == value.id) stopRecording()
        dao.deleteTracksForSession(value.id)
        dao.deleteTrackSession(value)
        if (selectedTrackSessionId.value == value.id) selectedTrackSessionId.value = null
    }

    fun showTrackOnMap(sessionId: Long) {
        selectedTrackSessionId.value = sessionId
        trackFocus.value = TrackFocus(sessionId, ++trackFocusRequestId)
        followLocation.value = false
    }

    fun consumeTrackFocus(requestId: Long) {
        if (trackFocus.value?.requestId == requestId) trackFocus.value = null
    }

    override fun onCleared() {
        tracker.stop()
        bufferJob?.cancel()
        super.onCleared()
    }
}

private fun navigationInfo(here: Location?, target: Waypoint?): NavInfo? {
    if (here == null || target == null) return null
    val result = FloatArray(2)
    Location.distanceBetween(
        here.latitude,
        here.longitude,
        target.latitude,
        target.longitude,
        result
    )
    return NavInfo(result[0], (result[1] + 360f) % 360f)
}
