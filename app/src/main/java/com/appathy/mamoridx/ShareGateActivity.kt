package com.appathy.mamoridx

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Phase 3: 漏洩ガード（関所）。
 * 他アプリの共有シートから本Activityを経由させ、
 * テキスト: マイナンバー(チェックデジット検証) / クレカ番号(Luhn) / 機密キーワード を検査。
 * 画像: EXIFの位置情報(GPS)の有無を検査。
 * 検査後に「マスクして転送 / そのまま転送 / 中止」を選択できる。
 */
class ShareGateActivity : Activity() {

    private val bgColor = Color.parseColor("#121212")
    private val cardColor = Color.parseColor("#1E1E1E")
    private val textColor = Color.parseColor("#EEEEEE")
    private val subColor = Color.parseColor("#9E9E9E")
    private val greenColor = Color.parseColor("#4CAF50")
    private val yellowColor = Color.parseColor("#FFC107")
    private val redColor = Color.parseColor("#F44336")

    data class Finding(val type: String, val display: String, val raw: String)

    private val keywords = listOf(
        "社外秘", "部外秘", "極秘", "マル秘", "機密", "取扱注意",
        "confidential", "internal only", "do not distribute"
    )

    private val snsPackages = mapOf(
        "com.twitter.android" to "X (Twitter)",
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "jp.naver.line.android" to "LINE",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.zhiliaoapp.musically" to "TikTok"
    )

    private var sharedText: String? = null
    private var sharedImage: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val type = intent.type ?: ""
        val findings = mutableListOf<Finding>()

