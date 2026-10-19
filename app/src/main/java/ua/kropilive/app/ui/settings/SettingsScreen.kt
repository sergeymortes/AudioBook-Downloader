package ua.kropilive.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.kropilive.app.KiroeAddressPicker
import ua.kropilive.app.data.repository.AddressRepository
import ua.kropilive.app.data.repository.ElectricityQueueChecker
import kotlinx.coroutines.launch
import ua.kropilive.app.data.repository.UserAddress
import ua.kropilive.app.ui.theme.ThemeMode
import ua.kropilive.app.background.NotificationPreferences
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current

    val addressRepository = remember(context) {
        AddressRepository(context)
    }

    val savedAddress by addressRepository.address.collectAsState(
        initial = UserAddress()
    )

    var showAddressPicker by remember {
        mutableStateOf(false)
    }

    var showNotificationSettings by remember { mutableStateOf(false) }
    var showThemeDialog by remember {
        mutableStateOf(false)
    }

    var showAboutDialog by remember { mutableStateOf(false) }
    val queueChecker = remember(context) { ElectricityQueueChecker(context) }
    val scope = rememberCoroutineScope()
    var checkingQueue by remember { mutableStateOf(false) }
    var queueCheckResult by remember { mutableStateOf<ElectricityQueueChecker.Result?>(null) }

    var infoDialog by remember {
        mutableStateOf<String?>(null)
    }

    val hasAddress = savedAddress.isFilled
    val canRefreshQueue = savedAddress.hasFullAddress

    val themeName = when (themeMode) {
        ThemeMode.SYSTEM -> "Як у системі"
        ThemeMode.LIGHT -> "Світла"
        ThemeMode.DARK -> "Темна"
    }

    if (showNotificationSettings) {
        NotificationSettingsScreen(onBack = { showNotificationSettings = false })
        return
    }

    if (showAddressPicker) {
        KiroeAddressPicker(
            onBack = {
                showAddressPicker = false
            },
            onSaved = {
                showAddressPicker = false
            }
        )

        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Text(
            text = "Налаштування",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(2.dp))

        SettingsSectionTitle("МОЯ АДРЕСА")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Домашня адреса",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (hasAddress) {
                            if (savedAddress.cityName.isNotBlank()) {
                                Text(
                                    text = savedAddress.cityName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Text(
                                text = "${savedAddress.street}, ${savedAddress.building}",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )

                                Text(
                                    text = if (canRefreshQueue) {
                                        "Адресу підтверджено"
                                    } else {
                                        "Стара адреса — потрібно оновити"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = "Адресу ще не додано",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = "Додайте адресу для персонального графіка світла",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            showAddressPicker = true
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            horizontal = 8.dp,
                            vertical = 10.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )

                        Spacer(Modifier.width(6.dp))

                        Text(
                            text = if (hasAddress) {
                                "Змінити"
                            } else {
                                "Додати"
                            },
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (!checkingQueue) {
                                val addressToCheck = savedAddress
                                checkingQueue = true
                                scope.launch {
                                    try {
                                        val result = queueChecker.check(addressToCheck)
                                        queueCheckResult = when (result) {
                                            is ElectricityQueueChecker.Result.Changed -> {
                                                val saved = addressRepository.updateElectricityQueue(
                                                    newQueue = result.newQueue,
                                                    expectedHouseId = addressToCheck.houseId
                                                )
                                                if (saved) result else ElectricityQueueChecker.Result.Error(
                                                    "Адресу було змінено під час перевірки. Спробуйте ще раз."
                                                )
                                            }
                                            else -> result
                                        }
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        queueCheckResult = ElectricityQueueChecker.Result.Error(
                                            "Не вдалося оновити чергу. Спробуйте ще раз."
                                        )
                                    } finally {
                                        checkingQueue = false
                                    }
                                }
                            }
                        },
                        enabled = canRefreshQueue && !checkingQueue,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            horizontal = 8.dp,
                            vertical = 10.dp
                        )
                    ) {
                        if (checkingQueue) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        Text(
                            text = if (checkingQueue) "Перевірка..." else "Оновити чергу",
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        SettingsSectionTitle("СИСТЕМА")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column {
                SettingsMenuItem(
                    icon = Icons.Default.Palette,
                    title = "Оформлення",
                    subtitle = themeName,
                    onClick = {
                        showThemeDialog = true
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                SettingsMenuItem(
                    icon = Icons.Default.Notifications,
                    title = "Сповіщення",
                    subtitle = "Зміни черги та важливі повідомлення",
                    onClick = {
                        showNotificationSettings = true
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                SettingsMenuItem(
                    icon = Icons.Default.Sync,
                    title = "Оновлення даних",
                    subtitle = "Перевірка та час останнього оновлення",
                    onClick = {
                        infoDialog =
                            "Тут будуть налаштування оновлення " +
                                    "даних Кроп Live."
                    }
                )
            }
        }

        SettingsSectionTitle("ПРО ЗАСТОСУНОК")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            SettingsMenuItem(
                icon = Icons.Default.Info,
                title = "Кроп Live",
                subtitle = "Версія, розробник та інформація",
                onClick = { showAboutDialog = true }
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            SettingsMenuItem(
                icon = Icons.Default.Share,
                title = "Поділитися застосунком",
                subtitle = "Надіслати посилання на завантаження",
                onClick = {
                    val apkUrl = "https://github.com/sergeymortes/KropLive/releases/latest/download/app-release.apk"
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Кроп Live")
                        putExtra(Intent.EXTRA_TEXT, "Завантажити Кроп Live:\n$apkUrl")
                    }
                    context.startActivity(
                        Intent.createChooser(shareIntent, "Поділитися Кроп Live")
                    )
                }
            )
        }

        Spacer(Modifier.height(12.dp))
    }

    queueCheckResult?.let { result ->
        val (title, message) = when (result) {
            is ElectricityQueueChecker.Result.Unchanged ->
                "Черга не змінилася" to "За вашою адресою залишається черга ${result.queue}. Дані перевірено."
            is ElectricityQueueChecker.Result.Changed ->
                "Чергу змінено" to "Попередня черга: ${result.previousQueue.ifBlank { "не визначена" }}\nНова черга: ${result.newQueue}.\n\nДані збережено."
            is ElectricityQueueChecker.Result.Error ->
                "Не вдалося перевірити чергу" to result.message
        }
        AlertDialog(
            onDismissRequest = { queueCheckResult = null },
            icon = {
                Icon(
                    imageVector = when (result) {
                        is ElectricityQueueChecker.Result.Unchanged -> Icons.Default.CheckCircle
                        is ElectricityQueueChecker.Result.Changed -> Icons.Default.Sync
                        is ElectricityQueueChecker.Result.Error -> Icons.Default.WarningAmber
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { queueCheckResult = null }) { Text("Зрозуміло") }
            }
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("Тема оформлення")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Оберіть вигляд застосунку",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(4.dp))

                    ThemeDialogOption(
                        icon = Icons.Default.Smartphone,
                        title = "Системна",
                        subtitle = "Як у налаштуваннях телефону",
                        selected = themeMode == ThemeMode.SYSTEM,
                        onClick = {
                            onThemeChange(ThemeMode.SYSTEM)
                            showThemeDialog = false
                        }
                    )

                    ThemeDialogOption(
                        icon = Icons.Default.WbSunny,
                        title = "Світла",
                        subtitle = "Світле оформлення",
                        selected = themeMode == ThemeMode.LIGHT,
                        onClick = {
                            onThemeChange(ThemeMode.LIGHT)
                            showThemeDialog = false
                        }
                    )

                    ThemeDialogOption(
                        icon = Icons.Default.DarkMode,
                        title = "Темна",
                        subtitle = "Темне оформлення",
                        selected = themeMode == ThemeMode.DARK,
                        onClick = {
                            onThemeChange(ThemeMode.DARK)
                            showThemeDialog = false
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Готово")
                }
            }
        )
    }

    if (showAboutDialog) {
        val packageInfo = remember(context) {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
                .getOrNull()
        }
        val version = packageInfo?.versionName ?: "Невідомо"
        val updatedAt = packageInfo?.lastUpdateTime
            ?.takeIf { it > 0L }
            ?.let {
                SimpleDateFormat("dd.MM.yyyy", Locale("uk", "UA")).format(Date(it))
            } ?: "Невідомо"

        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Кроп Live",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Інформаційний застосунок для Кропивницького та Кіровоградської області",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    AboutInfoRow("Версія", version)
                    AboutInfoRow("Рік", "2026")
                    AboutInfoRow("Останнє оновлення", updatedAt)
                    HorizontalDivider()
                    Text(
                        text = "Розробник",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        onClick = {
                            val link = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/sergeymortes/KropLive")
                            )
                            runCatching { context.startActivity(link) }
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null)
                            Text(
                                "sergeymortes",
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(Icons.Default.OpenInNew, contentDescription = "Відкрити GitHub")
                        }
                    }
                    Text(
                        "Дата оновлення — дата встановлення або оновлення застосунку на цьому пристрої.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Закрити")
                }
            }
        )
    }

    if (infoDialog != null) {
        AlertDialog(
            onDismissRequest = {
                infoDialog = null
            },
            title = {
                Text("Кроп Live")
            },
            text = {
                Text(infoDialog.orEmpty())
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        infoDialog = null
                    }
                ) {
                    Text("Зрозуміло")
                }
            }
        )
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SettingsSectionTitle(
    title: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
            start = 4.dp,
            top = 4.dp,
            bottom = 2.dp
        )
    )
}

