package tk.horiuchi.hashirimizumaru

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private const val MIN_LAT = 35.235
private const val MAX_LAT = 35.340
private const val MIN_LON = 139.650
private const val MAX_LON = 139.820

@Composable
fun MapScreen(
    vm: MainViewModel,
    onCancelNavigationPreview: () -> Unit = {},
    onToggleRecording: () -> Unit = vm::toggleRecording,
    onConfirmNavigation: () -> Unit = vm::confirmNavigation
) {
    val location by vm.location.collectAsStateWithLifecycle()
    val nav by vm.navInfo.collectAsStateWithLifecycle()
    val destination by vm.destination.collectAsStateWithLifecycle()
    val pendingDestination by vm.pendingDestination.collectAsStateWithLifecycle()
    val pendingNav by vm.pendingNavInfo.collectAsStateWithLifecycle()
    val navigationStart by vm.navigationStart.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val displayedTracks by vm.displayedTracks.collectAsStateWithLifecycle()
    val activeTrackSession by vm.activeTrackSession.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val powerSaving by vm.powerSaving.collectAsStateWithLifecycle()
    val contours by vm.contours.collectAsStateWithLifecycle()
    val waypoints by vm.waypoints.collectAsStateWithLifecycle()
    val showSeaMarks by vm.showSeaMarks.collectAsStateWithLifecycle()
    val showContours by vm.showContours.collectAsStateWithLifecycle()
    val showTracks by vm.showTracks.collectAsStateWithLifecycle()
    val showTide by vm.showTide.collectAsStateWithLifecycle()
    val followLocation by vm.followLocation.collectAsStateWithLifecycle()
    val mapFocus by vm.mapFocus.collectAsStateWithLifecycle()
    val trackFocus by vm.trackFocus.collectAsStateWithLifecycle()
    val savedMapCamera by vm.mapCamera.collectAsStateWithLifecycle()
    var layersOpen by remember { mutableStateOf(false) }
    var addWaypoint by remember { mutableStateOf(false) }
    var mapWaypointLocation by remember { mutableStateOf<LatLng?>(null) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(showContours) {
        if (showContours) vm.loadContours()
    }

    Box(Modifier.fillMaxSize()) {
        MapLibreView(
            location = location,
            seaMarks = showSeaMarks,
            contourGeoJson = (contours as? ContourState.Ready)?.geoJson.takeIf { showContours },
            waypoints = waypoints,
            destinationId = destination?.id ?: pendingDestination?.id,
            navigationStart = navigationStart,
            navigationDestination = destination,
            trackPoints = displayedTracks.takeIf { showTracks }.orEmpty(),
            activeTrackSessionId = activeTrackSession?.id,
            followLocation = followLocation,
            recenterRequest = recenterRequest,
            mapFocus = mapFocus,
            trackFocus = trackFocus,
            savedCamera = savedMapCamera,
            onCameraChanged = vm::saveMapCamera,
            onFollowLocationChanged = { vm.followLocation.value = it },
            onMapFocusHandled = vm::consumeMapFocus,
            onTrackFocusHandled = vm::consumeTrackFocus,
            onMapLongPress = { mapWaypointLocation = it }
        )
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .zIndex(2f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { layersOpen = !layersOpen },
                label = { Text("レイヤ") },
                leadingIcon = { Icon(Icons.Default.Layers, null) }
            )
            Text(
                "地図を長押ししてポイント登録",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .background(Color(0xCC06171E), MaterialTheme.shapes.small)
                    .padding(6.dp)
            )
            if (layersOpen) {
                Surface(color = Color(0xE6112730), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(showSeaMarks, { vm.showSeaMarks.value = it })
                            Text("シーマーク")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(showContours, { vm.showContours.value = it })
                            Text("等深線")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(showTracks, { vm.showTracks.value = it })
                            Text("航跡")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(showTide, { vm.showTide.value = it })
                            Text("タイドグラフ")
                        }
                        if (showTide) {
                            Text(
                                "海上保安庁の横須賀調和定数による推算値。航行判断には使用しないでください",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (showContours) {
                            when (val state = contours) {
                                ContourState.Idle, ContourState.Loading ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("20・50・100m線を取得中", style = MaterialTheme.typography.labelSmall)
                                    }
                                is ContourState.Ready -> {
                                    Text(
                                        if (state.fromCache) "20・50・100m線・保存データ" else "20・50・100m線・更新済み",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    ContourLegend()
                                    TextButton(onClick = { vm.loadContours(forceRefresh = true) }) {
                                        Text("再取得")
                                    }
                                }
                                is ContourState.Error -> {
                                    Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                    TextButton(onClick = { vm.loadContours(forceRefresh = true) }) {
                                        Text("再試行")
                                    }
                                }
                            }
                            Text(
                                "海しるAPIを利用（海上保安庁による保証なし）",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            FilledTonalIconButton(
                onClick = {
                    vm.followLocation.value = true
                    recenterRequest++
                },
                enabled = location != null
            ) {
                Icon(
                    if (followLocation) Icons.Default.GpsFixed else Icons.Default.MyLocation,
                    if (followLocation) "現在位置を追従中" else "現在位置の追従を再開"
                )
            }
            FilledTonalIconButton(onClick = vm::togglePowerSaving) {
                Icon(if (powerSaving) Icons.Default.BatterySaver else Icons.Default.GpsFixed, "省電力")
            }
            FilledTonalIconButton(onClick = onToggleRecording) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.Route, "航跡記録")
            }
            if (BuildConfig.DEBUG) {
                Surface(color = Color(0xDD06171E), shape = MaterialTheme.shapes.small) {
                    Text(
                        location?.let {
                            "GPS\n%.6f\n%.6f\n±%.0fm".format(
                                Locale.JAPAN,
                                it.latitude,
                                it.longitude,
                                it.accuracy
                            )
                        } ?: "GPS\n未受信",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
            if (showTide) TidePanel()
        }
        if (destination != null && nav != null) {
            NavigationPanel(
                destination!!.name,
                nav!!,
                onStop = vm::stopNavigation,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp)
            )
        }
        if (pendingDestination != null) {
            NavigationConfirmationPanel(
                waypoint = pendingDestination!!,
                nav = pendingNav,
                onConfirm = {
                    onConfirmNavigation()
                    recenterRequest++
                },
                onCancel = {
                    vm.cancelNavigationPreview()
                    onCancelNavigationPreview()
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 84.dp
                )
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = { if (location != null) addWaypoint = true }, enabled = location != null) {
                Icon(Icons.Default.AddLocation, null); Spacer(Modifier.width(6.dp)); Text("ポイント")
            }
            AssistChip(onClick = {}, label = {
                Text("${tracks.size}点${if (recording) "・記録中" else ""}")
            })
        }
        MapScaleBar(
            latitude = savedMapCamera?.latitude
                ?: location?.latitude
                ?: 35.2708,
            zoom = savedMapCamera?.zoom ?: 12.5,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = 12.dp,
                    bottom = when {
                        pendingDestination != null -> 250.dp
                        destination != null -> 180.dp
                        else -> 82.dp
                    }
                )
        )
        MapAttribution(
            showSeaMarks = showSeaMarks,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 8.dp,
                    bottom = when {
                        pendingDestination != null -> 250.dp
                        destination != null -> 180.dp
                        else -> 76.dp
                    }
                )
        )
    }
    if (addWaypoint && location != null) {
        WaypointEditor(
            initial = Waypoint(name = "", latitude = location!!.latitude, longitude = location!!.longitude),
            onDismiss = { addWaypoint = false },
            onSave = { vm.saveWaypoint(it); addWaypoint = false }
        )
    }
    mapWaypointLocation?.let { point ->
        WaypointEditor(
            initial = Waypoint(name = "", latitude = point.latitude, longitude = point.longitude),
            onDismiss = { mapWaypointLocation = null },
            onSave = {
                vm.saveWaypoint(it)
                mapWaypointLocation = null
            }
        )
    }
}

@Composable
private fun MapAttribution(
    showSeaMarks: Boolean,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = modifier,
        color = Color(0xD906171E),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.clickable {
                    uriHandler.openUri("https://www.openstreetmap.org/copyright")
                }
            )
            if (showSeaMarks) {
                Text(" / ", style = MaterialTheme.typography.labelSmall, color = Color.White)
                Text(
                    "OpenSeaMap",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://www.openseamap.org/")
                    }
                )
            }
        }
    }
}

