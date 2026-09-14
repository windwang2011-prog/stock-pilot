package com.stockpilot.app.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 板块
 * 字段语义（已用真实接口核对）：
 *   changePct=f3 涨跌幅 ｜ mainFund=f62 主力净流入额 ｜ mainPct=f184 主力净占比
 *   turnover=f8 换手率   ｜ upCount=f104 上涨家数 ｜ downCount=f105 下跌家数
 *   leaderName=f128 领涨股名称 ｜ leaderCode=f140 领涨股代码
 */
data class Sector(
    val code: String,
    val name: String,
    val changePct: Double,
    val mainFund: Double,
    val turnover: Double,
    val mainPct: Double = 0.0,
    val upCount: Int = 0,
    val downCount: Int = 0,
    val leaderName: String = "",
    val leaderCode: String = ""
)

/** 成分股简表 */
data class StockBrief(
    val secid: String,
    val code: String,
    val name: String,
    val changePct: Double
)

/**
 * 行情数据层：东方财富为主、腾讯为备用（不同域名，可绕开对 eastmoney 的定向拦截）。
 * 多候选地址降级 + 按域名熔断 + 内存缓存。与 core/datasource.ts 行为一致。
 */
class DataSource {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val breaker = ConcurrentHashMap<String, LongArray>()      // host -> [fails, untilMs]
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private class CacheEntry(val t: Long, val v: String)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36",
        "Referer" to "https://quote.eastmoney.com/"
    )

    private val altHosts: Map<String, List<String>> = mapOf(
        "push2.eastmoney.com" to listOf("http://push2.eastmoney.com", "https://push2delay.eastmoney.com", "http://push2delay.eastmoney.com"),
        "push2his.eastmoney.com" to listOf("http://push2his.eastmoney.com"),
        "web.ifzq.gtimg.cn" to listOf("http://web.ifzq.gtimg.cn"),
        "qt.gtimg.cn" to listOf("http://qt.gtimg.cn")
    )

    companion object {
        private const val BREAKER_THRESHOLD = 3
        private const val BREAKER_COOLDOWN = 30_000L
    }

    private fun hostOf(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return "unknown"
        val rest = url.substring(i + 3)
        val j = rest.indexOf('/')
        return if (j < 0) rest else rest.substring(0, j)
    }

    private fun altUrls(url: String): List<String> {
        val host = hostOf(url)
        val alts = altHosts[host] ?: return emptyList()
        val path = url.removePrefix("https://$host").removePrefix("http://$host")
        return alts.map { it + path }
    }

    /** 带降级 / 熔断 / 缓存的取文本；失败抛异常 */
    fun fetchText(url: String, cacheTtlMs: Long): String {
        val hit = cache[url]
        if (hit != null && System.currentTimeMillis() - hit.t < cacheTtlMs) return hit.v

        val candidates = ArrayList<String>()
        candidates.add(url)
        candidates.addAll(altUrls(url))

        var lastErr = "network failed"
        for ((ci, cand) in candidates.withIndex()) {
            val host = hostOf(cand)
            val st = breaker[host] ?: longArrayOf(0, 0)
            if (System.currentTimeMillis() < st[1]) {
                lastErr = "$host 熔断冷却中"
                continue
            }
            val attempts = if (ci == 0) 2 else 1
            var ok: String? = null
            for (i in 0 until attempts) {
                try {
                    ok = httpGet(cand)
                    break
                } catch (e: Exception) {
                    lastErr = e.message ?: e.javaClass.simpleName
                    if (i < attempts - 1) Thread.sleep(250)
                }
            }
            if (ok != null) {
                breaker[host] = longArrayOf(0, 0)
                cache[url] = CacheEntry(System.currentTimeMillis(), ok)
                return ok
            }
            st[0] = st[0] + 1
            if (st[0] >= BREAKER_THRESHOLD) st[1] = System.currentTimeMillis() + BREAKER_COOLDOWN
            breaker[host] = st
        }
        throw RuntimeException(lastErr)
    }

    private fun httpGet(url: String): String {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.header(k, v)
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP " + resp.code)
            val body = resp.body ?: throw RuntimeException("empty body")
            return body.string()
        }
    }

    // ---------------- 实时报价 ----------------
    fun getQuotes(secids: List<String>): List<Quote> {
        if (secids.isEmpty()) return emptyList()
        return try {
            emQuotes(secids)
        } catch (e: Exception) {
            tencentQuotes(secids)
        }
    }

    private fun emQuotes(secids: List<String>): List<Quote> {
        val url = "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2" +
                "&fields=f1,f2,f3,f4,f5,f6,f7,f8,f10,f12,f13,f14,f15,f16,f17,f18,f22,f62,f104,f105,f106,f184,f124" +
                "&secids=" + secids.joinToString(",")
        val txt = fetchText(url, 5_000)
        val diff = JSONObject(txt).optJSONObject("data")?.optJSONArray("diff") ?: JSONArray()
        val out = ArrayList<Quote>()
        for (i in 0 until diff.length()) {
            val d = diff.optJSONObject(i) ?: continue
            val px = jd(d, "f2") ?: continue
            out.add(
                Quote(
                    secid = js(d, "f13") + "." + js(d, "f12"),
                    code = js(d, "f12") ?: "",
                    market = js(d, "f13") ?: "",
                    name = js(d, "f14"),
                    price = px,
                    changePct = jd(d, "f3"),
                    change = jd(d, "f4"),
                    volume = jd(d, "f5"),
                    amount = jd(d, "f6"),
                    amplitude = jd(d, "f7"),
                    turnover = jd(d, "f8"),
                    volumeRatio = jd(d, "f10"),
                    high = jd(d, "f15"),
                    low = jd(d, "f16"),
                    open = jd(d, "f17"),
                    prevClose = jd(d, "f18"),
                    speed = jd(d, "f22"),
                    mainNet = jd(d, "f62"),
                    mainPct = jd(d, "f184"),
                    advanceCount = jd(d, "f104")?.toInt(),
                    declineCount = jd(d, "f105")?.toInt(),
                    flatCount = jd(d, "f106")?.toInt()
                )
            )
        }
        if (out.isEmpty()) throw RuntimeException("empty quotes")
        return out
    }

    private fun tencentQuotes(secids: List<String>): List<Quote> {
        val syms = secids.map { sid ->
            val p = sid.split(".")
            (if (p[0] == "1") "sh" else "sz") + p.getOrElse(1) { "" }
        }
        val url = "https://qt.gtimg.cn/q=" + syms.joinToString(",")
        val txt = fetchText(url, 5_000)
        val map = HashMap<String, Quote>()
        for (seg in txt.split(";")) {
            val m = Regex("v_([a-z]{2}\\d+)=\"([^\"]*)\"").find(seg) ?: continue
            val sym = m.groupValues[1]
            val f = m.groupValues[2].split("~")
            if (f.size < 52) continue
            val px = f[3].toDoubleOrNull() ?: continue
            if (px <= 0) continue
            val code = sym.substring(2)
            val mkt = if (sym.startsWith("sh")) "1" else "0"
            map[sym] = Quote(
                secid = "$mkt.$code", code = code, market = mkt,
                price = px,
                prevClose = f[4].toDoubleOrNull(),
                open = f[5].toDoubleOrNull(),
                volume = f[6].toDoubleOrNull(),
                change = f[31].toDoubleOrNull(),
                changePct = f[32].toDoubleOrNull(),
                high = f[33].toDoubleOrNull(),
                low = f[34].toDoubleOrNull(),
                amount = f[37].toDoubleOrNull()?.times(10000),
                turnover = f[38].toDoubleOrNull(),
                amplitude = f[43].toDoubleOrNull(),
                volumeRatio = f[49].toDoubleOrNull(),
                avg = f[51].toDoubleOrNull()
            )
        }
        val out = ArrayList<Quote>()
        for (sym in syms) {
            map[sym]?.let { out.add(it) }
        }
        if (out.isEmpty()) throw RuntimeException("empty tencent quotes")
        return out
    }

    // ---------------- K 线 ----------------
    fun getKlines(secid: String, limit: Int, klt: Int = 101): List<Kline> {
        return try {
            emKline(secid, limit, klt)
        } catch (e: Exception) {
            tencentKline(secid, limit, klt)
        }
    }

    private fun emKline(secid: String, limit: Int, klt: Int): List<Kline> {
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
                "?secid=$secid&klt=$klt&fqt=1&lmt=$limit&end=20500101" +
                "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
        val txt = fetchText(url, 60_000)
        val arr = JSONObject(txt).optJSONObject("data")?.optJSONArray("klines") ?: JSONArray()
        val rows = ArrayList<Kline>()
        for (i in 0 until arr.length()) {
            val p = arr.optString(i).split(",")
            if (p.size < 6) continue
            val close = p[2].toDoubleOrNull() ?: continue
            if (close <= 0) continue
            rows.add(Kline(p[0], p[1].toDoubleOrNull() ?: 0.0, close, p[3].toDoubleOrNull() ?: 0.0,
                p[4].toDoubleOrNull() ?: 0.0, p[5].toDoubleOrNull() ?: 0.0))
        }
        if (rows.isEmpty()) throw RuntimeException("empty klines")
        return rows
    }

    private fun tencentKline(secid: String, limit: Int, klt: Int): List<Kline> {
        val period = when (klt) {
            101 -> "day"
            102 -> "week"
            103 -> "month"
            else -> throw RuntimeException("unsupported klt $klt")
        }
        val p = secid.split(".")
        val sym = (if (p[0] == "1") "sh" else "sz") + p.getOrElse(1) { "" }
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$sym,$period,,,$limit,qfq"
        val txt = fetchText(url, 60_000)
        val node = JSONObject(txt).optJSONObject("data")?.optJSONObject(sym)
            ?: throw RuntimeException("empty tencent kline")
        val arr = node.optJSONArray("qfq$period") ?: node.optJSONArray(period) ?: JSONArray()
        val rows = ArrayList<Kline>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            val close = r.optString(2).toDoubleOrNull() ?: continue
            if (close <= 0) continue
            rows.add(Kline(r.optString(0), r.optString(1).toDoubleOrNull() ?: 0.0, close,
                r.optString(3).toDoubleOrNull() ?: 0.0, r.optString(4).toDoubleOrNull() ?: 0.0,
                r.optString(5).toDoubleOrNull() ?: 0.0))
        }
        if (rows.isEmpty()) throw RuntimeException("empty tencent klines")
        return rows
    }

    // ---------------- 板块 ----------------
    /**
     * 概念板块全量列表（东方财富共 500+ 个，接口每页上限 100，需要翻页）。
     * 只取第一页会得到「涨幅榜前 100」，用它统计涨跌家数会严重失真，因此必须取全量。
     */
    fun getHotSectors(): List<Sector> {
        val out = ArrayList<Sector>()
        val seen = HashSet<String>()
        var page = 1
        var total = Int.MAX_VALUE
        while (out.size < total && page <= 8) {
            val url = "https://push2.eastmoney.com/api/qt/clist/get?pn=$page&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3" +
                    "&fs=m:90+t:3&fields=f12,f14,f3,f8,f62,f104,f105,f128,f140,f184"
            val txt = try {
                fetchText(url, 120_000)
            } catch (e: Exception) {
                break   // 部分页失败时保留已取到的数据，避免整体不可用
            }
            val data = JSONObject(txt).optJSONObject("data") ?: break
            total = data.optInt("total", 0)
            val diff = data.optJSONArray("diff") ?: break
            if (diff.length() == 0) break
            for (i in 0 until diff.length()) {
                val d = diff.optJSONObject(i) ?: continue
                val code = js(d, "f12") ?: continue
                if (!seen.add(code)) continue
                out.add(
                    Sector(
                        code = code,
                        name = js(d, "f14") ?: "",
                        changePct = jd(d, "f3") ?: 0.0,
                        mainFund = jd(d, "f62") ?: 0.0,
                        turnover = jd(d, "f8") ?: 0.0,
                        mainPct = jd(d, "f184") ?: 0.0,
                        upCount = (jd(d, "f104") ?: 0.0).toInt(),
                        downCount = (jd(d, "f105") ?: 0.0).toInt(),
                        leaderName = js(d, "f128") ?: "",
                        leaderCode = js(d, "f140") ?: ""
                    )
                )
            }
            if (diff.length() < 100) break
            page++
        }
        if (out.isEmpty()) throw RuntimeException("empty sectors")
        return out
    }

    fun getSectorStocks(code: String): List<StockBrief> {
        val url = "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3" +
                "&fs=b:$code&fields=f12,f13,f14,f3"
        val txt = fetchText(url, 60_000)
        val diff = JSONObject(txt).optJSONObject("data")?.optJSONArray("diff") ?: JSONArray()
        val out = ArrayList<StockBrief>()
        for (i in 0 until diff.length()) {
            val d = diff.optJSONObject(i) ?: continue
            val c = js(d, "f12") ?: continue
            val m = js(d, "f13") ?: continue
            out.add(StockBrief("$m.$c", c, js(d, "f14") ?: c, jd(d, "f3") ?: 0.0))
        }
        return out
    }

    // ---------------- 搜索 ----------------
    fun searchStocks(kw: String): List<StockRef> {
        val token = "D43BF7224FB9E8E14DAEAFBAB648853A"
        val url = "https://searchapi.eastmoney.com/api/suggest/get?input=" +
                java.net.URLEncoder.encode(kw, "UTF-8") +
                "&type=14&token=$token&client=PC&src=EM_quote&version=2019"
        val txt = fetchText(url, 60_000)
        val rows = JSONObject(txt).optJSONObject("QuotationCodeTable")?.optJSONArray("Data") ?: JSONArray()
        val out = ArrayList<StockRef>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val code = js(r, "Code") ?: continue
            if (!code.matches(Regex("^\\d.*"))) continue
            val market = if (code.startsWith("6")) "1" else "0"
            out.add(StockRef("$market.$code", code, js(r, "Name") ?: code))
            if (out.size >= 15) break
        }
        return out
    }

    // ---------------- JSON 取值助手 ----------------
    private fun jd(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        return when (v) {
            is Number -> v.toDouble()
            is String -> {
                if (v.isEmpty() || v == "-") null else v.toDoubleOrNull()
            }
            else -> null
        }
    }

    private fun js(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.opt(key)?.toString()
    }
}
