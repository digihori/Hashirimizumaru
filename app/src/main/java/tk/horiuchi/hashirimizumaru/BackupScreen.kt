package tk.horiuchi.hashirimizumaru

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(vm: MainViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val destination by vm.destination.collectAsStateWithLifecycle()
    val pendingDestination by vm.pendingDestination.collectAsStateWithLifecycle()
    var summary by remember { mutableStateOf<BackupSummary?>(null) }
    var prepared by remember { mutableStateOf<PreparedBackup?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmBackup by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val allowed = !recording && destination == null && pendingDestination == null
    fun fail(error: Throwable) { busy = false; message = error.message ?: "処理に失敗しました" }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.MIME)) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { vm.writeBackup(uri) }.onSuccess {
                summary = it; busy = false; message = "バックアップを保存しました（${it.totalItems()}件）"
            }.onFailure(::fail)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { vm.prepareRestore(uri) }.onSuccess {
                prepared = it; busy = false
            }.onFailure(::fail)
        }
    }
    LaunchedEffect(Unit) {
        busy = true
        runCatching { vm.backupSummary() }.onSuccess { summary = it; busy = false }.onFailure(::fail)
    }
    DisposableEffect(Unit) { onDispose { prepared?.discard() } }
    BackHandler(enabled = !busy) { prepared?.discard(); prepared = null; onClose() }

    Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("バックアップと復元") }, navigationIcon = {
                IconButton(onClick = { prepared?.discard(); prepared = null; onClose() }, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
                }
            })
        }) { padding ->
            Column(Modifier.padding(padding).padding(20.dp).fillMaxSize()) {
                Text("ポイント、航跡、釣果、釣果写真を1つのファイルに保存します。", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                summary?.let { Text(it.detail() + "\n写真容量 約${formatBytes(it.estimatedBytes)}") }
                Spacer(Modifier.height(20.dp))
                Text("バックアップには正確な位置情報と写真が含まれます。共有先と保管場所にご注意ください。", color = MaterialTheme.colorScheme.secondary)
                if (!allowed) { Spacer(Modifier.height(12.dp)); Text("ナビまたは航跡記録を終了してから実行してください。", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { confirmBackup = true }, enabled = allowed && !busy, modifier = Modifier.fillMaxWidth()) { Text("バックアップを作成") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { open.launch(arrayOf(BackupRepository.MIME, "application/octet-stream")) }, enabled = allowed && !busy, modifier = Modifier.fillMaxWidth()) { Text("バックアップから復元") }
                if (busy) { Spacer(Modifier.height(24.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
    if (confirmBackup) AlertDialog(
        onDismissRequest = { confirmBackup = false }, title = { Text("バックアップを作成しますか？") },
        text = { Text("保存先は次の画面で選択します。位置情報と写真を含むため、安全な場所を選んでください。") },
        confirmButton = { Button(onClick = { confirmBackup = false; create.launch(BackupRepository.defaultFileName()) }) { Text("保存先を選ぶ") } },
        dismissButton = { TextButton(onClick = { confirmBackup = false }) { Text("キャンセル") } }
    )
    prepared?.let { backup ->
        AlertDialog(
            onDismissRequest = { backup.discard(); prepared = null }, title = { Text("現在のデータを置き換えますか？") },
            text = { Text("作成日時: ${formatDate(backup.summary.createdAt)}\n${backup.summary.detail()}\n\n現在のポイント、航跡、釣果、写真はすべて置き換えられます。この操作は元に戻せません。") },
            confirmButton = { Button(onClick = {
                prepared = null; busy = true
                scope.launch { runCatching { vm.restoreBackup(backup) }.onSuccess { summary = it; busy = false; message = "復元が完了しました（${it.totalItems()}件）" }.onFailure(::fail) }
            }) { Text("置き換えて復元") } },
            dismissButton = { TextButton(onClick = { backup.discard(); prepared = null }) { Text("キャンセル") } }
        )
    }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("お知らせ") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("閉じる") } }) }
}

private fun BackupSummary.totalItems() = waypointCount + trackSessionCount + trackPointCount + catchCount
private fun BackupSummary.detail() = "ポイント ${waypointCount}件\n航跡 ${trackSessionCount}件（座標 ${trackPointCount}件）\n釣果 ${catchCount}件（写真 ${photoCount}件）"
private fun formatDate(time: Long) = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(time))
private fun formatBytes(bytes: Long) = when { bytes >= 1024 * 1024 -> String.format(Locale.JAPAN, "%.1f MB", bytes / 1024.0 / 1024.0); bytes >= 1024 -> String.format(Locale.JAPAN, "%.1f KB", bytes / 1024.0); else -> "$bytes B" }