@Composable
private fun MapScaleBar(
    latitude: Double,
    zoom: Double,
    modifier: Modifier = Modifier
) {
    val maximumWidth = 56.dp
    val metersPerDp =
        78271.51696 * cos(Math.toRadians(latitude)) / 2.0.pow(zoom)
    val maximumMeters = maximumWidth.value * metersPerDp
    val scaleMeters = niceScaleDistance(maximumMeters)
    val barWidth = (scaleMeters / metersPerDp).toFloat().dp

    Surface(
        modifier = modifier,
        color = Color(0xC906171E),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                scaleDistanceText(scaleMeters),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
            Canvas(Modifier.width(barWidth).height(5.dp)) {
                val stroke = 1.dp.toPx()
                val halfStroke = stroke / 2f
                val y = halfStroke
                drawLine(
                    color = Color.White,
                    start = androidx.compose.ui.geometry.Offset(halfStroke, y),
                    end = androidx.compose.ui.geometry.Offset(size.width - halfStroke, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square
                )
                drawLine(
                    color = Color.White,
                    start = androidx.compose.ui.geometry.Offset(halfStroke, y),
                    end = androidx.compose.ui.geometry.Offset(halfStroke, size.height),
                    strokeWidth = stroke
                )
                drawLine(
                    color = Color.White,
                    start = androidx.compose.ui.geometry.Offset(size.width - halfStroke, y),
                    end = androidx.compose.ui.geometry.Offset(size.width - halfStroke, size.height),
                    strokeWidth = stroke
                )
            }
        }
    }
}

