package dev.luma.monitor

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** An instance root, not a arbitrary browser URL. All native requests stay on this origin. */
@ConsistentCopyVisibility
data class Endpoint private constructor(val baseUrl: String, val origin: String) {
    fun api(path: String): String = "$baseUrl/${path.trimStart('/')}"
    fun permits(url: String): Boolean = runCatching {
        val uri = URI(url)
        val root = URI(origin)
        uri.userInfo == null && uri.scheme.equals(root.scheme, true) &&
            uri.host.equals(root.host, true) && effectivePort(uri) == effectivePort(root)
    }.getOrDefault(false)

    companion object {
        fun parse(input: String): Endpoint {
            val uri = URI(input.trim())
            require(uri.scheme.equals("https", true)) { "请填写 HTTPS 地址" }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "地址必须包含有效域名，不能包含账号密码" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "请填写服务根地址，不要包含查询参数或页面锚点" }
            require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
            val host = uri.host.lowercase().let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
            val authority = host + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            val origin = "https://$authority"
            val path = uri.normalize().rawPath.orEmpty().trimEnd('/')
            return Endpoint(origin + path, origin)
        }

        private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port
    }
}

data class ServerInstance(val id: String, val name: String, val endpoint: Endpoint) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("endpoint", endpoint.baseUrl)
    override fun toString() = name
    companion object {
        fun fromJson(json: JSONObject) = ServerInstance(json.getString("id"), json.getString("name"),
            Endpoint.parse(json.getString("endpoint")))
    }
}

data class NodeChoice(val uuid: String, val name: String) {
    override fun toString() = name
}

data class TaskChoice(val id: Int, val name: String) {
    override fun toString() = name
}

data class CardConfig(
    val nodeId: String,
    val nodeName: String,
    val taskId: Int,
    val taskName: String,
    val interval: Int = 30,
    val hours: Int = 1,
    val demo: Boolean = false,
    val instanceId: String? = null,
    val allTasks: Boolean = true,
    val taskIds: List<Int> = emptyList(),
) {
    fun toJson() = JSONObject().put("nodeId", nodeId).put("nodeName", nodeName)
        .put("taskId", taskId).put("taskName", taskName).put("interval", interval)
        .put("hours", hours).put("demo", demo).put("instanceId", instanceId).put("allTasks", allTasks)
        .put("taskIds", JSONArray(taskIds))

    fun selectedTasks(available: List<TaskChoice>): List<TaskChoice> = when {
        taskIds.isNotEmpty() -> taskIds.take(3).mapNotNull { id -> available.firstOrNull { it.id == id } }
        !allTasks -> available.filter { it.id == taskId }.take(1)
        else -> available.take(3)
    }

    companion object {
        fun fromJson(json: JSONObject) = CardConfig(json.getString("nodeId"), json.getString("nodeName"),
            json.getInt("taskId"), json.getString("taskName"), json.optInt("interval", 30).coerceAtLeast(15),
            1, json.optBoolean("demo"),
            json.optString("instanceId").ifBlank { null }, json.optBoolean("allTasks", true),
            json.optJSONArray("taskIds")?.let { ids -> (0 until ids.length()).map { ids.getInt(it) }.distinct().take(3) }.orEmpty())
    }
}

