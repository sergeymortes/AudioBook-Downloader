package ua.kropilive.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import android.os.SystemClock
import ua.kropilive.app.data.repository.AirAlertLevel
import ua.kropilive.app.data.repository.AirAlertState
import ua.kropilive.app.data.repository.AirAlertRepository
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ua.kropilive.app.background.AirAlertMonitorService
import ua.kropilive.app.background.BackgroundRefresh
import ua.kropilive.app.KiroeAddressPicker
import ua.kropilive.app.data.repository.UserAddress
import ua.kropilive.app.data.repository.AddressRepository
import ua.kropilive.app.data.repository.StartupPreload
import ua.kropilive.app.ui.home.HomeScreen
import ua.kropilive.app.ui.schedules.SchedulesScreen
import ua.kropilive.app.ui.settings.SettingsScreen
import ua.kropilive.app.ui.theme.ThemeMode
import ua.kropilive.app.ui.weather.WeatherScreen
import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import ua.kropilive.app.updates.AppUpdateInfo
import ua.kropilive.app.updates.GitHubUpdateChecker
import ua.kropilive.app.updates.GitHubApkDownloader
import ua.kropilive.app.updates.GitHubApkInstaller
import ua.kropilive.app.updates.UpdateCheckResult
import ua.kropilive.app.updates.ApkDownloadResult
import ua.kropilive.app.updates.ApkInstallResult
import ua.kropilive.app.updates.UpdateDialog
import ua.kropilive.app.updates.UpdateDownloadState
import java.io.File

// A swipe from Recents may destroy Activity but keep the app process alive.
// In that case HomeScreen can restore its own cached data without replaying splash.
private object StartupSession {
    @Volatile var completed = false
    @Volatile var lastAddress: UserAddress? = null
    @Volatile var lastAirState: AirAlertState? = null
    @Volatile var lastAirSuccessElapsed: Long = 0L
}

private enum class AppTab(
    val title: String,
    val icon: ImageVector
) {
    HOME("Головна", Icons.Default.Home),
    WEATHER("Погода", Icons.Default.Cloud),
    SCHEDULES("Графіки", Icons.Default.CalendarMonth),
    EVENTS("Події", Icons.Default.Article),
    SETTINGS("Налашт.", Icons.Default.Settings)
}

