package tk.horiuchi.hashirimizumaru

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SetMeal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HashirimizumaruTheme {
                val preferences = remember { getSharedPreferences("settings", MODE_PRIVATE) }
                var accepted by remember { mutableStateOf(preferences.getBoolean("disclaimer_accepted", false)) }
                if (!accepted) {
                    DisclaimerScreen {
                        preferences.edit().putBoolean("disclaimer_accepted", true).apply()
                        accepted = true
                    }
                } else {
                    BoatApp()
                }
            }
        }
    }
}

@Composable
private fun HashirimizumaruTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFF67E8F9),
        onPrimary = Color(0xFF001F27),
        secondary = Color(0xFFFBBF24),
        background = Color(0xFF06171E),
        surface = Color(0xFF0D2833),
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

@Composable
private fun DisclaimerScreen(onAccept: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("安全に関する重要な注意", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Text(
                "走水丸は釣りを支援するためのアプリであり、航海用電子海図ではありません。" +
                    "表示される位置・地図・水深・航路標識は正確性や最新性を保証しません。\n\n" +
                    "航行判断には、法令で求められる海図・航海計器・目視確認を使用してください。" +
                    "利用に伴う事故や損害について、開発者は責任を負いません。"
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("内容を理解して同意する")
            }
        }
    }
}

