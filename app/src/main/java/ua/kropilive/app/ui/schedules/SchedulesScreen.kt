package ua.kropilive.app.ui.schedules

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.kropilive.app.data.repository.StartupPreload
import ua.kropilive.app.data.repository.AddressRepository
import ua.kropilive.app.data.repository.UserAddress
import ua.kropilive.app.data.repository.ElectricityOutageRepository
import ua.kropilive.app.data.repository.OutageKind
import ua.kropilive.app.data.repository.OutageRecord
import kotlinx.coroutines.launch
import kotlinx.coroutines.async

/** Shared electricity results; HomeScreen publishes, SchedulesScreen only reads. */
object SharedElectricityState {
    var addressKey by mutableStateOf("")
    var queue by mutableStateOf("")
    var siteCheck by mutableStateOf<ua.kropilive.app.data.repository.HourlyGraphCheck?>(null)
    var telegramCheck by mutableStateOf<ua.kropilive.app.data.repository.TelegramGraphCheck?>(null)
    var planned by mutableStateOf<ua.kropilive.app.data.repository.OutageResult?>(null)
    var emergency by mutableStateOf<ua.kropilive.app.data.repository.OutageResult?>(null)
    var day by mutableStateOf("")

    fun matches(address: UserAddress): Boolean =
        day == java.time.LocalDate.now().toString() &&
                queue == address.electricityQueue &&
                addressKey == listOf(address.communityId, address.cityName, address.street, address.building).joinToString("|")
}

private val ElectricityYellow = Color(0xFFFACC15)
private val WaterBlue = Color(0xFF38BDF8)
private val GasOrange = Color(0xFFFB923C)
private val WasteGreen = Color(0xFF1F7A45)

private enum class UtilityType(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val url: String
) {
    ELECTRICITY(
        "Світло",
        Icons.Default.FlashOn,
        ElectricityYellow,
        "https://kiroe.com.ua/electricity-blackout"
    ),
    WATER(
        "Вода",
        Icons.Default.WaterDrop,
        WaterBlue,
        "https://t.me/dniprokirovograd"
    ),
    GAS(
        "Газ",
        Icons.Default.LocalFireDepartment,
        GasOrange,
        "https://kr.grmu.com.ua/"
    ),
    WASTE(
        "Відходи",
        Icons.Default.Delete,
        WasteGreen,
        "https://www.ecostyle.ua/grafik"
    )
}

@Composable
fun SchedulesScreen(
    openWater: Boolean = false,
    openEcostyle: Boolean = false,
    openGas: Boolean = false,
    navigationRequestId: Int = 0
) {
    val context = LocalContext.current

    val addressRepository = remember(context) {
        AddressRepository(context.applicationContext)
    }

    val userAddress by addressRepository.address.collectAsState(
        initial = UserAddress()
    )

    var selectedType by remember {
        mutableStateOf(
            when {
                openEcostyle -> UtilityType.WASTE
                openGas -> UtilityType.GAS
                openWater -> UtilityType.WATER
                else -> UtilityType.ELECTRICITY
            }
        )
    }

    LaunchedEffect(navigationRequestId) {
        when {
            openEcostyle -> selectedType = UtilityType.WASTE
            openGas -> selectedType = UtilityType.GAS
            openWater -> selectedType = UtilityType.WATER
        }
    }

    var showEcostyleTariffs by remember { mutableStateOf(false) }
    var showElectricityTariffs by remember { mutableStateOf(false) }
    var showWaterTariffs by remember { mutableStateOf(false) }
    var showGasTariffs by remember { mutableStateOf(false) }
    BackHandler(enabled = showEcostyleTariffs || showElectricityTariffs || showWaterTariffs || showGasTariffs) {
        showEcostyleTariffs = false
        showElectricityTariffs = false
        showWaterTariffs = false
        showGasTariffs = false
    }
    if (showGasTariffs) {
        GasTariffsFullScreen(onBack = { showGasTariffs = false })
        return
    }
    if (showWaterTariffs) {
        WaterTariffsFullScreen(onBack = { showWaterTariffs = false })
        return
    }
    if (showElectricityTariffs) {
        ElectricityTariffsScreen(onBack = { showElectricityTariffs = false })
        return
    }

    if (showEcostyleTariffs) {
        EcostyleTariffsScreen(onBack = { showEcostyleTariffs = false })
        return
    }

    fun openOfficialSource() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(selectedType.url)
        )

        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Відсутність браузера не повинна закривати застосунок.
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        Text(
            text = "Графіки та відключення",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )

            Text(
                text = "Кіровоградська область",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Адаптивна панель: на вузьких екранах — 2 × 2,
        // на ширших — один ряд без обрізання назв.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val availableWidth = maxWidth
            val compact = availableWidth < 390.dp
            val showIcons = compact || availableWidth >= 510.dp

            @Composable
            fun UtilityChip(type: UtilityType, modifier: Modifier = Modifier) {
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                    modifier = modifier,
                    label = {
                        Text(
                            text = type.title,
                            maxLines = 1,
                            softWrap = false,
                            fontSize = if (compact) 14.sp else 13.sp
                        )
                    },
                    leadingIcon = if (showIcons) {
                        {
                            Icon(
                                imageVector = type.icon,
                                contentDescription = null,
                                tint = type.color,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null
                )
            }

            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    UtilityType.entries.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { type ->
                                UtilityChip(type, Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    UtilityType.entries.forEach { type ->
                        UtilityChip(type, Modifier.weight(1f))
                    }
                }
            }
        }

        when (selectedType) {
            UtilityType.ELECTRICITY -> {
                ElectricityContent(
                    address = userAddress,
                    onOpenSource = ::openOfficialSource,
                    onOpenTariffs = { showElectricityTariffs = true }
                )
            }

            UtilityType.WATER -> { WaterContent(address = userAddress, onOpenTariffs = { showWaterTariffs = true }) }

            UtilityType.GAS -> {
                GasContent(address = userAddress, onOpenTariffs = { showGasTariffs = true })
            }

            UtilityType.WASTE -> {
                EcostyleContent(address = userAddress, onOpenTariffs = { showEcostyleTariffs = true })
            }
        }

        Text(
            text = "Інформація про відключення має " +
                    "підтверджуватися офіційними джерелами.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ElectricityContent(
    address: UserAddress,
    onOpenSource: () -> Unit,
    onOpenTariffs: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { ElectricityOutageRepository() }
    var selectedKind by remember { mutableStateOf(OutageKind.PLANNED) }

    // No network calls: use exactly the state already checked on Home.
    val shared = SharedElectricityState
    val valid = shared.matches(address)
    val planned = if (valid) shared.planned else null
    val emergency = if (valid) shared.emergency else null
    val siteCheck = if (valid) shared.siteCheck else null
    val telegramCheck = if (valid) shared.telegramCheck else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.FlashOn, null, tint = ElectricityYellow)
                Text("Електропостачання", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text("ВАША ЧЕРГА", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (!address.hasFullAddress) "Адресу не вибрано" else address.electricityQueue.ifBlank { "Черга невідома" },
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                if (address.hasFullAddress) address.formattedAddress else "Оберіть домашню адресу в налаштуваннях",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            HourlyBlackoutCard(
                queue = address.electricityQueue,
                siteCheck = siteCheck,
                telegramCheck = telegramCheck
            )
            Button(onClick = onOpenSource, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text("Графік черги на сайті")
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selectedKind == OutageKind.PLANNED,
            onClick = { selectedKind = OutageKind.PLANNED },
            label = { Text("Планові") }
        )
        FilterChip(
            selected = selectedKind == OutageKind.EMERGENCY,
            onClick = { selectedKind = OutageKind.EMERGENCY },
            label = { Text("Аварійні") }
        )
    }

    val result = if (selectedKind == OutageKind.PLANNED) planned else emergency
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (selectedKind == OutageKind.PLANNED) "Планові роботи за адресою" else "Аварійні відключення",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
            )
            when {
                !address.hasFullAddress -> Text("Спочатку виберіть адресу в налаштуваннях")
                result == null -> Text("Очікуємо результатів перевірки на головній.")
                result.matching.isNotEmpty() -> {
                    result.matching.forEach { record -> OutageRecordCard(record) }
                }
                else -> Text(
                    if (result.message.isNotBlank()) result.message
                    else "У завантаженому списку вашу адресу не знайдено. Це не гарантує відсутність відключень.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(repository.sourceUrl(selectedKind, address)))
                    try { context.startActivity(intent) } catch (_: Exception) { }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text("Відкрити офіційне джерело")
            }
        }
    }
    SourceInfoCard("Джерело: Кіровоградобленерго. Дані надходять із перевірки на головній без повторних запитів. Перевіряйте інформацію на сайті оператора.")
    ElectricityContactsSection(onOpenTariffs = onOpenTariffs)
}