@Composable
fun MainNavigation(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    var selectedTab by remember {
        mutableStateOf(AppTab.HOME)
    }

    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val updateChecker = remember(context) { GitHubUpdateChecker(context) }
    val apkDownloader = remember(context) { GitHubApkDownloader(context) }
    val apkInstaller = remember(context) { GitHubApkInstaller(context) }
    val updateScope = rememberCoroutineScope()
    var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var downloadState by remember { mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle) }
    var pendingApk by remember { mutableStateOf<File?>(null) }
    var waitingForInstallPermission by remember { mutableStateOf(false) }

    // Check GitHub independently: network failures must never block startup.
    LaunchedEffect(Unit) {
        if (!StartupSession.completed) {
            apkDownloader.cleanupDownloadedApk()
            val result = withTimeoutOrNull(7_000L) {
                updateChecker.checkForUpdates()
            }
            if (result is UpdateCheckResult.UpdateAvailable) {
                availableUpdate = result.info
            }
        }
    }


    val addressRepository = remember(context) { AddressRepository(context) }
    // null means the saved address has not been loaded yet.
    val savedAddress by produceState<UserAddress?>(
        initialValue = StartupSession.lastAddress,
        key1 = addressRepository
    ) {
        addressRepository.address.collect { address ->
            StartupSession.lastAddress = address
            value = address
        }
    }
    var showFirstAddressPicker by remember { mutableStateOf(false) }
    val hasSavedAddress = savedAddress?.isFilled == true


    val permissionPrefs = remember {
        context.getSharedPreferences(
            "startup_permissions_v1",
            Context.MODE_PRIVATE
        )
    }

    var showPermissionSetup by remember {
        mutableStateOf(
            !permissionPrefs.getBoolean("setup_completed", false)
        )
    }

    var permissionCheckVersion by remember {
        mutableIntStateOf(0)
    }

    // Refresh permission states on returning from Android settings as well as
    // from the permission dialog. A saved onboarding flag is not a permission.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionCheckVersion++
                if (waitingForInstallPermission && !apkInstaller.canInstallPackages()) {
                    waitingForInstallPermission = false
                    downloadState = UpdateDownloadState.Failed(
                        "Дозвольте встановлення з цього джерела, щоб продовжити"
                    )
                } else if (waitingForInstallPermission && apkInstaller.canInstallPackages()) {
                    waitingForInstallPermission = false
                    pendingApk?.let { apk ->
                        when (val result = apkInstaller.installApk(apk)) {
                            ApkInstallResult.InstallerOpened -> {
                                availableUpdate = null
                                downloadState = UpdateDownloadState.Idle
                            }
                            is ApkInstallResult.Error -> {
                                downloadState = UpdateDownloadState.Failed(result.message)
                            }
                            ApkInstallResult.PermissionRequired -> {
                                waitingForInstallPermission = true
                            }
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionGranted = remember(permissionCheckVersion) {
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    val alarmManager = remember(context) {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    val exactAlarmGranted = remember(permissionCheckVersion) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            permissionCheckVersion++
        }

    val exactAlarmSettingsLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            permissionCheckVersion++
        }

    // Background refresh scheduling does not wait for network requests.
    LaunchedEffect(Unit) {
        BackgroundRefresh.schedule(context)
    }

    var waterRequested by remember {
        mutableStateOf(false)
    }

    // Сохраняем запрос перехода в раздел Екостайл.
    // В следующем шаге подключим его к SchedulesScreen.
    var ecostyleRequested by remember {
        mutableStateOf(false)
    }
    var gasRequested by remember {
        mutableStateOf(false)
    }
    var navigationRequestId by remember {
        mutableIntStateOf(0)
    }

    var showSplash by remember {
        mutableStateOf(!StartupSession.completed)
    }

    // Retained across tab changes: no blank air card on returning Home.
    var lastAirState by remember { mutableStateOf(StartupSession.lastAirState) }
    var lastAirSuccessElapsed by remember { mutableLongStateOf(StartupSession.lastAirSuccessElapsed) }

    // Start the address-based preload and NEPTUN together on the splash screen.
    // The preload writes snapshots that HomeScreen can consume immediately.
    // Never wait forever for a slow or unavailable source.
    LaunchedEffect(Unit) {
        // A new Activity in an existing process is not a cold start.
        // HomeScreen refreshes its own sources and restores NEPTUN cache.
        if (StartupSession.completed) {
            // Restore the verified NEPTUN snapshot without waiting for the full preload.
            // HomeScreen remains responsible for its normal background refresh.
            val address = try {
                addressRepository.address.first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (address != null) {
                StartupSession.lastAddress = address
                val district = AirAlertRepository.districtFor(address)
                val cached = AirAlertRepository.cached(context, district)
                if (cached != null) {
                    val sourceTime = runCatching {
                        Instant.parse(cached.sourceUpdated).toEpochMilli()
                    }.getOrNull()
                    if (sourceTime != null) {
                        val age = (System.currentTimeMillis() - sourceTime).coerceAtLeast(0L)
                        lastAirState = cached
                        lastAirSuccessElapsed =
                            (SystemClock.elapsedRealtime() - age).coerceAtLeast(1L)
                        StartupSession.lastAirState = cached
                        StartupSession.lastAirSuccessElapsed = lastAirSuccessElapsed
                    }
                }
            }
            return@LaunchedEffect
        }

        val startedAt = SystemClock.elapsedRealtime()
        val address = try {
            addressRepository.address.first()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        if (address != null) {
            StartupSession.lastAddress = address
            val district = AirAlertRepository.districtFor(address)
            AirAlertRepository.cached(context, district)?.let { cached ->
                val sourceTime = runCatching { Instant.parse(cached.sourceUpdated).toEpochMilli() }.getOrNull()
                if (sourceTime != null) {
                    val age = (System.currentTimeMillis() - sourceTime).coerceAtLeast(0L)
                    lastAirState = cached
                    lastAirSuccessElapsed = (SystemClock.elapsedRealtime() - age).coerceAtLeast(1L)
                }
            }
            val preloadRequest = async {
                try {
                    StartupPreload.run(address, context)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // HomeScreen can retry individual sources.
                }
            }
            val airRequest = async {
                try {
                    val district = AirAlertRepository.districtFor(address)
                    if (!district.isNullOrBlank()) {
                        // Preserve source age so stale results never look newly verified.
                        fun elapsedFor(state: AirAlertState): Long {
                            val sourceTime = runCatching { Instant.parse(state.sourceUpdated).toEpochMilli() }
                                .getOrNull() ?: return 0L
                            val age = (System.currentTimeMillis() - sourceTime).coerceAtLeast(0L)
                            return (SystemClock.elapsedRealtime() - age).coerceAtLeast(1L)
                        }
                        val loaded = AirAlertRepository.loadAndCache(district, context)
                        val verifiedElapsed = elapsedFor(loaded)
                        if (loaded.level != AirAlertLevel.UNKNOWN &&
                            verifiedElapsed > 0L &&
                            SystemClock.elapsedRealtime() - verifiedElapsed < 150_000L
                        ) {
                            lastAirState = loaded
                            lastAirSuccessElapsed = verifiedElapsed
                            StartupSession.lastAirState = loaded
                            StartupSession.lastAirSuccessElapsed = verifiedElapsed
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // HomeScreen will retry NEPTUN.
                }
            }

            // Both requests run concurrently. A timeout prevents an endless splash.
            // Timeout cancellation affects only the wait, not the app's navigation.
            withTimeoutOrNull(10_000L) { preloadRequest.await() }
            withTimeoutOrNull(1_000L) { airRequest.await() }
        }

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (elapsed < 1400L) delay(1400L - elapsed)
        StartupSession.lastAirState = lastAirState
        StartupSession.lastAirSuccessElapsed = lastAirSuccessElapsed
        StartupSession.completed = true
        showSplash = false
    }

    // Start the user-requested NEPTUN monitoring session only while the app
    // is visibly open. Android restricts background foreground-service starts.
    // This is not a guarantee of continuous 24/7 execution.
    LaunchedEffect(showSplash, showPermissionSetup, notificationPermissionGranted, hasSavedAddress) {
        if (!showSplash && !showPermissionSetup && hasSavedAddress && notificationPermissionGranted) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    AirAlertMonitorService.startIntent(context)
                )
            } catch (_: Exception) {
                // Unsupported/blocked foreground start: WorkManager remains a fallback.
            }
        }
    }

    // Dialog is composed before early navigation returns, so it can appear
    // over the main screen or onboarding, but never interrupt the splash.
    val updateToShow = availableUpdate
    if (!showSplash && updateToShow != null) {
        UpdateDialog(
            updateInfo = updateToShow,
            downloadState = downloadState,
            onDismiss = {
                if (downloadState !is UpdateDownloadState.Downloading &&
                    downloadState != UpdateDownloadState.Installing
                ) {
                    availableUpdate = null
                }
            },
            onUpdateClick = {
                if (downloadState !is UpdateDownloadState.Downloading &&
                    downloadState != UpdateDownloadState.Installing
                ) {
                    updateScope.launch {
                        downloadState = UpdateDownloadState.Downloading(0)
                        when (val result = apkDownloader.downloadApk(
                            updateToShow.downloadUrl,
                            onProgress = { progress ->
                                downloadState = UpdateDownloadState.Downloading(progress)
                            }
                        )) {
                            is ApkDownloadResult.Success -> {
                                pendingApk = result.apkFile
                                downloadState = UpdateDownloadState.Installing
                                when (val install = apkInstaller.installApk(result.apkFile)) {
                                    ApkInstallResult.InstallerOpened -> {
                                        availableUpdate = null
                                        downloadState = UpdateDownloadState.Idle
                                    }
                                    ApkInstallResult.PermissionRequired -> {
                                        waitingForInstallPermission = true
                                        if (!apkInstaller.openInstallPermissionSettings()) {
                                            waitingForInstallPermission = false
                                            downloadState = UpdateDownloadState.Failed(
                                                "Не вдалося відкрити налаштування встановлення APK"
                                            )
                                        }
                                    }
                                    is ApkInstallResult.Error -> {
                                        downloadState = UpdateDownloadState.Failed(install.message)
                                    }
                                }
                            }
                            is ApkDownloadResult.Error -> {
                                downloadState = UpdateDownloadState.Failed(result.message)
                            }
                        }
                    }
                }
            }
        )
    }

    if (showSplash) {
        StartupLoadingScreen(
            onSkip = {
                StartupSession.completed = true
                showSplash = false
            }
        )
        return
    }

    if (showPermissionSetup) {
        PermissionSetupScreen(
            notificationGranted = notificationPermissionGranted,
            exactAlarmGranted = exactAlarmGranted,
            onRequestNotifications = {
                val runtimePermissionMissing =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED

                if (runtimePermissionMissing) {
                    // Android 13+: only Android can show the real permission dialog.
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Android 12 and older, or notifications blocked in Settings.
                    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    try {
                        exactAlarmSettingsLauncher.launch(notificationSettings)
                    } catch (_: Exception) {
                        try {
                            exactAlarmSettingsLauncher.launch(appSettings)
                        } catch (_: Exception) {
                            permissionCheckVersion++
                        }
                    }
                }
            },
            onRequestExactAlarms = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+: special app access, not a runtime permission popup.
                    val exactSettings = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    try {
                        exactAlarmSettingsLauncher.launch(exactSettings)
                    } catch (_: Exception) {
                        try {
                            exactAlarmSettingsLauncher.launch(appSettings)
                        } catch (_: Exception) {
                            permissionCheckVersion++
                        }
                    }
                }
            },
            onContinue = {
                permissionPrefs.edit()
                    .putBoolean("setup_completed", true)
                    .apply()

                showPermissionSetup = false
            }
        )
        return
    }

    // Onboarding follows permissions, and never reappears once an address exists.
    if (savedAddress == null) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (!hasSavedAddress) {
        if (showFirstAddressPicker) {
            KiroeAddressPicker(
                onBack = { showFirstAddressPicker = false },
                onSaved = { showFirstAddressPicker = false }
            )
        } else {
            FirstAddressSetupScreen(onAddAddress = { showFirstAddressPicker = true })
        }
        return
    }

    // System Back (button or gesture) returns from any main tab to Home.
    // Nested screens can handle Back themselves when their own handler is active.
    BackHandler(enabled = selectedTab != AppTab.HOME) {
        selectedTab = AppTab.HOME
        waterRequested = false
        ecostyleRequested = false
        gasRequested = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp
            ) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab

                            if (tab != AppTab.SCHEDULES) {
                                waterRequested = false
                                ecostyleRequested = false
                                gasRequested = false
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title
                            )
                        },
                        label = {
                            Text(tab.title)
                        },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                AppTab.HOME -> HomeScreen(
                    initialAirAlert = lastAirState,
                    initialAirElapsed = lastAirSuccessElapsed,
                    onAirAlertLoaded = { loaded, elapsed ->
                        lastAirState = loaded
                        lastAirSuccessElapsed = elapsed
                        StartupSession.lastAirState = loaded
                        StartupSession.lastAirSuccessElapsed = elapsed
                    },
                    onWeatherClick = {
                        selectedTab = AppTab.WEATHER
                    },
                    onWaterClick = {
                        ecostyleRequested = false
                        gasRequested = false
                        waterRequested = true
                        navigationRequestId++
                        selectedTab = AppTab.SCHEDULES
                    },
                    onEcostyleClick = {
                        waterRequested = false
                        gasRequested = false
                        ecostyleRequested = true
                        navigationRequestId++
                        selectedTab = AppTab.SCHEDULES
                    },
                    onGasClick = {
                        waterRequested = false
                        ecostyleRequested = false
                        gasRequested = true
                        navigationRequestId++
                        selectedTab = AppTab.SCHEDULES
                    }
                )

                AppTab.WEATHER -> WeatherScreen()

                AppTab.SCHEDULES -> {
                    SchedulesScreen(
                        openWater = waterRequested,
                        openEcostyle = ecostyleRequested,
                        openGas = gasRequested,
                        navigationRequestId = navigationRequestId
                    )
                }

                AppTab.EVENTS -> PlaceholderScreen(
                    icon = Icons.Default.Article,
                    title = "Події",
                    description = "Тут будуть новини та події міста."
                )

                AppTab.SETTINGS -> SettingsScreen(
                    themeMode = themeMode,
                    onThemeChange = onThemeChange
                )
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(
    icon: ImageVector,
    title: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(58.dp)
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StartupLoadingScreen(
    onSkip: () -> Unit
) {
    val transition = rememberInfiniteTransition(
        label = "startup"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(950),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoPulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = null,
            modifier = Modifier
                .size(74.dp)
                .alpha(pulse),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Кроп Live",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Оновлюємо дані для вашої адреси…",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(0.65f)
        )

        Spacer(Modifier.height(20.dp))

        TextButton(
            onClick = onSkip
        ) {
            Text("Перейти до застосунку", color = MaterialTheme.colorScheme.primary)
        }
    }
}


@Composable
private fun PermissionSetupScreen(
    notificationGranted: Boolean,
    exactAlarmGranted: Boolean,
    onRequestNotifications: () -> Unit,
    onRequestExactAlarms: () -> Unit,
    onContinue: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .navigationBarsPadding()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.NotificationsActive,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Налаштування сповіщень",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Щоб Кроп Live попереджав про відключення світла та інші важливі події, надайте необхідні дозволи.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))

        PermissionStatusCard(
            title = "Сповіщення",
            granted = notificationGranted,
            description = if (notificationGranted) "Сповіщення дозволені" else "Сповіщення вимкнені або дозвіл не надано",
            buttonLabel = "Дозволити сповіщення",
            onRequest = onRequestNotifications
        )
        Spacer(Modifier.height(12.dp))
        PermissionStatusCard(
            title = "Точні нагадування",
            granted = exactAlarmGranted,
            description = if (exactAlarmGranted) "Точне планування доступне" else "Для нагадувань за 15 хвилин потрібен додатковий дозвіл",
            buttonLabel = "Налаштувати нагадування",
            onRequest = onRequestExactAlarms
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Перейти до Кроп Live")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Дозволи можна змінити пізніше в налаштуваннях Android.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    granted: Boolean,
    description: String,
    buttonLabel: String,
    onRequest: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val statusColor = if (granted) Color(0xFF22A05A) else Color(0xFFD94848)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold, color = colors.onSurface)
                    Text(
                        if (granted) "Дозвіл надано" else "Дозвіл не надано",
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (!granted) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonLabel)
                }
            }
        }
    }
}

@Composable
private fun FirstAddressSetupScreen(onAddAddress: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Додайте свою адресу",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Адреса потрібна для персонального графіка відключень світла та сповіщень для вашого району. Ви зможете змінити її пізніше в налаштуваннях.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAddAddress, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.AddLocationAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Додати адресу")
        }
    }
}