data class CardSnapshot(
    val online: Boolean? = null,
    val latency: Double? = null,
    val loss: Double? = null,
    val samples: Long? = null,
    val cpu: Double? = null,
    val memory: Double? = null,
    val updatedAt: Long = 0,
    val error: String? = null,
    val averageLatency: Double? = null,
    val minLatency: Double? = null,
    val maxLatency: Double? = null,
    val disk: Double? = null,
    val download: Double? = null,
    val upload: Double? = null,
    val uptime: Long? = null,
    val latencyTimedOut: Boolean = false,
    val load: Double? = null,
    val totalDownload: Double? = null,
    val totalUpload: Double? = null,
    val pingTasks: List<PingTaskSnapshot> = emptyList(),
    val trafficLimit: Double? = null,
    val trafficUsed: Double? = null,
) {
    fun toJson() = JSONObject().put("online", online).put("latency", latency).put("loss", loss)
        .put("samples", samples).put("cpu", cpu).put("memory", memory).put("updatedAt", updatedAt).put("error", error)
        .put("averageLatency", averageLatency).put("minLatency", minLatency).put("maxLatency", maxLatency)
        .put("disk", disk).put("download", download).put("upload", upload).put("uptime", uptime)
        .put("latencyTimedOut", latencyTimedOut)
        .put("load", load).put("totalDownload", totalDownload).put("totalUpload", totalUpload)
        .put("pingTasks", JSONArray().apply { pingTasks.forEach { put(it.toJson()) } })
        .put("trafficLimit", trafficLimit).put("trafficUsed", trafficUsed)

    companion object {
        fun fromJson(json: JSONObject) = CardSnapshot(
            online = if (json.has("online") && !json.isNull("online")) json.getBoolean("online") else null,
            latency = json.numberOrNull("latency"), loss = json.numberOrNull("loss"),
            samples = json.numberOrNull("samples")?.toLong(), cpu = json.numberOrNull("cpu"),
            memory = json.numberOrNull("memory"), updatedAt = json.optLong("updatedAt"),
            error = if (json.isNull("error")) null else json.optString("error").ifBlank { null },
            averageLatency = json.numberOrNull("averageLatency"), minLatency = json.numberOrNull("minLatency"),
            maxLatency = json.numberOrNull("maxLatency"), disk = json.numberOrNull("disk"),
            download = json.numberOrNull("download"), upload = json.numberOrNull("upload"),
            uptime = json.numberOrNull("uptime")?.toLong(), latencyTimedOut = json.optBoolean("latencyTimedOut"),
            load = json.numberOrNull("load"), totalDownload = json.numberOrNull("totalDownload"),
            totalUpload = json.numberOrNull("totalUpload"),
            pingTasks = json.optJSONArray("pingTasks")?.let { tasks ->
                (0 until tasks.length()).map { PingTaskSnapshot.fromJson(tasks.getJSONObject(it)) }
            }.orEmpty(),
            trafficLimit = json.numberOrNull("trafficLimit"), trafficUsed = json.numberOrNull("trafficUsed"),
        )
        fun demo() = CardSnapshot(true, 28.4, 0.7, 120, 12.0, 38.0, System.currentTimeMillis(),
            averageLatency = 30.2, minLatency = 24.1, maxLatency = 68.5, disk = 45.0,
            download = 2_400_000.0, upload = 580_000.0, uptime = 864_000, load = 0.05,
            totalDownload = 27_700_000_000.0, totalUpload = 27_100_000_000.0,
            trafficLimit = 1000.0 * 1024 * 1024 * 1024, trafficUsed = 54.9 * 1024 * 1024 * 1024,
            pingTasks = listOf(
                PingTaskSnapshot(1, "电信", null, 100.0, 120, true),
                PingTaskSnapshot(2, "联通", 158.0, 0.0, 120),
                PingTaskSnapshot(3, "移动", 159.0, 0.0, 120),
            ))
    }
}

data class TrafficQuota(val limit: Double?, val used: Double?) {
    val unlimited: Boolean get() = limit == 0.0
    val remaining: Double? get() = if (limit != null && limit > 0 && used != null) (limit - used).coerceAtLeast(0.0) else null
    val percent: Double? get() = if (limit != null && limit > 0 && used != null) used / limit * 100 else null

    companion object {
        fun calculate(limit: Double?, type: String, up: Double?, down: Double?): TrafficQuota {
            val validUp = up?.takeIf { it.isFinite() && it >= 0 }
            val validDown = down?.takeIf { it.isFinite() && it >= 0 }
            val used = when (type.trim().lowercase()) {
                "up" -> validUp
                "down" -> validDown
                else -> if (validUp != null && validDown != null) when (type.trim().lowercase()) {
                    "sum" -> validUp + validDown
                    "min" -> minOf(validUp, validDown)
                    else -> maxOf(validUp, validDown)
                } else null
            }
            return TrafficQuota(limit?.takeIf { it.isFinite() && it >= 0 }, used)
        }
    }
}

internal fun JSONObject.numberOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { it.isFinite() }

data class PingSummary(
    val latency: Double?, val loss: Double?, val samples: Long?,
    val average: Double? = null, val minimum: Double? = null, val maximum: Double? = null,
    val latestKnown: Boolean = false, val timedOut: Boolean = false,
)

data class PingTaskSnapshot(val id: Int, val name: String, val latency: Double?, val loss: Double?,
    val samples: Long?, val timedOut: Boolean = false) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("latency", latency)
        .put("loss", loss).put("samples", samples).put("timedOut", timedOut)
    companion object {
        fun fromJson(json: JSONObject) = PingTaskSnapshot(json.getInt("id"), json.getString("name"),
            json.numberOrNull("latency"), json.numberOrNull("loss"), json.numberOrNull("samples")?.toLong(),
            json.optBoolean("timedOut"))
    }
}

object DisplayValues {
    fun percent(value: Double?): String = value?.takeIf { it.isFinite() && it in 0.0..100.0 }
        ?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "—"

    fun bytes(value: Double?): String {
        if (value == null || !value.isFinite() || value < 0) return "—"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var amount = value
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
        return String.format(java.util.Locale.US, if (unit == 0) "%.0f %s" else "%.1f %s", amount, units[unit])
    }

