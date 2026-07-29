package tk.horiuchi.hashirimizumaru

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale
import kotlin.math.roundToInt

private const val MIN_LAT = 35.235
private const val MAX_LAT = 35.340
private const val MIN_LON = 139.650
private const val MAX_LON = 139.820

@Composable
fun MapScreen(vm: MainViewModel) {
    val location by vm.location.collectAsStateWithLifecycle()
    val nav by vm.navInfo.collectAsStateWithLifecycle()
    val destination by vm.destination.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val powerSaving by vm.powerSaving.collectAsStateWithLifecycle()
    var showSeaMarks by remember { mutableStateOf(true) }
    var showContours by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var addWaypoint by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        MapLibreView(location = location, seaMarks = showSeaMarks)
        Icon(
            Icons.Default.Navigation,
            contentDescription = "現在位置",
            tint = Color(0xFFFFB300),
            modifier = Modifier.align(Alignment.Center).size(44.dp)
        )
        Column(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { layersOpen = !layersOpen },
                label = { Text("レイヤ") },
                leadingIcon = { Icon(Icons.Default.Layers, null) }
            )
            if (layersOpen) {
                Surface(color = Color(0xE6112730), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(showSeaMarks, { showSeaMarks = it })
                            Text("シーマーク")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(showContours, { showContours = it })
                            Text("等深線")
                        }
                        if (showContours) Text("海しる配信設定が必要です", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            FilledTonalIconButton(onClick = vm::togglePowerSaving) {
                Icon(if (powerSaving) Icons.Default.BatterySaver else Icons.Default.GpsFixed, "省電力")
            }
            FilledTonalIconButton(onClick = vm::toggleRecording) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.Route, "航跡記録")
            }
        }
        if (destination != null && nav != null) {
            NavigationPanel(
                destination!!.name,
                nav!!,
                onStop = { vm.destination.value = null },
                Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp)
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
    }
    if (addWaypoint && location != null) {
        WaypointEditor(
            initial = Waypoint(name = "", latitude = location!!.latitude, longitude = location!!.longitude),
            onDismiss = { addWaypoint = false },
            onSave = { vm.saveWaypoint(it); addWaypoint = false }
        )
    }
}

@Composable
private fun MapLibreView(location: Location?, seaMarks: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { mutableStateOf<MapView?>(null) }
    val styleJson = remember(seaMarks) { mapStyle(seaMarks) }
    AndroidView(
        factory = { context ->
            MapView(context).also { view ->
                mapView.value = view
                view.onCreate(null)
                view.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(styleJson))
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(35.2708, 139.7305)).zoom(12.5).build()
                    map.setLatLngBoundsForCameraTarget(
                        org.maplibre.android.geometry.LatLngBounds.from(MAX_LAT, MAX_LON, MIN_LAT, MIN_LON)
                    )
                    map.setMinZoomPreference(11.5)
                    map.setMaxZoomPreference(18.0)
                }
            }
        },
        update = { view ->
            view.getMapAsync { map ->
                if (map.style?.json != styleJson) map.setStyle(Style.Builder().fromJson(styleJson))
                location?.takeIf {
                    it.latitude in MIN_LAT..MAX_LAT && it.longitude in MIN_LON..MAX_LON
                }?.let {
                    map.cameraPosition = CameraPosition.Builder(map.cameraPosition)
                        .target(LatLng(it.latitude, it.longitude)).build()
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

private fun mapStyle(seaMarks: Boolean): String {
    val seamark = if (seaMarks) """,
      "seamarks": {
        "type": "raster",
        "tiles": ["https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png"],
        "tileSize": 256,
        "maxzoom": 18
      }""" else ""
    val layer = if (seaMarks) """,
      {"id":"seamarks","type":"raster","source":"seamarks","minzoom":9}""" else ""
    return """
    {"version":8,"name":"走水丸","sources":{
      "base":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19}
      $seamark
    },"layers":[
      {"id":"background","type":"background","paint":{"background-color":"#071820"}},
      {"id":"base","type":"raster","source":"base","paint":{"raster-saturation":-0.65,"raster-brightness-max":0.72}}
      $layer
    ]}
    """.trimIndent()
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

private fun distanceText(meters: Float) =
    if (meters < 1000) "${meters.roundToInt()} m" else String.format(Locale.JAPAN, "%.1f km", meters / 1000)
private fun compass(bearing: Float) =
    listOf("北", "北東", "東", "南東", "南", "南西", "西", "北西")[((bearing + 22.5f) / 45f).toInt() % 8]
