package ua.kropilive.app.ui.home

import androidx.activity.compose.BackHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.graphics.Bitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.os.SystemClock
import ua.kropilive.app.ui.theme.KropiLiveStatusTheme
import ua.kropilive.app.ui.theme.AirAlertStatusColors
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ua.kropilive.app.data.repository.HourlyBlackoutRepository
import ua.kropilive.app.data.repository.HourlyGraph
import ua.kropilive.app.data.repository.HourlyGraphCheck
import ua.kropilive.app.data.repository.ElectricityOutageRepository
import ua.kropilive.app.data.repository.OutageKind
import ua.kropilive.app.data.repository.OutageResult
import ua.kropilive.app.data.repository.OutageRecord
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import ua.kropilive.app.data.repository.TelegramBlackoutRepository
import ua.kropilive.app.data.repository.TelegramQueueGraph
import ua.kropilive.app.data.repository.TelegramGraphCheck
import java.time.LocalDate
import java.time.LocalTime
import ua.kropilive.app.ui.schedules.SharedElectricityState
import ua.kropilive.app.data.repository.StartupPreload
import ua.kropilive.app.data.repository.GasNoticeRepository
import ua.kropilive.app.data.repository.SharedGasState
import ua.kropilive.app.data.repository.WaterNoticeRepository
import ua.kropilive.app.data.repository.WaterReport
import ua.kropilive.app.data.repository.AirAlertRepository
import ua.kropilive.app.data.repository.AirAlertState
import ua.kropilive.app.data.repository.AirAlertLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ua.kropilive.app.data.repository.AddressRepository
import ua.kropilive.app.data.repository.UserAddress
import ua.kropilive.app.data.repository.WeatherData
import ua.kropilive.app.data.repository.WeatherRepository
import kotlin.math.roundToInt

private val AccentBlue = Color(0xFF38BDF8)
private val AccentYellow = Color(0xFFFACC15)
private val MutedBlue = Color(0xFF94A3B8)
private val DarkBlue = Color(0xFF1E293B)

// Water status survives bottom-tab navigation and is always tied to the selected address.
private object HomeWaterSession {
    private var key: String = ""
    private var report: WaterReport? = null

    private fun addressKey(a: UserAddress): String =
        listOf(a.communityId, a.cityName, a.street, a.building).joinToString("|")

    fun get(a: UserAddress): WaterReport? =
        report?.takeIf { key == addressKey(a) }

    fun save(a: UserAddress, value: WaterReport) {
        if (a.street.isBlank() || a.building.isBlank() || !value.addressSpecified) return
        key = addressKey(a)
        report = value
    }
}

// Keeps the last successful checks while Home is temporarily removed from composition
// when the user switches bottom-navigation tabs. Never reuse yesterday's results.
private object HomeElectricitySession {
    var day: String = ""
    var queue: String = ""
    var addressKey: String = ""
    var site: HourlyGraphCheck? = null
    var telegram: TelegramGraphCheck? = null
    var planned: OutageResult? = null
    var emergency: OutageResult? = null
    var siteGraph: HourlyGraph? = null
    var telegramGraph: TelegramQueueGraph? = null

    fun validQueue(q: String) = day == LocalDate.now().toString() && queue == q
    fun validAddress(key: String) = day == LocalDate.now().toString() && addressKey == key

    fun prepare(q: String, key: String) {
        val today = LocalDate.now().toString()
        if (day != today) {
            day = today
            queue = ""
            addressKey = ""
            site = null
            telegram = null
            planned = null
            emergency = null
            siteGraph = null
            telegramGraph = null
        }
        if (queue != q) {
            queue = q
            site = null
            telegram = null
            siteGraph = null
            telegramGraph = null
        }
        if (addressKey != key) {
            addressKey = key
            planned = null
            emergency = null
        }
    }
}


