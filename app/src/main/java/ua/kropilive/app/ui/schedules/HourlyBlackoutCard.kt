package ua.kropilive.app.ui.schedules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.kropilive.app.data.repository.HourlyGraph
import ua.kropilive.app.data.repository.HourlyGraphCheck
import ua.kropilive.app.data.repository.TelegramGraphCheck
import java.time.LocalDate

@Composable
fun HourlyBlackoutCard(
    queue: String,
    modifier: Modifier = Modifier,
    siteCheck: HourlyGraphCheck? = null,
    telegramCheck: TelegramGraphCheck? = null
) {
    // This component is presentation-only: opening it never calls the network.
    // The parent screen supplies results from the shared electricity refresh.
    val today = LocalDate.now().toString()
    val site = (siteCheck as? HourlyGraphCheck.Published)?.graph
        ?.takeIf { it.date == today && !it.cached }
    val channel = (telegramCheck as? TelegramGraphCheck.Published)?.graph
        ?.takeIf { it.date == today && !it.cached }
    val graph = site ?: channel?.let {
        HourlyGraph(it.queue, it.date, it.times, cached = false)
    }
    val verifiedNoGraph = siteCheck is HourlyGraphCheck.NotPublished &&
            telegramCheck is TelegramGraphCheck.NoCurrentGraphInVisiblePosts
    val source = when {
        site != null -> "Кіровоградобленерго"
        channel != null -> "Telegram · офіційний канал"
        else -> "Актуального графіка немає"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, null, tint = Color(0xFFFACC15))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Погодинний графік",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // No refresh button: the main screen owns refresh and swipe-to-refresh.
            }
            if (queue.isBlank()) {
                Text(
                    "Номер черги невідомий. Оберіть адресу в налаштуваннях.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Черга $queue · $source" + if (graph != null) " · ${graph.date}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (graph == null) {
                    Text(
                        when {
                            verifiedNoGraph -> "ГПВ не опубліковано. Нових повідомлень у Telegram немає."
                            siteCheck == null && telegramCheck == null -> "Очікуємо результатів перевірки джерел."
                            else -> "Актуальний графік не підтверджено."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LegendDot(Color(0xFF22C55E), "Є")
                    LegendDot(Color(0xFFEF4444), "Немає")
                    LegendDot(Color(0xFFF59E0B), "Перемикання")
                    LegendDot(Color.Gray, "Невідомо")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Година", modifier = Modifier.width(53.dp), style = MaterialTheme.typography.labelSmall)
                    Text("00–30", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text("30–60", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                }
                for (hour in 0..23) {
                    val time = "%02d".format(java.util.Locale.US, hour)
                    val first = normalize(graph?.times?.get("$time:00"), true)
                    val second = normalize(graph?.times?.get("$time:30"), false)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("$time:00", modifier = Modifier.width(53.dp), style = MaterialTheme.typography.labelMedium)
                        StatusHalf(first, Modifier.weight(1f))
                        StatusHalf(second, Modifier.weight(1f))
                    }
                }
                Text(
                    "Кожна смуга — 30 хвилин. Сірий колір означає відсутність підтверджених даних, а не відсутність відключень.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Джерела: kiroe.com.ua та t.me/SvitloKropyvnytskyiMisto. Дані оновлюються разом з основною перевіркою електропостачання.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun normalize(raw: String?, firstHalf: Boolean): String = when (raw) {
    "0" -> "0"; "1" -> "1"; "10" -> "10"; else -> "?"
}

@Composable
private fun StatusHalf(status: String, modifier: Modifier = Modifier) {
    val color = when (status) {
        "0" -> Color(0xFF22C55E)
        "1" -> Color(0xFFEF4444)
        "10" -> Color(0xFFF59E0B)
        else -> Color.Gray
    }
    Surface(modifier = modifier.height(15.dp), color = color, shape = RoundedCornerShape(5.dp)) { }
}

@Composable
private fun RowScope.LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Surface(modifier = Modifier.size(8.dp), color = color, shape = RoundedCornerShape(3.dp)) { }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}