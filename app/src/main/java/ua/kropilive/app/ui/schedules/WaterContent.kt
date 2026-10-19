package ua.kropilive.app.ui.schedules

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ua.kropilive.app.data.repository.*

@Composable
fun WaterContent(address: UserAddress, onOpenTariffs: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<WaterReport?>(null) }
    var error by remember { mutableStateOf(false) }
    var showCabinetWarning by remember { mutableStateOf(false) }
    var openedNotice by remember { mutableStateOf<WaterNotice?>(null) }
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { }
    }
    LaunchedEffect(address.street, address.building, address.cityName) {
        report = StartupPreload.water(address)
        while (true) {
            try {
                report = WaterNoticeRepository.load(address)
                error = false
            } catch (_: Exception) {
                error = report == null
            }
            delay(if (error) 15_000L else 5 * 60_000L)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.WaterDrop, contentDescription = null, tint = Color(0xFF38BDF8))
                    Text("Водопостачання · Дніпро-Кіровоград", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(if (address.isFilled) address.formattedAddress else "Оберіть адресу в налаштуваннях")

                val status = when {
                    error -> "Статус не підтверджено"
                    report == null -> "Перевірка повідомлень…"
                    !report!!.addressSpecified -> "Оберіть адресу"
                    report!!.hasReportedIssue -> "Можливе відключення"
                    else -> "Відключень не оголошено"
                }
                val statusColor = when {
                    error || report == null || !address.isFilled -> MaterialTheme.colorScheme.onSurfaceVariant
                    report!!.hasReportedIssue -> MaterialTheme.colorScheme.error
                    androidx.compose.foundation.isSystemInDarkTheme() -> Color(0xFF22A05A)
                    else -> Color(0xFF187542)
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                Text(
                    text = when {
                        error -> "Не вдалося перевірити повідомлення водоканалу."
                        report == null -> "Отримуємо дані водоканалу."
                        !report!!.addressSpecified -> "Вкажіть повну адресу для перевірки повідомлень."
                        report!!.hasReportedIssue -> "У повідомленнях водоканалу знайдено можливий збіг за вашою адресою."
                        else -> "У перевірених повідомленнях водоканалу збігів за вашою адресою не знайдено. Це не гарантує фактичну наявність води."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text("Актуальні повідомлення водоканалу", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Показуємо до 3 повідомлень за сьогодні. Якщо сьогодні нових немає — 3 останні публікації (вони можуть бути за минулі дні).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val notices = report?.notices.orEmpty()
        if (notices.isEmpty() && !error) CircularProgressIndicator()
        if (notices.isEmpty() && error) Text(
            "Не вдалося завантажити повідомлення. Спробуємо ще раз автоматично через 15 секунд.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        notices.take(3).forEach { item ->
            val relevant = report?.relevant?.any { it.id == item.id } == true
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(if (item.restored) "Відновлення водопостачання" else "Обмеження водопостачання", fontWeight = FontWeight.Bold)
                    Text("Опубліковано: ${item.date}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (relevant) Text("Можливо стосується вашої адреси", color = MaterialTheme.colorScheme.error)
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { openedNotice = item }, modifier = Modifier.fillMaxWidth()) {
                        Text("Читати повністю")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        OutlinedButton(onClick = { open(WaterNoticeRepository.OFFICIAL_SITE) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.WaterDrop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Сайт водоканалу")
        }
        Text("Джерело повідомлень: публічний канал ОКВП «Дніпро-Кіровоград». Відсутність збігу не гарантує наявність води. Інформація може оновлюватися із затримкою.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.SupportAgent, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Контакти водоканалу", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text("Кол-центр 24/7. Натисніть номер, щоб відкрити набір телефону.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val phones = listOf(
                    "(0522) 368 368" to "+380522368368",
                    "050 488 86 91" to "+380504888691",
                    "050 488 86 92" to "+380504888692",
                    "050 424 65 00" to "+380504246500",
                    "067 414 41 74" to "+380674144174"
                )
                phones.forEach { (label, phone) ->
                    OutlinedButton(onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) }
                        catch (_: Exception) { }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
                OutlinedButton(onClick = { open("https://dnipro-kirovograd.com.ua/contacts") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Усі контакти на сайті")
                }
                HorizontalDivider()
                OutlinedButton(onClick = onOpenTariffs, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Тарифи на воду")
                }
                Text("Особистий кабінет", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(onClick = { showCabinetWarning = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Увійти в особистий кабінет")
                }
            }
        }
    }
    openedNotice?.let { notice ->
        Dialog(
            onDismissRequest = { openedNotice = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { openedNotice = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }
                        Text(
                            "Повідомлення водоканалу",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(
                        Modifier.weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (notice.restored) "Відновлення водопостачання"
                            else "Повідомлення про водопостачання",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Опубліковано: ${notice.date}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
                        notice.text.lines().forEach { raw ->
                            val line = raw.trim()
                            if (line.isNotEmpty()) {
                                val heading = line.matches(Regex("""^\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?.*""")) ||
                                        line.startsWith("УВАГА", ignoreCase = true) ||
                                        line.startsWith("Шановні", ignoreCase = true)
                                Text(
                                    text = line,
                                    style = if (heading) MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal,
                                    color = if (heading) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        HorizontalDivider()
                        OutlinedButton(
                            onClick = { open(notice.url) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Відкрити оригінал повідомлення")
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (showCabinetWarning) {
        AlertDialog(
            onDismissRequest = { showCabinetWarning = false },
            title = { Text("Перехід на зовнішній сайт") },
            text = { Text("КропіLive не запитує, не збирає та не зберігає ваш логін і пароль. Ви перейдете на офіційний сайт особистого кабінету ОКВП «Дніпро-Кіровоград». Переконайтеся, що адреса сайту — my.dnipro-kirovograd.com.ua.") },
            confirmButton = { TextButton(onClick = { showCabinetWarning = false; open("https://my.dnipro-kirovograd.com.ua") }) { Text("Прийняти") } },
            dismissButton = { TextButton(onClick = { showCabinetWarning = false }) { Text("Скасувати") } }
        )
    }
}


@Composable
fun WaterTariffsScreen(onBack: () -> Unit, open: (String) -> Unit) {
    var category by remember { mutableIntStateOf(0) }
    val source = if (category == 0) WaterTariffsRepository.PHYSICAL else WaterTariffsRepository.LEGAL
    var page by remember { mutableStateOf<WaterTariffsPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(category) {
        loading = true
        page = null
        try { page = WaterTariffsRepository.load(source) }
        catch (_: Exception) { page = null }
        loading = false
    }
    // Parent SchedulesScreen already owns the vertical scroll. Do NOT nest a
    // fillMaxSize/verticalScroll here: it can crash with infinite height constraints.
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
            Spacer(Modifier.width(4.dp))
            Text("Тарифи на воду", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("ОКВП «Дніпро-Кіровоград»", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Централізоване водопостачання та водовідведення",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Фізичні особи", "Юридичні особи").forEachIndexed { index, label ->
                FilterChip(
                    selected = category == index,
                    onClick = { category = index },
                    label = { Text(label, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (page != null && page!!.lines.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Інформація з сайту водоканалу", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    page!!.lines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else if (!loading) {
            Text("Сайт водоканалу не надав доступного тексту тарифів. Нижче — останні перевірені значення за постановою НКРЕКП № 2285 від 30.12.2025.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (category == 0) {
                WaterPriceCard("Водопостачання", "21,01", "25,21")
                WaterPriceCard("Водовідведення", "17,30", "20,76")
            } else {
                WaterPriceCard("Водопостачання · інші споживачі", "50,24", "60,29")
                WaterPriceCard("Водовідведення · інші споживачі", "35,95", "43,14")
                WaterPriceCard("Водопостачання · підприємства у сфері водопостачання", "22,51", "27,01")
            }
            Text("Довідкові тарифи НКРЕКП з 01.01.2026. Перевіряйте чинність на офіційному сайті. Ці суми не є результатом поточного завантаження.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { open(source) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Перевірити на офіційному сайті")
        }
    }
}

@Composable
private fun WaterPriceCard(title: String, noVat: String, vat: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$noVat грн/м³ без ПДВ", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("$vat грн/м³ з ПДВ (розрахунково)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}