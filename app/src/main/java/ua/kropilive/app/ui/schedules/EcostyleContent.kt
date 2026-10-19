package ua.kropilive.app.ui.schedules

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.kropilive.app.data.repository.EcostyleAddressLocator
import ua.kropilive.app.data.repository.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val EcoGreen = Color(0xFF1F7A45)
private val EcoYellow = Color(0xFFE8A400)
private val EcoBlue = Color(0xFF2563EB)
private val EcoPurple = Color(0xFF7C3AED)
private val EcoTeal = Color(0xFF0E7490)

@Composable
fun EcostyleContent(address: UserAddress, onOpenTariffs: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var db by remember { mutableStateOf<EcostyleDb?>(StartupPreload.ecostyleDb()) }
    var houseCoordinates by remember { mutableStateOf<Pair<Double, Double>?>(StartupPreload.ecostyleCoordinates(address)) }
    var locating by remember { mutableStateOf(false) }
    val addressKey = "${address.street}|${address.building}"
    fun reload() {
        scope.launch {
            loading = true
            error = null
            try { db = EcostyleRepository.load() }
            catch (e: Exception) { error = "Не вдалося завантажити дані Екостайл. Перевірте інтернет і повторіть спробу." }
            finally { loading = false }
        }
    }
    LaunchedEffect(addressKey) {
        houseCoordinates = StartupPreload.ecostyleCoordinates(address)
        if (address.street.isNotBlank()) {
            // Startup has already fetched the database and geocoded the address when possible.
            // If startup timed out or was skipped, fetch the missing parts here.
            db = StartupPreload.ecostyleDb() ?: db
            if (db == null) reload()
            if (houseCoordinates == null) {
                locating = true
                try {
                    houseCoordinates = EcostyleAddressLocator.find(context, address.cityName, address.street, address.building)
                } finally { locating = false }
            }
        }
    }
    val result = remember(db, addressKey, houseCoordinates) {
        if (address.street.isBlank()) null else db?.resolve(address.street, address.building, houseCoordinates)
    }
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
    }
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Delete, null, tint = EcoGreen)
                Text("Вивезення відходів · Екостайл", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (!address.isFilled) {
                Text("Спочатку виберіть вулицю та будинок у налаштуваннях адреси.")
            } else {
                Text(address.formattedAddress, style = MaterialTheme.typography.bodyMedium)
                if (loading || locating) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
                if (result == null && !loading && error == null) {
                    Text("Вулицю не знайдено в базі Екостайл. Перевірте назву або скористайтеся офіційним сайтом.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open("https://www.ecostyle.ua/grafik") }) { Text("Сайт Екостайл") }
                }
            }
        }
    }
    if (result != null) {
        EcoPointCard("Змішані побутові", EcoGreen, result.mixed, result.street, ::open)
        EcoPointCard("Великогабаритні", EcoYellow, result.bulky, result.street, ::open)
        EcoPointCard("Будівельні (ремонтні)", EcoBlue, result.construction, result.street, ::open)
        EcoPointCard("Бункер для будівельного сміття", EcoPurple, result.bunker, result.street, ::open)
        EcoPointCard("Роздільний збір · папір, пластик, скло", EcoTeal, result.recycling, result.street, ::open)
        EcoCalendar(result)
        Text("Відстані наведені по прямій, не за маршрутом. У святкові дні графік може змінюватися. Джерело: ecostyle.ua.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    EcostyleContactsSection(::open, onOpenTariffs)
}

@Composable
private fun EcoPointCard(title: String, color: Color, selection: EcostyleSelection?, street: String, open: (String) -> Unit) {
    if (selection == null) return
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            }
            selection.rule?.let { rule ->
                Text(rule.description(), fontWeight = FontWeight.Medium)
                if (rule.kind == "d") {
                    // Daily service has no separately confirmed next pickup date.
                } else if (rule.kind == "eo" && rule.anchor == null) {
                    // Дати чергування не уточнюємо у картці.
                } else {
                    rule.next(ecostyleToday())?.let { date ->
                        Text("Наступний вивіз: ${date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("uk")))}")
                    }
                }
            }
            if (!selection.description.contains("Графік за вулицею (точку збору для будинку не підтверджено)", ignoreCase = true)) {
                Text(selection.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            selection.point?.let { point ->
                Text("${street}, ${point.label}", style = MaterialTheme.typography.bodyMedium)
                selection.distanceMeters?.let { dist ->
                    Text("Приблизно ${if (dist < 950) "${(dist / 10).roundToInt() * 10} м" else "%.1f км".format(Locale.US, dist / 1000)} по прямій", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = {
                    open("https://www.google.com/maps/dir/?api=1&destination=${point.lat},${point.lon}")
                }) {
                    Icon(Icons.Default.Place, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("На карті / маршрут")
                }
            }
            if (selection.rule == null) Text("Точний графік потребує уточнення.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EcoCalendar(result: EcostyleResult) {
    val rules = listOfNotNull(
        result.mixed?.rule?.takeIf { it.isDated() }?.let { it to EcoGreen },
        result.bulky?.rule?.takeIf { it.isDated() }?.let { it to EcoYellow },
        result.construction?.rule?.takeIf { it.isDated() }?.let { it to EcoBlue }
    )
    if (rules.isEmpty()) return
    var month by remember { mutableStateOf(YearMonth.from(ecostyleToday())) }
    val locale = Locale.forLanguageTag("uk")
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "Попередній місяць") }
                Text(month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "Наступний місяць") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) }
            }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val count = month.lengthOfMonth()
            val rows = (offset + count + 6) / 7
            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { col ->
                        val d = row * 7 + col - offset + 1
                        if (d in 1..count) {
                            val date = month.atDay(d)
                            val colors = rules.filter { it.first.matches(date) }.map { it.second }
                            Column(Modifier.weight(1f).height(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$d", style = MaterialTheme.typography.bodySmall, fontWeight = if (date == ecostyleToday()) FontWeight.Bold else FontWeight.Normal)
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    colors.forEach { c -> Box(Modifier.size(7.dp).background(c, RoundedCornerShape(50))) }
                                }
                            }
                        } else Spacer(Modifier.weight(1f))
                    }
                }
            }
            // Two rows avoid squeezing the long Ukrainian label on narrow screens.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    EcoLegendItem("Побутові", EcoGreen)
                    EcoLegendItem("Великогабаритні", EcoYellow)
                }
                EcoLegendItem("Будівельні", EcoBlue)
            }
        }
    }
}

