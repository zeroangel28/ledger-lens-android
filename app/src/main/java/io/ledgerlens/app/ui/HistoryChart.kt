package io.ledgerlens.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ledgerlens.app.model.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun historyMoney(value: BigDecimal) = value.setScale(2, RoundingMode.HALF_UP).toPlainString()
private fun historyDate(day: Long) = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))

@Composable fun HistoryChartCard(history: PortfolioHistory, privacy: Boolean, s: Strings, onRefresh: () -> Unit = {}) {
    var range by rememberSaveable { mutableStateOf(HistoryRange.MONTH) }
    var selectedDay by rememberSaveable(range) { mutableStateOf<Long?>(null) }
    var table by rememberSaveable { mutableStateOf(false) }
    val today = history.points.lastOrNull()?.day
    val points = remember(history.points, range) { today?.let { day -> history.points.filter { it.day >= range.start(LocalDate.ofEpochDay(day)).toEpochDay() } }.orEmpty() }
    val selectedIndex = points.indexOfFirst { it.day == selectedDay }.takeIf { it >= 0 } ?: points.lastIndex
    val selected = points.getOrNull(selectedIndex)
    val maximum = points.maxOfOrNull { it.value } ?: BigDecimal.ZERO
    val change = points.lastOrNull()?.value?.minus(points.first().value)
    val percent = points.firstOrNull()?.value?.takeIf { it.signum() > 0 }?.let { change!!.multiply(BigDecimal(100)).divide(it, 2, RoundingMode.HALF_UP) }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s["historyTitle"], style = MaterialTheme.typography.titleLarge)
            Text(s["historyBasis"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRange.entries.forEach { period -> FilterChip(selected = range == period, onClick = { range = period }, modifier = Modifier.heightIn(min = 48.dp), label = { Text(s[period.label]) }) }
            }
            if (history.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s["historyLoading"], style = MaterialTheme.typography.bodySmall) }
            if (privacy) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Outlined.VisibilityOff, null); Text(s["historyPrivacy"]); Text("•••• USDT") }
                }
            } else if (selected != null) {
                Text(historyDate(selected.day) + " · " + s[if (selected.day == today) "historyLatest" else "historyDaily"], modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium)
                Text(historyMoney(selected.value) + " USDT", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (change != null) Text(s["historyChange"] + " · " + (if (change.signum() > 0) "+" else "") + historyMoney(change) + " USDT · " + (percent?.let { (if (it.signum() > 0) "+" else "") + it.toPlainString() + "%" } ?: "—"), style = MaterialTheme.typography.bodySmall)
                Text(historyMoney(maximum) + " USDT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = s["historyChartDescription"] }
                    .pointerInput(points) { detectTapGestures { tap ->
                        val index = (((tap.x - 8.dp.toPx()) / (size.width - 16.dp.toPx()).coerceAtLeast(1f)).coerceIn(0f, 1f) * points.lastIndex).roundToInt()
                        selectedDay = points[index].day
                    } }) {
                    val pad = 8.dp.toPx(); val width = (size.width - pad * 2).coerceAtLeast(1f); val height = (size.height - pad * 2).coerceAtLeast(1f)
                    fun location(index: Int): Offset {
                        val fraction = if (maximum.signum() == 0) 0f else points[index].value.divide(maximum, MathContext.DECIMAL64).toFloat().coerceIn(0f, 1f)
                        return Offset(pad + width * index / points.lastIndex.coerceAtLeast(1), pad + height * (1 - fraction))
                    }
                    repeat(3) { index -> val y = pad + height * index / 2; drawLine(gridColor, Offset(pad, y), Offset(pad + width, y), 1.dp.toPx()) }
                    val path = Path(); points.indices.forEach { i -> val p = location(i); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    val fill = Path().apply { addPath(path); lineTo(pad + width, pad + height); lineTo(pad, pad + height); close() }
                    drawPath(fill, lineColor.copy(alpha = 0.1f)); drawPath(path, lineColor, style = Stroke(3.dp.toPx()))
                    val point = location(selectedIndex)
                    drawLine(lineColor.copy(alpha = 0.5f), Offset(point.x, pad), Offset(point.x, pad + height), 1.dp.toPx())
                    drawCircle(lineColor, 5.dp.toPx(), point)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("0 USDT", style = MaterialTheme.typography.labelSmall); Text("UTC", style = MaterialTheme.typography.labelSmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(historyDate(points.first().day), style = MaterialTheme.typography.labelSmall); Text(historyDate(points.last().day), style = MaterialTheme.typography.labelSmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedDay = points[selectedIndex - 1].day }, enabled = selectedIndex > 0, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ChevronLeft, s["historyPrevious"]) }
                    Slider(value = selectedIndex.toFloat(), onValueChange = { selectedDay = points[it.roundToInt().coerceIn(points.indices)].day }, valueRange = 0f..points.lastIndex.coerceAtLeast(1).toFloat(), steps = if (points.size <= 32) (points.size - 2).coerceAtLeast(0) else 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = s["historySelectDate"]; stateDescription = historyDate(selected.day) + " · " + historyMoney(selected.value) + " USDT" })
                    IconButton(onClick = { selectedDay = points[selectedIndex + 1].day }, enabled = selectedIndex < points.lastIndex, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ChevronRight, s["historyNext"]) }
                }
                if (selected.missingPrices > 0) Text(s["historyMissingOnDate"] + " · " + selected.missingPrices, style = MaterialTheme.typography.bodySmall)
                if (points.any { it.missingPrices > 0 }) Text(s["historyMissing"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { table = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.TableChart, null); Spacer(Modifier.width(8.dp)); Text(s["historyTable"]) }
            } else if (!history.loading) { Text(s["historyEmpty"]) }
            if (history.unknownBalances > 0) Text(s["historyUnknown"], style = MaterialTheme.typography.bodySmall)
            if (history.error != null) {
                Text(s["historyError"] + "\n" + s.explain(history.error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRefresh, enabled = !history.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(s["historyRetry"]) }
            }
            Text(s["historyMethod"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (table && !privacy) AlertDialog(onDismissRequest = { table = false }, title = { Text(s["historyTable"]) }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(points, key = { it.day }) { point -> Column {
                Text(historyDate(point.day) + " UTC", style = MaterialTheme.typography.labelMedium)
                Text(historyMoney(point.value) + " USDT")
                if (point.missingPrices > 0) Text(s["historyMissingOnDate"] + " · " + point.missingPrices, style = MaterialTheme.typography.bodySmall)
            } }
        }
    }, confirmButton = { TextButton(onClick = { table = false }) { Text(s["close"]) } })
}