@Composable
fun HomeScreen(
    onWeatherClick: () -> Unit = {},
    onWaterClick: () -> Unit = {},
    onEcostyleClick: () -> Unit = {},
    onGasClick: () -> Unit = {},
    initialAirAlert: AirAlertState? = null,
    initialAirElapsed: Long = 0L,
    onAirAlertLoaded: (AirAlertState, Long) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current.applicationContext

    val addressRepository = remember(context) {
        AddressRepository(context)
    }

    val savedAddress by addressRepository.address.collectAsState(
        initial = UserAddress()
    )

    val weatherRepository = remember {
        WeatherRepository()
    }

    val scope = rememberCoroutineScope()

    var waterReport by remember { mutableStateOf<WaterReport?>(null) }
    var waterError by remember { mutableStateOf(false) }
    LaunchedEffect(savedAddress.communityId, savedAddress.street, savedAddress.building, savedAddress.cityName) {
        if (savedAddress.street.isBlank() || savedAddress.building.isBlank()) {
            waterReport = null
            waterError = false
            return@LaunchedEffect
        }
        // Restore the verified result for this exact address before requesting anything.
        waterReport = HomeWaterSession.get(savedAddress) ?: StartupPreload.water(savedAddress)
        waterReport?.let { HomeWaterSession.save(savedAddress, it) }
        waterError = false
        while (true) {
            try {
                val loaded = WaterNoticeRepository.load(savedAddress)
                waterReport = loaded
                HomeWaterSession.save(savedAddress, loaded)
                waterError = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                waterError = waterReport == null
            }
            delay(if (waterError) 15_000L else 5 * 60_000L)
        }
    }

    val gasReport = SharedGasState.forAddress(savedAddress)
    LaunchedEffect(savedAddress.communityId, savedAddress.cityName, savedAddress.street, savedAddress.building) {
        if (!savedAddress.isFilled) return@LaunchedEffect
        while (true) {
            try {
                SharedGasState.begin(savedAddress)
                SharedGasState.publish(savedAddress, GasNoticeRepository.load(savedAddress))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { SharedGasState.finish() }
            delay(5 * 60_000L)
        }
    }

    // Read the verified splash/disk snapshot before the first Home frame.
    // Preserve the source timestamp: receiving a cached response is NOT a new check.
    val addressDistrict = AirAlertRepository.districtFor(savedAddress)
    val district = addressDistrict ?: if (!savedAddress.isFilled) initialAirAlert?.district else null

    fun sourceElapsed(state: AirAlertState): Long {
        val sourceMillis = runCatching {
            java.time.Instant.parse(state.sourceUpdated).toEpochMilli()
        }.getOrNull() ?: return 0L
        val age = System.currentTimeMillis() - sourceMillis
        if (age !in 0L until 150_000L) return 0L
        return (SystemClock.elapsedRealtime() - age).coerceAtLeast(1L)
    }

    val diskAir = remember(context, district) { AirAlertRepository.cached(context, district) }
    val splashAir = initialAirAlert?.takeIf { it.district == district }
    val splashElapsed = splashAir?.let { sourceElapsed(it) } ?: 0L
    val diskElapsed = diskAir?.let { sourceElapsed(it) } ?: 0L
    val firstAir = if (splashElapsed >= diskElapsed && splashElapsed > 0L) splashAir
    else if (diskElapsed > 0L) diskAir else null
    val firstElapsed = maxOf(splashElapsed, diskElapsed)

    var airAlert by remember(district) { mutableStateOf(firstAir) }
    var alertError by remember(district) { mutableStateOf(false) }
    var airLastSuccessElapsed by remember(district) { mutableLongStateOf(firstElapsed) }

    // The foreground monitor publishes validated NEPTUN WebSocket states here.
    // Prefer a live stream state for this district; if it goes stale, fall back
    // to the existing REST/splash snapshot instead of showing an old all-clear.
    val streamedAir by AirAlertRepository.streamState.collectAsState()
    val currentStream = streamedAir?.takeIf {
        it.district == district && it.level != AirAlertLevel.UNKNOWN &&
                sourceElapsed(it) > 0L
    }
    val visibleAir = currentStream ?: airAlert
    val visibleAirElapsed = currentStream?.let { sourceElapsed(it) } ?: airLastSuccessElapsed
    val visibleAirError = if (currentStream != null) false else alertError

    // The splash request can complete after Home was created. Adopt its result
    // without clearing a newer verified value.
    LaunchedEffect(initialAirAlert, initialAirElapsed, district) {
        val incoming = initialAirAlert?.takeIf { it.district == district }
        val incomingElapsed = incoming?.let { sourceElapsed(it) } ?: 0L
        if (incoming != null && incomingElapsed > airLastSuccessElapsed) {
            airAlert = incoming
            airLastSuccessElapsed = incomingElapsed
            alertError = false
        }
    }

    LaunchedEffect(district) {
        if (district.isNullOrBlank()) {
            airAlert = null
            airLastSuccessElapsed = 0L
            alertError = false
            return@LaunchedEffect
        }
        while (true) {
            // The splash has already contacted NEPTUN. Avoid a duplicate request
            // while its result is still recent, but keep the regular refresh loop.
            val age = SystemClock.elapsedRealtime() - airLastSuccessElapsed
            if (airAlert?.district == district && age in 0L until 30_000L) {
                delay(30_000L - age)
            }
            try {
                val loaded = AirAlertRepository.loadAndCache(district, context)
                val elapsed = sourceElapsed(loaded)
                if (elapsed > 0L) {
                    airAlert = loaded
                    airLastSuccessElapsed = elapsed
                    alertError = false
                    onAirAlertLoaded(loaded, elapsed)
                } else {
                    // Never promote stale or undated data to a fresh status.
                    alertError = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A network failure must not erase a still-fresh verified state.
                alertError = true
            }
            delay(if (alertError) 15_000L else 30_000L)
        }
    }

    var weather by remember {
        mutableStateOf<WeatherData?>(null)
    }

    var weatherLoading by remember {
        mutableStateOf(true)
    }

    var weatherError by remember {
        mutableStateOf(false)
    }

    // Shared repositories and the same source priority as HourlyBlackoutCard.
    // Only today's dated schedules can describe today's planned outages.
    val queue = savedAddress.electricityQueue
    val addressKey = listOf(savedAddress.communityId, savedAddress.cityName, savedAddress.street, savedAddress.building).joinToString("|")
    // Use the verified splash results on the FIRST composition, not in a later
    // LaunchedEffect: that avoids a brief unconfirmed status on entering Home.
    HomeElectricitySession.prepare(queue, addressKey)
    val initialSiteCheck = HomeElectricitySession.site ?: StartupPreload.hourlyGraphCheck(savedAddress)
    val initialTelegramCheck = HomeElectricitySession.telegram ?: StartupPreload.telegramGraphCheck(savedAddress)
    val initialSiteGraph = (initialSiteCheck as? HourlyGraphCheck.Published)?.graph
    val initialTelegramGraph = (initialTelegramCheck as? TelegramGraphCheck.Published)?.graph
    var siteGraph by remember(queue, addressKey) {
        mutableStateOf(initialSiteGraph ?: HomeElectricitySession.siteGraph ?: HourlyBlackoutRepository.cached(context, queue))
    }
    var telegramGraph by remember(queue, addressKey) {
        mutableStateOf(initialTelegramGraph ?: HomeElectricitySession.telegramGraph ?: TelegramBlackoutRepository.cached(context, queue))
    }
    var clock by remember { mutableStateOf(LocalTime.now()) }
    var graphCheck by remember(queue, addressKey) { mutableStateOf(initialSiteCheck) }
    var telegramLiveCheck by remember(queue, addressKey) {
        mutableStateOf(initialTelegramGraph)
    }
    var telegramCheck by remember(queue, addressKey) { mutableStateOf(initialTelegramCheck) }
    var plannedCheck by remember(addressKey) {
        mutableStateOf(HomeElectricitySession.planned ?: StartupPreload.electricity(savedAddress, OutageKind.PLANNED))
    }
    var emergencyCheck by remember(addressKey) {
        mutableStateOf(HomeElectricitySession.emergency ?: StartupPreload.electricity(savedAddress, OutageKind.EMERGENCY))
    }
    // Retain the splash snapshot across tab switches without mixing addresses.
    HomeElectricitySession.site = graphCheck
    HomeElectricitySession.telegram = telegramCheck
    HomeElectricitySession.siteGraph = siteGraph
    HomeElectricitySession.telegramGraph = telegramGraph
    HomeElectricitySession.planned = plannedCheck
    HomeElectricitySession.emergency = emergencyCheck
    // Publish exactly the values already checked on Home to the schedules tab.
    // SideEffect runs after a successful composition and performs no network I/O.
    SideEffect {
        SharedElectricityState.day = LocalDate.now().toString()
        SharedElectricityState.queue = queue
        SharedElectricityState.addressKey = addressKey
        SharedElectricityState.siteCheck = graphCheck
        SharedElectricityState.telegramCheck = telegramCheck
        SharedElectricityState.planned = plannedCheck
        SharedElectricityState.emergency = emergencyCheck
    }
    val outageRepository = remember { ElectricityOutageRepository() }

    LaunchedEffect(addressKey) {
        if (!savedAddress.hasFullAddress) return@LaunchedEffect
        if (plannedCheck == null) {
            plannedCheck = StartupPreload.electricity(savedAddress, OutageKind.PLANNED)
            HomeElectricitySession.planned = plannedCheck
        }
        if (emergencyCheck == null) {
            emergencyCheck = StartupPreload.electricity(savedAddress, OutageKind.EMERGENCY)
            HomeElectricitySession.emergency = emergencyCheck
        }
        while (true) {
            coroutineScope {
                val plannedJob = async {
                    try { outageRepository.fetch(OutageKind.PLANNED, savedAddress) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
                val emergencyJob = async {
                    try { outageRepository.fetch(OutageKind.EMERGENCY, savedAddress) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
                plannedJob.await()?.let {
                    plannedCheck = it
                    HomeElectricitySession.planned = it
                }
                emergencyJob.await()?.let {
                    emergencyCheck = it
                    HomeElectricitySession.emergency = it
                }
            }
            delay(5 * 60_000L)
        }
    }

    LaunchedEffect(queue) {
        while (true) {
            clock = LocalTime.now()
            delay(30_000L)
        }
    }
    LaunchedEffect(queue) {
        if (queue.isNotBlank()) {
            while (true) {
                // Fetch independently: a slow source must not prevent the other one.
                coroutineScope {
                    val site = async {
                        HourlyBlackoutRepository.check(context, queue)
                    }
                    val channel = async {
                        TelegramBlackoutRepository.check(context, queue)
                    }
                    val checked = site.await()
                    graphCheck = checked
                    HomeElectricitySession.site = checked
                    if (checked is HourlyGraphCheck.Published) {
                        siteGraph = checked.graph
                        HomeElectricitySession.siteGraph = checked.graph
                    }
                    val channelResult = channel.await()
                    telegramCheck = channelResult
                    HomeElectricitySession.telegram = channelResult
                    telegramLiveCheck = (channelResult as? TelegramGraphCheck.Published)?.graph
                    telegramLiveCheck?.let {
                        telegramGraph = it
                        HomeElectricitySession.telegramGraph = it
                    }
                }
                delay(2 * 60_000L)
            }
        }
    }

    var showElectricityScreen by remember {
        mutableStateOf(false)
    }
    var showAirDetails by remember { mutableStateOf(false) }

    fun loadWeather() {
        scope.launch {
            weatherLoading = true
            weatherError = false

            try {
                weather = weatherRepository.getCurrentWeather()
            } catch (e: Exception) {
                weatherError = true
            } finally {
                weatherLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadWeather()
    }

    var isRefreshing by remember { mutableStateOf(false) }

    fun refreshHome() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            try {
                // Every source is independent: one failure must not block the others.
                coroutineScope {
                    val waterJob = async {
                        try {
                            val loaded = WaterNoticeRepository.load(savedAddress)
                            waterReport = loaded
                            HomeWaterSession.save(savedAddress, loaded)
                            waterError = false
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            waterError = waterReport == null
                        }
                    }
                    val airJob = async {
                        if (!district.isNullOrBlank()) {
                            try {
                                val loaded = AirAlertRepository.loadAndCache(district, context)
                                val elapsed = sourceElapsed(loaded)
                                if (elapsed > 0L) {
                                    airAlert = loaded
                                    airLastSuccessElapsed = elapsed
                                    alertError = false
                                    onAirAlertLoaded(loaded, elapsed)
                                } else {
                                    alertError = true
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                alertError = true
                            }
                        }
                    }
                    val weatherJob = async {
                        weatherLoading = true
                        try {
                            weather = weatherRepository.getCurrentWeather()
                            weatherError = false
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            weatherError = true
                        } finally {
                            weatherLoading = false
                        }
                    }
                    val electricityJob = async {
                        if (savedAddress.hasFullAddress) {
                            val plannedJob = async {
                                try { outageRepository.fetch(OutageKind.PLANNED, savedAddress) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                            }
                            val emergencyJob = async {
                                try { outageRepository.fetch(OutageKind.EMERGENCY, savedAddress) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                            }
                            plannedJob.await()?.let {
                                plannedCheck = it
                                HomeElectricitySession.planned = it
                            }
                            emergencyJob.await()?.let {
                                emergencyCheck = it
                                HomeElectricitySession.emergency = it
                            }
                        }
                        if (queue.isNotBlank()) {
                            val siteJob = async {
                                try { HourlyBlackoutRepository.check(context, queue) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                            }
                            val telegramJob = async {
                                try { TelegramBlackoutRepository.check(context, queue) }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { null }
                            }
                            siteJob.await()?.let { checked ->
                                graphCheck = checked
                                HomeElectricitySession.site = checked
                                if (checked is HourlyGraphCheck.Published) {
                                    siteGraph = checked.graph
                                    HomeElectricitySession.siteGraph = checked.graph
                                }
                            }
                            telegramJob.await()?.let { channel ->
                                telegramCheck = channel
                                HomeElectricitySession.telegram = channel
                                telegramLiveCheck = (channel as? TelegramGraphCheck.Published)?.graph
                                telegramLiveCheck?.let {
                                    telegramGraph = it
                                    HomeElectricitySession.telegramGraph = it
                                }
                            }
                        }
                    }
                    waterJob.await()
                    airJob.await()
                    weatherJob.await()
                    electricityJob.await()
                }
                clock = LocalTime.now()
            } finally {
                isRefreshing = false
            }
        }
    }

    if (showAirDetails) {
        AirAlertDetailsScreen(
            state = visibleAir?.takeIf {
                visibleAirElapsed > 0L &&
                        SystemClock.elapsedRealtime() - visibleAirElapsed < 150_000L
            },
            district = district,
            onBack = { showAirDetails = false }
        )
    } else if (showElectricityScreen) {
        ElectricityScheduleScreen(
            address = savedAddress,
            siteCheck = graphCheck,
            telegramCheck = telegramCheck,
            onBack = {
                showElectricityScreen = false
            }
        )
    } else {
        HomeContent(
            address = savedAddress,
            siteGraph = siteGraph,
            telegramGraph = telegramGraph,
            graphCheck = graphCheck,
            telegramLiveCheck = telegramLiveCheck,
            telegramCheck = telegramCheck,
            plannedCheck = plannedCheck,
            emergencyCheck = emergencyCheck,
            clock = clock,
            airAlert = visibleAir,
            alertError = visibleAirError,
            airLastSuccessElapsed = visibleAirElapsed,
            waterReport = if (savedAddress.hasFullAddress) HomeWaterSession.get(savedAddress) else null,
            waterError = waterError,
            gasReport = gasReport,
            onWaterClick = onWaterClick,
            onEcostyleClick = onEcostyleClick,
            onGasClick = onGasClick,
            weather = weather,
            weatherLoading = weatherLoading,
            weatherError = weatherError,
            onWeatherRefresh = { loadWeather() },
            onWeatherClick = onWeatherClick,
            onElectricityClick = {
                showElectricityScreen = true
            },
            onAirClick = { showAirDetails = true },
            isRefreshing = isRefreshing,
            onRefresh = { refreshHome() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    address: UserAddress,
    siteGraph: HourlyGraph?,
    telegramGraph: TelegramQueueGraph?,
    graphCheck: HourlyGraphCheck?,
    telegramLiveCheck: TelegramQueueGraph?,
    telegramCheck: TelegramGraphCheck?,
    plannedCheck: OutageResult?,
    emergencyCheck: OutageResult?,
    clock: LocalTime,
    airAlert: AirAlertState?,
    alertError: Boolean,
    airLastSuccessElapsed: Long,
    waterReport: WaterReport?,
    waterError: Boolean,
    gasReport: ua.kropilive.app.data.repository.GasReport?,
    onWaterClick: () -> Unit,
    onEcostyleClick: () -> Unit,
    onGasClick: () -> Unit,
    weather: WeatherData?,
    weatherLoading: Boolean,
    weatherError: Boolean,
    onWeatherRefresh: () -> Unit,
    onWeatherClick: () -> Unit,
    onElectricityClick: () -> Unit,
    onAirClick: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val today = LocalDate.now().toString()
    val verifiedSite = siteGraph?.takeIf { it.date == today && !it.cached }
    val todayTelegram = telegramGraph?.takeIf { it.date == today }
    val todaySiteCache = siteGraph?.takeIf { it.date == today }
    val selectedGraph = verifiedSite
        ?: todayTelegram?.let { HourlyGraph(it.queue, it.date, it.times, it.cached) }
        ?: todaySiteCache
    val electricityStatus = remember(selectedGraph, clock.hour, clock.minute) {
        electricityStatusFor(selectedGraph, clock)
    }
    val activeEmergency = emergencyCheck?.matching?.firstOrNull { outageRecordIsActive(it) }
    val activePlanned = plannedCheck?.matching?.firstOrNull { outageRecordIsActive(it) }
    // Both GPV channels matter. A cached Telegram post is not a fresh check.
    val graphNow = (graphCheck as? HourlyGraphCheck.Published)?.let {
        electricityStatusFor(it.graph, clock)
    }
    val telegramNow = telegramLiveCheck?.let {
        electricityStatusFor(HourlyGraph(it.queue, it.date, it.times, false), clock)
    }
    val siteActive = graphNow?.first == "Є відключення"
    val telegramActive = telegramNow?.first == "Є відключення"
    val siteSwitching = graphNow?.first == "Можливе перемикання"
    val telegramSwitching = telegramNow?.first == "Можливе перемикання"
    val siteUnknown = graphNow?.first == "Статус невідомий"
    val telegramUnknown = telegramNow?.first == "Статус невідомий"
    // An actual current outage always wins, even if another source failed.
    // A confirmed normal GPV state from either live channel can be used;
    // a missing/failed request alone cannot confirm no outage.
    val telegramNoGraph = telegramCheck is TelegramGraphCheck.NoCurrentGraphInVisiblePosts
    // A live published graph is authoritative for its covered time slots.
    // A missing Telegram graph is useful evidence only when the official site
    // independently confirms that no graph was published.
    val gpvNormal = graphNow?.first == "Відключень немає" ||
            telegramNow?.first == "Відключень немає" ||
            (graphCheck is HourlyGraphCheck.NotPublished && telegramNoGraph)
    val electricityValue = when {
        !address.hasFullAddress -> "Додайте адресу"
        activeEmergency != null || activePlanned != null || siteActive || telegramActive -> "Є відключення"
        address.electricityQueue.isBlank() -> "Черга невідома"
        siteSwitching || telegramSwitching -> "Можливе перемикання"
        siteUnknown || telegramUnknown -> "Статус не підтверджено"
        plannedCheck != null && emergencyCheck != null && gpvNormal -> "Відключень немає"
        else -> "Статус не підтверджено"
    }
    val electricityStatusColor = when (electricityValue) {
        "Є відключення" -> Color(0xFFD94848)
        "Відключень немає" -> if (isSystemInDarkTheme()) Color(0xFF22A05A) else Color(0xFF187542)
        else -> null
    }
    val electricitySource = when {
        !address.hasFullAddress -> "Додайте адресу для перевірки"
        activeEmergency != null -> "Аварійне відключення"
        activePlanned != null -> "Планове відключення"
        siteActive -> "За ГПВ · офіційний сайт"
        telegramActive -> "За ГПВ · Telegram"
        siteSwitching || siteUnknown -> "За ГПВ · офіційний сайт"
        telegramSwitching || telegramUnknown -> "За ГПВ · Telegram"
        electricityValue == "Відключень немає" ->
            when {
                graphNow?.first == "Відключень немає" -> "За ГПВ · офіційний сайт"
                telegramNow?.first == "Відключень немає" -> "За ГПВ · Telegram"
                else -> "За плановими, аварійними та ГПВ"
            }
        else -> "Перевірка джерел"
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Кроп Live",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                val homeContext = LocalContext.current
                val appVersion = remember(homeContext) {
                    runCatching {
                        val info = homeContext.packageManager.getPackageInfo(homeContext.packageName, 0)
                        info.versionName ?: "—"
                    }.getOrDefault("—")
                }
                Text(
                    text = "v$appVersion",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(15.dp)
                )

                Text(
                    text = "Кіровоградська область · Усе важливе поруч",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))

            // A status older than 2.5 minutes must not be presented as current.
            val freshAir = airAlert != null && airLastSuccessElapsed > 0L &&
                    SystemClock.elapsedRealtime() - airLastSuccessElapsed < 150_000L
            val level = if (freshAir) airAlert!!.level else AirAlertLevel.UNKNOWN
            val alertPalette = KropiLiveStatusTheme.airAlerts
            val alertColors = when (level) {
                AirAlertLevel.RED -> alertPalette.red
                AirAlertLevel.YELLOW -> alertPalette.yellow
                AirAlertLevel.GREEN -> alertPalette.green
                AirAlertLevel.UNKNOWN -> alertPalette.unknown
            }
            val alertValue = when (level) {
                AirAlertLevel.RED -> "Повітряна тривога!"
                AirAlertLevel.YELLOW -> "Підвищена небезпека"
                AirAlertLevel.GREEN -> "Немає тривоги"
                AirAlertLevel.UNKNOWN -> when {
                    alertError -> "Немає зв'язку з джерелом"
                    airAlert == null -> "Перевірка статусу…"
                    !freshAir -> "Дані застаріли"
                    else -> "Статус не підтверджено"
                }
            }
            val formatAlertTime = remember { SimpleDateFormat("dd.MM HH:mm", Locale("uk", "UA")) }
            val alertDescription = when (level) {
                AirAlertLevel.RED -> "${airAlert?.district.orEmpty()} · Початок: " +
                        (airAlert?.startedAt?.let { formatAlertTime.format(Date(it * 1000)) } ?: "час невідомий")
                AirAlertLevel.YELLOW -> "${airAlert?.district.orEmpty()} · Повідомлення про загрози: ${airAlert?.threats?.size ?: 0}"
                AirAlertLevel.GREEN -> "${airAlert?.district.orEmpty()} · Активної тривоги не зафіксовано"
                AirAlertLevel.UNKNOWN -> when {
                    alertError -> "Помилка завантаження. Перевірте інтернет та офіційні сповіщення"
                    airAlert == null -> "Очікуємо відповідь NEPTUN. Перевіряйте офіційні сповіщення"
                    !freshAir -> "Остання перевірка понад 150 с тому. Перевіряйте офіційні сповіщення"
                    else -> "Немає достатньо даних для підтвердження статусу"
                }
            }
            HomeInfoCard(
                icon = when (level) {
                    AirAlertLevel.RED, AirAlertLevel.YELLOW -> Icons.Default.Warning
                    AirAlertLevel.GREEN -> Icons.Default.Security
                    AirAlertLevel.UNKNOWN -> if (alertError) Icons.Default.WifiOff else Icons.Default.CloudOff
                },
                iconColor = alertColors.accent,
                title = "Повітряна небезпека",
                value = alertValue,
                description = alertDescription,
                modifier = Modifier.fillMaxWidth(),
                onClick = onAirClick,
                statusColors = alertColors,
                iconTitleInline = true
            )
            val uriHandler = LocalUriHandler.current
            Text(
                text = "Джерело: NEPTUN · Оновлення під час роботи застосунку щохвилини. Не замінює офіційні сигнали тривоги. Відкрити джерело ↗",
                modifier = Modifier.clickable { uriHandler.openUri(AirAlertRepository.SOURCE) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeInfoCard(
                    icon = if (electricityValue == "Є відключення") Icons.Default.FlashOff
                    else Icons.Default.FlashOn,
                    iconColor = AccentYellow,
                    title = "Світло",
                    value = electricityValue,
                    description = electricitySource,
                    modifier = Modifier.weight(1f),
                    onClick = onElectricityClick,
                    highlighted = true,
                    valueColor = electricityStatusColor,
                    cardHeight = 208.dp,
                    descriptionMaxLines = 2,
                    queueLabel = if (address.electricityQueue.isBlank()) "Черга не визначена"
                    else "Черга ${address.electricityQueue}"
                )

                HomeInfoCard(
                    icon = Icons.Default.WaterDrop,
                    iconColor = AccentBlue,
                    title = "Вода",
                    value = when {
                        !address.hasFullAddress -> "Оберіть адресу"
                        waterReport == null && waterError -> "Немає даних"
                        waterReport == null || !waterReport.addressSpecified -> "Перевірка…"
                        waterReport.hasReportedIssue -> "Є повідомлення"
                        else -> "Відключень немає"
                    },
                    description = "За повідомленнями водоканалу",
                    modifier = Modifier.weight(1f),
                    onClick = onWaterClick,
                    cardHeight = 208.dp,
                    valueColor = when {
                        waterReport?.hasReportedIssue == true -> Color(0xFFD94848)
                        waterReport != null && !waterError && waterReport.addressSpecified -> Color(0xFF22A05A)
                        else -> null
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeInfoCard(
                    icon = Icons.Default.LocalFireDepartment,
                    iconColor = Color(0xFFF97316),
                    title = "Газ",
                    value = when {
                        !address.isFilled -> "Оберіть адресу"
                        gasReport == null || !gasReport.verified -> "Перевірка повідомлень"
                        gasReport.hasReportedIssue -> "Є повідомлення про відключення"
                        gasReport.hasUpcomingOutage -> "Заплановане відключення"
                        else -> "Відключень немає"
                    },
                    description = gasReport?.scheduled?.minByOrNull { it.startDate }?.let {
                        "Дата: ${it.startDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}"
                    } ?: "За даними Газмережі",
                    modifier = Modifier.weight(1f),
                    onClick = onGasClick,
                    cardHeight = 170.dp,
                    valueColor = when {
                        gasReport?.verified != true -> null
                        gasReport.hasReportedIssue -> Color(0xFFD94848)
                        gasReport.hasUpcomingOutage -> if (isSystemInDarkTheme()) Color(0xFFFBBF24) else Color(0xFF9A5700)
                        else -> if (isSystemInDarkTheme()) Color(0xFF22A05A) else Color(0xFF187542)
                    }
                )

                HomeInfoCard(
                    icon = Icons.Default.DeleteOutline,
                    iconColor = Color(0xFF22C55E),
                    title = "Екостайл",
                    value = "Вивезення відходів",
                    description = "Графіки та оголошення",
                    modifier = Modifier.weight(1f),
                    onClick = onEcostyleClick
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeInfoCard(
                    icon = Icons.Default.DirectionsBus,
                    iconColor = Color(0xFFA5B4FC),
                    title = "Транспорт",
                    value = "Маршрути",
                    description = "Зміни руху",
                    modifier = Modifier.weight(1f)
                )

                HomeInfoCard(
                    icon = Icons.Default.Article,
                    iconColor = MutedBlue,
                    title = "Місто",
                    value = "Оголошення",
                    description = "Міські новини",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Погода зараз",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                TextButton(onClick = onWeatherClick) {
                    Text("Детальніше")
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = onWeatherClick,
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                when {
                    weather == null && weatherLoading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 3.dp
                            )

                            Text(
                                text = "Завантаження погоди...",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    weather == null -> {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MutedBlue,
                                modifier = Modifier.size(30.dp)
                            )

                            Text(
                                text = "Погода недоступна",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "Не вдалося отримати дані Open-Meteo",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Кропивницький",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Text(
                                        text = "${weather.temperature.roundToInt()}°",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 48.sp,
                                        lineHeight = 52.sp,
                                        fontWeight = FontWeight.Light
                                    )

                                    Text(
                                        text = weather.description,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Text(
                                        text = "Відчувається як " +
                                                "${weather.feelsLike.roundToInt()}°",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                HomeWeatherIcon(
                                    code = weather.weatherCode,
                                    isDay = weather.isDay
                                )
                            }

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WaterDrop,
                                        contentDescription = null,
                                        tint = AccentBlue,
                                        modifier = Modifier.size(16.dp)
                                    )

                                    Text(
                                        text = "${weather.humidity}%",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Air,
                                        contentDescription = null,
                                        tint = AccentBlue,
                                        modifier = Modifier.size(16.dp)
                                    )

                                    Text(
                                        text = "${weather.windSpeed.roundToInt()} км/год",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            if (weatherError) {
                                Text(
                                    text = "Не вдалося оновити прогноз",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            if (weather == null && !weatherLoading) {
                OutlinedButton(
                    onClick = onWeatherRefresh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(Modifier.width(8.dp))

                    Text("Повторити завантаження")
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Важливе для вас",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Тут з'являться персональні повідомлення " +
                        "про зміни у вашому місті.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun HomeInfoCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    value: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    statusColors: AirAlertStatusColors? = null,
    valueColor: Color? = null,
    cardHeight: androidx.compose.ui.unit.Dp = 170.dp,
    descriptionMaxLines: Int = 2,
    queueLabel: String? = null,
    iconTitleInline: Boolean = false
) {
    Card(
        modifier = modifier,
        onClick = { onClick?.invoke() },
        enabled = true,
        shape = RoundedCornerShape(18.dp),
        border = statusColors?.let { BorderStroke(1.dp, it.border) },
        colors = CardDefaults.cardColors(
            containerColor = statusColors?.background ?: MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            if (iconTitleInline) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(27.dp)
                    )
                    Text(
                        text = title,
                        color = statusColors?.secondaryText ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(27.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        color = statusColors?.secondaryText ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                    if (queueLabel != null) {
                        Text(
                            text = queueLabel,
                            color = if (isSystemInDarkTheme()) AccentBlue else Color(0xFF17699A),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            Text(
                text = value,
                color = valueColor ?: if (highlighted && value.startsWith("Черга"))
                    Color(0xFF2563EB) else (statusColors?.text ?: MaterialTheme.colorScheme.onSurface),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = description,
                color = statusColors?.secondaryText ?: MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = descriptionMaxLines,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}


private fun outageRecordIsActive(record: OutageRecord): Boolean {
    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    val start = runCatching { LocalDateTime.parse(record.start.take(16), formatter) }.getOrNull()
        ?: return false
    val end = runCatching { LocalDateTime.parse(record.end.take(16), formatter) }.getOrNull()
    val now = LocalDateTime.now()
    return !start.isAfter(now) && (end == null || end.isAfter(now))
}

private fun outageRecordTime(record: OutageRecord): String {
    val start = record.start.take(16).ifBlank { "час невідомий" }
    val end = record.end.take(16)
    return if (end.isBlank()) "з $start" else "$start – $end"
}

/** A schedule is a prediction, not proof of the physical state of the power grid. */
private fun electricityStatusFor(graph: HourlyGraph?, now: LocalTime): Pair<String, String> {
    if (graph == null || graph.date != LocalDate.now().toString()) {
        return "Графік недоступний" to ""
    }
    val index = now.hour * 2 + if (now.minute >= 30) 1 else 0
    fun status(slot: Int): String? {
        if (slot !in 0..47) return null
        val h = slot / 2
        val m = if (slot % 2 == 0) "00" else "30"
        return graph.times["%02d:%s".format(Locale.US, h, m)]
    }
    fun time(slot: Int): String = "%02d:%02d".format(Locale.US, slot / 2, (slot % 2) * 30)
    return when (status(index)) {
        "1" -> {
            var start = index
            var end = index + 1
            while (start > 0 && status(start - 1) == "1") start--
            while (end < 48 && status(end) == "1") end++
            "Є відключення" to "${time(start)}–${time(end)}"
        }
        "0" -> "Відключень немає" to ""
        "10" -> "Можливе перемикання" to ""
        else -> "Статус невідомий" to ""
    }
}

@Composable
private fun HomeWeatherIcon(
    code: Int,
    isDay: Boolean
) {
    val icon: ImageVector
    val color: Color

    when (code) {
        0 -> {
            icon = if (isDay) Icons.Default.WbSunny
            else Icons.Default.NightsStay
            color = if (isDay) AccentYellow else AccentBlue
        }

        1, 2 -> {
            icon = if (isDay) Icons.Default.WbCloudy
            else Icons.Default.NightsStay
            color = AccentYellow
        }

        3 -> {
            icon = Icons.Default.Cloud
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }

        45, 48 -> {
            icon = Icons.Default.Dehaze
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }

        51, 53, 55, 56, 57,
        61, 63, 65, 66, 67,
        80, 81, 82 -> {
            icon = Icons.Default.Grain
            color = AccentBlue
        }

        71, 73, 75, 77, 85, 86 -> {
            icon = Icons.Default.AcUnit
            color = AccentBlue
        }

        95, 96, 99 -> {
            icon = Icons.Default.FlashOn
            color = AccentYellow
        }

        else -> {
            icon = Icons.Default.Cloud
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(64.dp)
    )
}

@Composable
private fun AirAlertDetailsScreen(
    state: AirAlertState?,
    district: String?,
    onBack: () -> Unit
) {
    var showNeptunMap by remember { mutableStateOf(false) }
    if (showNeptunMap) {
        NeptunEmbeddedMapScreen(onBack = { showNeptunMap = false })
        return
    }
    BackHandler(onBack = onBack)
    val uriHandler = LocalUriHandler.current
    val palette = KropiLiveStatusTheme.airAlerts
    val level = state?.level ?: AirAlertLevel.UNKNOWN
    val colors = when (level) {
        AirAlertLevel.GREEN -> palette.green
        AirAlertLevel.YELLOW -> palette.yellow
        AirAlertLevel.RED -> palette.red
        AirAlertLevel.UNKNOWN -> palette.unknown
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Мій район", "Область", "Україна")
    val oblastNotices = state?.areaNotices.orEmpty().filter {
        it.oblast.contains("Кіровоград", ignoreCase = true) ||
                it.name.contains("Кіровоград", ignoreCase = true)
    }
    val shownNotices = when (selectedTab) {
        0 -> oblastNotices.filter {
            it.isOblast || it.name.equals(district, ignoreCase = true)
        }
        1 -> oblastNotices
        else -> state?.areaNotices.orEmpty()
    }
    val shownThreats = when (selectedTab) {
        0 -> state?.threats.orEmpty()
        1 -> state?.allThreats.orEmpty().filter {
            it.region.contains("Кіровоград", ignoreCase = true)
        }
        else -> state?.allThreats.orEmpty()
    }
    fun formattedTime(value: String): String = try {
        val instant = java.time.Instant.parse(value)
        SimpleDateFormat("dd.MM HH:mm", Locale("uk", "UA")).format(Date(instant.toEpochMilli()))
    } catch (_: Exception) { value.ifBlank { "Невідомо" } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Text("Повітряна небезпека", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.background),
                border = BorderStroke(1.dp, colors.border),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (level == AirAlertLevel.GREEN) Icons.Default.Security else Icons.Default.Warning,
                        null, tint = colors.accent, modifier = Modifier.size(34.dp))
                    Text(when (level) {
                        AirAlertLevel.GREEN -> "Активних загроз для району не виявлено"
                        AirAlertLevel.YELLOW -> "Підвищена небезпека"
                        AirAlertLevel.RED -> "Червоний рівень NEPTUN"
                        AirAlertLevel.UNKNOWN -> "Статус не підтверджено"
                    }, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = colors.text)
                    Text(district ?: "Район не визначено", color = colors.secondaryText)
                    Text("Інформація NEPTUN не замінює офіційні сигнали тривоги.",
                        color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Оновлення джерел", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        onClick = { showNeptunMap = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Відкрити мапу NEPTUN ↗")
                    }
                    Text("Повідомлення: ${formattedTime(state?.sourceUpdated.orEmpty())}")
                    Text("Загрози: ${formattedTime(state?.threatsUpdated.orEmpty())}")
                    if (state?.threatsUpdated.isNullOrBlank()) {
                        Text("Перелік загроз може бути недоступним для цього рівня.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEachIndexed { index, label ->
                    FilterChip(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text("Повідомлення за територіями · ${shownNotices.size}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (shownNotices.isEmpty()) {
                Text("Немає записів у поточній вибірці.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shownNotices.forEach { notice ->
                val noticeColor = when (notice.level.lowercase(Locale.ROOT)) {
                    "red" -> palette.red
                    "yellow" -> palette.yellow
                    else -> palette.unknown
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = noticeColor.background),
                    border = BorderStroke(1.dp, noticeColor.border),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(notice.name, fontWeight = FontWeight.Bold, color = noticeColor.text)
                        Text("${notice.oblast} · " + when (notice.level.lowercase(Locale.ROOT)) {
                            "red" -> "Червоний рівень"
                            "yellow" -> "Жовтий рівень"
                            else -> "Рівень не визначено"
                        },
                            color = noticeColor.secondaryText)
                        Text("З ${formattedTime(notice.since)}", color = noticeColor.secondaryText)
                        notice.reasons.forEach { Text(it, color = noticeColor.text) }
                    }
                }
            }
            Text("Повідомлення про об'єкти · ${shownThreats.size}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (shownThreats.isEmpty()) {
                Text("Актуальних записів у вибірці немає або джерело недоступне.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shownThreats.forEach { threat ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AccentBlue)
                            Text(threat.title, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                        }
                        val location = listOf(threat.locality, threat.district, threat.region)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        if (location.isNotBlank()) Text(location)
                        if (threat.explanation.isNotBlank()) Text(threat.explanation)
                        Text("Оновлено: ${formattedTime(threat.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall)
                        fun localizedConfidence(value: String): String = when (value.lowercase(Locale.ROOT)) {
                            "low" -> "низька"
                            "medium" -> "середня"
                            "high" -> "висока"
                            else -> value.ifBlank { "невідома" }
                        }
                        fun localizedLifecycle(value: String): String = when (value.lowercase(Locale.ROOT)) {
                            "active" -> "активний"
                            "confirmed" -> "підтверджений"
                            "uncertain" -> "невизначений"
                            "tentative" -> "попередній"
                            "stale" -> "застарілий"
                            "expired" -> "завершений"
                            "removed" -> "видалений"
                            "resolved" -> "завершений"
                            else -> value
                        }
                        fun localizedPosition(value: String): String = when (value.lowercase(Locale.ROOT)) {
                            "approx" -> "приблизна"
                            "confirmed" -> "підтверджена"
                            "exact" -> "точна"
                            "precise" -> "точна"
                            "estimated" -> "орієнтовна"
                            "unknown" -> "невідома"
                            "area" -> "лише район"
                            else -> value
                        }
                        Text("Достовірність: ${localizedConfidence(threat.confidenceLevel)}" +
                                " · Джерел: ${threat.sourceCount}",
                            style = MaterialTheme.typography.bodySmall)
                        if (threat.count > 1) Text("Кількість: ${threat.count}")
                        if (threat.lifecycle.isNotBlank()) Text("Стан: ${localizedLifecycle(threat.lifecycle)}")
                        if (threat.positionQuality.isNotBlank()) {
                            Text("Позиція: ${localizedPosition(threat.positionQuality)}" +
                                    (threat.uncertaintyKm?.let { " · похибка ≈ $it км" } ?: ""),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (threat.heading != null) {
                            Text("Напрямок: ${threat.heading.roundToInt()}°")
                        }
                        if (threat.trail.isNotEmpty()) {
                            Text("Точок історії руху: ${threat.trail.size}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (threat.latitude != null && threat.longitude != null) {
                            TextButton(onClick = {
                                uriHandler.openUri("https://www.openstreetmap.org/?mlat=${threat.latitude}" +
                                        "&mlon=${threat.longitude}#map=9/${threat.latitude}/${threat.longitude}")
                            }) {
                                Icon(Icons.Default.Map, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Приблизна точка на мапі ↗")
                            }
                        }
                    }
                }
            }
            Text("Позиції можуть бути неточними або застарілими. Не використовуйте їх " +
                    "для вибору безпечного маршруту.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { uriHandler.openUri(AirAlertRepository.SOURCE) },
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Відкрити NEPTUN")
            }
        }
    }
}

@Composable
private fun NeptunEmbeddedMapScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val webView = remember(context) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(16, 21, 34))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loading = true
                    loadError = null
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    // The NEPTUN embedded MapLibre map can have zero-height containers
                    // in Android WebView. Apply the tested dimensions after React mounts.
                    val fixMapHeight = """(function(){
                      try {
                        const selectors = [
                          'html', 'body', '#__next', 'main',
                          '[class*="MapExperience_experience"]',
                          '[class*="MapExperience_stage"]',
                          '[class*="ThreatMap_mapRoot"]',
                          '[class*="ThreatMap_canvas"]',
                          '.maplibregl-map', '.maplibregl-canvas-container',
                          'canvas.maplibregl-canvas'
                        ];
                        const h=Math.max(window.innerHeight, 400);
                        for(const sel of selectors){
                          document.querySelectorAll(sel).forEach(el=>{
                            // Set the dimensions on the actual nodes, including nodes
                            // mounted by React after the initial page load.
                            el.style.setProperty('height', h+'px', 'important');
                            el.style.setProperty('min-height', h+'px', 'important');
                            if(sel==='canvas.maplibregl-canvas'){
                              el.style.setProperty('width','100%','important');
                            }
                          });
                        }
                        window.dispatchEvent(new Event('resize'));
                        return true;
                      } catch(e) { return false; }
                    })()""".trimIndent()
                    fun applyHeight() {
                        view?.evaluateJavascript(fixMapHeight, null)
                    }
                    applyHeight()
                    view?.postDelayed({ applyHeight() }, 1000L)
                    view?.postDelayed({ applyHeight() }, 3000L)
                }
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    loadError = "Збій процесу WebView"
                    loading = false
                    return true
                }
                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        loading = false
                        loadError = error?.description?.toString() ?: "Не вдалося завантажити мапу"
                    }
                }
                override fun onReceivedHttpError(
                    view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
                ) {
                    if (request?.isForMainFrame == true) {
                        loading = false
                        loadError = "HTTP ${errorResponse?.statusCode}"
                    }
                }
            }
            loadUrl("https://neptun.in.ua/embed/map?lang=uk")
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
            }
            Text("Мапа NEPTUN", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            if (loadError != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Не вдалося завантажити мапу: $loadError",
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center)
                    OutlinedButton(onClick = {
                        loading = true
                        loadError = null
                        webView.loadUrl("https://neptun.in.ua/embed/map?lang=uk")
                    }) { Text("Спробувати ще раз") }
                }
            }
        }
        OutlinedButton(
            onClick = { uriHandler.openUri("https://neptun.in.ua/embed/map?lang=uk") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Відкрити мапу в браузері")
        }
        Text(
            "Дані: Карта повітряних тривог — NEPTUN · neptun.in.ua",
            modifier = Modifier.fillMaxWidth().clickable {
                uriHandler.openUri("https://neptun.in.ua/")
            }.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "NEPTUN — інформаційний агрегатор. Дотримуйтеся офіційних сигналів тривоги.",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ElectricityScheduleScreen(
    address: UserAddress,
    siteCheck: HourlyGraphCheck?,
    telegramCheck: TelegramGraphCheck?,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Назад"
                )
            }

            Column {
                Text(
                    text = "Графіки відключень",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Електропостачання",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ua.kropilive.app.ui.schedules.HourlyBlackoutCard(
                queue = address.electricityQueue,
                siteCheck = siteCheck,
                telegramCheck = telegramCheck
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )

                    Text(
                        text = "Джерело адреси та черги: " +
                                "Кіровоградобленерго. " +
                                "Графік отримуємо з офіційного сайту, якщо він активний.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}