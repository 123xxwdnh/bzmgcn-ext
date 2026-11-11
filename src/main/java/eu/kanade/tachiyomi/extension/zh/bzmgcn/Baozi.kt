package eu.kanade.tachiyomi.extension.zh.bzmgcn

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class Baozi : HttpSource() {

    override val name = "包子漫画"
    override val baseUrl = "https://cn.bzmgcn.com"
    override val lang = "zh"
    override val supportsLatest = true
    override val versionId = 1

    private val readerHost = "https://cn.cnbzmg.com"

    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val cookies = SimpleCookieJar()

    override val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .addInterceptor(ImageFallback())
        .addInterceptor(Gatekeeper(cookies, ua))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // headers 在 extensions-lib 里是 final，只能改 headersBuilder
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", ua)
        .set("Accept-Language", "zh-CN,zh;q=0.9")

    // ---------------------------------------------------------------- 列表

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/classify?type=all&region=all&state=all&filter=*&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val list = parseCards(response.doc())
        return MangasPage(list, list.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/new", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val list = parseCards(response.doc())
        // 最新页不分页，避免 Mihon 反复拉同一页
        return MangasPage(list, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val list = parseCards(response.doc())
        return MangasPage(list, list.isNotEmpty())
    }

    private fun parseCards(doc: Document): List<SManga> =
        doc.select("div.comics-card").mapNotNull { card ->
            val link = card.selectFirst("a.comics-card__poster") ?: return@mapNotNull null
            val href = link.attr("href")
            if (!href.contains("/comic/")) return@mapNotNull null
            val slug = href.substringAfter("/comic/").substringBefore("?").trimEnd('/')
            if (slug.isEmpty() || slug == "sitemap") return@mapNotNull null
            SManga.create().apply {
                url = slug
                title = link.attr("title").ifBlank {
                    card.selectFirst("amp-img")?.attr("alt").orEmpty()
                }
                thumbnail_url = card.selectFirst("amp-img")?.attr("src")
            }
        }

    // ---------------------------------------------------------------- 详情

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/comic/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.doc()
        return SManga.create().apply {
            url = response.request.url.encodedPath.removePrefix("/comic/").trimEnd('/')
            title = doc.metaContent("og:novel:book_name").ifBlank {
                doc.selectFirst("h1")?.text()?.trim().orEmpty()
            }
            author = doc.metaContent("og:novel:author")
            genre = doc.metaContent("og:novel:category")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
            status = when (doc.metaContent("og:novel:status")) {
                "已完结", "完结", "已完結" -> SManga.COMPLETED
                else -> SManga.ONGOING
            }
            description = doc.metaContent("description")
            thumbnail_url = doc.selectFirst(".de-info__bg")
                ?.attr("style")
                ?.let { Regex("""url\('([^']+)'\)""").find(it)?.groupValues?.get(1) }
        }
    }

    // ---------------------------------------------------------------- 章节

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/comic/${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.doc()
        val slug = response.request.url.encodedPath.removePrefix("/comic/").trimEnd('/')
        val out = LinkedHashMap<String, SChapter>()

        doc.select("a[href*=page_direct]").forEach { a ->
            val href = a.attr("href")
            val section = Regex("""section_slot=(\d+)""").find(href)?.groupValues?.get(1) ?: return@forEach
            val slot = Regex("""chapter_slot=(\d+)""").find(href)?.groupValues?.get(1) ?: return@forEach
            val chapterName = a.text().trim()
            if (chapterName.isEmpty()) return@forEach
            val key = "${section}_$slot"
            if (out.containsKey(key)) return@forEach
            out[key] = SChapter.create().apply {
                name = chapterName
                url = "/comic/chapter/$slug/$key.html"
                chapter_number = parseChapterNumber(chapterName)
            }
        }
        return out.values.toList()
    }

    /**
     * 章节名有两种写法：「第536话 圣帝之力」和「783 新三贤」「1.姐姐1」。
     * 站点不提供每话日期，排序全靠 chapter_number，解析不出来就排不了。
     */
    private fun parseChapterNumber(name: String): Float {
        Regex("""第\s*(\d+(?:\.\d+)?)\s*[话話]""").find(name)?.let {
            return it.groupValues[1].toFloatOrNull() ?: -1f
        }
        Regex("""^\s*(\d+(?:\.\d+)?)""").find(name)?.let {
            return it.groupValues[1].toFloatOrNull() ?: -1f
        }
        return -1f
    }

    // ---------------------------------------------------------------- 图片

    override fun pageListRequest(chapter: SChapter): Request = GET(readerHost + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.doc()
        val urls = doc.select("amp-img").mapNotNull { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }
            if (src.contains("/scomic/")) src else null
        }.distinct()
        return urls.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    // 图片走 pageListParse 直接给绝对地址，这个用不到
    override fun imageUrlParse(response: Response): String = ""

    private fun Response.doc(): Document = Jsoup.parse(body!!.string(), request.url.toString())

    private fun Document.metaContent(key: String): String =
        selectFirst("meta[property=\"$key\"], meta[name=\"$key\"]")?.attr("content")?.trim().orEmpty()
}