private enum class AppTab(val label: String) {
    MAP("地図"), WAYPOINTS("ポイント"), TRACKS("航跡"), CATCHES("釣果")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoatApp(
    vm: MainViewModel = viewModel()
) {
    var tab by remember { mutableStateOf(AppTab.MAP) }
    var menuOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var privacyOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val navInfo by vm.navInfo.collectAsStateWithLifecycle()
    val destination by vm.destination.collectAsStateWithLifecycle()
    val interruptedTrack by vm.interruptedTrackSession.collectAsStateWithLifecycle()
    var alertedDestinationId by remember { mutableStateOf<Long?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) vm.startLocation()
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) vm.startLocation()
        else permissionLauncher.launch(
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
        )
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    LaunchedEffect(navInfo?.distanceMeters, destination?.id) {
        val target = destination
        if (target == null) alertedDestinationId = null
        else if (navInfo?.distanceMeters?.let { it <= 30f } == true && alertedDestinationId != target.id) {
            notifyArrival(context, target.name)
            alertedDestinationId = target.id
            vm.stopNavigation()
            scope.launch {
                snackbarHostState.showSnackbar(
                    "ポイント「${target.name}」に到着しました。ナビを終了します"
                )
            }
        }
    }
    DisposableEffect(Unit) { onDispose { vm.stopLocation() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Column { Text("走水丸"); Text("横須賀ボートフィッシングナビ", style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "メニュー")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("プライバシーポリシー") },
                            onClick = { menuOpen = false; privacyOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text("バージョン情報") },
                            onClick = { menuOpen = false; aboutOpen = true }
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Icon(
                                when (item) {
                                    AppTab.MAP -> Icons.Default.Map
                                    AppTab.WAYPOINTS -> Icons.Default.Place
                                    AppTab.TRACKS -> Icons.Default.Timeline
                                    AppTab.CATCHES -> Icons.Default.SetMeal
                                },
                                item.label
                            )
                        },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                AppTab.MAP -> MapScreen(
                    vm = vm,
                    onCancelNavigationPreview = { tab = AppTab.WAYPOINTS }
                )
                AppTab.WAYPOINTS -> WaypointScreen(
                    vm = vm,
                    onShowOnMap = { waypoint ->
                        vm.focusOnMap(waypoint)
                        tab = AppTab.MAP
                    },
                    onStartNavigation = { waypoint ->
                        vm.previewNavigation(waypoint)
                        vm.focusOnMap(waypoint)
                        tab = AppTab.MAP
                    }
                )
                AppTab.TRACKS -> TrackScreen(
                    vm = vm,
                    onShowOnMap = { session ->
                        vm.showTrackOnMap(session.id)
                        tab = AppTab.MAP
                    }
                )
                AppTab.CATCHES -> CatchScreen(vm)
            }
        }
    }
    if (aboutOpen) {
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            title = { Text("走水丸") },
            text = { Text("バージョン ${BuildConfig.VERSION_NAME}\n\n軽い・速い・一日使える、横須賀のボート釣り専用ナビ。\n\n© デジホリ工房") },
            confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text("閉じる") } }
        )
    }
    if (privacyOpen) {
        PrivacyPolicyScreen(onClose = { privacyOpen = false })
    }
    interruptedTrack?.let { session ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("未終了の航跡があります") },
            text = {
                Text(
                    "前回の「${session.name}」は終了操作が行われていません。" +
                        "記録を続けますか？"
                )
            },
            confirmButton = {
                Button(onClick = vm::resumeInterruptedTrack) { Text("記録を続ける") }
            },
            dismissButton = {
                TextButton(onClick = vm::finishInterruptedTrack) { Text("記録を終了") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyPolicyScreen(onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else onClose()
    }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("プライバシーポリシー") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webView = this
                            settings.javaScriptEnabled = false
                            settings.domStorageEnabled = false
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            val markdown = context.assets.open("privacy_ja.md")
                                .bufferedReader()
                                .use { it.readText() }
                            loadDataWithBaseURL(
                                null,
                                markdownDocument(markdown),
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }
}

private fun markdownDocument(markdown: String): String {
    fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun inline(value: String): String {
        var result = escape(value)
        result = Regex("""\*\*(.+?)\*\*""").replace(result) {
            "<strong>${it.groupValues[1]}</strong>"
        }
        result = Regex("""`(.+?)`""").replace(result) {
            "<code>${it.groupValues[1]}</code>"
        }
        result = Regex("""\[([^\]]+)]\((https?://[^)]+)\)""")
            .replace(result) {
                """<a href="${it.groupValues[2]}">${it.groupValues[1]}</a>"""
            }
        return result
    }

    val body = buildString {
        var listOpen = false
        markdown.lineSequence().forEach { source ->
            val line = source.trim()
            when {
                line.startsWith("# ") -> {
                    if (listOpen) { append("</ul>"); listOpen = false }
                    append("<h1>${inline(line.drop(2))}</h1>")
                }
                line.startsWith("## ") -> {
                    if (listOpen) { append("</ul>"); listOpen = false }
                    append("<h2>${inline(line.drop(3))}</h2>")
                }
                line.startsWith("### ") -> {
                    if (listOpen) { append("</ul>"); listOpen = false }
                    append("<h3>${inline(line.drop(4))}</h3>")
                }
                line.startsWith("- ") -> {
                    if (!listOpen) { append("<ul>"); listOpen = true }
                    append("<li>${inline(line.drop(2))}</li>")
                }
                line == "---" -> {
                    if (listOpen) { append("</ul>"); listOpen = false }
                    append("<hr>")
                }
                line.isBlank() -> {
                    if (listOpen) { append("</ul>"); listOpen = false }
                }
                else -> {
                    if (listOpen) { append("</ul>"); listOpen = false }
                    append("<p>${inline(line)}</p>")
                }
            }
        }
        if (listOpen) append("</ul>")
    }
    return """
        <!doctype html>
        <html lang="ja">
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <style>
            :root { color-scheme: dark; }
            body {
              margin: 0; padding: 20px 18px 48px;
              color: #f4f7f8; background: #06171e;
              font-family: sans-serif; font-size: 16px; line-height: 1.75;
              overflow-wrap: anywhere;
            }
            h1 { color: #67e8f9; font-size: 25px; line-height: 1.35; margin: 8px 0 24px; }
            h2 { color: #67e8f9; font-size: 21px; margin: 32px 0 12px; }
            h3 { color: #fbbf24; font-size: 18px; margin: 24px 0 8px; }
            p { margin: 8px 0 14px; }
            ul { margin: 8px 0 18px; padding-left: 24px; }
            li { margin: 6px 0; }
            a { color: #67e8f9; }
            code { background: #173744; padding: 2px 5px; border-radius: 4px; }
            hr { border: 0; border-top: 1px solid #36535e; margin: 32px 0; }
          </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()
}

private fun notifyArrival(context: android.content.Context, name: String) {
    val vibrator = context.getSystemService(Vibrator::class.java)
    vibrator?.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))
    val manager = context.getSystemService(NotificationManager::class.java)
    val channelId = "arrival"
    manager.createNotificationChannel(
        NotificationChannel(channelId, "目的地への到着", NotificationManager.IMPORTANCE_HIGH)
    )
    if (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    ) {
        manager.notify(
            1001,
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("目的地付近です")
                .setContentText("「$name」まで約50m以内に入りました")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        )
    }
}
