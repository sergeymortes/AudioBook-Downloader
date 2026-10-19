package ua.kropilive.app.ui.schedules

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ua.kropilive.app.data.repository.SharedGasState
import ua.kropilive.app.data.repository.GasNoticeRepository
import ua.kropilive.app.data.repository.GasReport
import ua.kropilive.app.data.repository.UserAddress
import androidx.compose.ui.platform.LocalContext

/**
 * Gas section, deliberately without an inner verticalScroll:
 * SchedulesScreen already owns the scrolling container.
 */
@Composable
fun GasContent(address: UserAddress, onOpenTariffs: () -> Unit) {
    val context = LocalContext.current
    val report = SharedGasState.forAddress(address)
    val loading = SharedGasState.loading && report == null
    val failed = report?.verified == false
    var openedNotice by remember { mutableStateOf<ua.kropilive.app.data.repository.GasNotice?>(null) }
    var showCabinetWarning by remember { mutableStateOf(false) }

    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { }
    }

    fun dial(phone: String) {
        try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) }
        catch (_: Exception) { }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = Color(0xFFF97316))
                    Text("Газопостачання · Газмережі", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(if (address.isFilled) address.formattedAddress else "Оберіть адресу в налаштуваннях")
                val status = when {
                    !address.isFilled -> "Оберіть адресу"
                    loading && report == null -> "Перевірка повідомлень…"
                    failed || report?.verified != true -> "Статус не підтверджено"
                    report.hasReportedIssue -> "Є повідомлення про відключення"
                    report.hasUpcomingOutage -> "Заплановане відключення"
                    else -> "Відключень не оголошено"
                }
                val statusColor = when {
                    !address.isFilled || report?.verified != true -> MaterialTheme.colorScheme.onSurfaceVariant
                    report.hasReportedIssue -> MaterialTheme.colorScheme.error
                    report.hasUpcomingOutage -> if (isSystemInDarkTheme()) Color(0xFFFBBF24) else Color(0xFF9A5700)
                    isSystemInDarkTheme() -> Color(0xFF22A05A)
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
                        !address.isFilled -> "Вкажіть повну адресу для перевірки повідомлень."
                        failed || (report == null && !loading) -> "Не вдалося перевірити повідомлення Газмережі."
                        report == null -> "Отримуємо дані Газмережі."
                        report.hasReportedIssue -> "За повідомленнями Газмережі знайдено збіг із вашою адресою."
                        report.hasUpcomingOutage -> "У повідомленнях Газмережі є заплановане відключення для вашої адреси."
                        else -> "У перевірених повідомленнях Газмережі збігів за вашою адресою не знайдено. Це не гарантує фактичну наявність газу."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        Text("Актуальні повідомлення Газмережі", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Показуємо до 3 доступних повідомлень. Дати в оголошеннях можуть відрізнятися від дати публікації.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val notices = report?.notices.orEmpty()
        if (notices.isEmpty() && !loading) {
            Text(
                if (failed) "Не вдалося перевірити оголошення. Спробуйте оновити пізніше."
                else "Актуальних оголошень у доступній вибірці немає.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        notices.forEach { notice ->
            val relevant = report?.verified == true && report?.relevant?.any { it.id == notice.id } == true
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        if (notice.restored) "Відновлення газопостачання" else "Повідомлення про газопостачання",
                        fontWeight = FontWeight.Bold
                    )
                    Text("Дати відключень: ${notice.date}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (relevant) Text("Можливо стосується вашої адреси", color = MaterialTheme.colorScheme.error)
                    Text(
                        notice.text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { openedNotice = notice }, modifier = Modifier.fillMaxWidth()) {
                        Text("Читати повністю")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.OpenInNew, null)
                    }
                    TextButton(onClick = { open(notice.url) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Офіційне повідомлення")
                    }
                }
            }
        }

        OutlinedButton(onClick = { open(GasNoticeRepository.OFFICIAL_SITE) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.LocalFireDepartment, null)
            Spacer(Modifier.width(8.dp))
            Text("Сайт Кропивницької філії Газмережі")
        }
        Text(
            "Джерело відключень: Кропивницька філія «Газмережі». Пошук виконується за оприлюдненими адресами, а не за фактичним станом мережі. У разі запаху газу телефонуйте 104.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.SupportAgent, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Контакти газових служб", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text("Аварійна газова служба", fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { dial(GasNoticeRepository.EMERGENCY_PHONE) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Call, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("104 · аварійна служба")
                }
                OutlinedButton(onClick = { dial(GasNoticeRepository.OPERATOR_PHONE) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Call, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("0 800 303 104 · Газмережі")
                }
                Text(
                    "Кол-центр: пн–пт 08:00–17:00, сб–нд 09:00–18:00. Під час повітряної тривоги консультації можуть бути недоступні.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { dial("0800357291") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Call, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("0 800 357 291 · Лінія довіри")
                }
                Text(
                    "Лінія довіри: пн–пт 09:00–18:00, для повідомлень про порушення етики, не для аварій.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { open("https://kr.grmu.com.ua/контакти/") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Усі контакти Газмережі")
                }
                HorizontalDivider()
                OutlinedButton(onClick = onOpenTariffs, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ReceiptLong, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Тарифи на газ")
                }
                Text("Особистий кабінет", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(onClick = { showCabinetWarning = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Увійти в особистий кабінет Газмережі")
                }
            }
        }
    }

    openedNotice?.let { notice ->
        Dialog(
            onDismissRequest = { openedNotice = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { openedNotice = null }) {
                            Icon(Icons.Default.ArrowBack, "Назад")
                        }
                        Text("Повідомлення Газмережі", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (notice.restored) "Відновлення газопостачання" else "Тимчасове припинення газопостачання",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Дати, зазначені в оголошенні: ${notice.date}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Дата публікації не підтверджена джерелом", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        notice.text.lines().forEach { raw ->
                            val line = raw.trim()
                            if (line.isNotEmpty()) {
                                val dateHeading = Regex("""^\d{1,2}(?:\s*[-–]\s*\d{1,2})?\.\s*\d{1,2}\.\s*20\d{2}.*""").containsMatchIn(line)
                                Text(
                                    line,
                                    style = if (dateHeading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (dateHeading || line.contains("ШАНОВНІ АБОНЕНТИ", true)) FontWeight.Bold else FontWeight.Normal,
                                    color = if (dateHeading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        HorizontalDivider()
                        OutlinedButton(onClick = { open(notice.url) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.OpenInNew, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Відкрити офіційне джерело")
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
            text = {
                Text("Кроп Live не запитує і не зберігає ваші облікові дані. Відкриється офіційний особистий кабінет my.grmu.com.ua. Авторизуйтеся лише після перевірки адреси сайту.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showCabinetWarning = false
                    open("https://my.grmu.com.ua/")
                }) { Text("Продовжити") }
            },
            dismissButton = {
                TextButton(onClick = { showCabinetWarning = false }) { Text("Скасувати") }
            }
        )
    }
}

@Composable
fun GasTariffsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { }
    }
    // Parent SchedulesScreen owns vertical scrolling.
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Spacer(Modifier.width(4.dp))
            Text("Тарифи на газ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.ReceiptLong, null, tint = MaterialTheme.colorScheme.primary)
                Text("Нафтогаз України", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Постачання природного газу побутовим споживачам", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Тариф «Фіксований»", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("7,96 грн/м³", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Довідкова ціна для побутових споживачів. Не є результатом поточного завантаження з сайту.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Вартість доставки (розподілу) газу оплачується окремо за тарифом оператора мережі.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = { open(GasNoticeRepository.TARIFF_URL) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Перевірити тариф на офіційному сайті")
        }
        OutlinedButton(onClick = { open(GasNoticeRepository.OFFICIAL_SITE) }, modifier = Modifier.fillMaxWidth()) {
            Text("Оператор газорозподільних мереж")
        }
    }
}