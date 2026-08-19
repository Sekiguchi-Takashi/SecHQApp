package com.appathy.mamoridx

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * ユーザー補助（アクセシビリティ）権限の監査。
 *
 * ユーザー補助は「画面に表示されている内容をすべて読み取り」「代わりに操作する」
 * ことができる最も強力な権限で、不正アプリの主要な悪用先になっている。
 */
object AccessibilityAudit {

    const val RISK_LOW = 0
    const val RISK_MID = 1
    const val RISK_HIGH = 2
    const val RISK_CRITICAL = 3

    data class Entry(
        val packageName: String,
        val label: String,
        val serviceLabel: String,
        val enabled: Boolean,
        val isSystem: Boolean,
        val fromStore: Boolean,
        val installerName: String,
        val canReadScreen: Boolean,
        val canPerformGestures: Boolean,
        val canFilterKeys: Boolean,
        val canReadPasswords: Boolean,
        val watchesAllApps: Boolean,
        val capabilities: List<String>,
        val risk: Int,
        val reasons: List<String>,
        val description: String
    )

    data class Report(
        val enabledEntries: List<Entry>,
        val availableEntries: List<Entry>,
        val globalRisk: Int,
        val summary: String
    )

    /** 正規に利用されることが多い支援系アプリ（誤警告を減らすため） */
    private val knownAssistive = setOf(
        "com.google.android.marvin.talkback",
        "com.google.android.apps.accessibility.voiceaccess",
        "com.google.android.accessibility.switchaccess",
        "com.google.android.apps.accessibility.reveal",
        "com.samsung.accessibility",
        "com.lastpass.lpandroid",
        "com.agilebits.onepassword",
        "com.bitwarden.authenticator",
        "com.x8bit.bitwarden",
        "com.dashlane",
        "com.keepersecurity.parent",
        "com.microsoft.launcher"
    )