private fun niceScaleDistance(maximumMeters: Double): Double {
    if (!maximumMeters.isFinite() || maximumMeters <= 0.0) return 100.0
    val magnitude = 10.0.pow(floor(log10(maximumMeters)))
    val normalized = maximumMeters / magnitude
    val nice = when {
        normalized >= 5.0 -> 5.0
        normalized >= 2.0 -> 2.0
        else -> 1.0
    }
    return nice * magnitude
}

private fun scaleDistanceText(meters: Double): String =
    if (meters >= 1000.0) {
        val kilometers = meters / 1000.0
        if (kilometers % 1.0 == 0.0) "${kilometers.toInt()} km"
        else String.format(Locale.JAPAN, "%.1f km", kilometers)
    } else {
        "${meters.roundToInt()} m"
    }

@Composable
private fun MapLibreView(
    location: Location?,
    seaMarks: Boolean,
    contourGeoJson: String?,
    waypoints: List<Waypoint>,
    destinationId: Long?,
    navigationStart: NavigationStart?,
    navigationDestination: Waypoint?,
    trackPoints: List<TrackPoint>,
    activeTrackSessionId: Long?,
    followLocation: Boolean,
    recenterRequest: Int,
    mapFocus: MapFocus?,
    trackFocus: TrackFocus?,
    savedCamera: MapCamera?,
    onCameraChanged: (Double, Double, Double) -> Unit,
    onFollowLocationChanged: (Boolean) -> Unit,
    onMapFocusHandled: (Long) -> Unit,
    onTrackFocusHandled: (Long) -> Unit,
    onMapLongPress: (LatLng) -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { mutableStateOf<MapView?>(null) }
    val currentLongPressHandler by rememberUpdatedState(onMapLongPress)
    val currentCameraHandler by rememberUpdatedState(onCameraChanged)
    val currentFollowLocationHandler by rememberUpdatedState(onFollowLocationChanged)
    val currentFocusHandledHandler by rememberUpdatedState(onMapFocusHandled)
    val currentTrackFocusHandledHandler by rememberUpdatedState(onTrackFocusHandled)
    var centeredOnFirstLocation by remember { mutableStateOf(savedCamera != null) }
    var handledRecenterRequest by remember { mutableIntStateOf(recenterRequest) }
    var handledMapFocusRequest by remember { mutableLongStateOf(0L) }
    var handledTrackFocusRequest by remember { mutableLongStateOf(0L) }
    val updateLocationSource: (Style) -> Unit = { style ->
        location?.let { current ->
            style.getSourceAs<GeoJsonSource>("current-location")
                ?.setGeoJson(
                    Feature.fromGeometry(
                        Point.fromLngLat(current.longitude, current.latitude)
                    )
                )
        }
    }
    val styleJson = remember(
        seaMarks,
        contourGeoJson,
        waypoints,
        destinationId,
        navigationStart,
        navigationDestination,
        trackPoints,
        activeTrackSessionId
    ) {
        mapStyle(
            seaMarks,
            contourGeoJson,
            waypoints,
            destinationId,
            navigationStart,
            navigationDestination,
            trackPoints,
            activeTrackSessionId
        )
    }
    AndroidView(
        factory = { context ->
            MapView(context).also { view ->
                mapView.value = view
                view.tag = styleJson.hashCode()
                view.onCreate(null)
                view.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(styleJson), updateLocationSource)
                    map.cameraPosition = CameraPosition.Builder()
                        .target(
                            savedCamera?.let { LatLng(it.latitude, it.longitude) }
                                ?: LatLng(35.2708, 139.7305)
                        )
                        .zoom(savedCamera?.zoom ?: 12.5)
                        .build()
                    map.setLatLngBoundsForCameraTarget(
                        org.maplibre.android.geometry.LatLngBounds.from(MAX_LAT, MAX_LON, MIN_LAT, MIN_LON)
                    )
                    map.setMinZoomPreference(11.5)
                    map.setMaxZoomPreference(18.0)
                    map.addOnMapLongClickListener { point ->
                        if (point.latitude in MIN_LAT..MAX_LAT &&
                            point.longitude in MIN_LON..MAX_LON
                        ) {
                            currentLongPressHandler(point)
                        }
                        true
                    }
                    map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                        override fun onMoveBegin(detector: MoveGestureDetector) {
                            currentFollowLocationHandler(false)
                        }

                        override fun onMove(detector: MoveGestureDetector) = Unit

                        override fun onMoveEnd(detector: MoveGestureDetector) = Unit
                    })
                    map.addOnCameraIdleListener {
                        val camera = map.cameraPosition
                        currentCameraHandler(
                            camera.target?.latitude ?: return@addOnCameraIdleListener,
                            camera.target?.longitude ?: return@addOnCameraIdleListener,
                            camera.zoom
                        )
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { map ->
                if (view.tag != styleJson.hashCode()) {
                    view.tag = styleJson.hashCode()
                    map.setStyle(Style.Builder().fromJson(styleJson), updateLocationSource)
                }
                map.style?.let(updateLocationSource)
                val shouldCenter = followLocation ||
                    !centeredOnFirstLocation || handledRecenterRequest != recenterRequest
                location?.takeIf {
                    it.latitude in MIN_LAT..MAX_LAT && it.longitude in MIN_LON..MAX_LON
                }?.takeIf { shouldCenter }?.let {
                    map.easeCamera(
                        CameraUpdateFactory.newLatLng(LatLng(it.latitude, it.longitude)),
                        800
                    )
                    centeredOnFirstLocation = true
                    handledRecenterRequest = recenterRequest
                }
                mapFocus?.takeIf { it.requestId != handledMapFocusRequest }?.let { focus ->
                    currentFollowLocationHandler(false)
                    map.cameraPosition = CameraPosition.Builder(map.cameraPosition)
                        .target(LatLng(focus.waypoint.latitude, focus.waypoint.longitude))
                        .zoom(maxOf(map.cameraPosition.zoom, 15.0))
                        .build()
                    handledMapFocusRequest = focus.requestId
                    currentFocusHandledHandler(focus.requestId)
                }
                trackFocus?.takeIf { it.requestId != handledTrackFocusRequest }?.let { focus ->
                    val focusPoints = trackPoints.filter { it.sessionId == focus.sessionId }
                    when {
                        focusPoints.size == 1 -> map.easeCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(focusPoints.first().latitude, focusPoints.first().longitude),
                                15.0
                            )
                        )
                        focusPoints.size > 1 -> {
                            val bounds = LatLngBounds.Builder()
                                .includes(focusPoints.map { LatLng(it.latitude, it.longitude) })
                                .build()
                            map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
                        }
                    }
                    handledTrackFocusRequest = focus.requestId
                    currentTrackFocusHandledHandler(focus.requestId)
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
    DisposableEffect(lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { mapView.value?.onStart() }
            override fun onResume(owner: LifecycleOwner) { mapView.value?.onResume() }
            override fun onPause(owner: LifecycleOwner) { mapView.value?.onPause() }
            override fun onStop(owner: LifecycleOwner) { mapView.value?.onStop() }
            override fun onDestroy(owner: LifecycleOwner) { mapView.value?.onDestroy() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

private fun mapStyle(
    seaMarks: Boolean,
    contourGeoJson: String?,
    waypoints: List<Waypoint>,
    destinationId: Long?,
    navigationStart: NavigationStart?,
    navigationDestination: Waypoint?,
    trackPoints: List<TrackPoint>,
    activeTrackSessionId: Long?
): String {
    val seamark = if (seaMarks) """,
      "seamarks": {
        "type": "raster",
        "tiles": ["https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png"],
        "attribution": "OpenSeaMap",
        "tileSize": 256,
        "maxzoom": 18
      }""" else ""
    val layer = if (seaMarks) """,
      {"id":"seamarks","type":"raster","source":"seamarks","minzoom":9}""" else ""
    val contourSource = contourGeoJson?.let {
        """,
      "depth-contours":{"type":"geojson","data":$it}"""
    }.orEmpty()
    val contourLayer = if (contourGeoJson != null) """,
      {"id":"depth-contours","type":"line","source":"depth-contours",
       "paint":{
         "line-color":["match",["get","Depth"],20,"#67E8F9",50,"#FBBF24",100,"#FB7185","#FFFFFF"],
         "line-width":["match",["get","Depth"],20,2.8,50,2.4,100,2.2,2.0],
         "line-opacity":0.95,
         "line-dasharray":[2,1.5]
       }}""" else ""
    val waypointData = waypointGeoJson(waypoints, destinationId)
    val waypointSource = """,
      "waypoints":{"type":"geojson","data":$waypointData}"""
    val trackSource = """,
      "tracks":{"type":"geojson","data":${trackGeoJson(trackPoints, activeTrackSessionId)}}"""
    val navigationSource = """,
      "navigation-route":{"type":"geojson","data":${navigationRouteGeoJson(navigationStart, navigationDestination)}}"""
    val trackLayers = """,
      {"id":"tracks","type":"line","source":"tracks",
       "paint":{
         "line-color":["case",["==",["get","active"],true],"#FBBF24","#67E8F9"],
         "line-width":["case",["==",["get","active"],true],5,3],
         "line-opacity":["case",["==",["get","active"],true],0.95,0.7]
       }}"""
    val navigationLayer = """,
      {"id":"navigation-route","type":"line","source":"navigation-route",
       "paint":{
         "line-color":"#FBBF24",
         "line-width":4,
         "line-opacity":0.95,
         "line-dasharray":[2,1.5]
       }}"""
    val waypointLayers = """,
      {"id":"waypoint-halo","type":"circle","source":"waypoints",
       "filter":["==",["get","selected"],true],
       "paint":{"circle-radius":14,"circle-color":"#001F27","circle-stroke-width":3,"circle-stroke-color":"#FBBF24"}},
      {"id":"waypoints","type":"circle","source":"waypoints",
       "paint":{
         "circle-radius":["case",["==",["get","selected"],true],9,7],
         "circle-color":["case",["==",["get","selected"],true],"#FBBF24","#67E8F9"],
         "circle-stroke-width":2,
         "circle-stroke-color":"#06171E"
       }},
      {"id":"waypoint-labels","type":"symbol","source":"waypoints",
       "layout":{
         "text-field":["get","name"],
         "text-font":["Noto Sans Regular"],
         "text-size":14,
         "text-anchor":"top",
         "text-offset":[0,0.9],
         "text-allow-overlap":false,
         "text-optional":true
       },
       "paint":{
         "text-color":"#FFFFFF",
         "text-halo-color":"#06171E",
         "text-halo-width":2
       }}"""
    return """
    {"version":8,"name":"走水丸",
    "glyphs":"https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
    "sources":{
      "base":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"attribution":"© OpenStreetMap contributors","tileSize":256,"maxzoom":19},
      "current-location":{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}
      $seamark
      $contourSource
      $waypointSource
      $trackSource
      $navigationSource
    },"layers":[
      {"id":"background","type":"background","paint":{"background-color":"#071820"}},
      {"id":"base","type":"raster","source":"base","paint":{"raster-saturation":-0.65,"raster-brightness-max":0.72}}
      $layer
      $contourLayer
      $trackLayers
      $navigationLayer
      $waypointLayers
      ,{"id":"current-location-halo","type":"circle","source":"current-location",
        "paint":{"circle-radius":13,"circle-color":"#06171E","circle-opacity":0.75}},
      {"id":"current-location","type":"circle","source":"current-location",
       "paint":{"circle-radius":8,"circle-color":"#FFB300","circle-stroke-width":3,"circle-stroke-color":"#FFFFFF"}}
    ]}
    """.trimIndent()
}

private fun navigationRouteGeoJson(
    start: NavigationStart?,
    destination: Waypoint?
): String {
    if (start == null || destination == null) {
        return """{"type":"FeatureCollection","features":[]}"""
    }
    return """
        {
          "type":"FeatureCollection",
          "features":[{
            "type":"Feature",
            "geometry":{
              "type":"LineString",
              "coordinates":[
                [${start.longitude},${start.latitude}],
                [${destination.longitude},${destination.latitude}]
              ]
            },
            "properties":{}
          }]
        }
    """.trimIndent()
}

private fun trackGeoJson(
    points: List<TrackPoint>,
    activeTrackSessionId: Long?
): String {
    val features = points
        .groupBy { it.sessionId }
        .mapNotNull { (sessionId, sessionPoints) ->
            if (sessionPoints.size < 2) return@mapNotNull null
            val coordinates = sessionPoints
                .sortedBy { it.time }
                .joinToString(",") { "[${it.longitude},${it.latitude}]" }
            """
            {
              "type":"Feature",
              "geometry":{"type":"LineString","coordinates":[$coordinates]},
              "properties":{"sessionId":$sessionId,"active":${sessionId == activeTrackSessionId}}
            }
            """.trimIndent()
        }
        .joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

private fun waypointGeoJson(waypoints: List<Waypoint>, destinationId: Long?): String {
    val features = waypoints
        .filter { it.latitude in MIN_LAT..MAX_LAT && it.longitude in MIN_LON..MAX_LON }
        .joinToString(",") { waypoint ->
            """
            {
              "type":"Feature",
              "geometry":{"type":"Point","coordinates":[${waypoint.longitude},${waypoint.latitude}]},
              "properties":{
                "id":${waypoint.id},
                "name":${JSONObject.quote(waypoint.name)},
                "selected":${waypoint.id == destinationId}
              }
            }
            """.trimIndent()
        }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

@Composable
private fun ContourLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            "20m" to Color(0xFF67E8F9),
            "50m" to Color(0xFFFBBF24),
            "100m" to Color(0xFFFB7185)
        ).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp, 3.dp).background(color))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun NavigationPanel(name: String, nav: NavInfo, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xEE0D2833), shape = MaterialTheme.shapes.large, shadowElevation = 8.dp) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Navigation, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${distanceText(nav.distanceMeters)}  ${nav.bearing.roundToInt()}° ${compass(nav.bearing)}",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            TextButton(onClick = onStop) { Text("終了") }
        }
    }
}