@Composable
private fun ElectricityContactsSection(onOpenTariffs: () -> Unit) {
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
                Text("Контакти Кіровоградобленерго", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text("Кол-центр 24/7. Натисніть номер, щоб відкрити набір телефону.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

            val phones = listOf(
                Triple("0 800 300 552", "Безкоштовно", "0800300552"),
                Triple("0 800 501 148", "Безкоштовно", "0800501148"),
                Triple("050 488 80 63", "Vodafone", "0504888063"),
                Triple("067 521 15 70", "Київстар", "0675211570")
            )
            phones.forEach { (display, description, dial) ->
                OutlinedButton(onClick = { openDialer(dial) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("$display · $description")
                }
            }
            HorizontalDivider()
            OutlinedButton(onClick = onOpenTariffs, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Тарифи Кіровоградобленерго")
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
                            "Якщо ви маєте сумніви, можете самостійно відкрити https://ok.kiroe.com.ua/company на комп’ютері."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLoginWarning = false
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ok.kiroe.com.ua/company"))
                    try { context.startActivity(intent) } catch (_: Exception) { }
                }) { Text("Прийняти") }
            },
            dismissButton = {
                TextButton(onClick = { showLoginWarning = false }) { Text("Скасувати") }
            }
        )
    }
}

@Composable
private fun OutageRecordCard(record: OutageRecord) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Знайдено запис за вашою адресою", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text("Початок: ${record.start}", color = MaterialTheme.colorScheme.onErrorContainer)
            Text("Закінчення: ${record.end}", color = MaterialTheme.colorScheme.onErrorContainer)
            if (record.reason.isNotBlank()) Text("Причина: ${record.reason}", color = MaterialTheme.colorScheme.onErrorContainer)
            Text(record.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun UtilityPlaceholder(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    description: String,
    onOpenSource: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(28.dp)
                )

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Construction,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(52.dp)
                )

                Text(
                    text = "Дані поки недоступні",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onOpenSource,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(Modifier.width(8.dp))

                Text("Відкрити джерело")
            }
        }
    }

    Spacer(Modifier.height(2.dp))

    SourceInfoCard(
        text = "Повідомлення про відключення та аварійні " +
                "роботи ще не завантажуються автоматично. " +
                "Поки що користуйтеся посиланням на джерело."
    )
}

@Composable
private fun SourceInfoCard(
    text: String
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )

            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}



@Composable
private fun WaterTariffsFullScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) { }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            WaterTariffsScreen(onBack = onBack, open = ::open)
        }
    }
}

@Composable
private fun GasTariffsFullScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GasTariffsScreen(onBack = onBack)
        }
    }
}