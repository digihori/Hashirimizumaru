package tk.horiuchi.hashirimizumaru

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupPayload(
    val waypoints: List<Waypoint>,
    val trackSessions: List<TrackSession>,
    val tracks: List<TrackPoint>,
    val catches: List<CatchRecord>
)

data class BackupSummary(
    val createdAt: Long,
    val waypointCount: Int,
    val trackSessionCount: Int,
    val trackPointCount: Int,
    val catchCount: Int,
    val photoCount: Int,
    val estimatedBytes: Long
)

class PreparedBackup internal constructor(
    internal val payload: BackupPayload,
    internal val photos: Map<String, File>,
    internal val directory: File,
    val summary: BackupSummary
) {
    fun discard() = directory.deleteRecursively()
}

class BackupRepository(
    private val context: Context,
    private val dao: BoatDao
) {
    suspend fun summary(): BackupSummary = withContext(Dispatchers.IO) {
        val payload = snapshot()
        val photos = payload.catches.mapNotNull { safePhoto(it.photoUri) }.distinctBy { it.path }
        summary(payload, photos.size, photos.sumOf { it.length() }, System.currentTimeMillis())
    }

    suspend fun write(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val payload = snapshot()
        validate(payload)
        val photos = payload.catches.mapNotNull { record ->
            safePhoto(record.photoUri)?.let { record.photoUri!! to it }
        }.distinctBy { it.first }
        val createdAt = System.currentTimeMillis()
        val result = summary(payload, photos.size, photos.sumOf { it.second.length() }, createdAt)
        context.contentResolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
            ZipOutputStream(output).use { zip ->
                putJson(zip, MANIFEST, manifest(result))
                putJson(zip, WAYPOINTS, waypointsJson(payload.waypoints))
                putJson(zip, SESSIONS, sessionsJson(payload.trackSessions))
                putJson(zip, TRACKS, tracksJson(payload.tracks))
                putJson(zip, CATCHES, catchesJson(payload.catches))
                photos.forEach { (path, file) ->
                    zip.putNextEntry(ZipEntry("photos/${File(path).name}"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("保存先を開けませんでした")
        result
    }

    suspend fun prepare(uri: Uri): PreparedBackup = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "backup_restore_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val json = mutableMapOf<String, ByteArray>()
            val photos = mutableMapOf<String, File>()
            var expanded = 0L
            var entryCount = 0
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++entryCount <= MAX_ENTRIES) { "バックアップ内のファイル数が多すぎます" }
                        require(!entry.isDirectory) { "不正なフォルダーが含まれています" }
                        val name = entry.name
                        require(name == File(name).name || name.matches(Regex("photos/[A-Za-z0-9._-]+"))) {
                            "不正なファイル名です"
                        }
                        require(name !in json && name.removePrefix("photos/") !in photos) { "ファイルが重複しています" }
                        if (name in JSON_FILES) {
                            val bytes = zip.readLimited(MAX_JSON_BYTES) { expanded += it }
                            json[name] = bytes
                        } else if (name.startsWith("photos/")) {
                            val filename = name.removePrefix("photos/")
                            val target = File(directory, filename)
                            target.outputStream().buffered().use { out ->
                                zip.copyLimited(out, MAX_PHOTO_BYTES) { expanded += it }
                            }
                            require(BitmapFactory.Options().also { options ->
                                options.inJustDecodeBounds = true
                                BitmapFactory.decodeFile(target.path, options)
                            }.let { it.outWidth > 0 && it.outHeight > 0 }) { "写真を読み取れません" }
                            photos[filename] = target
                        } else error("未対応のファイルが含まれています")
                        require(expanded <= MAX_EXPANDED_BYTES) { "バックアップの容量が大きすぎます" }
                        zip.closeEntry()
                    }
                }
            } ?: error("バックアップを開けませんでした")
            require(json.keys.containsAll(JSON_FILES)) { "必要なデータがありません" }
            val manifest = JSONObject(json.getValue(MANIFEST).decodeToString())
            require(manifest.getString("format") == "hashirimizumaru-backup") { "走水丸のバックアップではありません" }
            val formatVersion = manifest.getInt("formatVersion")
            require(formatVersion in 1..FORMAT_VERSION) { "未対応のバックアップ形式です" }
            val payload = BackupPayload(
                parseWaypoints(JSONArray(json.getValue(WAYPOINTS).decodeToString()), formatVersion),
                parseSessions(JSONArray(json.getValue(SESSIONS).decodeToString())),
                parseTracks(JSONArray(json.getValue(TRACKS).decodeToString())),
                parseCatches(JSONArray(json.getValue(CATCHES).decodeToString()))
            )
            validate(payload)
            val referencedPhotos = payload.catches.mapNotNull { it.photoUri }.map { File(it).name }.toSet()
            payload.catches.mapNotNull { it.photoUri }.forEach {
                require(File(it).parent == "catch_photos" && photos.containsKey(File(it).name)) {
                    "釣果写真が不足しています"
                }
            }
            require(photos.keys == referencedPhotos) { "使用されていない写真が含まれています" }
            require(manifest.getInt("waypoints") == payload.waypoints.size &&
                manifest.getInt("trackSessions") == payload.trackSessions.size &&
                manifest.getInt("trackPoints") == payload.tracks.size &&
                manifest.getInt("catches") == payload.catches.size &&
                manifest.getInt("photos") == photos.size) { "件数が一致しません" }
            val result = summary(payload, photos.size, expanded, manifest.getLong("createdAt"))
            PreparedBackup(payload, photos, directory, result)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    suspend fun restore(prepared: PreparedBackup): BackupSummary = withContext(Dispatchers.IO) {
        val newFiles = mutableListOf<File>()
        val oldPhotos = dao.catchSnapshot().mapNotNull { safePhoto(it.photoUri) }
        try {
            val mapped = mutableMapOf<String, String>()
            prepared.photos.forEach { (oldName, source) ->
                val destination = File(context.filesDir, "catch_photos/${UUID.randomUUID()}.jpg")
                destination.parentFile?.mkdirs()
                source.copyTo(destination)
                newFiles += destination
                mapped[oldName] = "catch_photos/${destination.name}"
            }
            val payload = prepared.payload.copy(catches = prepared.payload.catches.map { record ->
                record.copy(photoUri = record.photoUri?.let { mapped.getValue(File(it).name) })
            })
            dao.replaceAll(payload)
            oldPhotos.forEach { it.delete() }
            prepared.summary
        } catch (error: Throwable) {
            newFiles.forEach { it.delete() }
            throw error
        } finally {
            prepared.discard()
        }
    }

    private suspend fun snapshot() = BackupPayload(
        dao.waypointSnapshot(),
        dao.trackSessionSnapshot(),
        dao.trackSnapshot(),
        dao.catchSnapshot().map { record ->
            record.copy(photoUri = record.photoUri?.takeIf { safePhoto(it) != null })
        }
    )

    private fun safePhoto(path: String?): File? {
        if (path == null || !path.matches(Regex("catch_photos/[A-Za-z0-9._-]+"))) return null
        return File(context.filesDir, path).takeIf { it.isFile }
    }

    private fun validate(value: BackupPayload) {
        fun validCoordinate(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
        require(value.waypoints.distinctBy { it.id }.size == value.waypoints.size &&
            value.waypoints.distinctBy { it.sortOrder }.size == value.waypoints.size &&
            value.waypoints.all { it.id > 0 && it.sortOrder >= 0 && validCoordinate(it.latitude, it.longitude) }) { "ポイントデータが不正です" }
        require(value.trackSessions.distinctBy { it.id }.size == value.trackSessions.size && value.trackSessions.all { it.id > 0 }) { "航跡データが不正です" }
        val sessionIds = value.trackSessions.map { it.id }.toSet()
        require(value.tracks.size <= MAX_TRACK_POINTS && value.tracks.distinctBy { it.id }.size == value.tracks.size && value.tracks.all { it.id > 0 && it.sessionId in sessionIds && validCoordinate(it.latitude, it.longitude) }) { "航跡座標が不正です" }
        require(value.catches.distinctBy { it.id }.size == value.catches.size && value.catches.all { it.id > 0 && validCoordinate(it.latitude, it.longitude) && (it.size == null || it.size.isFinite()) }) { "釣果データが不正です" }
    }

    private fun summary(p: BackupPayload, photos: Int, bytes: Long, created: Long) = BackupSummary(created, p.waypoints.size, p.trackSessions.size, p.tracks.size, p.catches.size, photos, bytes)
    private fun manifest(s: BackupSummary) = JSONObject().put("format", "hashirimizumaru-backup").put("formatVersion", FORMAT_VERSION).put("appVersion", BuildConfig.VERSION_NAME).put("createdAt", s.createdAt).put("waypoints", s.waypointCount).put("trackSessions", s.trackSessionCount).put("trackPoints", s.trackPointCount).put("catches", s.catchCount).put("photos", s.photoCount)
    private fun putJson(zip: ZipOutputStream, name: String, value: Any) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toString().toByteArray()); zip.closeEntry() }

    private fun waypointsJson(values: List<Waypoint>) = JSONArray().apply { values.forEach { put(JSONObject().put("id",it.id).put("name",it.name).put("memo",it.memo).put("latitude",it.latitude).put("longitude",it.longitude).put("created",it.created).put("updated",it.updated).put("sortOrder",it.sortOrder)) } }
    private fun sessionsJson(values: List<TrackSession>) = JSONArray().apply { values.forEach { put(JSONObject().put("id",it.id).put("name",it.name).put("memo",it.memo).put("startedAt",it.startedAt).put("endedAt",it.endedAt ?: JSONObject.NULL).put("startedByNavigation",it.startedByNavigation)) } }
    private fun tracksJson(values: List<TrackPoint>) = JSONArray().apply { values.forEach { put(JSONObject().put("id",it.id).put("sessionId",it.sessionId).put("time",it.time).put("latitude",it.latitude).put("longitude",it.longitude)) } }
    private fun catchesJson(values: List<CatchRecord>) = JSONArray().apply { values.forEach { put(JSONObject().put("id",it.id).put("time",it.time).put("latitude",it.latitude).put("longitude",it.longitude).put("size",it.size ?: JSONObject.NULL).put("photoUri",it.photoUri ?: JSONObject.NULL).put("memo",it.memo)) } }
    private fun parseWaypoints(a: JSONArray, formatVersion: Int)=List(a.length()) { index -> a.getJSONObject(index).let { Waypoint(it.getLong("id"),it.getString("name"),it.getString("memo"),it.getDouble("latitude"),it.getDouble("longitude"),it.getLong("created"),it.getLong("updated"),if(formatVersion >= 2) it.getInt("sortOrder") else index) } }
    private fun parseSessions(a: JSONArray)=a.objects { TrackSession(it.getLong("id"),it.getString("name"),it.getString("memo"),it.getLong("startedAt"),it.optLongOrNull("endedAt"),it.getBoolean("startedByNavigation")) }
    private fun parseTracks(a: JSONArray)=a.objects { TrackPoint(it.getLong("id"),it.getLong("sessionId"),it.getLong("time"),it.getDouble("latitude"),it.getDouble("longitude")) }
    private fun parseCatches(a: JSONArray)=a.objects { CatchRecord(it.getLong("id"),it.getLong("time"),it.getDouble("latitude"),it.getDouble("longitude"),it.optDoubleOrNull("size"),it.optStringOrNull("photoUri"),it.getString("memo")) }

    companion object {
        const val MIME = "application/zip"
        fun defaultFileName() = "hashirimizumaru_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN).format(Date())}.hmbak"
        private const val FORMAT_VERSION = 2
        private const val MANIFEST="manifest.json"; private const val WAYPOINTS="waypoints.json"; private const val SESSIONS="track_sessions.json"; private const val TRACKS="tracks.json"; private const val CATCHES="catches.json"
        private val JSON_FILES=setOf(MANIFEST,WAYPOINTS,SESSIONS,TRACKS,CATCHES)
        private const val MAX_JSON_BYTES=100L*1024*1024; private const val MAX_PHOTO_BYTES=25L*1024*1024; private const val MAX_EXPANDED_BYTES=500L*1024*1024; private const val MAX_TRACK_POINTS=1_000_000
        private const val MAX_ENTRIES=10_000
    }
}

private inline fun <T> JSONArray.objects(block: (JSONObject)->T)=List(length()) { block(getJSONObject(it)) }
private fun JSONObject.optLongOrNull(key:String)=if(isNull(key)) null else getLong(key)
private fun JSONObject.optDoubleOrNull(key:String)=if(isNull(key)) null else getDouble(key)
private fun JSONObject.optStringOrNull(key:String)=if(isNull(key)) null else getString(key)
private fun InputStream.readLimited(limit:Long, count:(Long)->Unit):ByteArray { val out=java.io.ByteArrayOutputStream(); copyLimited(out,limit,count); return out.toByteArray() }
private fun InputStream.copyLimited(out:OutputStream,limit:Long,count:(Long)->Unit) { val buffer=ByteArray(8192); var total=0L; while(true){ val n=read(buffer); if(n<0) break; total+=n; require(total<=limit){"ファイルの容量が大きすぎます"}; out.write(buffer,0,n); count(n.toLong()) } }