@Composable
private fun NavigationConfirmationPanel(
    waypoint: Waypoint,
    nav: NavInfo?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alreadyArrived = nav?.distanceMeters?.let { it <= 30f } == true
    Surface(
        modifier,
        color = Color(0xF20D2833),
        shape = MaterialTheme.shapes.large,
        shadowElevation = 10.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "「${waypoint.name}」へナビしますか？",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    nav == null -> "現在位置を取得しています"
                    alreadyArrived -> "このポイントの到着範囲（30m以内）です"
                    else -> "${distanceText(nav.distanceMeters)}・${nav.bearing.roundToInt()}° ${compass(nav.bearing)}"
                },
                color = if (alreadyArrived) MaterialTheme.colorScheme.secondary
                    else LocalContentColor.current
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text("キャンセル")
                }
                Button(
                    onClick = onConfirm,
                    enabled = nav != null && !alreadyArrived,
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Icon(Icons.Default.Navigation, null)
                    Spacer(Modifier.width(6.dp))
                    Text("ナビ開始")
                }
            }
        }
    }
}

private fun distanceText(meters: Float) =
    if (meters < 1000) "${meters.roundToInt()} m" else String.format(Locale.JAPAN, "%.1f km", meters / 1000)
private fun compass(bearing: Float) =
    listOf("北", "北東", "東", "南東", "南", "南西", "西", "北西")[((bearing + 22.5f) / 45f).toInt() % 8]
