package com.appathy.mamoridx

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony

/**
 * 「怪しいリンクを踏んだ後」の侵害チェック。
 * root不要・フレームワークAPIのみで、乗っ取りの足がかりになる設定を検出する。
 */
object ThreatScanner {

    const val SEV_INFO = 0
    const val SEV_WARN = 1
    const val SEV_CRIT = 2

    /**
     * @param actionKind "app_detail" / "uninstall" / "settings"
     */
    data class Threat(
        val category: String,
        val title: String,
        val detail: String,
        val severity: Int,
        val actionLabel: String? = null,
        val actionKind: String? = null,
        val pkg: String? = null,
        val settingsAction: String? = null
    )

    fun scan(ctx: Context, withinHours: Int): List<Threat> {
        val out = mutableListOf<Threat>()
        val pm = ctx.packageManager
        val now = System.currentTimeMillis()
        val threshold = now - withinHours * 3600_000L

        val packages: List<PackageInfo> = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) { emptyList() }

        // ---------- 1. 直近にインストールされたアプリ ----------
        for (pkg in packages) {
            val ai = pkg.applicationInfo ?: continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) continue
            if (pkg.firstInstallTime < threshold) continue
            if (pkg.packageName == ctx.packageName) continue

            val hours = ((now - pkg.firstInstallTime) / 3600_000L).toInt()
            val installerPkg = try {
                if (android.os.Build.VERSION.SDK_INT >= 30)
                    pm.getInstallSourceInfo(pkg.packageName).installingPackageName
                else
                    @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg.packageName)
            } catch (e: Exception) { null }
            val fromStore = installerPkg == "com.android.vending"

            out.add(Threat(
                category = "最近入ったアプリ",
                title = ai.loadLabel(pm).toString(),
                detail = "${hours}時間前にインストール（入手元: " +
                    (if (fromStore) "Playストア" else "ストア外・不明") + "）\n" +
                    if (fromStore) "心当たりがあれば問題ありません。"
                    else "リンクを踏んだ直後に入った覚えがなければ、削除を強く推奨します。",
                severity = if (fromStore) SEV_WARN else SEV_CRIT,
                actionLabel = "アンインストール",
                actionKind = "uninstall",
                pkg = pkg.packageName
            ))
        }

        // ---------- 2. ユーザー補助（Accessibility）が有効なアプリ ----------
        val enabledA11y = try {
            Settings.Secure.getString(ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (e: Exception) { "" }
        val a11yPkgs = enabledA11y.split(':')
            .mapNotNull { it.substringBefore('/').takeIf { s -> s.isNotBlank() } }
            .distinct()
        for (p in a11yPkgs) {
            if (p == ctx.packageName) continue
            val label = labelOf(pm, p) ?: p
            val trusted = isSystemApp(pm, p)
            out.add(Threat(
                category = "ユーザー補助が有効",
                title = label,
                detail = "画面の読み取りと自動操作ができる強力な権限です。" +
                    "不正アプリはこれを悪用して、入力内容の盗み見や勝手な操作を行います。\n" +
                    if (trusted) "標準アプリのため通常は問題ありません。"
                    else "身に覚えがなければ、ただちにOFFにしてください。",
                severity = if (trusted) SEV_INFO else SEV_CRIT,
                actionLabel = "ユーザー補助の設定を開く",
                actionKind = "settings",
                settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS
            ))
        }

        // ---------- 3. 通知の読み取りが有効なアプリ ----------
        val enabledNl = try {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        } catch (e: Exception) { "" }
        val nlPkgs = enabledNl.split(':')
            .mapNotNull { it.substringBefore('/').takeIf { s -> s.isNotBlank() } }
            .distinct()
        for (p in nlPkgs) {
            if (p == ctx.packageName) continue
            val label = labelOf(pm, p) ?: p
            out.add(Threat(
                category = "通知の読み取りが有効",
                title = label,
                detail = "すべての通知を読み取れます。SMSで届く認証コード（ワンタイムパスワード）を" +
                    "盗まれる恐れがあります。心当たりがなければOFFにしてください。",
                severity = if (isSystemApp(pm, p)) SEV_INFO else SEV_CRIT,
                actionLabel = "通知アクセスの設定を開く",
                actionKind = "settings",
                settingsAction = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
            ))
        }

        // ---------- 4. 端末管理者（Device Admin） ----------
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admins = dpm.activeAdmins
            if (admins != null) {
                for (cn in admins) {
                    val p = cn.packageName
                    if (p == ctx.packageName) continue
                    val label = labelOf(pm, p) ?: p
                    out.add(Threat(
                        category = "端末管理者アプリ",
                        title = label,
                        detail = "端末のロックや初期化ができる最上位の権限です。" +
                            "不正アプリがこれを取得すると、アンインストールを妨害します。\n" +
                            "会社支給端末のMDM以外で身に覚えがない場合は、ただちに無効化してください。",
                        severity = SEV_CRIT,
                        actionLabel = "端末管理者の設定を開く",
                        actionKind = "settings",
                        settingsAction = Settings.ACTION_SECURITY_SETTINGS
                    ))
                }
            }
        } catch (e: Exception) { }

        // ---------- 5. 他アプリをインストールできる権限 ----------
        for (pkg in packages) {
            val ai = pkg.applicationInfo ?: continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) continue
            if (pkg.packageName == ctx.packageName) continue
            val reqs = pkg.requestedPermissions ?: continue
            val flags = pkg.requestedPermissionsFlags ?: continue
            for (i in reqs.indices) {
                if (reqs[i] != "android.permission.REQUEST_INSTALL_PACKAGES") continue
                val granted = (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                if (!granted) continue
                if (pkg.packageName == "com.android.vending") continue
                out.add(Threat(
                    category = "アプリを追加インストールできる",
                    title = ai.loadLabel(pm).toString(),
                    detail = "このアプリは他のアプリを勝手にインストールできます。" +
                        "ブラウザやファイル管理アプリ以外でこの権限が付いている場合は、" +
                        "不正アプリの侵入口になります。",
                    severity = SEV_WARN,
                    actionLabel = "このアプリの設定を開く",
                    actionKind = "app_detail",
                    pkg = pkg.packageName
                ))
            }
        }

        // ---------- 6. 既定のSMSアプリ／ブラウザの乗っ取り ----------
        try {
            val smsPkg = Telephony.Sms.getDefaultSmsPackage(ctx)
            if (smsPkg != null && !isSystemApp(pm, smsPkg)) {
                out.add(Threat(
                    category = "既定のSMSアプリ",
                    title = labelOf(pm, smsPkg) ?: smsPkg,
                    detail = "SMSの送受信を担当するアプリが標準以外になっています。" +
                        "認証コードの盗み見や、勝手なSMS送信（拡散）の恐れがあります。",
                    severity = SEV_CRIT,
                    actionLabel = "このアプリの設定を開く",
                    actionKind = "app_detail",
                    pkg = smsPkg
                ))
            }
        } catch (e: Exception) { }

        try {
            val browse = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
            val ri = pm.resolveActivity(browse, PackageManager.MATCH_DEFAULT_ONLY)
            val bpkg = ri?.activityInfo?.packageName
            if (bpkg != null && bpkg != "android" && !isSystemApp(pm, bpkg)) {
                val known = setOf(
                    "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx",
                    "com.opera.browser", "com.brave.browser", "com.sec.android.app.sbrowser",
                    "com.duckduckgo.mobile.android", "com.kiwibrowser.browser"
                )
                if (!known.contains(bpkg)) {
                    out.add(Threat(
                        category = "既定のブラウザ",
                        title = labelOf(pm, bpkg) ?: bpkg,
                        detail = "リンクを開くアプリが一般的なブラウザ以外に設定されています。" +
                            "リンクを横取りして偽サイトへ誘導される恐れがあります。",
                        severity = SEV_WARN,
                        actionLabel = "このアプリの設定を開く",
                        actionKind = "app_detail",
                        pkg = bpkg
                    ))
                }
            }
        } catch (e: Exception) { }

        return out.sortedByDescending { it.severity }
    }

    private fun labelOf(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: Exception) { null }

    private fun isSystemApp(pm: PackageManager, pkg: String): Boolean = try {
        (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (e: Exception) { false }
}
