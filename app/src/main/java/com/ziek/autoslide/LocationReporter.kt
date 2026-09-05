package com.ziek.autoslide

/**
 * 设备地理位置采集。
 *
 * 走系统 [LocationManager]，不引入 Google Play 服务的 FusedLocationProviderClient——
 * 本项目的目标机型里华为/荣耀无 GMS、其余机型也常连不上 Google（见 [com.ziek.autoslide.ec.EcNetworkWatcher]
 * 的说明），依赖 play-services-location 会在这些设备上直接不可用。系统 LocationManager
 * 是所有 Android 设备都有的最小公分母。
 *
 * 采集策略分两步，为的是「尽量精确」而不是「拿到就行」：
 *   1. **主动求一个新定位**：向 GPS / NETWORK（API 31+ 再加 FUSED）三个 Provider 同时
 *      请求实时更新，在 [FRESH_FIX_TIMEOUT_MS] 内收集回调，保留精度最好的一条；
 *      一旦精度达到 [GOOD_ENOUGH_ACCURACY_M] 就提前结束，不必等满超时。
 *      GPS 冷启动户外通常几秒内到 5~15m，室内/超时才退化。
 *   2. **兜底读缓存**：主动定位超时没结果时，退回各 Provider 的「最后已知位置」
 *      （[LocationManager.getLastKnownLocation]）。两者里取精度更好的一条上报。
 *
 * 代价是每次采集会唤醒一次 GPS、最长阻塞 [FRESH_FIX_TIMEOUT_MS]，比只读缓存耗电；
 * 但上报本就低频（安装/更新、后台点刷新时各一次），换来的是米级而非百米级坐标。
 *
 * 只上报经纬度坐标本身（外加精度/来源/时间），不做归属地反查——后台按坐标展示即可。
 *
 * 没有定位权限、定位总开关关闭、或既无实时定位也无缓存位置时，一律静默返回 null，
 * 绝不抛异常、绝不影响统计上报本身。
 */

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object LocationReporter {

    private const val TAG = "LocationReporter"

    /* 主动等一个新定位的最长时间：GPS 冷启动可能要十几秒，给足但不至于让上报卡太久 */
    private const val FRESH_FIX_TIMEOUT_MS = 12_000L

    /* 精度达到该阈值（米）即认为够好，提前结束等待，不必耗到超时 */
    private const val GOOD_ENOUGH_ACCURACY_M = 30f

    /**
     * 是否已经拿到精确或粗略定位权限中的任意一个。
     *
     * 只要有其一就能读到位置（粗略权限也能拿到 NETWORK Provider 的结果），
     * 因此二者是「或」的关系。注意：只有 [Manifest.permission.ACCESS_FINE_LOCATION]
     * 才能拿到 GPS 的米级精度；只授了粗略权限时，系统会把结果降采样到街区级。
     */
    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * 同步采集一次设备位置，必须在后台线程调用（会阻塞等待实时定位，最长 [FRESH_FIX_TIMEOUT_MS]）。
     *
     * @param context 上下文
     * @return 形如 `{"lat":..,"lon":..,"accuracy":..,"provider":"..","time":..}`；
     *         无权限、定位关闭或彻底取不到位置时返回 null
     */
    fun collect(context: Context): JSONObject? = runCatching {
        if (!hasPermission(context)) {
            LogX.d(TAG, "no location permission, skip")
            return null
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        // 先主动求一个新定位，再和缓存里最好的一条比精度，取更准的那条
        val fresh = awaitFreshFix(lm)
        val cached = bestLastKnown(lm)
        val best = pickMoreAccurate(fresh, cached) ?: run {
            LogX.d(TAG, "no location available (fresh & cached both null)")
            return null
        }

        val json = JSONObject()
            .put("lat", best.latitude)
            .put("lon", best.longitude)
            .put("accuracy", if (best.hasAccuracy()) best.accuracy.toDouble() else JSONObject.NULL)
            .put("provider", best.provider.orEmpty())
            .put("time", best.time)

        LogX.i(TAG, "location collected: ${best.latitude},${best.longitude} " +
            "±${if (best.hasAccuracy()) best.accuracy.toInt() else -1}m (${best.provider})")
        json
    }.onFailure {
        LogX.w(TAG, "collect location failed", it)
    }.getOrNull()

    /**
     * 主动向可用 Provider 请求实时定位，收集回调取精度最好的一条。
     *
     * 精度一旦达到 [GOOD_ENOUGH_ACCURACY_M] 立即返回；否则收满 [FRESH_FIX_TIMEOUT_MS]
     * 内到达的所有回调后返回其中最好的一条，全程无回调则返回 null。
     *
     * 回调走主线程 Looper，本方法在后台线程用 [CountDownLatch] 阻塞等待，二者不同线程、
     * 不会自锁。无论是否拿到结果，退出前一定 removeUpdates，绝不留下常驻监听。
     */
    @SuppressLint("MissingPermission")
    private fun awaitFreshFix(lm: LocationManager): Location? {
        val providers = candidateProviders().filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) {
            LogX.d(TAG, "no enabled provider for fresh fix")
            return null
        }

        val holder = AtomicReference<Location?>(null)
        val latch = CountDownLatch(1)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val prev = holder.get()
                if (prev == null || isMoreAccurate(location, prev)) holder.set(location)
                // 够准了就别再等，提前放行
                if (location.hasAccuracy() && location.accuracy <= GOOD_ENOUGH_ACCURACY_M) {
                    latch.countDown()
                }
            }

            // 下面三个在 API 30 起是接口默认方法，但 minSdk 26 的旧机上必须有实体实现，
            // 否则运行时 AbstractMethodError，因此显式留空覆盖。
            //
            // onStatusChanged 自 API 29 起已废弃（系统不再回调它），但**不能删**：
            // 29 以下的机器仍会调用，缺了实现照样 AbstractMethodError。
            // 标上 @Deprecated 只是把父接口的废弃状态如实继承下来，消掉编译器的
            // OVERRIDE_DEPRECATION 警告；这里本来也没人会去调它。
            @Deprecated("API 29 起系统不再回调，保留仅为兼容 minSdk 26 的旧机")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        // 监听回调需要一个 Looper；后台 IO 线程没有，借用主线程的（回调很轻，仅存一个对象）
        val looper = Looper.getMainLooper()
        val registered = mutableListOf<Boolean>()
        try {
            for (provider in providers) {
                runCatching {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, looper)
                    registered.add(true)
                }.onFailure { LogX.d(TAG, "requestLocationUpdates($provider) failed: ${it.message}") }
            }
            if (registered.isEmpty()) return null
            runCatching { latch.await(FRESH_FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        } finally {
            runCatching { lm.removeUpdates(listener) }
        }
        return holder.get()
    }

    /* 主动定位请求的 Provider 列表：GPS 出精度、NETWORK 兜速度，API 31+ 再加融合定位 */
    private fun candidateProviders(): List<String> {
        val list = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) list.add(LocationManager.FUSED_PROVIDER)
        return list.distinct()
    }

    /* 遍历所有 Provider 的最后已知位置，取精度最好的一条（兜底用） */
    @SuppressLint("MissingPermission")
    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            providers.add(LocationManager.FUSED_PROVIDER)
        }

        var best: Location? = null
        for (provider in providers.distinct()) {
            // 逐个 Provider 兜异常：部分 ROM 上对未启用的 Provider 取值会抛
            val loc = runCatching {
                if (!lm.isProviderEnabled(provider)) return@runCatching null
                lm.getLastKnownLocation(provider)
            }.getOrNull() ?: continue
            best = pickMoreAccurate(loc, best)
        }
        return best
    }

    /* 两条里取更值得上报的一条：都为空回空；一方为空取另一方；否则取精度更好的 */
    private fun pickMoreAccurate(a: Location?, b: Location?): Location? {
        if (a == null) return b
        if (b == null) return a
        return if (isMoreAccurate(a, b)) a else b
    }

    /**
     * 判断 [cand] 是否比 [cur] 更值得采用。
     *
     * 精度（accuracy，越小越准）优先；两者都无精度信息时，取时间更新的一条。
     */
    private fun isMoreAccurate(cand: Location, cur: Location): Boolean {
        if (!cand.hasAccuracy()) return false
        if (!cur.hasAccuracy()) return true
        if (cand.accuracy != cur.accuracy) return cand.accuracy < cur.accuracy
        return cand.time > cur.time
    }
}
