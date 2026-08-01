package tk.horiuchi.hashirimizumaru

import android.app.Application
import android.location.Location
import android.net.Uri
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
data class CatchFocus(val catchId: Long, val requestId: Long)
data class NavigationStart(val latitude: Double, val longitude: Double)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val dao = (app as HashirimizumaruApp).database.dao()
    private val tracker = LocationTracker(app)
    private val contourRepository = ContourRepository(app)
    private val catchPhotoRepository = CatchPhotoRepository(app)
    private val backupRepository = BackupRepository(app, dao)
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
    val showContours = MutableStateFlow(true)
    val showSeaMarks = MutableStateFlow(true)
    val showTracks = MutableStateFlow(true)
    val showTide = MutableStateFlow(true)
    val showCatches = MutableStateFlow(true)
    val followLocation = MutableStateFlow(true)
    val mapFocus = MutableStateFlow<MapFocus?>(null)
    val mapCamera = MutableStateFlow<MapCamera?>(null)
    val selectedTrackSessionId = MutableStateFlow<Long?>(null)
    val trackFocus = MutableStateFlow<TrackFocus?>(null)
    val catchFocus = MutableStateFlow<CatchFocus?>(null)
    val selectedCatchId = MutableStateFlow<Long?>(null)
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
    private var catchFocusRequestId = 0L
    private var bufferJob: Job? = null
    private var currentRecordingSession: TrackSession? = null
    private var recordingStartedByNavigation = false
    private val trackBuffer = mutableListOf<TrackPoint>()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    fun backupAllowed() = !recording.value && destination.value == null && pendingDestination.value == null
    suspend fun backupSummary(): BackupSummary = backupRepository.summary()
    suspend fun writeBackup(uri: Uri): BackupSummary {
        check(backupAllowed()) { "ナビまたは航跡記録を終了してから実行してください" }
        return backupRepository.write(uri)
    }
    suspend fun prepareRestore(uri: Uri): PreparedBackup {
        check(backupAllowed()) { "ナビまたは航跡記録を終了してから実行してください" }
        return backupRepository.prepare(uri)
    }
    suspend fun restoreBackup(value: PreparedBackup): BackupSummary {
        check(backupAllowed()) { "ナビまたは航跡記録を終了してから実行してください" }
        return backupRepository.restore(value)
    }

    init {
        viewModelScope.launch {
            contourRepository.cached()?.let { _contours.value = it }
        }
        viewModelScope.launch {
            dao.activeTrackSession().first()?.let { session ->
                val status = LocationTrackingService.status(appContext)
                if (status?.sessionId == session.id) {
                    currentRecordingSession = session
                    recording.value = true
                    recordingStartedByNavigation = status.startedByNavigation
                    selectedTrackSessionId.value = session.id
                    status.destination?.let { restoredDestination ->
                        destination.value = restoredDestination
                        location.filterNotNull().first().let {
                            navigationStart.value =
                                NavigationStart(it.latitude, it.longitude)
                        }
                    }
                } else {
                    interruptedTrackSession.value = session
                }
            }
        }
        viewModelScope.launch {
            BackgroundTrackingEvents.arrivals.collect { arrival ->
                if (destination.value?.id == arrival.destinationId) {
                    val name = destination.value?.name.orEmpty()
                    destination.value = null
                    navigationStart.value = null
                    if (arrival.stoppedRecording) {
                        recording.value = false
                        currentRecordingSession = null
                        recordingStartedByNavigation = false
                    }
                    messages.emit("ポイント「$name」に到着しました。ナビを終了します")
                }
            }
        }
        viewModelScope.launch {
            BackgroundTrackingEvents.navigationStopped.collect { stoppedDestinationId ->
                if (destination.value?.id == stoppedDestinationId) {
                    destination.value = null
                    navigationStart.value = null
                }
            }
        }
        viewModelScope.launch {
            BackgroundTrackingEvents.recordingStopped.collect {
                recording.value = false
                currentRecordingSession = null
                recordingStartedByNavigation = false
            }
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
    fun deleteCatch(value: CatchRecord) = viewModelScope.launch {
        if (selectedCatchId.value == value.id) clearSelectedCatch()
        dao.deleteCatch(value)
        catchPhotoRepository.delete(value.photoUri)
    }
    suspend fun importCatchPhoto(
        uri: Uri,
        allowLocationMetadata: Boolean
    ): ImportedCatchPhoto = catchPhotoRepository.import(uri, allowLocationMetadata)
    fun catchPhotoFile(relativePath: String?) = catchPhotoRepository.file(relativePath)
    fun discardCatchPhoto(relativePath: String?) = catchPhotoRepository.delete(relativePath)
    fun focusCatchOnMap(value: CatchRecord) {
        showCatches.value = true
        selectedCatchId.value = value.id
        catchFocus.value = CatchFocus(value.id, ++catchFocusRequestId)
        followLocation.value = false
    }
    fun selectCatchOnMap(id: Long) { selectedCatchId.value = id }
    fun clearSelectedCatch() { selectedCatchId.value = null }
    fun consumeCatchFocus(requestId: Long) {
        if (catchFocus.value?.requestId == requestId) catchFocus.value = null
    }
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
        if (recording.value && destination.value != null) {
            messages.tryEmit("ナビ中は航跡記録を停止できません。先にナビを終了してください")
            return
        }
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
                LocationTrackingService.start(
                    appContext,
                    sessionId,
                    startedByNavigation,
                    destination.takeIf { startedByNavigation }?.value
                )
            } else {
                dao.updateTrackSession(
                    currentRecordingSession!!.copy(endedAt = System.currentTimeMillis())
                )
                currentRecordingSession = null
            }
        }
    }

    fun stopRecording() {
        if (!recording.value) return
        recording.value = false
        LocationTrackingService.stopRecording(appContext)
        currentRecordingSession = null
        recordingStartedByNavigation = false
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
        if (!recording.value) {
            startRecording(startedByNavigation = true)
        } else {
            LocationTrackingService.setNavigation(appContext, waypoint)
        }
        followLocation.value = true
        messages.tryEmit("ポイント「${waypoint.name}」へのナビを開始しました")
    }

    fun stopNavigation() {
        val autoRecording = recordingStartedByNavigation
        LocationTrackingService.stopNavigation(appContext)
        destination.value = null
        pendingDestination.value = null
        navigationStart.value = null
        if (autoRecording) {
            recording.value = false
            currentRecordingSession = null
            recordingStartedByNavigation = false
        }
    }

    fun resumeInterruptedTrack() {
        val session = interruptedTrackSession.value ?: return
        interruptedTrackSession.value = null
        currentRecordingSession = session
        recordingStartedByNavigation = false
        recording.value = true
        selectedTrackSessionId.value = session.id
        LocationTrackingService.start(
            appContext,
            session.id,
            startedByNavigation = false
        )
    }

    fun finishInterruptedTrack() {
        val session = interruptedTrackSession.value ?: return
        interruptedTrackSession.value = null
        LocationTrackingService.stopRecording(appContext)
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
