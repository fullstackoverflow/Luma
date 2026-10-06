package dev.luma.monitor

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class LoginRequired : Exception("登录已过期，请打开 Luma 重新登录")
class RpcFailure(val code: Int) : Exception("Komari 接口返回错误 ($code)")

/** One immutable origin + cookie per client; no global credential injection and no redirects. */
class KomariClient(private val endpoint: Endpoint, private val cookie: String) {
    fun nodes(): List<NodeChoice> {
        val result = rpc("common:getNodes")
        return when (result) {
            is JSONObject -> result.keys().asSequence().mapNotNull { uuid ->
                result.optJSONObject(uuid)?.let { NodeChoice(uuid, it.optString("name", uuid)) }
            }.toList()
            is JSONArray -> (0 until result.length()).map { result.getJSONObject(it) }.map {
                NodeChoice(it.getString("uuid"), it.optString("name", it.getString("uuid")))
            }
            else -> error("节点列表格式不受支持")
        }.sortedBy { it.name }
    }

    fun tasks(nodeId: String): List<TaskChoice> {
        val result = try { rpc("public:getPublicPingTasks") as JSONArray }
        catch (e: RpcFailure) {
            if (e.code != -32601) throw e
            records(nodeId, 1).optJSONArray("tasks") ?: JSONArray()
        }
        return (0 until result.length()).map { result.getJSONObject(it) }.filter { task ->
            val clients = task.optJSONArray("clients")
            clients == null || (0 until clients.length()).any { clients.optString(it) == nodeId }
        }.map { TaskChoice(it.getInt("id"), it.optString("name").ifBlank { "任务 ${it.getInt("id")}" }) }
    }

    fun snapshot(config: CardConfig): CardSnapshot {
        val nodeResult = rpc("common:getNodes", JSONObject().put("uuid", config.nodeId)) as? JSONObject
            ?: error("节点信息格式不受支持")
        val node = nodeResult.optJSONObject(config.nodeId) ?: nodeResult
        val statuses = rpc("common:getNodesLatestStatus") as? JSONObject
            ?: error("节点状态格式不受支持")
        val status = statuses.optJSONObject(config.nodeId)
        val quota = TrafficQuota.calculate(node.numberOrNull("traffic_limit"), node.optString("traffic_limit_type", "max"),
            status?.numberOrNull("net_total_up"), status?.numberOrNull("net_total_down"))
        val choices = config.selectedTasks(tasks(config.nodeId))
        val summaries = if (choices.isEmpty()) emptyMap() else try {
            val params = JSONObject().put("hours", 1).put("entity_ids", JSONArray().put(config.nodeId))
                .put("task_ids", JSONArray(choices.map { it.id }))
            val payload = rpc("public:getPingMetricStats", params) as JSONObject
            choices.associate { task -> task.id to (PingParser.metricStats(payload, config.nodeId, task.id) ?: PingSummary(null, null, 0)) }
        } catch (e: RpcFailure) {
            if (e.code != -32601) throw e
            val payload = records(config.nodeId, 1, if (choices.size == 1) choices.first().id else -1)
            choices.associate { task -> task.id to PingParser.legacy(payload, config.nodeId, task.id) }
        }
        val ping = choices.firstOrNull()?.let { summaries[it.id] } ?: PingSummary(null, null, 0)
        val memTotal = status?.numberOrNull("ram_total")
        val memUsed = status?.numberOrNull("ram")
        return CardSnapshot(
            online = status?.let { if (it.has("online")) it.optBoolean("online") else null },
            latency = ping.latency, loss = ping.loss, samples = ping.samples,
            cpu = status?.numberOrNull("cpu")?.takeIf { it in 0.0..100.0 },
            memory = if (memTotal != null && memTotal > 0 && memUsed != null) memUsed / memTotal * 100.0 else null,
            averageLatency = ping.average, minLatency = ping.minimum, maxLatency = ping.maximum,
            latencyTimedOut = ping.timedOut,
            disk = status?.numberOrNull("disk_total")?.takeIf { it > 0 }?.let { total ->
                status.numberOrNull("disk")?.let { it / total * 100.0 }?.takeIf { it in 0.0..100.0 }
            },
            download = status?.numberOrNull("net_in")?.takeIf { it >= 0 },
            upload = status?.numberOrNull("net_out")?.takeIf { it >= 0 },
            uptime = status?.numberOrNull("uptime")?.toLong()?.takeIf { it >= 0 },
            load = status?.numberOrNull("load")?.takeIf { it >= 0 },
            totalDownload = status?.numberOrNull("net_total_down")?.takeIf { it >= 0 },
            totalUpload = status?.numberOrNull("net_total_up")?.takeIf { it >= 0 },
            trafficLimit = quota.limit, trafficUsed = quota.used,
            pingTasks = choices.map { task ->
                val summary = summaries.getValue(task.id)
                PingTaskSnapshot(task.id, task.name, summary.latency, summary.loss, summary.samples, summary.timedOut)
            },
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun records(nodeId: String, hours: Int, taskId: Int = -1) = rpc("common:getRecords", JSONObject()
        .put("uuid", nodeId).put("type", "ping").put("hours", hours).put("task_id", taskId)) as JSONObject

    private fun rpc(method: String, params: JSONObject = JSONObject()): Any {
        val response = request(endpoint.api("api/rpc2"), JSONObject().put("jsonrpc", "2.0")
            .put("id", 1).put("method", method).put("params", params).toString())
        response.optJSONObject("error")?.let {
            val code = it.optInt("code")
            val message = it.optString("message").lowercase()
            if (code == 401 || code == 403 || message.contains("unauthorized") || message.contains("not logged") || message.contains("forbidden")) throw LoginRequired()
            throw RpcFailure(code)
        }
        require(response.has("result") && !response.isNull("result")) { "Komari 接口没有返回数据" }
        return response.get("result")
    }

    private fun request(url: String, body: String): JSONObject {
        require(endpoint.permits(url))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Origin", endpoint.origin)
            if (cookie.isNotBlank()) connection.setRequestProperty("Cookie", cookie)
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 401 || code == 403) throw LoginRequired()
            if (code in 300..399) error("接口发生跳转，请检查服务根地址")
            if (code !in 200..299) error("服务器请求失败 (HTTP $code)")
            val bytes = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val size = input.read(chunk)
                    if (size < 0) break
                    require(buffer.size() + size <= 2 * 1024 * 1024) { "服务器返回的数据过大" }
                    buffer.write(chunk, 0, size)
                }
                buffer.toByteArray()
            }
            return try { JSONObject(String(bytes, Charsets.UTF_8)) }
            catch (_: org.json.JSONException) { error("返回内容不是 Komari JSON 接口，请检查地址或登录状态") }
        } finally { connection.disconnect() }
    }
}