        if (type.startsWith("text/")) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            findings.addAll(scanText(sharedText ?: ""))
        } else if (type.startsWith("image/")) {
            @Suppress("DEPRECATION")
            sharedImage = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            findings.addAll(scanImage(sharedImage))
        }

        setContentView(buildUi(findings, type))
    }

    // =========================================================
    // 検査ロジック
    // =========================================================
    private fun scanText(text: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val normalized = normalizeDigits(text)

        // 数字列（ハイフン/スペース区切り許容）を抽出
        val digitSeq = Regex("[0-9](?:[ \\-][0-9]|[0-9]){9,18}")
        val seen = HashSet<String>()
        for (m in digitSeq.findAll(normalized)) {
            val digits = m.value.filter { it.isDigit() }
            if (!seen.add(digits)) continue
            when {
                digits.length == 12 && myNumberValid(digits) ->
                    findings.add(Finding("マイナンバー",
                        "マイナンバーの可能性（検査番号一致）: ${mask(digits)}", digits))
                digits.length in 14..16 && luhnValid(digits) ->
                    findings.add(Finding("クレジットカード",
                        "カード番号の可能性（Luhn一致）: ${mask(digits)}", digits))
            }
        }

        // 機密キーワード
        val lower = text.lowercase()
        for (kw in keywords) {
            if (lower.contains(kw.lowercase())) {
                findings.add(Finding("機密キーワード", "「$kw」を含んでいます", kw))
            }
        }

        // URLの安全性（オフライン検査）
        val url = UrlChecker.extractUrl(text)
        if (url != null) {
            val r = UrlChecker.analyze(url)
            if (r.level != UrlChecker.LEVEL_SAFE) {
                val head = if (r.level == UrlChecker.LEVEL_DANGER)
                    "危険なリンクの疑い" else "リンクに注意点あり"
                val body = "接続先: ${r.host}\n" +
                    r.findings.joinToString("\n") { "・${it.title}" }
                findings.add(Finding("リンク検査", "$head\n$body", url))
            }
        }
        return findings
    }

    private fun scanImage(uri: Uri?): List<Finding> {
        if (uri == null) return emptyList()
        val findings = mutableListOf<Finding>()
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                if (lat != null) {
                    findings.add(Finding("位置情報",
                        "画像に撮影場所のGPS情報が含まれています。" +
                        "自宅や職場の位置が特定される恐れがあります", "GPS"))
                }
            }
        } catch (e: Exception) { }
        return findings
    }

    private fun normalizeDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(if (c in '０'..'９') ('0' + (c - '０')) else c)
        }
        return sb.toString()
    }

    /** マイナンバー検査用数字（チェックデジット）検証: JIS準拠 */
    private fun myNumberValid(s: String): Boolean {
        if (s.length != 12 || s.any { !it.isDigit() }) return false
        val d = s.map { it - '0' }
        var sum = 0
        for (n in 1..11) {
            val p = d[11 - n]
            val q = if (n <= 6) n + 1 else n - 5
            sum += p * q
        }
        val r = sum % 11
        val cd = if (r <= 1) 0 else 11 - r
        return cd == d[11]
    }

    private fun luhnValid(s: String): Boolean {
        var sum = 0
        var alt = false
        for (i in s.length - 1 downTo 0) {
            var v = s[i] - '0'
            if (alt) { v *= 2; if (v > 9) v -= 9 }
            sum += v
            alt = !alt
        }
        return sum % 10 == 0
    }

    private fun mask(digits: String): String =
        "*".repeat(digits.length - 4) + digits.takeLast(4)

    private fun maskedText(): String {
        var t = normalizeDigits(sharedText ?: "")
        val digitSeq = Regex("[0-9](?:[ \\-][0-9]|[0-9]){9,18}")
        t = digitSeq.replace(t) { m ->
            val digits = m.value.filter { it.isDigit() }
            if ((digits.length == 12 && myNumberValid(digits)) ||
                (digits.length in 14..16 && luhnValid(digits))) mask(digits) else m.value
        }
        return t
    }

    // =========================================================
    // UI
    // =========================================================
    private fun buildUi(findings: List<Finding>, type: String): ScrollView {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        list.addView(TextView(this).apply {
            text = "守りのDX 関所"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
        })
        list.addView(TextView(this).apply {
            text = "転送前チェックが完了しました"
            textSize = 12f
            setTextColor(subColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(14))
        })

        // 判定サマリー
        val danger = findings.isNotEmpty()
        list.addView(card().apply {
            addView(TextView(this@ShareGateActivity).apply {
                text = if (danger) "⚠ ${findings.size} 件の注意事項があります"
                    else "✓ 機密情報は検出されませんでした"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (danger) redColor else greenColor)
            })
        })

        findings.forEach { f ->
            list.addView(card().apply {
                addView(TextView(this@ShareGateActivity).apply {
                    text = "【${f.type}】"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(yellowColor)
                })
                addView(TextView(this@ShareGateActivity).apply {
                    text = f.display
                    textSize = 13f
                    setTextColor(textColor)
                    setPadding(0, dp(4), 0, 0)
                })
            })
        }

        // SNS注意（検出時のみ）
        if (danger) {
            val installedSns = snsPackages.entries
                .filter { isInstalled(it.key) }
                .map { it.value }.distinct()
            if (installedSns.isNotEmpty()) {
                list.addView(card().apply {
                    addView(TextView(this@ShareGateActivity).apply {
                        text = "SNS（${installedSns.joinToString("・")}）への転送は特に注意してください。" +
                            "一度公開された情報は取り消せません。"
                        textSize = 13f
                        setTextColor(yellowColor)
                    })
                })
            }
        }

        // アクションボタン
        val isText = type.startsWith("text/")
        val hasNumberFinding = findings.any {
            it.type == "マイナンバー" || it.type == "クレジットカード"
        }
        if (danger && isText && hasNumberFinding) {
            list.addView(actionButton("マスクして転送（推奨）", greenColor) {
                forwardText(maskedText())
            })
        }
        list.addView(actionButton(
            if (danger) "そのまま転送（自己責任）" else "転送する",
            if (danger) yellowColor else greenColor
        ) {
            if (isText) forwardText(sharedText ?: "") else forwardImage()
        })
        list.addView(actionButton("中止", cardColor, textColor) { finish() })

        return ScrollView(this).apply {
            setBackgroundColor(bgColor)
            addView(list)
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) { false }

    private fun forwardText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        launchChooser(send)
    }

    private fun forwardImage() {
        val uri = sharedImage ?: run { finish(); return }
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = intent.type ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchChooser(send)
    }

    private fun launchChooser(send: Intent) {
        val chooser = Intent.createChooser(send, "転送先を選択")
        // 自分自身を除外して無限ループを防止
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(ComponentName(this, ShareGateActivity::class.java)))
        startActivity(chooser)
        finish()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(cardColor)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(8), 0, 0) }
    }

    private fun actionButton(label: String, bg: Int, fg: Int = Color.BLACK,
                             onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(fg)
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { setMargins(0, dp(10), 0, 0) }
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
