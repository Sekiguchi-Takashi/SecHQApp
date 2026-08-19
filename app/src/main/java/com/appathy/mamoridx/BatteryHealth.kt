package com.appathy.mamoridx

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/**
 * バッテリーの劣化度合いを推定する。
 * Androidには「設計容量」を返す公開APIが無いため、
 * 隠しリソース(config_batteryCapacity)＋BatteryManagerの充電カウンタから推定する。
 * 取得できない端末が一定数あるため、必ず取得可否を返す。
 */
object BatteryHealth {

    data class Info(
        val available: Boolean,
        val healthPercent: Int,        // 健康度(%) available=trueのときのみ有効
        val wearPercent: Int,          // 劣化度(%)
        val designCapacityMah: Int,    // 設計容量
        val currentCapacityMah: Int,   // 推定の現在容量
        val level: Int,                // 残量%
        val statusText: String,        // 充電状態
        val healthText: String,        // OS報告のバッテリー状態
        val temperatureC: Double,
        val voltageV: Double,
        val technology: String,
        val cycleCount: Int,           // -1なら不明
        val note: String,
        val advice: String
    )

    fun read(ctx: Context): Info {
        val bm = try {
            ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        } catch (e: Exception) { null }

        val intent: Intent? = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) { null }

        val level = try {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) { -1 }

        val statusText = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充電中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放電中"
            BatteryManager.BATTERY_STATUS_FULL -> "満充電"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "充電していません"
            else -> "不明"
        }

        val healthRaw = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthText = when (healthRaw) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "高温"
            BatteryManager.BATTERY_HEALTH_DEAD -> "寿命"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "過電圧"
            BatteryManager.BATTERY_HEALTH_COLD -> "低温"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "異常"
            else -> "不明"
        }

        val tempC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
            .let { if (it > 0) it / 10.0 else -1.0 }
        val voltV = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1)
            .let { if (it > 0) it / 1000.0 else -1.0 }
        val tech = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "不明"

        val cycle = if (Build.VERSION.SDK_INT >= 34) {
            try { intent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1 }
            catch (e: Exception) { -1 }
        } else -1

        // ---- 設計容量（隠しリソース） ----
        val design = readDesignCapacity(ctx)

        // ---- 現在の満充電容量の推定 ----
        // CHARGE_COUNTER(µAh)は現在の残量。残量%で割り戻して満充電時容量を推定する。
        val counterUah = try {
            bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: -1L
        } catch (e: Exception) { -1L }

        var currentFull = -1
        if (counterUah > 0 && level in 1..100) {
            currentFull = ((counterUah / 1000.0) / (level / 100.0)).toInt()
        }

        if (design <= 0 || currentFull <= 0) {
            val why = when {
                design <= 0 && currentFull <= 0 ->
                    "この端末では設計容量と現在容量のどちらも取得できませんでした。"
                design <= 0 -> "この端末では設計容量を取得できませんでした。"
                else -> "この端末では現在の容量を取得できませんでした。"
            }
            return Info(
                available = false,
                healthPercent = 0, wearPercent = 0,
                designCapacityMah = design.coerceAtLeast(0),
                currentCapacityMah = currentFull.coerceAtLeast(0),
                level = level, statusText = statusText, healthText = healthText,
                temperatureC = tempC, voltageV = voltV, technology = tech,
                cycleCount = cycle,
                note = why + "\n\nAndroidには劣化度を返す公開APIが無く、" +
                    "機種によっては算出に必要な値が公開されていません。" +
                    "下のOS報告値（バッテリー状態・温度・電圧）と、" +
                    "端末の設定→バッテリー→バッテリー情報をご確認ください。",
                advice = adviceFor(-1, tempC, healthRaw)
            )
        }

        var health = (currentFull * 100.0 / design).toInt()
        // 推定値なので100%超は丸める
        if (health > 100) health = 100
        if (health < 0) health = 0
        val wear = 100 - health

        return Info(
            available = true,
            healthPercent = health, wearPercent = wear,
            designCapacityMah = design, currentCapacityMah = currentFull,
            level = level, statusText = statusText, healthText = healthText,
            temperatureC = tempC, voltageV = voltV, technology = tech,
            cycleCount = cycle,
            note = "設計容量 ${design} mAh に対し、現在の満充電容量は約 ${currentFull} mAh と" +
                "推定されます。\n\n" +
                "この値は残量と充電カウンタからの推定です。" +
                "残量が極端に少ない時や充電直後は誤差が大きくなるため、" +
                "残量50〜80%程度・充電を外した状態で測ると安定します。",
            advice = adviceFor(health, tempC, healthRaw)
        )
    }

    private fun adviceFor(health: Int, tempC: Double, healthRaw: Int): String {
        val sb = StringBuilder()
        when {
            health < 0 -> sb.append("劣化度は算出できませんでしたが、" +
                "充電の減りが以前より明らかに早い、発熱する、膨らんでいるといった症状があれば" +
                "交換を検討してください。")
            health >= 90 -> sb.append("良好な状態です。特に対応は不要です。")
            health >= 80 -> sb.append("軽度の劣化です。通常の使用範囲内で、まだ交換は不要です。")
            health >= 70 -> sb.append("劣化が進んでいます。外出時に電池切れが起きやすくなるため、" +
                "モバイルバッテリーの携行か、交換の検討をおすすめします。")
            else -> sb.append("劣化がかなり進んでいます。急な電源断で作業中のデータを失う恐れがあるほか、" +
                "業務端末としての信頼性が下がります。交換を検討してください。")
        }
        if (healthRaw == BatteryManager.BATTERY_HEALTH_OVERHEAT ||
            (tempC in 45.0..200.0)) {
            sb.append("\n\n現在バッテリーが高温です。充電しながらの高負荷利用を避け、" +
                "ケースを外して冷ましてください。高温は劣化を大きく加速させます。")
        }
        if (healthRaw == BatteryManager.BATTERY_HEALTH_DEAD ||
            healthRaw == BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE) {
            sb.append("\n\nOSがバッテリーの異常を報告しています。早めに点検を受けてください。")
        }
        sb.append("\n\n長持ちさせるコツ: 20〜80%の範囲で使う、高温環境を避ける、" +
            "充電しながらの重い作業を控える。")
        return sb.toString()
    }

    /** 端末の隠しリソースから設計容量(mAh)を読む */
    private fun readDesignCapacity(ctx: Context): Int {
        // 方法1: frameworkの config_batteryCapacity
        try {
            val res = ctx.resources
            val id = res.getIdentifier("config_batteryCapacity", "integer", "android")
            if (id != 0) {
                val v = res.getInteger(id)
                if (v > 0) return v
            }
        } catch (e: Exception) { }

        // 方法2: com.android.internal.os.PowerProfile
        try {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val ctor = cls.getConstructor(Context::class.java)
            val obj = ctor.newInstance(ctx)
            val m = cls.getMethod("getBatteryCapacity")
            val v = m.invoke(obj) as? Double ?: 0.0
            if (v > 0) return v.toInt()
        } catch (e: Exception) { }

        return -1
    }
}
