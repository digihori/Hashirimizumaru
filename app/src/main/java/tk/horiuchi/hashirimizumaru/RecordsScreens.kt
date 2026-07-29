package tk.horiuchi.hashirimizumaru

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WaypointScreen(vm: MainViewModel) {
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
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(waypoint.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                buildString {
                                    append("%.5f, %.5f".format(Locale.JAPAN, waypoint.latitude, waypoint.longitude))
                                    waypoint.depth?.let { append("  水深 ${it}m") }
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (waypoint.memo.isNotBlank()) Text(waypoint.memo)
                        }
                        IconButton(onClick = { vm.destination.value = waypoint }) {
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
    var depth by remember(initial) { mutableStateOf(initial.depth?.toString().orEmpty()) }
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
                OutlinedTextField(
                    depth, { depth = it }, label = { Text("想定水深 (m)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
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
                        depth = depth.toDoubleOrNull(),
                        updated = System.currentTimeMillis()
                    ))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
fun CatchScreen(vm: MainViewModel) {
    val values by vm.catches.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (values.isEmpty()) EmptyState("釣果はまだありません", "魚が釣れたら右下のボタンから記録しましょう")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(values, key = { it.id }) { value ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SetMeal, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                value.fish + (value.size?.let { "  ${it}cm" } ?: ""),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value.time)))
                            Text("%.5f, %.5f".format(Locale.JAPAN, value.latitude, value.longitude), style = MaterialTheme.typography.bodySmall)
                            if (value.memo.isNotBlank()) Text(value.memo)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("釣果を記録") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }
    if (adding) {
        CatchEditor(
            latitude = location?.latitude ?: 35.2708,
            longitude = location?.longitude ?: 139.7305,
            onDismiss = { adding = false },
            onSave = { vm.saveCatch(it); adding = false }
        )
    }
}

@Composable
private fun CatchEditor(
    latitude: Double,
    longitude: Double,
    onDismiss: () -> Unit,
    onSave: (CatchRecord) -> Unit
) {
    var fish by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("釣果を記録") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(fish, { fish = it }, label = { Text("魚種 *") }, singleLine = true)
                OutlinedTextField(
                    size, { size = it }, label = { Text("サイズ (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(memo, { memo = it }, label = { Text("メモ") }, minLines = 2)
                Text("位置: %.5f, %.5f".format(Locale.JAPAN, latitude, longitude), style = MaterialTheme.typography.bodySmall)
                Text("写真は次期版で対応予定", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(
                enabled = fish.isNotBlank(),
                onClick = {
                    onSave(CatchRecord(
                        latitude = latitude,
                        longitude = longitude,
                        fish = fish.trim(),
                        size = size.toDoubleOrNull(),
                        memo = memo.trim()
                    ))
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
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
