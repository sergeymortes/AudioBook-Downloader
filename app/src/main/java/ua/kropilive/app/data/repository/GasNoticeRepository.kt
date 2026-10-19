package ua.kropilive.app.data.repository

import android.text.Html
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class GasNotice(
    val id: String,
    val date: String,
    val text: String,
    val url: String,
    val affected: Boolean = true,
    val restored: Boolean = false
)

data class GasScheduledOutage(
    val noticeId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val addressLine: String
)

data class GasReport(
    val notices: List<GasNotice>,
    val relevant: List<GasNotice>,
    val addressSpecified: Boolean,
    val verified: Boolean,
    val scheduled: List<GasScheduledOutage> = emptyList()
) {
    val hasReportedIssue: Boolean get() = scheduled.any { !LocalDate.now().isBefore(it.startDate) && !LocalDate.now().isAfter(it.endDate) }
    val hasUpcomingOutage: Boolean get() = scheduled.any { it.startDate.isAfter(LocalDate.now()) }
}

/** Shared observable snapshot for gas. The schedules tab never fetches. */
object SharedGasState {
    var report by mutableStateOf<GasReport?>(null)
        private set
    var addressKey by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set

    fun key(a: UserAddress): String =
        listOf(a.communityId, a.cityName, a.street, a.building).joinToString("|")

    fun forAddress(a: UserAddress): GasReport? = report?.takeIf { addressKey == key(a) }

    fun begin(a: UserAddress) {
        loading = true
        if (addressKey != key(a)) { report = null; addressKey = key(a) }
    }
    fun publish(a: UserAddress, result: GasReport) {
        addressKey = key(a)
        report = result
        loading = false
    }
    fun finish() { loading = false }
}

object GasNoticeRepository {
    const val OFFICIAL_SITE = "https://kr.grmu.com.ua/"
    const val SUPPLIER_SITE = "https://gas.ua/uk/home"
    const val TARIFF_URL = "https://gas.ua/uk/home/tariffs/tariffdetails?id=21"
    const val EMERGENCY_PHONE = "104"
    const val OPERATOR_PHONE = "0800303104"

    private const val CACHE_MS = 5 * 60_000L
    private val mutex = Mutex()
    private var cached: List<GasNotice> = emptyList()
    private var fetchedAt = 0L
    private val uk = Locale("uk", "UA")
    private val dayFormat = DateTimeFormatter.ofPattern("d.M.yyyy", uk)
    private val datePattern = Regex("""(?<!\d)(\d{1,2})\s*\.\s*(\d{1,2})\s*\.\s*(20\d{2})(?!\d)""")
    private val rangePattern = Regex("""(?<!\d)(\d{1,2})\s*[-–—]\s*(\d{1,2})\s*\.\s*(\d{1,2})\s*\.\s*(20\d{2})(?!\d)""")
    private val streetPattern = Regex("""(?:^|\s)(?:вул\.?|вулиця|пров\.?|провулок|просп\.?|проспект|бульвар|бул\.?|пл\.?|площа)\s+""", RegexOption.IGNORE_CASE)
    private val housePattern = Regex("""\d+[a-zа-яіїєґ]?(?:[/\-]\d+[a-zа-яіїєґ]?)?""", RegexOption.IGNORE_CASE)
    private val headingPattern = Regex("""ШАНОВНІ\s+АБОНЕНТИ\s*!?""", RegexOption.IGNORE_CASE)
    private val endingPattern = Regex("""ПРИНОСИМО\s+ВИБАЧЕННЯ[^!\n]*!?""", RegexOption.IGNORE_CASE)

    suspend fun load(address: UserAddress, forceRefresh: Boolean = false): GasReport {
        val (posts, verified) = mutex.withLock {
            val age = System.currentTimeMillis() - fetchedAt
            if (!forceRefresh && cached.isNotEmpty() && age in 0..CACHE_MS) cached to true
            else try {
                val fresh = fetch()
                cached = fresh
                fetchedAt = System.currentTimeMillis()
                fresh to true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                cached to false
            }
        }
        val today = LocalDate.now()
        val specified = address.street.isNotBlank() && address.building.isNotBlank()
        val current = verified && System.currentTimeMillis() - fetchedAt in 0..CACHE_MS
        val scheduled = if (specified && current) posts.filter { it.affected && !it.restored }
            .flatMap { notice ->
                dateSections(notice.text).flatMap { (period, lines) ->
                    lines.filter { matches(it, address) && !period.endInclusive.isBefore(today) }
                        .map { GasScheduledOutage(notice.id, period.start, period.endInclusive, it) }
                }
            }.distinctBy { Triple(it.noticeId, it.startDate, it.addressLine) }
            .sortedBy { it.startDate }
        else emptyList()
        val relevantIds = scheduled.map { it.noticeId }.toSet()
        val relevant = posts.filter { it.id in relevantIds }
        val upcoming = posts.filter { lastBulletinDate(it.text)?.let { d -> !d.isBefore(today) } == true }
        return GasReport(upcoming.ifEmpty { posts }.take(3), relevant, specified, current, scheduled)
    }