@Composable
private fun EcoLegendItem(label: String, dotColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.size(8.dp).background(dotColor, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@Composable
private fun EcostyleContactsSection(open: (String) -> Unit, onOpenTariffs: () -> Unit) {
    val context = LocalContext.current
    var showLoginWarning by remember { mutableStateOf(false) }

    fun openDialer(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        try { context.startActivity(intent) } catch (_: Exception) { }
    }

    HorizontalDivider()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SupportAgent, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Контакти Екостайл", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "Контакт-сервіс: Пн–Чт 8:00–17:00, Пт 8:00–16:00. Натисніть номер, щоб відкрити набір телефону.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { openDialer("+380504888620") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("050 488 86 20 · Контакт-сервіс")
            }
            OutlinedButton(
                onClick = { open("https://www.ecostyle.ua/kontakty") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Усі контакти на сайті")
            }
            HorizontalDivider()
            OutlinedButton(
                onClick = onOpenTariffs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Тарифи Екостайл")
            }
            Text("Особистий кабінет", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = { showLoginWarning = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Увійти в особистий кабінет")
            }
        }
    }
    if (showLoginWarning) {
        AlertDialog(
            onDismissRequest = { showLoginWarning = false },
            title = { Text("Перехід на зовнішній сайт") },
            text = {
                Text(
                    "КропіLive не запитує, не збирає та не зберігає логін і пароль від особистого кабінету. " +
                            "Після підтвердження відкриється сайт компанії у вашому браузері. " +
                            "Якщо ви маєте сумніви, можете самостійно відкрити https://www.ecostyle.ua/kabinet на комп’ютері."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLoginWarning = false
                    open("https://www.ecostyle.ua/kabinet")
                }) { Text("Прийняти") }
            },
            dismissButton = {
                TextButton(onClick = { showLoginWarning = false }) { Text("Скасувати") }
            }
        )
    }
}