@Composable
private fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 17.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(23.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun ThemeDialogOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        onClick = onClick,
        color = background,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RadioButton(
                selected = selected,
                onClick = null
            )
        }
    }
}
@Composable
private fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val sections = listOf(
        Triple("Світло", Icons.Default.Bolt, listOf(
            "power_off" to "За 15 хвилин до відключення",
            "power_on" to "За 15 хвилин до відновлення")),
        Triple("Вода", Icons.Default.WaterDrop, listOf(
            "water" to "Повідомлення для моєї адреси")),
        Triple("Відходи", Icons.Default.DeleteOutline, listOf(
            "bulky" to "Великогабаритні — за день",
            "construction" to "Будівельні — за день")),
        Triple("Повітряна тривога", Icons.Default.WarningAmber, listOf(
            "air" to "Оповіщення для мого району"))
    )
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var state by remember { mutableStateOf(sections.flatMap { it.third }.associate { it.first to NotificationPreferences.get(context, it.first) }) }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
            Text("Сповіщення", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Column(Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("Оновлення у фоні залежить від Android. Увімкнені перемикачі зберігають ваші побажання, але системні сповіщення для всіх категорій ще не гарантовані.",
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sections.forEach { (title, icon, entries) ->
                Card(shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier
                        .fillMaxWidth()
                        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        entries.forEachIndexed { index, (key, label) ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = state[key] == true, onCheckedChange = {
                                    NotificationPreferences.set(context, key, it)
                                    state = state + (key to it)
                                    if (it && Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                })
                            }
                        }
                    }
                }
            }


        }
    }
}