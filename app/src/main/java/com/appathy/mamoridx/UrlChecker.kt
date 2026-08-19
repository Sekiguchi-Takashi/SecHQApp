package com.appathy.mamoridx

/**
 * リンク検査（完全オフライン・ヒューリスティック方式）。
 * 外部の判定サービスには一切問い合わせない＝URLが第三者に渡らない。
 */
object UrlChecker {

    const val LEVEL_SAFE = 0
    const val LEVEL_CAUTION = 1
    const val LEVEL_DANGER = 2

    data class Finding(val severity: Int, val title: String, val detail: String)
    data class Result(
        val rawUrl: String,
        val host: String,
        val score: Int,
        val level: Int,
        val findings: List<Finding>
    )

    private val shorteners = setOf(
        "bit.ly", "t.co", "tinyurl.com", "is.gd", "ow.ly", "buff.ly", "goo.gl",
        "x.gd", "lnkd.in", "rb.gy", "cutt.ly", "shorturl.at", "t.ly", "urlz.fr", "onl.bz"
    )

    private val riskyTlds = setOf(
        "zip", "mov", "top", "xyz", "tk", "cf", "ga", "ml", "gq", "work", "click",
        "link", "rest", "cam", "icu", "buzz", "country", "kim", "men", "loan",
        "download", "review", "fit", "surf", "monster", "quest", "cyou", "sbs"
    )

    /** 詐称に使われやすいブランド名 */
    /** 詐称に使われやすいブランド名（短すぎる語は誤検知源のため入れない） */
    private val brands = listOf(
        "amazon", "apple", "icloud", "google", "rakuten", "paypay", "mufg", "smbc",
        "mizuho", "mastercard", "docomo", "softbank", "aupay",
        "japanpost", "jppost", "yamato", "kuronekoyamato", "sagawa",
        "netflix", "microsoft", "facebook", "instagram", "twitter",
        "aeon", "eposcard", "saison", "orico", "nicos", "viewcard", "mercari",
        "yahoo", "jreast", "suica", "amex", "jcb"
    )

    /** ブランドの正規ドメイン（登録可能ドメイン部分） */
    private val legitDomains = setOf(
        "amazon.co.jp", "amazon.com", "apple.com", "icloud.com", "google.com",
        "google.co.jp", "rakuten.co.jp", "paypay.ne.jp", "mufg.jp", "bk.mufg.jp",
        "smbc.co.jp", "mizuhobank.co.jp", "jcb.co.jp", "visa.co.jp",
        "mastercard.co.jp", "docomo.ne.jp", "softbank.jp", "japanpost.jp",
        "jp-bank.japanpost.jp", "kuronekoyamato.co.jp", "sagawa-exp.co.jp",
        "ana.co.jp", "jal.co.jp", "line.me", "netflix.com", "microsoft.com",
        "facebook.com", "instagram.com", "twitter.com", "x.com", "aeon.co.jp",
        "eposcard.co.jp", "saisoncard.co.jp", "orico.co.jp", "cr.mufg.jp",
        "mercari.com", "yahoo.co.jp", "ntt.com", "jreast.co.jp", "nta.go.jp"
    )

    private val jpSecondLevel = setOf("co", "ne", "or", "ac", "go", "lg", "ed", "gr")

    fun analyze(input: String): Result {
        val url = input.trim()
        val findings = mutableListOf<Finding>()

        // ---- スキームとホストの抽出 ----
        val schemeEnd = url.indexOf("://")
        val scheme = if (schemeEnd > 0) url.substring(0, schemeEnd).lowercase() else ""
        var rest = if (schemeEnd > 0) url.substring(schemeEnd + 3) else url

        // ユーザー情報部（@より前）は偽装の常套手段
        val slashPos = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (slashPos >= 0) rest.substring(0, slashPos) else rest
        val pathPart = if (slashPos >= 0) rest.substring(slashPos) else ""

        var host = authority
        if (authority.contains('@')) {
            findings.add(Finding(2, "アドレスに @ が含まれます",
                "@ の前は無視されるため、本物のドメインに見せかける典型的な手口です。" +
                "実際の接続先は @ の後ろの部分です。"))
            host = authority.substringAfterLast('@')
        }
        // ポート除去
        if (host.contains(':')) host = host.substringBefore(':')
        host = host.lowercase().trim().trimEnd('.')

        if (host.isEmpty()) {
            return Result(url, "", 0, LEVEL_CAUTION,
                listOf(Finding(1, "URLを読み取れません",
                    "正しいリンクの形式（https://〜）ではありません。入力内容を確認してください。")))
        }

        // ---- 各種チェック ----

        // 1. httpsでない
        if (scheme == "http") {
            findings.add(Finding(1, "暗号化されていません（http）",
                "通信内容が第三者に見られる恐れがあります。ログイン情報は絶対に入力しないでください。"))
        }

        // 2. IPアドレス直打ち
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) {
            findings.add(Finding(2, "ドメイン名でなくIPアドレスです",
                "正規企業のサイトがIPアドレス直接指定になることはほぼありません。"))
        }

