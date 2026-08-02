package tk.horiuchi.hashirimizumaru

import android.content.Context
import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

data class DegreeMinute(val degrees: Int, val minutes: Double)

fun decimalToDegreeMinute(value: Double): DegreeMinute {
    val absolute = abs(value)
    val degrees = floor(absolute).toInt()
    return DegreeMinute(degrees, (absolute - degrees) * 60.0)
}

fun degreeMinuteToDecimal(degrees: Int?, minutes: Double?, maximumDegrees: Int): Double? {
    if (degrees == null || minutes == null) return null
    if (degrees !in 0..maximumDegrees || minutes < 0.0 || minutes >= 60.0) return null
    if (degrees == maximumDegrees && minutes > 0.0) return null
    return degrees + minutes / 60.0
}

private enum class CoordinateFormat { DEGREE_MINUTE, DECIMAL }

@Composable
fun CoordinateInput(
    initialLatitude: Double,
    initialLongitude: Double,
    currentLocation: Location?,
    onValueChanged: (Double?, Double?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("coordinate_input", Context.MODE_PRIVATE) }
    var format by remember {
        mutableStateOf(
            if (preferences.getBoolean("decimal_format", false)) CoordinateFormat.DECIMAL
            else CoordinateFormat.DEGREE_MINUTE
        )
    }
    val initialLatDm = remember(initialLatitude) { decimalToDegreeMinute(initialLatitude) }
    val initialLonDm = remember(initialLongitude) { decimalToDegreeMinute(initialLongitude) }
    var latitudeDecimal by remember(initialLatitude) { mutableStateOf(decimalText(initialLatitude)) }
    var longitudeDecimal by remember(initialLongitude) { mutableStateOf(decimalText(initialLongitude)) }
    var latitudeDegrees by remember(initialLatitude) { mutableStateOf(initialLatDm.degrees.toString()) }
    var latitudeMinutes by remember(initialLatitude) { mutableStateOf(minutesText(initialLatDm.minutes)) }
    var longitudeDegrees by remember(initialLongitude) { mutableStateOf(initialLonDm.degrees.toString()) }
    var longitudeMinutes by remember(initialLongitude) { mutableStateOf(minutesText(initialLonDm.minutes)) }

    fun values(): Pair<Double?, Double?> = if (format == CoordinateFormat.DECIMAL) {
        latitudeDecimal.toDoubleOrNull()?.takeIf { it in 0.0..90.0 } to
            longitudeDecimal.toDoubleOrNull()?.takeIf { it in 0.0..180.0 }
    } else {
        degreeMinuteToDecimal(latitudeDegrees.toIntOrNull(), latitudeMinutes.toDoubleOrNull(), 90) to
            degreeMinuteToDecimal(longitudeDegrees.toIntOrNull(), longitudeMinutes.toDoubleOrNull(), 180)
    }
    fun emit() { values().let { onValueChanged(it.first, it.second) } }
    fun setCoordinates(latitude: Double, longitude: Double) {
        latitudeDecimal = decimalText(latitude)
        longitudeDecimal = decimalText(longitude)
        decimalToDegreeMinute(latitude).let {
            latitudeDegrees = it.degrees.toString(); latitudeMinutes = minutesText(it.minutes)
        }
        decimalToDegreeMinute(longitude).let {
            longitudeDegrees = it.degrees.toString(); longitudeMinutes = minutesText(it.minutes)
        }
        onValueChanged(latitude, longitude)
    }
    fun changeFormat(value: CoordinateFormat) {
        values().let { (latitude, longitude) ->
            if (latitude != null && longitude != null) setCoordinates(latitude, longitude)
        }
        format = value
        preferences.edit().putBoolean("decimal_format", value == CoordinateFormat.DECIMAL).apply()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("座標形式", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = format == CoordinateFormat.DEGREE_MINUTE,
                onClick = { changeFormat(CoordinateFormat.DEGREE_MINUTE) },
                label = { Text("度・分") }
            )
            FilterChip(
                selected = format == CoordinateFormat.DECIMAL,
                onClick = { changeFormat(CoordinateFormat.DECIMAL) },
                label = { Text("十進") }
            )
        }
        if (format == CoordinateFormat.DEGREE_MINUTE) {
            DegreeMinuteRow("北緯", latitudeDegrees, latitudeMinutes, {
                latitudeDegrees = it; emit()
            }, {
                latitudeMinutes = it; emit()
            }, values().first == null)
            DegreeMinuteRow("東経", longitudeDegrees, longitudeMinutes, {
                longitudeDegrees = it; emit()
            }, {
                longitudeMinutes = it; emit()
            }, values().second == null)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    latitudeDecimal, { latitudeDecimal = it; emit() },
                    label = { Text("北緯") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = values().first == null,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    longitudeDecimal, { longitudeDecimal = it; emit() },
                    label = { Text("東経") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = values().second == null,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        OutlinedButton(
            onClick = { currentLocation?.let { setCoordinates(it.latitude, it.longitude) } },
            enabled = currentLocation != null
        ) {
            Icon(Icons.Default.MyLocation, null)
            Spacer(Modifier.width(6.dp))
            Text(currentLocation?.let { "現在地（±${it.accuracy.roundToInt()}m）" } ?: "現在地未取得")
        }
    }
}

@Composable
private fun DegreeMinuteRow(
    direction: String,
    degrees: String,
    minutes: String,
    onDegreesChanged: (String) -> Unit,
    onMinutesChanged: (String) -> Unit,
    isError: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(direction, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(30.dp))
        OutlinedTextField(
            degrees, onDegreesChanged,
            suffix = { Text("°") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = isError,
            singleLine = true,
            modifier = Modifier.width(82.dp)
        )
        OutlinedTextField(
            minutes, onMinutesChanged,
            suffix = { Text("′") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = isError,
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun decimalText(value: Double) = String.format(Locale.US, "%.6f", value)
private fun minutesText(value: Double) = String.format(Locale.US, "%.3f", value)
