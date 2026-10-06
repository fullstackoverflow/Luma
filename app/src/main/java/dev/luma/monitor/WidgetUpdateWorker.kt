package dev.luma.monitor

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class WidgetUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getInt("id", -1)
        val store = LumaStore(applicationContext)
        val config = store.config(id) ?: return Result.success()
        if (config.demo) { LumaWidgetProvider.render(applicationContext, id); return Result.success() }
        val endpoint = store.instance(config.instanceId)?.endpoint ?: return Result.success()
        var shouldRetry = false
        val snapshot = try {
            KomariClient(endpoint, store.cookie(endpoint)).snapshot(config)
        } catch (e: Exception) {
            if (isStopped) return Result.success()
            shouldRetry = e is java.io.IOException
            val message = when (e) {
                is LoginRequired -> e.message
                is java.net.UnknownHostException -> "无法解析服务器地址"
                is javax.net.ssl.SSLException -> "HTTPS 连接失败"
                is java.io.IOException -> "网络暂不可用"
                else -> e.message ?: "更新失败"
            }
            store.snapshot(id).copy(error = message)
        }
        // Each card stays bound to its server even while another instance is open.
        if (store.instance(config.instanceId)?.endpoint == endpoint && store.config(id) == config && !isStopped) {
            store.saveSnapshot(id, snapshot)
            LumaWidgetProvider.render(applicationContext, id)
        }
        return if (shouldRetry && runAttemptCount < 2) Result.retry() else Result.success()
    }
}

object WidgetScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context: Context, id: Int) {
        val config = LumaStore(context).config(id) ?: return
        if (config.demo) { cancel(context, id); return }
        val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(config.interval.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setInputData(Data.Builder().putInt("id", id).build()).setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("card_periodic_$id", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    fun refresh(context: Context, id: Int) {
        // A manual refresh never changes the user's periodic interval.
        val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
            .setInputData(Data.Builder().putInt("id", id).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("card_refresh_$id", ExistingWorkPolicy.KEEP, request)
    }
    fun refreshAll(context: Context) { LumaWidgetProvider.ids(context).forEach { refresh(context, it) } }
    fun cancel(context: Context, id: Int) {
        WorkManager.getInstance(context).cancelUniqueWork("card_periodic_$id")
        WorkManager.getInstance(context).cancelUniqueWork("card_refresh_$id")
    }
}
