package com.appathy.mamoridx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Phase 8: 機器（ルーター/PC/NAS等）のバージョン台帳と更新確認。
 * メーカーの更新情報ページを取得し、前回との差分を検出する。
 */
object AssetLedger {

    private const val PREF = "mamoridx_assets"
    private const val KEY = "assets"

    data class Asset(
        val id: String,
        var name: String,
        var kind: String,
        var currentVersion: String,
        var checkUrl: String,
        var pattern: String,
        var lastFound: String = "",
        var lastHash: String = "",
        var lastCheck: Long = 0L,
        var changed: Boolean = false,
        var prevFound: String = ""
    )

    data class Outcome(
        val ok: Boolean,
        val found: String,
        val changed: Boolean,
        val message: String
    )

    /** 既定のバージョン抽出パターン */
    const val DEFAULT_PATTERN =
        "(?i)(?:ver(?:sion)?\\.?|v)\\s*([0-9]+(?:\\.[0-9]+){1,3})"

    private val assets = mutableListOf<Asset>()
    private var loaded = false

    @Synchronized
    fun load(ctx: Context): List<Asset> {
        if (!loaded) {
            try {
                val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY, "[]") ?: "[]"
                val arr = JSONArray(raw)
                assets.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    assets.add(Asset(
                        id = o.optString("id"),
                        name = o.optString("n"),
                        kind = o.optString("k"),
                        currentVersion = o.optString("cv"),
                        checkUrl = o.optString("u"),
                        pattern = o.optString("p"),
                        lastFound = o.optString("lf"),
                        lastHash = o.optString("lh"),
                        lastCheck = o.optLong("lc", 0L),
                        changed = o.optBoolean("ch", false),
                        prevFound = o.optString("pf")
                    ))
                }
            } catch (e: Exception) { }
            loaded = true
        }
        return assets.toList()
    }

    @Synchronized
    fun save(ctx: Context) {
        val arr = JSONArray()
        for (a in assets) {
            arr.put(JSONObject().apply {
                put("id", a.id); put("n", a.name); put("k", a.kind)
                put("cv", a.currentVersion); put("u", a.checkUrl); put("p", a.pattern)
                put("lf", a.lastFound); put("lh", a.lastHash); put("lc", a.lastCheck)
                put("ch", a.changed); put("pf", a.prevFound)
            })
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun add(ctx: Context, name: String, kind: String, version: String,
            url: String, pattern: String) {
        assets.add(Asset(
            id = System.currentTimeMillis().toString(),
            name = name,
            kind = kind,
            currentVersion = version,
            checkUrl = if (url.startsWith("http")) url else "https://$url",
            pattern = pattern.ifBlank { DEFAULT_PATTERN }
        ))
        save(ctx)
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        assets.removeAll { it.id == id }
        save(ctx)
    }

    @Synchronized
    fun find(id: String): Asset? = assets.firstOrNull { it.id == id }

    @Synchronized
    fun acknowledge(ctx: Context, id: String) {
        val a = find(id) ?: return
        if (a.lastFound.isNotEmpty()) a.currentVersion = a.lastFound
        a.changed = false
        save(ctx)
    }

    /** 更新確認（要バックグラウンドスレッド） */
    fun check(ctx: Context, asset: Asset): Outcome {
        val text = try {
            fetchText(asset.checkUrl)
        } catch (e: Exception) {
            return Outcome(false, "", false,
                "ページを取得できませんでした: ${e.javaClass.simpleName}")
        }
        if (text.isBlank()) {
            return Outcome(false, "", false, "ページの内容が空でした")
        }

        val plain = stripHtml(text)
        val hash = sha256Hex(plain.toByteArray())

        var found = ""
        try {
            val re = Regex(asset.pattern.ifBlank { DEFAULT_PATTERN })
            val matches = re.findAll(plain).toList()
            if (matches.isNotEmpty()) {
                // 最も大きいバージョン番号を採用
                found = matches
                    .mapNotNull { it.groupValues.getOrNull(1)?.takeIf { v -> v.isNotBlank() } }
                    .maxByOrNull { versionKey(it) } ?: ""
            }
        } catch (e: Exception) {
            return Outcome(false, "", false, "抽出パターンが不正です: ${e.message}")
        }

        val prevFound = asset.lastFound
        val prevHash = asset.lastHash
        val versionChanged = found.isNotEmpty() && prevFound.isNotEmpty() && found != prevFound
        val pageChanged = prevHash.isNotEmpty() && hash != prevHash

        asset.prevFound = prevFound
        asset.lastFound = found
        asset.lastHash = hash
        asset.lastCheck = System.currentTimeMillis()

        val msg: String
        val changed: Boolean
        when {
            prevHash.isEmpty() -> {
                changed = found.isNotEmpty() && found != asset.currentVersion
                msg = if (found.isEmpty())
                    "初回取得。ページ内でバージョン表記を見つけられませんでした（変化検知のみ有効）"
                else if (changed)
                    "初回取得。ページ上の最新は $found、台帳は ${asset.currentVersion} です"
                else "初回取得。台帳のバージョンと一致しています"
            }
            versionChanged -> {
                changed = true
                msg = "バージョンが変わりました: $prevFound → $found"
            }
            found.isNotEmpty() && found != asset.currentVersion -> {
                changed = true
                msg = "ページ上の最新は $found（台帳は ${asset.currentVersion}）"
            }
            pageChanged -> {
                changed = true
                msg = "バージョン表記は同じですが、ページ内容が更新されています。手動で確認してください"
            }
            else -> {
                changed = false
                msg = if (found.isEmpty()) "変化はありません" else "最新のままです（$found）"
            }
        }
        asset.changed = changed
        save(ctx)
        return Outcome(true, found, changed, msg)
    }

    private fun fetchText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MamoriDX")
        try {
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(conn.inputStream)).use { r ->
                val buf = CharArray(8192)
                var total = 0
                while (total < 800_000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.appendRange(buf, 0, n)
                    total += n
                }
            }
            return sb.toString()
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    private fun stripHtml(html: String): String {
        var s = html
        s = Regex("(?is)<script.*?</script>").replace(s, " ")
        s = Regex("(?is)<style.*?</style>").replace(s, " ")
        s = Regex("(?is)<!--.*?-->").replace(s, " ")
        s = Regex("(?s)<[^>]*>").replace(s, " ")
        s = Regex("\\s+").replace(s, " ")
        return s.trim()
    }

    /** 比較用にバージョン文字列を数値化 */
    private fun versionKey(v: String): Long {
        val parts = v.split('.').mapNotNull { it.toIntOrNull() }
        var key = 0L
        for (i in 0 until 4) {
            key = key * 1000 + (parts.getOrNull(i) ?: 0).coerceIn(0, 999)
        }
        return key
    }

    private fun sha256Hex(data: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