    private suspend fun fetch(): List<GasNotice> = withContext(Dispatchers.IO) {
        val connection = (URL(OFFICIAL_SITE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "uk-UA,uk;q=0.9")
        }
        val html = try {
            if (connection.responseCode !in 200..299) error("Gas Networks HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
        parse(html).ifEmpty { error("Не знайдено повідомлень про газопостачання") }
    }

    private fun normalize(input: String): String = input.lowercase(uk)
        .replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
        .replace('і', 'i').replace('ї', 'i').replace('є', 'е').replace('ґ', 'г')
        .replace(Regex("""[^\p{L}\p{N}/\-]+"""), " ")
        .trim().replace(Regex("""\s+"""), " ")

    private fun streetName(input: String): String = normalize(input)
        .replace(Regex("""^(?:вул|вулиця|пров|провулок|просп|проспект|бул|бульвар|пл|площа)\s+"""), "")
        .trim()

    private fun houseId(input: String): String = normalize(input)
        .replace(Regex("""^(?:буд|будинок|номер|№)\s*"""), "")
        .replace(" ", "")

    /** Match an individual address row, never a whole multi-city announcement. */
    fun matches(text: String, address: UserAddress): Boolean {
        val street = streetName(address.street)
        val house = houseId(address.building)
        if (street.length < 3 || house.isBlank()) return false
        val line = normalize(text)
        val city = normalize(address.cityName)
        if (city.isNotBlank() && !line.contains(city)) return false
        val found = Regex("""(?:^| )""" + Regex.escape(street) + """(?: |$)""").find(line) ?: return false
        val after = line.substring(found.range.last + 1)
        val next = streetPattern.find(after)?.range?.first ?: after.length
        return housePattern.findAll(after.take(next).take(140)).any { houseId(it.value) == house }
    }

    private fun parseDate(value: String): LocalDate? {
        val m = datePattern.find(value) ?: return null
        return runCatching {
            LocalDate.parse("${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}", dayFormat)
        }.getOrNull()
    }

    private fun datesFromHeading(line: String): ClosedRange<LocalDate>? {
        val range = rangePattern.find(line)
        if (range != null) {
            val start = runCatching { LocalDate.parse("${range.groupValues[1]}.${range.groupValues[3]}.${range.groupValues[4]}", dayFormat) }.getOrNull()
            val end = runCatching { LocalDate.parse("${range.groupValues[2]}.${range.groupValues[3]}.${range.groupValues[4]}", dayFormat) }.getOrNull()
            if (start != null && end != null && !end.isBefore(start)) return start..end
        }
        val date = parseDate(line) ?: return null
        return date..date
    }

    private fun isDateHeading(line: String): Boolean {
        if (!datePattern.containsMatchIn(line)) return false
        val value = line.trim()
        return value.length < 65 && (value.contains("року", true) || value.endsWith(":") || rangePattern.containsMatchIn(value))
    }

    private fun dateSections(text: String): List<Pair<ClosedRange<LocalDate>, List<String>>> {
        val result = mutableListOf<Pair<ClosedRange<LocalDate>, List<String>>>()
        var current: ClosedRange<LocalDate>? = null
        var lines = mutableListOf<String>()
        fun flush() {
            val period = current
            if (period != null) result.add(period to lines.toList())
            lines = mutableListOf()
        }
        for (raw in text.lines()) {
            val line = raw.trim().trimStart('☑', '✔', '✅', '•', ' ')
            if (isDateHeading(line)) {
                flush()
                current = datesFromHeading(line)
            } else if (current != null && line.isNotBlank()) {
                lines.add(line)
            }
        }
        flush()
        return result
    }

    private fun bulletinMatchesOnDate(text: String, address: UserAddress, today: LocalDate): Boolean =
        dateSections(text).any { (period, lines) -> today in period && lines.any { matches(it, address) } }

    private fun lastBulletinDate(text: String): LocalDate? =
        dateSections(text).maxOfOrNull { it.first.endInclusive }

    /** One complete official bulletin becomes one expandable UI card. */
    fun parse(html: String): List<GasNotice> {
        val cleaned = html
            .replace(Regex("""(?is)<(script|style|noscript)[^>]*>.*?</\1>"""), " ")
            .replace(Regex("""(?i)<br\s*/?>"""), "<br/>")
            .replace(Regex("""(?i)</(?:p|div|li|h[1-6]|tr)>"""), "<br/>")
        val text = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('\u00a0', ' ')
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
        val headings = headingPattern.findAll(text).toList()
        val result = mutableListOf<GasNotice>()
        for ((index, heading) in headings.withIndex()) {
            val next = headings.getOrNull(index + 1)?.range?.first ?: text.length
            val block = text.substring(heading.range.first, next)
            val end = endingPattern.find(block)
            val bulletin = (if (end != null) block.take(end.range.last + 1) else block.take(12_000)).trim()
            if (!bulletin.contains("припинення газопостачання", true)) continue
            val sections = dateSections(bulletin)
            if (sections.isEmpty() || sections.none { (_, lines) -> lines.any { streetPattern.containsMatchIn(it) } }) continue
            val first = sections.minOf { it.first.start }
            val last = sections.maxOf { it.first.endInclusive }
            val restored = bulletin.contains("відновлено газопостачання", true) || bulletin.contains("відновлення газопостачання", true)
            val title = if (restored) "Відновлення газопостачання" else "Тимчасове припинення газопостачання"
            val period = if (first == last) first.toString() else "$first — $last"
            result.add(GasNotice("bulletin_${first}_${last}_$index", period, bulletin, OFFICIAL_SITE, !restored, restored))
        }
        return result.distinctBy { normalize(it.text) }.sortedByDescending { lastBulletinDate(it.text) ?: LocalDate.MIN }
    }
}
