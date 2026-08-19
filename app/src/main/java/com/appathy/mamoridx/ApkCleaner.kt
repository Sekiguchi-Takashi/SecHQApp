package com.appathy.mamoridx

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/**
 * 端末内に残ったAPKファイルの検出と一括削除、
 * および「提供元不明アプリのインストール許可」の棚卸し。
 *
 * 【制約】
 * ・ファイル削除はSAFで利用者がフォルダを選んだ範囲でのみ可能
 * ・インストール許可の取り消しはアプリからは実行できず、設定画面へ誘導する方式
 */
object ApkCleaner {

    data class ApkFile(
        val docId: String,
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val lastModified: Long
    )

    data class ScanResult(
        val files: List<ApkFile>,
        val scannedFiles: Int,
        val truncated: Boolean
    )

    data class Installer(
        val packageName: String,
        val label: String,
        val granted: Boolean,
        val isSystem: Boolean,
        val risk: Int,     // 0=低 1=中 2=高
        val reason: String
    )

    /** 通常この権限を持っていて不自然でないアプリ */
    private val expectedInstallers = setOf(
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    private val browserLike = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx",
        "com.opera.browser", "com.brave.browser", "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android", "com.kiwibrowser.browser",
        "com.android.documentsui", "com.google.android.apps.nbu.files",
        "com.termux"
    )

    // =========================================================
    // APKファイルの検索
    // =========================================================
    fun scan(ctx: Context, treeUri: Uri, maxFiles: Int = 6000): ScanResult {
        val found = mutableListOf<ApkFile>()
        var scanned = 0
        var truncated = false

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val queue = ArrayDeque<Pair<String, String>>()
        try {
            queue.add(DocumentsContract.getTreeDocumentId(treeUri) to "")
        } catch (e: Exception) {
            return ScanResult(emptyList(), 0, false)
        }

        var guard = 0
        loop@ while (queue.isNotEmpty() && guard < 10000) {
            guard++
            val (docId, parent) = queue.removeFirst()
            val childUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            } catch (e: Exception) { continue }
            val cursor = try {
                ctx.contentResolver.query(childUri, projection, null, null, null)
            } catch (e: Exception) { null } ?: continue

            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = try { c.getLong(3) } catch (e: Exception) { 0L }
                    val mod = try { c.getLong(4) } catch (e: Exception) { 0L }
                    val path = if (parent.isEmpty()) name else "$parent/$name"

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue.add(id to path)
                        continue
                    }
                    scanned++
                    if (scanned > maxFiles) { truncated = true; return@use }

                    val lower = name.lowercase()
                    if (lower.endsWith(".apk") || lower.endsWith(".apks") ||
                        lower.endsWith(".xapk") || lower.endsWith(".apkm") ||
                        mime == "application/vnd.android.package-archive") {
                        found.add(ApkFile(id, name, path, size, mod))
                    }
                }
            }
            if (scanned > maxFiles) break@loop
        }
        if (queue.isNotEmpty()) truncated = true

        return ScanResult(found.sortedByDescending { it.sizeBytes }, scanned, truncated)
    }

    /** 指定したAPKを削除する。戻り値は(成功数, 失敗数) */
    fun deleteAll(ctx: Context, treeUri: Uri, files: List<ApkFile>): Pair<Int, Int> {
        var ok = 0
        var ng = 0
        for (f in files) {
            try {
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, f.docId)
                val result = DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                if (result) ok++ else ng++
            } catch (e: Exception) {
                ng++
            }
        }
        return ok to ng
    }

    fun totalSize(files: List<ApkFile>): Long = files.sumOf { it.sizeBytes }

    // =========================================================
    // 提供元不明アプリのインストール許可
    // =========================================================
    fun installers(ctx: Context): List<Installer> {
        val pm = ctx.packageManager
        val out = mutableListOf<Installer>()

        val packages: List<PackageInfo> = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) { return emptyList() }

        for (pkg in packages) {
            val ai = pkg.applicationInfo ?: continue
            val reqs = pkg.requestedPermissions ?: continue
            if (!reqs.contains("android.permission.REQUEST_INSTALL_PACKAGES")) continue
            if (pkg.packageName == ctx.packageName) continue
            if (expectedInstallers.contains(pkg.packageName)) continue

            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            // 実際に許可されているか（API26+）
            val granted = if (Build.VERSION.SDK_INT >= 26) {
                try { pm.canRequestPackageInstalls() && false } catch (e: Exception) { false }
                // 自アプリ以外の状態はAPIで取得できないため、権限宣言の有無で代替判定する
                declaredGranted(pkg)
            } else declaredGranted(pkg)

            val risk: Int
            val reason: String
            when {
                !granted -> {
                    risk = 0
                    reason = "この機能を要求していますが、現在は有効になっていません。"
                }
                isSystem -> {
                    risk = 1
                    reason = "端末に最初から入っているアプリです。通常は問題ありませんが、" +
                        "使っていないなら無効にしておくと安全です。"
                }
                browserLike.contains(pkg.packageName) -> {
                    risk = 1
                    reason = "ブラウザやファイル管理アプリは、この機能を使うことがあります。" +
                        "アプリを自分で入れる用途がないなら、無効にしてください。"
                }
                else -> {
                    risk = 2
                    reason = "このアプリが他のアプリを勝手に導入できる状態です。" +
                        "不正アプリの侵入口になるため、必要がなければ無効にしてください。"
                }
            }

            out.add(Installer(
                pkg.packageName,
                ai.loadLabel(pm).toString(),
                granted, isSystem, risk, reason))
        }
        return out.sortedByDescending { it.risk }
    }

    private fun declaredGranted(pkg: PackageInfo): Boolean {
        val reqs = pkg.requestedPermissions ?: return false
        val flags = pkg.requestedPermissionsFlags ?: return false
        for (i in reqs.indices) {
            if (reqs[i] == "android.permission.REQUEST_INSTALL_PACKAGES") {
                return (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            }
        }
        return false
    }

    fun formatBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