    fun run(ctx: Context): Report {
        val pm = ctx.packageManager
        val am = try {
            ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        } catch (e: Exception) { null }

        // 有効になっているサービスのパッケージ名
        val enabledRaw = try {
            Settings.Secure.getString(ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (e: Exception) { "" }
        val enabledPkgs = enabledRaw.split(':')
            .mapNotNull { it.substringBefore('/').takeIf { s -> s.isNotBlank() } }
            .toSet()

        val masterOn = try {
            Settings.Secure.getInt(ctx.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        } catch (e: Exception) { false }

        val installed: List<AccessibilityServiceInfo> = try {
            am?.getInstalledAccessibilityServiceList() ?: emptyList()
        } catch (e: Exception) { emptyList() }

        val enabledList = mutableListOf<Entry>()
        val availableList = mutableListOf<Entry>()

        for (info in installed) {
            val ri = info.resolveInfo ?: continue
            val si = ri.serviceInfo ?: continue
            val pkg = si.packageName ?: continue
            if (pkg == ctx.packageName) continue

            val isEnabled = masterOn && enabledPkgs.contains(pkg)
            val entry = buildEntry(ctx, pm, info, pkg, si.name ?: "", isEnabled)
            if (isEnabled) enabledList.add(entry) else availableList.add(entry)
        }

        // インストール一覧に出ないが有効になっているものを補完
        for (pkg in enabledPkgs) {
            if (pkg == ctx.packageName) continue
            if (enabledList.any { it.packageName == pkg }) continue
            val label = labelOf(pm, pkg) ?: pkg
            val isSystem = isSystemApp(pm, pkg)
            val (installer, fromStore) = installerOf(pm, pkg)
            val reasons = mutableListOf(
                "画面の読み取りと操作ができる状態です。",
                "このアプリの詳細情報を取得できませんでした。")
            val risk = if (isSystem) RISK_MID else RISK_CRITICAL
            if (!isSystem) reasons.add("提供元を確認できないため、特に注意が必要です。")
            enabledList.add(Entry(
                pkg, label, "", true, isSystem, fromStore, installer,
                canReadScreen = true, canPerformGestures = true,
                canFilterKeys = false, canReadPasswords = false,
                watchesAllApps = true,
                capabilities = listOf("画面の読み取り", "画面の操作"),
                risk = risk, reasons = reasons,
                description = ""))
        }

        enabledList.sortByDescending { it.risk }
        availableList.sortByDescending { it.risk }

        val globalRisk = enabledList.maxOfOrNull { it.risk } ?: RISK_LOW
        val critical = enabledList.count { it.risk >= RISK_HIGH }

        val summary = when {
            !masterOn || enabledList.isEmpty() ->
                "ユーザー補助を使用しているアプリはありません。最も安全な状態です。\n\n" +
                "この権限は、画面の内容をすべて読み取り、代わりに操作できる非常に強力なものです。" +
                "不正アプリの主要な悪用先でもあるため、必要なとき以外はOFFのままにしてください。"
            critical > 0 ->
                "${critical} 件のアプリが、画面の読み取りと操作ができる状態です。" +
                "身に覚えのないアプリや、支援機能・パスワード管理以外の用途のアプリが" +
                "含まれていないか確認してください。心当たりがなければ、ただちにOFFにしてください。"
            else ->
                "${enabledList.size} 件のアプリが有効になっています。" +
                "いずれも一般に利用される支援系アプリと判定しましたが、" +
                "使っていないものはOFFにしておくとより安全です。"
        }

        return Report(enabledList, availableList, globalRisk, summary)
    }

    private fun buildEntry(
        ctx: Context, pm: PackageManager,
        info: AccessibilityServiceInfo, pkg: String,
        serviceName: String, isEnabled: Boolean
    ): Entry {
        val label = labelOf(pm, pkg) ?: pkg
        val serviceLabel = try {
            info.resolveInfo.loadLabel(pm)?.toString() ?: ""
        } catch (e: Exception) { "" }
        val isSystem = isSystemApp(pm, pkg)
        val (installerName, fromStore) = installerOf(pm, pkg)

        val caps = try { info.capabilities } catch (e: Exception) { 0 }
        val canRead = (caps and
            AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
        val canGesture = if (Build.VERSION.SDK_INT >= 24)
            (caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        else false
        val canFilterKeys = (caps and
            AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) != 0

        val flags = try { info.flags } catch (e: Exception) { 0 }
        val canReadPasswords = (flags and
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0
        val watchesAll = try {
            info.packageNames == null || info.packageNames.isEmpty()
        } catch (e: Exception) { true }

        val capList = mutableListOf<String>()
        if (canRead) capList.add("画面に表示されている内容の読み取り")
        if (canGesture) capList.add("タップやスワイプの代行（勝手な操作）")
        if (canFilterKeys) capList.add("キー入力の監視")
        if (watchesAll) capList.add("すべてのアプリを対象に動作")
        else capList.add("特定のアプリのみを対象に動作")
        if (capList.isEmpty()) capList.add("限定的な機能のみ")

        val desc = try {
            info.loadDescription(pm)?.toString() ?: ""
        } catch (e: Exception) { "" }

        // ---- リスク判定 ----
        val reasons = mutableListOf<String>()
        var score = 0

        if (canRead) {
            score += 2
            reasons.add("画面の内容をすべて読み取れます。" +
                "入力中のIDやパスワード、メールの中身も見える状態です。")
        }
        if (canGesture) {
            score += 2
            reasons.add("利用者の代わりに画面を操作できます。" +
                "送金操作や設定変更を勝手に行うことが技術的に可能です。")
        }
        if (canFilterKeys) {
            score += 1
            reasons.add("キー入力を監視できます。")
        }
        if (watchesAll) {
            score += 1
            reasons.add("対象アプリの限定がなく、銀行アプリを含むすべての画面で動作します。")
        }
        if (!isSystem && !fromStore) {
            score += 3
            reasons.add("ストア以外から導入されたアプリです。" +
                "この組み合わせは不正アプリの典型的な特徴です。")
        }
        if (isSystem) {
            score -= 2
            reasons.add("端末に最初から入っているアプリです。")
        }
        if (knownAssistive.contains(pkg)) {
            score -= 2
            reasons.add("一般に利用される支援機能またはパスワード管理アプリです。")
        }

        val risk = when {
            !isEnabled -> RISK_LOW
            score >= 6 -> RISK_CRITICAL
            score >= 4 -> RISK_HIGH
            score >= 2 -> RISK_MID
            else -> RISK_LOW
        }

        if (!isEnabled) {
            reasons.add(0, "現在は有効になっていません（この機能を使える状態にあるだけです）。")
        }

        return Entry(
            pkg, label, serviceLabel, isEnabled, isSystem, fromStore, installerName,
            canRead, canGesture, canFilterKeys, canReadPasswords, watchesAll,
            capList, risk, reasons, desc)
    }

    fun riskLabel(r: Int): String = when (r) {
        RISK_CRITICAL -> "危険"
        RISK_HIGH -> "高"
        RISK_MID -> "中"
        else -> "低"
    }

    private fun labelOf(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: Exception) { null }

    private fun isSystemApp(pm: PackageManager, pkg: String): Boolean = try {
        (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (e: Exception) { false }

    private fun installerOf(pm: PackageManager, pkg: String): Pair<String, Boolean> = try {
        val ip = if (Build.VERSION.SDK_INT >= 30)
            pm.getInstallSourceInfo(pkg).installingPackageName
        else
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        when (ip) {
            "com.android.vending" -> "Play ストア" to true
            null -> "不明（手動導入）" to false
            else -> ip to false
        }
    } catch (e: Exception) { "不明" to false }
}
