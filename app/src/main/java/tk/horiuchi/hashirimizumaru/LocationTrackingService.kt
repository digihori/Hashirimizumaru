package tk.horiuchi.hashirimizumaru

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

data class BackgroundArrival(val destinationId: Long, val stoppedRecording: Boolean)
data class BackgroundTrackingStatus(
    val sessionId: Long,
    val startedByNavigation: Boolean,
    val destination: Waypoint?
)

object BackgroundTrackingEvents {
    val arrivals = MutableSharedFlow<BackgroundArrival>(extraBufferCapacity = 1)
    val navigationStopped = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val recordingStopped = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

class LocationTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val dao by lazy { (application as HashirimizumaruApp).database.dao() }
    private val preferences by lazy {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
    private var sessionId = 0L
    private var startedByNavigation = false
    private var destinationId = 0L
    private var destinationName = ""
    private var destinationLatitude: Double? = null
    private var destinationLongitude: Double? = null
    private var lastSavedAt = 0L
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START && sessionId == 0L) restore()
        when (intent?.action) {
            ACTION_START -> {
                sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
                startedByNavigation = intent.getBooleanExtra(EXTRA_AUTO_RECORDING, false)
                readDestination(intent)
                persist()
            }
            ACTION_SET_NAVIGATION -> {
                readDestination(intent)
                persist()
            }
            ACTION_STOP_NAVIGATION -> {
                BackgroundTrackingEvents.navigationStopped.tryEmit(destinationId)
                if (startedByNavigation) {
                    finishRecording()
                    return START_NOT_STICKY
                }
                clearDestination()
                persist()
            }
            ACTION_STOP_RECORDING -> {
                finishRecording()
                return START_NOT_STICKY
            }
            else -> restore()
        }
        if (sessionId == 0L) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        requestLocationUpdates()
        return START_STICKY
    }

    private fun readDestination(intent: Intent) {
        destinationId = intent.getLongExtra(EXTRA_DESTINATION_ID, 0L)
        destinationName = intent.getStringExtra(EXTRA_DESTINATION_NAME).orEmpty()
        destinationLatitude = intent.takeIf {
            it.hasExtra(EXTRA_DESTINATION_LATITUDE)
        }?.getDoubleExtra(EXTRA_DESTINATION_LATITUDE, 0.0)
        destinationLongitude = intent.takeIf {
            it.hasExtra(EXTRA_DESTINATION_LONGITUDE)
        }?.getDoubleExtra(EXTRA_DESTINATION_LONGITUDE, 0.0)
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (callback != null) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            finishRecording()
            return
        }
        val request = LocationRequest.Builder(
            if (BuildConfig.DEBUG) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            if (BuildConfig.DEBUG) 1_000L else 15_000L
        )
            .setMinUpdateIntervalMillis(if (BuildConfig.DEBUG) 500L else 10_000L)
            .setMinUpdateDistanceMeters(if (BuildConfig.DEBUG) 0f else 30f)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(::handleLocation)
            }
        }
        client.requestLocationUpdates(request, callback!!, Looper.getMainLooper())
    }

    private fun handleLocation(location: Location) {
        val now = System.currentTimeMillis()
        if (now - lastSavedAt >= 15_000L && sessionId != 0L) {
            lastSavedAt = now
            scope.launch {
                dao.saveTracks(
                    listOf(
                        TrackPoint(
                            sessionId = sessionId,
                            time = now,
                            latitude = location.latitude,
                            longitude = location.longitude
                        )
                    )
                )
            }
        }
        val latitude = destinationLatitude ?: return
        val longitude = destinationLongitude ?: return
        val result = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            latitude,
            longitude,
            result
        )
        if (result[0] <= ARRIVAL_METERS) arrive()
    }

    private fun arrive() {
        val arrivedDestinationId = destinationId
        val stopRecording = startedByNavigation
        notifyArrival()
        BackgroundTrackingEvents.arrivals.tryEmit(
            BackgroundArrival(arrivedDestinationId, stopRecording)
        )
        if (stopRecording) finishRecording() else {
            clearDestination()
            persist()
            updateNotification()
        }
    }

    private fun finishRecording() {
        val endingSessionId = sessionId
        BackgroundTrackingEvents.recordingStopped.tryEmit(Unit)
        removeLocationUpdates()
        clearPersisted()
        sessionId = 0L
        scope.launch {
            dao.trackSession(endingSessionId)?.let {
                if (it.endedAt == null) {
                    dao.updateTrackSession(it.copy(endedAt = System.currentTimeMillis()))
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startAsForeground() {
        val notification = trackingNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (sessionId == 0L) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, trackingNotification())
    }

    private fun trackingNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = if (destinationId != 0L) ACTION_STOP_NAVIGATION else ACTION_STOP_RECORDING
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationTrackingService::class.java).setAction(stopAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val navigating = destinationId != 0L
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (navigating) "ナビ中" else "航跡を記録中")
            .setContentText(
                if (navigating) "「$destinationName」へ移動しています"
                else "位置情報を15秒間隔で保存しています"
            )
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, if (navigating) "ナビ終了" else "記録終了", stop)
            .build()
    }

    private fun notifyArrival() {
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(
            VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE)
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ARRIVAL_CHANNEL_ID,
                "目的地への到着",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            manager.notify(
                ARRIVAL_NOTIFICATION_ID,
                NotificationCompat.Builder(this, ARRIVAL_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle("目的地に到着しました")
                    .setContentText("「$destinationName」の30m以内に入りました")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ナビ・航跡記録",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun persist() {
        preferences.edit()
            .putLong(KEY_SESSION_ID, sessionId)
            .putBoolean(KEY_AUTO_RECORDING, startedByNavigation)
            .putLong(KEY_DESTINATION_ID, destinationId)
            .putString(KEY_DESTINATION_NAME, destinationName)
            .apply {
                destinationLatitude?.let { putLong(KEY_DESTINATION_LATITUDE, it.toBits()) }
                    ?: remove(KEY_DESTINATION_LATITUDE)
                destinationLongitude?.let { putLong(KEY_DESTINATION_LONGITUDE, it.toBits()) }
                    ?: remove(KEY_DESTINATION_LONGITUDE)
            }
            .apply()
    }

    private fun restore() {
        sessionId = preferences.getLong(KEY_SESSION_ID, 0L)
        startedByNavigation = preferences.getBoolean(KEY_AUTO_RECORDING, false)
        destinationId = preferences.getLong(KEY_DESTINATION_ID, 0L)
        destinationName = preferences.getString(KEY_DESTINATION_NAME, "").orEmpty()
        destinationLatitude = preferences.takeIf {
            it.contains(KEY_DESTINATION_LATITUDE)
        }?.getLong(KEY_DESTINATION_LATITUDE, 0L)?.let(Double::fromBits)
        destinationLongitude = preferences.takeIf {
            it.contains(KEY_DESTINATION_LONGITUDE)
        }?.getLong(KEY_DESTINATION_LONGITUDE, 0L)?.let(Double::fromBits)
    }

    private fun clearDestination() {
        destinationId = 0L
        destinationName = ""
        destinationLatitude = null
        destinationLongitude = null
    }

    private fun clearPersisted() {
        preferences.edit().clear().apply()
    }

    private fun removeLocationUpdates() {
        callback?.let(client::removeLocationUpdates)
        callback = null
    }

    override fun onDestroy() {
        removeLocationUpdates()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_START = "tracking.start"
        private const val ACTION_SET_NAVIGATION = "tracking.set_navigation"
        private const val ACTION_STOP_NAVIGATION = "tracking.stop_navigation"
        private const val ACTION_STOP_RECORDING = "tracking.stop_recording"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_AUTO_RECORDING = "auto_recording"
        private const val EXTRA_DESTINATION_ID = "destination_id"
        private const val EXTRA_DESTINATION_NAME = "destination_name"
        private const val EXTRA_DESTINATION_LATITUDE = "destination_latitude"
        private const val EXTRA_DESTINATION_LONGITUDE = "destination_longitude"
        private const val PREFERENCES = "background_tracking"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_AUTO_RECORDING = "auto_recording"
        private const val KEY_DESTINATION_ID = "destination_id"
        private const val KEY_DESTINATION_NAME = "destination_name"
        private const val KEY_DESTINATION_LATITUDE = "destination_latitude"
        private const val KEY_DESTINATION_LONGITUDE = "destination_longitude"
        private const val CHANNEL_ID = "tracking"
        private const val ARRIVAL_CHANNEL_ID = "arrival"
        private const val NOTIFICATION_ID = 2001
        private const val ARRIVAL_NOTIFICATION_ID = 1001
        private const val ARRIVAL_METERS = 30f

        fun start(
            context: Context,
            sessionId: Long,
            startedByNavigation: Boolean,
            destination: Waypoint? = null
        ) {
            val intent = Intent(context, LocationTrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_AUTO_RECORDING, startedByNavigation)
                .withDestination(destination)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setNavigation(context: Context, destination: Waypoint) {
            context.startService(
                Intent(context, LocationTrackingService::class.java)
                    .setAction(ACTION_SET_NAVIGATION)
                    .withDestination(destination)
            )
        }

        fun stopNavigation(context: Context) {
            context.startService(
                Intent(context, LocationTrackingService::class.java)
                    .setAction(ACTION_STOP_NAVIGATION)
            )
        }

        fun stopRecording(context: Context) {
            context.startService(
                Intent(context, LocationTrackingService::class.java)
                    .setAction(ACTION_STOP_RECORDING)
            )
        }

        fun status(context: Context): BackgroundTrackingStatus? {
            val preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val sessionId = preferences.getLong(KEY_SESSION_ID, 0L)
            if (sessionId == 0L) return null
            val destinationId = preferences.getLong(KEY_DESTINATION_ID, 0L)
            val destination = if (
                destinationId != 0L &&
                preferences.contains(KEY_DESTINATION_LATITUDE) &&
                preferences.contains(KEY_DESTINATION_LONGITUDE)
            ) {
                Waypoint(
                    id = destinationId,
                    name = preferences.getString(KEY_DESTINATION_NAME, "").orEmpty(),
                    latitude = Double.fromBits(
                        preferences.getLong(KEY_DESTINATION_LATITUDE, 0L)
                    ),
                    longitude = Double.fromBits(
                        preferences.getLong(KEY_DESTINATION_LONGITUDE, 0L)
                    )
                )
            } else {
                null
            }
            return BackgroundTrackingStatus(
                sessionId = sessionId,
                startedByNavigation = preferences.getBoolean(KEY_AUTO_RECORDING, false),
                destination = destination
            )
        }

        private fun Intent.withDestination(destination: Waypoint?): Intent {
            if (destination == null) return this
            return putExtra(EXTRA_DESTINATION_ID, destination.id)
                .putExtra(EXTRA_DESTINATION_NAME, destination.name)
                .putExtra(EXTRA_DESTINATION_LATITUDE, destination.latitude)
                .putExtra(EXTRA_DESTINATION_LONGITUDE, destination.longitude)
        }
    }
}
