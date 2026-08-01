package tk.horiuchi.hashirimizumaru

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TidePanel(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val tide = remember(now) { YokosukaTideCalculator.snapshot(now) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = System.currentTimeMillis()
        }
    }
    Surface(modifier.width(72.dp), color = Color(0xDD06171E), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                tide.tideCycle,
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${tide.nextExtremum.type.label.take(1)} ${formatTideTime(tide.nextExtremum.timeMillis)}",
                style = MaterialTheme.typography.labelSmall
            )
            TideGraph(tide, Modifier.fillMaxWidth().height(24.dp))
        }
    }
}

private fun formatTideTime(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(timeMillis))

@Composable
private fun TideGraph(tide: TideSnapshot, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        val values = tide.graph.map { it.second }
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 1.0
        val range = (max - min).coerceAtLeast(1.0)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1)
            val y = size.height - ((value - min) / range * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val center = values.size / 2
        val markerY = size.height - ((values[center] - min) / range * size.height).toFloat()
        drawLine(Color.White.copy(alpha = 0.35f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
        drawCircle(markerColor, 3.5.dp.toPx(), Offset(size.width / 2, markerY))
    }
}