    fun speed(value: Double?): String = if (value == null) "—" else bytes(value).let { if (it == "—") it else "$it/s" }
    fun uptime(seconds: Long?): String = when {
        seconds == null || seconds < 0 -> "—"
        seconds >= 86400 -> "${seconds / 86400} 天 ${seconds % 86400 / 3600} 小时"
        seconds >= 3600 -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
        else -> "${seconds / 60} 分钟"
    }
    fun latency(ms: Double?): String = when {
        ms == null || !ms.isFinite() || ms < 0 -> "—"
        ms >= 1000 -> String.format(java.util.Locale.US, "%.1f s", ms / 1000)
        ms >= 100 -> String.format(java.util.Locale.US, "%.0f ms", ms)
        else -> String.format(java.util.Locale.US, "%.1f ms", ms)
    }
    fun loss(percent: Double?): String = when {
        percent == null || !percent.isFinite() || percent !in 0.0..100.0 -> "—"
        percent == 100.0 -> "100%"
        else -> String.format(java.util.Locale.US, "%.1f%%", percent)
    }
}

object PingParser {
    fun metricStats(payload: JSONObject, nodeId: String, taskId: Int): PingSummary? {
        val stats = payload.optJSONArray("stats") ?: return null
        for (i in 0 until stats.length()) {
            val stat = stats.getJSONObject(i)
            if (stat.optString("entity_id") != nodeId || stat.optInt("task_id") != taskId) continue
            val count = stat.numberOrNull("total")?.toLong()?.takeIf { it > 0 }
            if (count == null) return PingSummary(null, null, 0)
            val loss = stat.numberOrNull("loss")?.takeIf { it in 0.0..100.0 }
            val latest = stat.numberOrNull("latest")
            return PingSummary(if (loss == 100.0) null else latest?.takeIf { it >= 0 }, loss, count,
                stat.numberOrNull("avg")?.takeIf { it >= 0 && loss != 100.0 },
                stat.numberOrNull("min")?.takeIf { it >= 0 && loss != 100.0 },
                stat.numberOrNull("max")?.takeIf { it >= 0 && loss != 100.0 },
                latestKnown = latest != null || loss == 100.0, timedOut = (latest != null && latest < 0) || loss == 100.0)
        }
        return null
    }

    /** Aggregated buckets must be weighted by raw sample count, never averaged equally. */
    fun legacy(payload: JSONObject, nodeId: String, taskId: Int): PingSummary {
        val records = payload.optJSONArray("records") ?: JSONArray()
        var total = 0L
        var lost = 0.0
        var newest = Long.MIN_VALUE
        var latest: Double? = null
        var valid = 0L
        var sum = 0.0
        var minimum: Double? = null
        var maximum: Double? = null
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            if (record.optInt("task_id") != taskId) continue
            if (record.optString("client").isNotBlank() && record.optString("client") != nodeId) continue
            val value = record.numberOrNull("value") ?: continue
            val count = record.numberOrNull("count")?.toLong() ?: 1L
            if (count <= 0) continue
            val bucketLoss = record.numberOrNull("loss")?.takeIf { it in 0.0..100.0 }
                ?: if (value < 0) 100.0 else 0.0
            total += count
            lost += count * bucketLoss / 100.0
            if (value >= 0) {
                val successes = (count * (1 - bucketLoss / 100.0)).toLong()
                valid += successes
                sum += value * successes
                minimum = minimum?.let { minOf(it, value) } ?: value
                maximum = maximum?.let { maxOf(it, value) } ?: value
            }
            val timestamp = parseTime(record.opt("time"))
            if (timestamp >= newest) {
                newest = timestamp
                latest = value.takeIf { it >= 0 }
            }
        }
        val tasks = payload.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if (task.optInt("id") != taskId) continue
            val taskTotal = task.numberOrNull("total")?.toLong() ?: 0
            if (taskTotal > 0) {
                val authoritativeLoss = task.numberOrNull("loss")?.takeIf { it in 0.0..100.0 }
                if (authoritativeLoss != null) return PingSummary(
                    if (authoritativeLoss == 100.0) null else if (total > 0) latest else task.numberOrNull("latest")?.takeIf { it >= 0 },
                    authoritativeLoss, taskTotal,
                    task.numberOrNull("avg")?.takeIf { it >= 0 && authoritativeLoss != 100.0 },
                    task.numberOrNull("min")?.takeIf { it >= 0 && authoritativeLoss != 100.0 },
                    task.numberOrNull("max")?.takeIf { it >= 0 && authoritativeLoss != 100.0 },
                    latestKnown = total > 0 || task.numberOrNull("latest") != null || authoritativeLoss == 100.0,
                    timedOut = authoritativeLoss == 100.0 || (total > 0 && latest == null) ||
                        (total == 0L && (task.numberOrNull("latest") ?: 0.0) < 0),
                )
            }
        }
        return PingSummary(latest, if (total > 0) lost / total * 100.0 else null, total,
            if (valid > 0) sum / valid else null, minimum, maximum,
            latestKnown = total > 0, timedOut = total > 0 && latest == null)
    }


    private fun parseTime(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
        else -> Long.MIN_VALUE
    }
}
