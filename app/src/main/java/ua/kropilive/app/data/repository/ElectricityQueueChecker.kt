package ua.kropilive.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** Перевіряє актуальну чергу за збереженими ідентифікаторами адреси. */
class ElectricityQueueChecker(context: Context) {
    private val appContext = context.applicationContext

    sealed class Result {
        data class Unchanged(val queue: String) : Result()
        data class Changed(val previousQueue: String, val newQueue: String) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun check(address: UserAddress): Result {
        if (!address.hasFullAddress) {
            return Result.Error("Спочатку збережіть повну адресу.")
        }

        return try {
            val actualQueue = withContext(Dispatchers.Main) {
                withTimeout(35_000L) { fetchQueue(address) }
            }
            val oldQueue = address.electricityQueue.trim()
            if (actualQueue == oldQueue) {
                Result.Unchanged(actualQueue)
            } else {
                Result.Changed(oldQueue, actualQueue)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.Error("Не вдалося перевірити чергу. Перевірте інтернет і спробуйте ще раз.")
        }
    }

    private suspend fun fetchQueue(address: UserAddress): String {
        val pageReady = CompletableDeferred<Unit>()
        val webView = WebView(appContext)
        return try {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!pageReady.isCompleted) pageReady.complete(Unit)
                }
            }
            webView.loadUrl("https://kiroe.com.ua/electricity-blackout")
            pageReady.await()

            // Чекаємо, поки сайт підготує форму і список громад.
            awaitJs(webView, """
                (function() {
                    const s = document.getElementById('fn_office');
                    return !!(window.jQuery && s && Array.from(s.options).some(o => o.value));
                })();
            """.trimIndent())

            val officeId = JSONObject.quote(address.communityId)
            val cityId = JSONObject.quote(address.cityId)
            val streetId = JSONObject.quote(address.streetId)
            val houseId = JSONObject.quote(address.houseId)

            val officeResult = js(webView, """
                (function() {
                    try {
                        const id = $officeId;
                        const s = document.getElementById('fn_office');
                        const o = Array.from(s.options).find(x => x.value === id);
                        if (!o) return 'ERROR';
                        jQuery(s).val(id).trigger('change');
                        jQuery(s).trigger({type:'select2:select',params:{data:{id:id,text:o.text.trim()}}});
                        return 'OK';
                    } catch(e) { return 'ERROR'; }
                })();
            """.trimIndent())
            if (officeResult != "OK") error("Громаду не знайдено")

            awaitJs(webView, """
                (function() {
                    const s = document.getElementById('fn_city');
                    return !!(s && !s.disabled && Array.from(s.options).some(o => o.value === $cityId));
                })();
            """.trimIndent())

            val cityResult = js(webView, """
                (function() {
                    try {
                        const id = $cityId;
                        const s = document.getElementById('fn_city');
                        const o = Array.from(s.options).find(x => x.value === id);
                        if (!o) return 'ERROR';
                        jQuery(s).val(id).trigger('change');
                        jQuery(s).trigger({type:'select2:select',params:{data:{id:id,text:o.text.trim()}}});
                        return 'OK';
                    } catch(e) { return 'ERROR'; }
                })();
            """.trimIndent())
            if (cityResult != "OK") error("Населений пункт не знайдено")

            awaitJs(webView, """
                (function() {
                    const ac = jQuery('#fn_street').data('autocomplete');
                    return !!(ac && Array.isArray(ac.options.lookup) &&
                        ac.options.lookup.some(x => String(x.data?.StreetId) === $streetId));
                })();
            """.trimIndent())

            val streetResult = js(webView, """
                (function() {
                    try {
                        const ac = jQuery('#fn_street').data('autocomplete');
                        const item = ac.options.lookup.find(x => String(x.data?.StreetId) === $streetId);
                        if (!item) return 'ERROR';
                        jQuery('#fn_street').val(item.value);
                        ac.selection = item;
                        ac.options.onSelect(item);
                        return 'OK';
                    } catch(e) { return 'ERROR'; }
                })();
            """.trimIndent())
            if (streetResult != "OK") error("Вулицю не знайдено")

            // Черга надходить у chergGpv в результатах автодоповнення будинків.
            awaitJs(webView, """
                (function() {
                    const ac = jQuery('#fn_house').data('autocomplete');
                    return !!(ac && Array.isArray(ac.options.lookup) &&
                        ac.options.lookup.some(x => String(x.data?.HouseId) === $houseId));
                })();
            """.trimIndent())

            val queue = js(webView, """
                (function() {
                    try {
                        const ac = jQuery('#fn_house').data('autocomplete');
                        const house = ac.options.lookup.find(x => String(x.data?.HouseId) === $houseId);
                        return house ? String(house.data?.chergGpv || '').trim() : '';
                    } catch(e) { return ''; }
                })();
            """.trimIndent()).trim()

            if (queue.isBlank() || !Regex("^\\d+(?:[.,]\\d+)?$").matches(queue)) {
                error("Чергу не визначено")
            }
            queue
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private suspend fun awaitJs(webView: WebView, script: String) {
        repeat(45) {
            if (js(webView, script) == "true") return
            delay(350L)
        }
        error("Час очікування даних минув")
    }

    private suspend fun js(webView: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { raw ->
                if (continuation.isActive) {
                    val decoded = try {
                        JSONArray("[$raw]").get(0).toString()
                    } catch (_: Exception) {
                        raw
                    }
                    continuation.resume(decoded)
                }
            }
        }
}