// -------------------------------------------------------------------- 反爬

/**
 * 图片 CDN（s1/s2.bzcdn.net）经常 302 到挂掉的边缘节点（如 s1-mha1-nlams.bzcdn.net）。
 * 连不上时换备用静态域名重试，路径不变。
 */
private class ImageFallback : Interceptor {

    private val altHosts = listOf("static-tw.baozimh.com", "static-tw.bzmgcn.com")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.endsWith("bzcdn.net")) return chain.proceed(request)

        try {
            return chain.proceed(request)
        } catch (_: Exception) {
            // 原 CDN 连不上，落到下面的备用域名
        }

        var last: Exception? = null
        for (host in altHosts) {
            try {
                val alt = request.url.newBuilder().scheme("https").host(host).build()
                return chain.proceed(request.newBuilder().url(alt).build())
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("image fallback failed")
    }
}

private class SimpleCookieJar : CookieJar {
    private val store = HashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { c ->
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
        store[url.host]?.filter { it.matches(url) } ?: emptyList()
    }
}

/**
 * 站点用 gatekeeper 挑战挡爬虫：
 *   GET  -> 403 + 一段 JS，里面带 challengeId / ticket / difficultyBits
 *   算 SHA-256("gatekeeper-pow-v1:<id>:<nonce>") 前导零 >= difficultyBits
 *   POST /__gatekeeper_challenge/verify {challenge_id, ticket, nonce} -> 拿到 clearance cookie
 * 这里在拦截器里自动完成，然后用不带拦截器的 client 重放原请求。
 */
private class Gatekeeper(private val jar: CookieJar, private val ua: String) : Interceptor {

    private val bare = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cfgRe = Regex("(challengeId|ticket|verifyUrl)\\s*:\\s*\"([^\"]*)\"")
    private val bitsRe = Regex("difficultyBits\\s*:\\s*(\\d+)")
    private val challengeUrlRe = Regex("\"challenge_url\"\\s*:\\s*\"([^\"]+)\"")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var guard = 0

        while (response.code == 403 && guard < 3) {
            val body = response.peekBody(2L * 1024 * 1024).string()
            val url = response.request.url
            response.close()
            val solved = when {
                // 带浏览器风格的头（Sec-Fetch 等）时，站点直接吐 HTML 挑战页
                body.contains("gatekeeperChallenge") -> solve(body, url)
                // OkHttp 的默认头会触发 JSON 版：{"challenge_url": "...", "error": "challenge_required"}
                body.trimStart().startsWith("{") -> solveJson(body, url)
                else -> false
            }
            if (!solved) break
            response = bare.newCall(request).execute()
            guard++
        }
        return response
    }

    private fun solveJson(body: String, url: HttpUrl): Boolean {
        val path = challengeUrlRe.find(body)?.groupValues?.get(1) ?: return false
        val challengeUrl = url.resolve(path) ?: return false
        val html = try {
            val req = Request.Builder().url(challengeUrl).header("User-Agent", ua).build()
            bare.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (_: Exception) {
            return false
        }
        return solve(html, challengeUrl)
    }

    private fun solve(html: String, url: HttpUrl): Boolean {
        val cfg = cfgRe.findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
        val cid = cfg["challengeId"] ?: return false
        val ticket = cfg["ticket"] ?: return false
        val verifyPath = cfg["verifyUrl"] ?: "/__gatekeeper_challenge/verify"
        val bits = bitsRe.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 16
        val nonce = findNonce(cid, bits)

        val target = url.newBuilder().encodedPath(verifyPath).query(null).build()
        val payload = "{\"challenge_id\":\"$cid\",\"ticket\":\"$ticket\",\"nonce\":\"$nonce\"}"
        val req = Request.Builder()
            .url(target)
            .header("User-Agent", ua)
            .header("Origin", "${url.scheme}://${url.host}")
            .header("Referer", url.toString())
            .header("Accept", "*/*")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            bare.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun findNonce(challengeId: String, bits: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        var n = 0L
        while (true) {
            val digest = md.digest("gatekeeper-pow-v1:$challengeId:$n".toByteArray(Charsets.UTF_8))
            if (leadingZeroBits(digest) >= bits) return n.toString()
            n++
        }
    }

    private fun leadingZeroBits(bytes: ByteArray): Int {
        var count = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                count += 8
                continue
            }
            count += Integer.numberOfLeadingZeros(v) - 24
            return count
        }
        return count
    }
}
