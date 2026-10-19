package ua.kropilive.app.data.repository

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId

/**
 * One startup fetch per source. Address-keyed snapshots prevent cross-address display.
 * No global success state: every service can fail independently.
 */
object StartupPreload {
    private fun key(a: UserAddress) = listOf(a.cityName, a.communityId, a.street, a.building).joinToString("|")
    private data class Entry<T>(val key: String, val at: Long, val value: T)
    private val kyivZone = ZoneId.of("Europe/Kyiv")

    @Volatile private var ecoDb: EcostyleDb? = null
    @Volatile private var ecoCoordinates: Entry<Pair<Double, Double>?>? = null
    fun ecostyleDb(): EcostyleDb? = ecoDb
    fun ecostyleCoordinates(a: UserAddress): Pair<Double, Double>? = ecoCoordinates?.takeIf { it.key == key(a) }?.value

    @Volatile private var water: Entry<WaterReport>? = null
    @Volatile private var air: Entry<AirAlertState>? = null
    @Volatile private var planned: Entry<OutageResult>? = null
    @Volatile private var emergency: Entry<OutageResult>? = null

    // Keep LIVE check outcomes (including NotPublished) rather than only graphs.
    // A failed check is not a verified absence of outages.
    @Volatile private var siteGpv: Entry<HourlyGraphCheck>? = null
    @Volatile private var telegramGpv: Entry<TelegramGraphCheck>? = null

    private fun fresh(at: Long, ttl: Long) = System.currentTimeMillis() - at in 0..ttl
    private fun graphKey(a: UserAddress) = "${key(a)}|${a.electricityQueue.trim().replace(',', '.')}|${LocalDate.now(kyivZone)}"

    fun water(a: UserAddress): WaterReport? = water?.takeIf { it.key == key(a) && fresh(it.at, 5 * 60_000L) }?.value
    fun air(a: UserAddress): AirAlertState? = air?.takeIf { it.key == key(a) && fresh(it.at, 60_000L) }?.value
    fun electricity(a: UserAddress, kind: OutageKind): OutageResult? {
        val entry = if (kind == OutageKind.PLANNED) planned else emergency
        return entry?.takeIf { it.key == key(a) && fresh(it.at, 5 * 60_000L) }?.value
    }

    /** Initial official-site result for this address, queue and Kyiv date. */
    fun hourlyGraphCheck(a: UserAddress): HourlyGraphCheck? =
        siteGpv?.takeIf { it.key == graphKey(a) && fresh(it.at, 2 * 60_000L) }?.value

    /** Initial Telegram result for this address, queue and Kyiv date. */
    fun telegramGraphCheck(a: UserAddress): TelegramGraphCheck? =
        telegramGpv?.takeIf { it.key == graphKey(a) && fresh(it.at, 2 * 60_000L) }?.value

    suspend fun run(address: UserAddress, context: Context) { coroutineScope {
        val k = key(address)
        val appContext = context.applicationContext
        val gasJob = async {
            SharedGasState.begin(address)
            try {
                withTimeoutOrNull(12_000L) { GasNoticeRepository.load(address) }
                    ?.let { SharedGasState.publish(address, it) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            finally { SharedGasState.finish() }
        }
        val waterJob = async {
            try {
                withTimeoutOrNull(7_000L) { WaterNoticeRepository.load(address) }
                    ?.let { water = Entry(k, System.currentTimeMillis(), it) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        // NEPTUN is loaded and cached separately by MainNavigation.
        // Avoid a second simultaneous request to the same source.
        val ecoJob = async {
            try {
                withTimeoutOrNull(9_000L) { EcostyleRepository.load() }?.let { ecoDb = it }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        val ecoLocationJob = async {
            if (address.street.isNotBlank() && address.building.isNotBlank()) {
                try {
                    val coords = withTimeoutOrNull(7_000L) {
                        EcostyleAddressLocator.find(appContext, address.cityName, address.street, address.building)
                    }
                    ecoCoordinates = Entry(k, System.currentTimeMillis(), coords)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
        val electricityJob = async {
            if (address.hasFullAddress) {
                val repository = ElectricityOutageRepository()
                val p = async {
                    try {
                        withTimeoutOrNull(8_000L) { repository.fetch(OutageKind.PLANNED, address) }
                            ?.let { planned = Entry(k, System.currentTimeMillis(), it) }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                val e = async {
                    try {
                        withTimeoutOrNull(8_000L) { repository.fetch(OutageKind.EMERGENCY, address) }
                            ?.let { emergency = Entry(k, System.currentTimeMillis(), it) }
                    } catch (ex: CancellationException) { throw ex } catch (_: Exception) { }
                }
                p.await()
                e.await()
            }
        }
        val gpvJob = async {
            val queue = address.electricityQueue.trim().replace(',', '.')
            if (address.hasFullAddress && Regex("""[1-6]\.[12]""").matches(queue)) {
                val graphSnapshotKey = graphKey(address)
                val site = async {
                    try {
                        withTimeoutOrNull(9_000L) { HourlyBlackoutRepository.check(appContext, queue) }
                            ?.let { siteGpv = Entry(graphSnapshotKey, System.currentTimeMillis(), it) }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                val tg = async {
                    try {
                        withTimeoutOrNull(9_000L) { TelegramBlackoutRepository.check(appContext, queue) }
                            ?.let { telegramGpv = Entry(graphSnapshotKey, System.currentTimeMillis(), it) }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                site.await()
                tg.await()
            }
        }
        // All requests run concurrently; each has a timeout and may fail independently.
        gasJob.await()
        waterJob.await()
        ecoJob.await()
        ecoLocationJob.await()
        electricityJob.await()
        gpvJob.await()
    } }
}
