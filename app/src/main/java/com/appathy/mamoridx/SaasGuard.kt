package com.appathy.mamoridx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Phase 6: 公衆Wi-Fi対策。
 * 登録したSaaSのログインURLについて、
 * ①リダイレクト連鎖　②最終接続先ホスト　③サーバー証明書のフィンガープリント
 * を安全な回線で記録しておき、外出先で変化がないかを照合する。
 */
object SaasGuard {

    private const val PREF = "mamoridx_saas"
    private const val KEY = "sites"

    const val LEVEL_OK = 0
    const val LEVEL_WARN = 1
    const val LEVEL_DANGER = 2

    data class Site(
        val id: String,
        var name: String,
        var url: String,
        var baseHost: String = "",
        var baseFingerprint: String = "",
        var baseChain: String = "",
        var lastResult: String = "",
        var lastLevel: Int = -1,
        var lastCheck: Long = 0L
    )

    data class Probe(
        val ok: Boolean,
        val finalHost: String,
        val fingerprint: String,
        val chain: List<String>,
        val error: String? = null
    )

    data class Verdict(val level: Int, val summary: String, val details: List<String>)

    private val sites = mutableListOf<Site>()
    private var loaded = false

    @Synchronized
    fun load(ctx: Context): List<Site> {
        if (!loaded) {
            try {
                val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY, "[]") ?: "[]"
                val arr = JSONArray(raw)
                sites.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    sites.add(Site(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        url = o.optString("url"),
                        baseHost = o.optString("bh"),
                        baseFingerprint = o.optString("bf"),
                        baseChain = o.optString("bc"),
                        lastResult = o.optString("lr"),
                        lastLevel = o.optInt("ll", -1),
                        lastCheck = o.optLong("lc", 0L)
                    ))
                }
            } catch (e: Exception) { }
            loaded = true
        }
        return sites.toList()
    }

    @Synchronized
    fun save(ctx: Context) {
        val arr = JSONArray()
        for (s in sites) {
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("url", s.url)
                put("bh", s.baseHost); put("bf", s.baseFingerprint)
                put("bc", s.baseChain); put("lr", s.lastResult)
                put("ll", s.lastLevel); put("lc", s.lastCheck)
            })
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun add(ctx: Context, name: String, url: String) {
        sites.add(Site(
            id = System.currentTimeMillis().toString(),
            name = name,
            url = if (url.startsWith("http")) url else "https://$url"
        ))
        save(ctx)
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        sites.removeAll { it.id == id }
        save(ctx)
    }

    @Synchronized
    fun find(id: String): Site? = sites.firstOrNull { it.id == id }

    /** 実際に接続して、リダイレクト連鎖と証明書を取得する（要バックグラウンドスレッド） */
    fun probe(startUrl: String): Probe {
        var url = startUrl
        val chain = mutableListOf<String>()
        var fingerprint = ""
        var finalHost = ""
        try {
            for (hop in 0 until 8) {
                val u = URL(url)
                finalHost = u.host.lowercase()
                val conn = u.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MamoriDX")
                val code = conn.responseCode

                if (conn is HttpsURLConnection && fingerprint.isEmpty()) {
                    try {
                        val certs = conn.serverCertificates
                        if (certs.isNotEmpty()) {
                            fingerprint = sha256Hex(certs[0].encoded)
                        }
                    } catch (e: Exception) { }
                }

                chain.add("${u.protocol}://${u.host} → HTTP $code")

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) break
                    url = URL(u, loc).toString()
                } else {
                    conn.disconnect()
                    break
                }
            }
            return Probe(true, finalHost, fingerprint, chain)
        } catch (e: Exception) {
            return Probe(false, finalHost, fingerprint, chain,
                e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    /** 安全な回線での基準登録 */
    @Synchronized
    fun registerBaseline(ctx: Context, site: Site, p: Probe): Boolean {
        if (!p.ok) return false
        site.baseHost = p.finalHost
        site.baseFingerprint = p.fingerprint
        site.baseChain = p.chain.joinToString(" / ")
        site.lastResult = "基準を登録しました"
        site.lastLevel = LEVEL_OK
        site.lastCheck = System.currentTimeMillis()
        save(ctx)
        return true
    }

    /** 外出先での照合 */
    fun verify(site: Site, p: Probe): Verdict {
        val details = mutableListOf<String>()
        if (!p.ok) {
            details.add("接続できませんでした: ${p.error ?: "不明なエラー"}")
            details.add("公衆Wi-Fiの認証画面（ログインページ）が未完了の可能性もあります。")
            return Verdict(LEVEL_WARN, "接続失敗", details)
        }

        var level = LEVEL_OK
        details.add("接続経路: " + p.chain.joinToString("\n　→ "))
        details.add("最終接続先: ${p.finalHost}")

        if (site.baseHost.isEmpty()) {
            details.add("基準が未登録のため照合できません。安全な回線で先に基準登録してください。")
            return Verdict(LEVEL_WARN, "基準未登録", details)
        }

        if (p.finalHost != site.baseHost) {
            level = LEVEL_DANGER
            details.add("【危険】最終接続先が基準と違います（基準: ${site.baseHost}）。" +
                "別サイトへ誘導されています。公衆Wi-Fiの偽装アクセスポイントや" +
                "認証画面の割り込みが疑われます。ID・パスワードを入力しないでください。")
        }

        if (p.chain.any { it.startsWith("http://") }) {
            if (level < LEVEL_WARN) level = LEVEL_WARN
            details.add("【注意】経路に暗号化されていない通信(http)が含まれます。")
        }

        if (site.baseFingerprint.isNotEmpty() && p.fingerprint.isNotEmpty()) {
            if (p.fingerprint != site.baseFingerprint) {
                level = LEVEL_DANGER
                details.add("【危険】サーバー証明書が基準と異なります。" +
                    "通信の盗聴・改ざん（中間者攻撃）の可能性があります。\n" +
                    "ただし、サイト側が証明書を正規に更新した場合も変化します。" +
                    "安全な回線で確認したうえで、問題なければ基準を再登録してください。")
            } else {
                details.add("証明書は基準と一致しています。")
            }
        } else if (p.fingerprint.isEmpty()) {
            if (level < LEVEL_WARN) level = LEVEL_WARN
            details.add("【注意】証明書を取得できませんでした（httpsではない可能性）。")
        }

        val summary = when (level) {
            LEVEL_DANGER -> "危険：接続先または証明書が変化"
            LEVEL_WARN -> "注意"
            else -> "正常：基準と一致"
        }
        return Verdict(level, summary, details)
    }

    @Synchronized
    fun recordResult(ctx: Context, site: Site, v: Verdict) {
        site.lastResult = v.summary
        site.lastLevel = v.level
        site.lastCheck = System.currentTimeMillis()
        save(ctx)
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(data)
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    fun shortFp(fp: String): String =
        if (fp.length >= 16) fp.chunked(4).take(4).joinToString(":") + "…" else fp
}
