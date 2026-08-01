package tk.horiuchi.hashirimizumaru

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.location.Location
import java.io.File
import kotlin.math.roundToInt

@Composable
fun WaypointScreen(
    vm: MainViewModel,
    onShowOnMap: (Waypoint) -> Unit,
    onStartNavigation: (Waypoint) -> Unit
) {
    val values by vm.waypoints.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Waypoint?>(null) }
    var deleting by remember { mutableStateOf<Waypoint?>(null) }
    Box(Modifier.fillMaxSize()) {
        if (values.isEmpty()) EmptyState("ポイントはまだありません", "地図画面の「ポイント」から現在地を登録できます")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(values, key = { it.id }) { waypoint ->
                ElevatedCard(
                    onClick = { onShowOnMap(waypoint) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(waypoint.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                buildString {
                                    append("%.5f, %.5f".format(Locale.JAPAN, waypoint.latitude, waypoint.longitude))
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (waypoint.memo.isNotBlank()) Text(waypoint.memo)
                        }
                        IconButton(onClick = { onStartNavigation(waypoint) }) {
                            Icon(Icons.Default.Navigation, "ナビ開始", tint = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = { editing = waypoint }) { Icon(Icons.Default.Edit, "編集") }
                        IconButton(onClick = { deleting = waypoint }) { Icon(Icons.Default.Delete, "削除") }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = {
                val lat = location?.latitude ?: 35.2708
                val lon = location?.longitude ?: 139.7305
                editing = Waypoint(name = "", latitude = lat, longitude = lon)
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) { Icon(Icons.Default.Add, "追加") }
    }
    editing?.let { value ->
        WaypointEditor(
            initial = value,
            onDismiss = { editing = null },
            onSave = { vm.saveWaypoint(it); editing = null }
        )
    }
    deleting?.let { value ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("ポイントを削除") },
            text = { Text("「${value.name}」を削除しますか？") },
            confirmButton = {
                TextButton(onClick = { vm.deleteWaypoint(value); deleting = null }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } }
        )
    }
}

@Composable
fun WaypointEditor(initial: Waypoint, onDismiss: () -> Unit, onSave: (Waypoint) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var memo by remember(initial) { mutableStateOf(initial.memo) }
    var latitude by remember(initial) { mutableStateOf(initial.latitude.toString()) }
    var longitude by remember(initial) { mutableStateOf(initial.longitude.toString()) }
    val valid = name.isNotBlank() && latitude.toDoubleOrNull() != null && longitude.toDoubleOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "ポイントを追加" else "ポイントを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名前 *") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        latitude, { latitude = it }, label = { Text("緯度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        longitude, { longitude = it }, label = { Text("経度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(memo, { memo = it }, label = { Text("メモ") }, minLines = 2)
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(initial.copy(
                        name = name.trim(),
                        memo = memo.trim(),
                        latitude = latitude.toDouble(),
                        longitude = longitude.toDouble(),
                        updated = System.currentTimeMillis()
                    ))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
fun CatchScreen(vm: MainViewModel, onShowOnMap: (CatchRecord) -> Unit) {
    val values by vm.catches.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importedPhoto by remember { mutableStateOf<ImportedCatchPhoto?>(null) }
    var importing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CatchRecord?>(null) }
    var deleting by remember { mutableStateOf<CatchRecord?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showPhotoLocationExplanation by remember { mutableStateOf(false) }
    val latestImportedPhoto by rememberUpdatedState(importedPhoto)
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                val canReadLocation = Build.VERSION.SDK_INT < 29 ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_MEDIA_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                runCatching { vm.importCatchPhoto(uri, canReadLocation) }
                    .onSuccess { importedPhoto = it }
                    .onFailure {
                        errorMessage = it.message ?: "写真を読み込めませんでした"
                    }
                importing = false
            }
        }
    }
    val mediaLocationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        photoPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
    fun launchPhotoPicker() {
        photoPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
    fun selectPhoto() {
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showPhotoLocationExplanation = true
        } else {
            launchPhotoPicker()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            latestImportedPhoto?.let { vm.discardCatchPhoto(it.relativePath) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (values.isEmpty()) {
            EmptyState("釣果はまだありません", "写真を選んで釣果を記録しましょう")
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(values, key = { it.id }) { value ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column {
                        CatchPhoto(
                            file = vm.catchPhotoFile(value.photoUri),
                            modifier = Modifier.fillMaxWidth().height(210.dp)
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    DateFormat.getDateTimeInstance(
                                        DateFormat.MEDIUM,
                                        DateFormat.SHORT
                                    ).format(Date(value.time)),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                value.size?.let { Text("$it cm") }
                                Text(
                                    "%.5f, %.5f".format(
                                        Locale.JAPAN,
                                        value.latitude,
                                        value.longitude
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (value.memo.isNotBlank()) Text(value.memo)
                            }
                            IconButton(onClick = { editing = value }) {
                                Icon(Icons.Default.Edit, "編集")
                            }
                            IconButton(onClick = { onShowOnMap(value) }) {
                                Icon(Icons.Default.Map, "地図で表示")
                            }
                            IconButton(onClick = { deleting = value }) {
                                Icon(Icons.Default.Delete, "削除")
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = ::selectPhoto,
            icon = { Icon(Icons.Default.AddAPhoto, null) },
            text = { Text("写真から記録") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
        if (importing) {
            Surface(
                Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 8.dp
            ) {
                Row(
                    Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("写真を読み込んでいます")
                }
            }
        }
    }

    importedPhoto?.let { photo ->
        CatchEditor(
            photo = photo,
            photoFile = vm.catchPhotoFile(photo.relativePath),
            fallbackLatitude = location?.latitude ?: 35.2708,
            fallbackLongitude = location?.longitude ?: 139.7305,
            currentLocation = location,
            onDismiss = {
                vm.discardCatchPhoto(photo.relativePath)
                importedPhoto = null
            },
            onSave = {
                vm.saveCatch(it)
                importedPhoto = null
            }
        )
    }
    editing?.let { value ->
        CatchRecordEditor(
            initial = value,
            photoFile = vm.catchPhotoFile(value.photoUri),
            currentLocation = location,
            onDismiss = { editing = null },
            onSave = {
                vm.saveCatch(it)
                editing = null
            }
        )
    }
    deleting?.let { value ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("釣果を削除") },
            text = { Text("この釣果と保存された写真を削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCatch(value)
                    deleting = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("キャンセル") }
            }
        )
    }
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("写真を読み込めませんでした") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("閉じる") }
            }
        )
    }
    if (showPhotoLocationExplanation) {
        AlertDialog(
            onDismissRequest = { showPhotoLocationExplanation = false },
            title = { Text("写真の位置情報") },
            text = {
                Text(
                    "写真に保存された撮影場所を釣果へ自動入力するため、" +
                        "選択した写真の位置情報へのアクセスを許可できます。" +
                        "許可しない場合は現在地を使用します。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPhotoLocationExplanation = false
                    mediaLocationPermission.launch(
                        Manifest.permission.ACCESS_MEDIA_LOCATION
                    )
                }) { Text("許可して選択") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPhotoLocationExplanation = false
                    launchPhotoPicker()
                }) { Text("許可せず選択") }
            }
        )
    }
}

@Composable
fun TrackScreen(
    vm: MainViewModel,
    onShowOnMap: (TrackSession) -> Unit
) {
    val sessions by vm.trackSessions.collectAsStateWithLifecycle()
    val points by vm.allTrackPoints.collectAsStateWithLifecycle()
    val activeSession by vm.activeTrackSession.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TrackSession?>(null) }
    var deleting by remember { mutableStateOf<TrackSession?>(null) }
    val pointsBySession = remember(points) { points.groupBy { it.sessionId } }

    Box(Modifier.fillMaxSize()) {
        if (sessions.isEmpty()) {
            EmptyState("航跡はまだありません", "地図画面で航跡記録を開始するか、ポイントへのナビを開始してください")
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sessions, key = { it.id }) { session ->
                val sessionPoints = pointsBySession[session.id].orEmpty()
                val isRecording = activeSession?.id == session.id
                ElevatedCard(
                    onClick = { onShowOnMap(session) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isRecording) Icons.Default.RadioButtonChecked else Icons.Default.Timeline,
                                null,
                                tint = if (isRecording) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(session.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (isRecording) "記録中"
                                    else trackPeriodText(session),
                                    color = if (isRecording) MaterialTheme.colorScheme.secondary
                                        else LocalContentColor.current,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = { onShowOnMap(session) }) {
                                Icon(Icons.Default.Map, "地図に表示")
                            }
                            IconButton(onClick = { editing = session }) {
                                Icon(Icons.Default.Edit, "編集")
                            }
                            IconButton(
                                onClick = { deleting = session },
                                enabled = !isRecording
                            ) {
                                Icon(Icons.Default.Delete, "削除")
                            }
                        }
                        Text(
                            "${durationText(session)}・${distanceText(trackDistance(sessionPoints))}・${sessionPoints.size}点",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (session.memo.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(session.memo)
                        }
                        if (isRecording) {
                            TextButton(
                                onClick = vm::stopRecording,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Icon(Icons.Default.Stop, null)
                                Spacer(Modifier.width(4.dp))
                                Text("記録を終了")
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { session ->
        TrackSessionEditor(
            initial = session,
            onDismiss = { editing = null },
            onSave = {
                vm.updateTrackSession(it)
                editing = null
            }
        )
    }
    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("航跡を削除") },
            text = { Text("「${session.name}」と記録された位置情報を削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTrackSession(session)
                    deleting = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun TrackSessionEditor(
    initial: TrackSession,
    onDismiss: () -> Unit,
    onSave: (TrackSession) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var memo by remember(initial) { mutableStateOf(initial.memo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("航跡を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("名前 *") },
                    singleLine = true
                )
                OutlinedTextField(
                    memo,
                    { memo = it },
                    label = { Text("メモ") },
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(initial.copy(name = name.trim(), memo = memo.trim()))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

private fun trackPeriodText(session: TrackSession): String {
    val format = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
    val end = session.endedAt ?: System.currentTimeMillis()
    return "${format.format(Date(session.startedAt))}〜${format.format(Date(end))}"
}

private fun durationText(session: TrackSession): String {
    val millis = (session.endedAt ?: System.currentTimeMillis()) - session.startedAt
    val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}時間${minutes}分" else "${minutes}分"
}

private fun trackDistance(points: List<TrackPoint>): Float {
    var total = 0f
    points.zipWithNext().forEach { (from, to) ->
        val result = FloatArray(1)
        Location.distanceBetween(
            from.latitude,
            from.longitude,
            to.latitude,
            to.longitude,
            result
        )
        total += result[0]
    }
    return total
}

private fun distanceText(meters: Float): String =
    if (meters < 1000) "${meters.roundToInt()}m"
    else String.format(Locale.JAPAN, "%.1fkm", meters / 1000)

@Composable
private fun CatchEditor(
    photo: ImportedCatchPhoto,
    photoFile: File?,
    fallbackLatitude: Double,
    fallbackLongitude: Double,
    currentLocation: Location?,
    onDismiss: () -> Unit,
    onSave: (CatchRecord) -> Unit
) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val time = photo.takenAt ?: System.currentTimeMillis()
    var latitude by remember(photo) {
        mutableStateOf((photo.latitude ?: fallbackLatitude).toString())
    }
    var longitude by remember(photo) {
        mutableStateOf((photo.longitude ?: fallbackLongitude).toString())
    }
    var size by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    val valid = latitude.toDoubleOrNull() != null && longitude.toDoubleOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("写真から釣果を記録") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (imeVisible) 220.dp else 420.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CatchPhoto(
                    file = photoFile,
                    modifier = Modifier.fillMaxWidth().height(190.dp)
                )
                Text(
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT
                    ).format(Date(time)),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (photo.takenAt != null) "撮影日時を写真から取得"
                    else "撮影日時がないため現在時刻を使用",
                    style = MaterialTheme.typography.labelSmall
                )
                OutlinedTextField(
                    size, { size = it }, label = { Text("サイズ (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        latitude,
                        { latitude = it },
                        label = { Text("緯度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        longitude,
                        { longitude = it },
                        label = { Text("経度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        currentLocation?.let {
                            latitude = String.format(Locale.US, "%.6f", it.latitude)
                            longitude = String.format(Locale.US, "%.6f", it.longitude)
                        }
                    },
                    enabled = currentLocation != null
                ) {
                    Icon(Icons.Default.MyLocation, null)
                    Spacer(Modifier.width(6.dp))
                    Text(currentLocation?.let { "現在地を入力（±${it.accuracy.roundToInt()}m）" } ?: "現在地未取得")
                }
                Text(
                    if (photo.latitude != null && photo.longitude != null) {
                        "位置を写真から取得"
                    } else {
                        "写真に位置情報がないため現在地を使用"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                OutlinedTextField(
                    memo,
                    { memo = it },
                    label = { Text("メモ") },
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(CatchRecord(
                        time = time,
                        latitude = latitude.toDouble(),
                        longitude = longitude.toDouble(),
                        size = size.toDoubleOrNull(),
                        photoUri = photo.relativePath,
                        memo = memo.trim()
                    ))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun CatchRecordEditor(
    initial: CatchRecord,
    photoFile: File?,
    currentLocation: Location?,
    onDismiss: () -> Unit,
    onSave: (CatchRecord) -> Unit
) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN) }
    var dateTime by remember(initial) { mutableStateOf(dateFormat.format(Date(initial.time))) }
    var latitude by remember(initial) { mutableStateOf(initial.latitude.toString()) }
    var longitude by remember(initial) { mutableStateOf(initial.longitude.toString()) }
    var size by remember(initial) { mutableStateOf(initial.size?.toString().orEmpty()) }
    var memo by remember(initial) { mutableStateOf(initial.memo) }
    val parsedTime = runCatching {
        dateFormat.apply { isLenient = false }.parse(dateTime)?.time
    }.getOrNull()
    val parsedLatitude = latitude.toDoubleOrNull()
    val parsedLongitude = longitude.toDoubleOrNull()
    val parsedSize = size.toDoubleOrNull()
    val valid = parsedTime != null &&
        parsedLatitude != null && parsedLatitude in -90.0..90.0 &&
        parsedLongitude != null && parsedLongitude in -180.0..180.0 &&
        (size.isBlank() || parsedSize != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("釣果を編集") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (imeVisible) 220.dp else 420.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CatchPhoto(
                    file = photoFile,
                    modifier = Modifier.fillMaxWidth().height(190.dp)
                )
                OutlinedTextField(
                    dateTime,
                    { dateTime = it },
                    label = { Text("撮影日時") },
                    supportingText = { Text("yyyy/MM/dd HH:mm") },
                    isError = dateTime.isNotBlank() && parsedTime == null,
                    singleLine = true
                )
                OutlinedTextField(
                    size,
                    { size = it },
                    label = { Text("サイズ (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = size.isNotBlank() && parsedSize == null,
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        latitude,
                        { latitude = it },
                        label = { Text("緯度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = parsedLatitude == null || parsedLatitude !in -90.0..90.0,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        longitude,
                        { longitude = it },
                        label = { Text("経度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = parsedLongitude == null || parsedLongitude !in -180.0..180.0,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        currentLocation?.let {
                            latitude = String.format(Locale.US, "%.6f", it.latitude)
                            longitude = String.format(Locale.US, "%.6f", it.longitude)
                        }
                    },
                    enabled = currentLocation != null
                ) {
                    Icon(Icons.Default.MyLocation, null)
                    Spacer(Modifier.width(6.dp))
                    Text(currentLocation?.let { "現在地を入力（±${it.accuracy.roundToInt()}m）" } ?: "現在地未取得")
                }
                OutlinedTextField(
                    memo,
                    { memo = it },
                    label = { Text("メモ") },
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            time = parsedTime!!,
                            latitude = parsedLatitude!!,
                            longitude = parsedLongitude!!,
                            size = parsedSize,
                            memo = memo.trim()
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@Composable
fun CatchPhoto(file: File?, modifier: Modifier = Modifier) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            file?.takeIf { it.isFile }?.let { source ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1000 || bounds.outHeight / sample > 1000) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    source.path,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )?.asImageBitmap()
            }
        }
    }
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = "釣果写真",
                modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop
            )
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.HideImage, "写真なし", modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