        // 3. Punycode（見た目そっくりの偽ドメイン）
        if (host.contains("xn--")) {
            findings.add(Finding(2, "特殊文字を使ったドメインです（Punycode）",
                "キリル文字などを混ぜて本物そっくりに見せる『ホモグラフ攻撃』の可能性があります。"))
        }

        // 4. ホスト名に非ASCII文字
        if (host.any { it.code > 127 }) {
            findings.add(Finding(2, "ドメインに日本語等の非ASCII文字が含まれます",
                "見た目を似せる偽装の可能性があります。"))
        }

        val labels = host.split('.')
        val tld = labels.lastOrNull() ?: ""

        // 5. 短縮URL
        if (shorteners.contains(host)) {
            findings.add(Finding(1, "短縮URLです",
                "本当の接続先が隠されています。展開先が分からないまま開かないでください。"))
        }

        // 6. リスクの高いTLD
        if (riskyTlds.contains(tld)) {
            findings.add(Finding(1, "危険度の高いドメイン種別（.$tld）",
                "無料または低価格で大量取得でき、詐欺サイトに多用される種別です。"))
        }

        // 7. サブドメインが異常に多い
        if (labels.size >= 5) {
            findings.add(Finding(1, "サブドメインが多すぎます（${labels.size}階層）",
                "本物のドメイン名に見せかけるため、長く分割している可能性があります。"))
        }

        // 8. ホスト名が異常に長い
        if (host.length > 40) {
            findings.add(Finding(1, "ドメイン名が異常に長い（${host.length}文字）",
                "画面に収まりきらず、末尾の本当のドメインを隠す狙いがあります。"))
        }

        // 9. ブランド名の詐称（登録ドメインが正規でないのにブランド名を含む）
        // 「online」に"line"が含まれる等の誤検知を避けるため、区切り文字で分割した
        // トークン単位で照合する
        val registrable = registrableDomain(labels)
        val tokens = host.split('.', '-', '_').filter { it.isNotBlank() }
        val brandHit = brands.firstOrNull { b ->
            tokens.any { it == b || (b.length >= 5 && it.startsWith(b)) }
        }
        if (brandHit != null && !legitDomains.contains(registrable)) {
            findings.add(Finding(2, "有名企業名を含む偽ドメインの疑い",
                "「$brandHit」の名前が入っていますが、実際の接続先は「$registrable」です。" +
                "本物の公式ドメインではありません。"))
        }

        // 10. ハイフンや数字の乱用
        val domainLabel = labels.getOrNull(labels.size - 2) ?: ""
        if (domainLabel.count { it == '-' } >= 2) {
            findings.add(Finding(1, "ハイフンを多用したドメイン",
                "「brand-security-login」のように単語を繋げる偽サイトに多い形です。"))
        }

        // 11. ログイン誘導系のパス
        val lowerPath = pathPart.lowercase()
        val lure = listOf("login", "signin", "verify", "confirm", "update", "secure",
            "account", "webscr", "unlock", "suspend", "payment")
        val lureHit = lure.firstOrNull { lowerPath.contains(it) }
        if (lureHit != null && findings.isNotEmpty()) {
            findings.add(Finding(1, "認証情報の入力を促すページの可能性",
                "アドレスに「$lureHit」が含まれます。ID・パスワード・カード番号の入力要求に注意してください。"))
        }

        // 12. 過剰なパーセントエンコード
        if (Regex("%[0-9A-Fa-f]{2}").findAll(url).count() >= 5) {
            findings.add(Finding(1, "文字が大量に符号化されています",
                "内容を読み取りにくくするための難読化の可能性があります。"))
        }

        // 13. APKの直接ダウンロード
        if (lowerPath.endsWith(".apk") || lowerPath.contains(".apk?")) {
            findings.add(Finding(2, "アプリ（APK）の直接ダウンロードリンクです",
                "ストアを経由しないアプリ配布です。インストールしないでください。"))
        }

        val score = findings.sumOf { it.severity }
        val level = when {
            findings.any { it.severity == 2 } || score >= 4 -> LEVEL_DANGER
            score >= 1 -> LEVEL_CAUTION
            else -> LEVEL_SAFE
        }
        return Result(url, host, score, level, findings)
    }

    /** 登録可能ドメイン（例: sub.example.co.jp -> example.co.jp）の近似算出 */
    private fun registrableDomain(labels: List<String>): String {
        if (labels.size <= 2) return labels.joinToString(".")
        val second = labels[labels.size - 2]
        return if (jpSecondLevel.contains(second) && labels.size >= 3) {
            labels.takeLast(3).joinToString(".")
        } else {
            labels.takeLast(2).joinToString(".")
        }
    }

    /** テキスト中の最初のURLを抽出（なければnull） */
    fun extractUrl(text: String): String? {
        val m = Regex("(https?://[^\\s　\"'<>]+)").find(text)
        return m?.value
    }
}
