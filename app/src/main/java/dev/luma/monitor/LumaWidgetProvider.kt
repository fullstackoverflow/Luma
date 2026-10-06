package dev.luma.monitor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LumaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            render(context, id)
            if (LumaStore(context).config(id) != null) {
                WidgetScheduler.schedule(context, id)
                WidgetScheduler.refresh(context, id)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetScheduler.cancel(context, it); LumaStore(context).deleteCard(it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id in ids(context)) WidgetScheduler.refresh(context, id)
        }
    }

    companion object {
        private const val REFRESH = "dev.luma.monitor.REFRESH"
        fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, LumaWidgetProvider::class.java))

        fun renderAll(context: Context) { ids(context).forEach { render(context, it) } }

        fun render(context: Context, id: Int) {
            val store = LumaStore(context)
            val config = store.config(id)
            val snapshot = if (config?.demo == true) CardSnapshot.demo() else store.snapshot(id)
            val manager = AppWidgetManager.getInstance(context)
            val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
            val wideWidth = 280f * scale
            val wideHeight = 300f * scale
            val views = if (Build.VERSION.SDK_INT >= 31) {
                RemoteViews(mapOf(
                    SizeF(170f, 120f) to views(context, id, config, snapshot, false),
                    SizeF(wideWidth, wideHeight) to views(context, id, config, snapshot, true),
                ))
            } else {
                val options = manager.getAppWidgetOptions(id)
                val wide = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) >= wideWidth &&
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) >= wideHeight
                views(context, id, config, snapshot, wide)
            }
            manager.updateAppWidget(id, views)
        }

        fun views(context: Context, id: Int, config: CardConfig?, data: CardSnapshot, wide: Boolean): RemoteViews {
            val view = RemoteViews(context.packageName, if (wide) R.layout.widget_wide else R.layout.widget_compact)
            val status = when (data.online) { true -> "● 在线"; false -> "● 离线"; null -> "● 未知" }
            val time = if (data.updatedAt > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(data.updatedAt)) else "等待更新"
            val waiting = data.updatedAt == 0L
            view.setTextViewText(R.id.node_name, config?.nodeName ?: "Luma · 点击配置")
            if (!wide) {
                val latency = DisplayValues.latency(data.latency)
                view.setTextViewText(R.id.latency, latency.substringBeforeLast(' '))
                view.setTextViewText(R.id.latency_label, "延迟 · ${latency.substringAfterLast(' ', "ms")}")
                view.setTextViewText(R.id.loss, DisplayValues.loss(data.loss).removeSuffix("%"))
                view.setTextColor(R.id.loss, context.getColor(if ((data.loss ?: 0.0) > 5.0) R.color.widget_bad else R.color.widget_good))
            }
            val footer = when {
                config == null -> "点击选择节点和探测目标"
                config.demo -> "示例数据 · 不代表真实监测"
                data.error != null -> "${data.error}${if (waiting) "" else " · $time 缓存"}"
                waiting -> "首次获取中 · 点击刷新"
                data.samples == 0L -> "无探测记录 · $time 更新"
                wide -> "$time 更新 · 运行 ${DisplayValues.uptime(data.uptime)} · 1h"
                else -> "$status · ${config.taskName} · $time"
            }
            view.setTextViewText(R.id.footer, footer)
            if (wide) {
                view.setTextViewText(R.id.status, if (data.error != null) "● 待更新" else status)
                view.setTextColor(R.id.status, context.getColor(if (data.online == true && data.error == null) R.color.widget_good else R.color.widget_muted))
                listOf(Triple(R.id.cpu, R.id.cpu_bar, Pair(data.cpu, R.color.widget_cpu)),
                    Triple(R.id.memory, R.id.memory_bar, Pair(data.memory, R.color.widget_memory)),
                    Triple(R.id.disk, R.id.disk_bar, Pair(data.disk, R.color.widget_disk))).forEach { (text, bar, metric) ->
                    view.setTextViewText(text, DisplayValues.percent(metric.first))
                    view.setImageViewBitmap(bar, segments(context, metric.first, 100.0, metric.second))
                }
                view.setTextViewText(R.id.load, data.load?.let { String.format(Locale.US, "%.2f", it) } ?: "—")
                // Load is a count, not a utilization percentage; don't invent a capacity for its bar.
                view.setImageViewBitmap(R.id.load_bar, segments(context, null, 1.0, R.color.widget_load))
                view.setTextViewText(R.id.upload, "↑ ${DisplayValues.speed(data.upload)}")
                view.setTextViewText(R.id.download, "↓ ${DisplayValues.speed(data.download)}")
                view.setTextViewText(R.id.total_upload, "已上传 ${DisplayValues.bytes(data.totalUpload)}")
                view.setTextViewText(R.id.total_download, "已下载 ${DisplayValues.bytes(data.totalDownload)}")
                val quota = TrafficQuota(data.trafficLimit, data.trafficUsed)
                view.setTextViewText(R.id.traffic_remaining, "剩余 ${if (quota.unlimited) "∞" else DisplayValues.bytes(quota.remaining)}")
                view.setTextViewText(R.id.traffic_detail, "${DisplayValues.bytes(quota.used)} / ${if (quota.unlimited) "∞" else DisplayValues.bytes(quota.limit)}")
                val quotaColor = when {
                    (quota.percent ?: 0.0) >= 90 -> R.color.widget_bad
                    (quota.percent ?: 0.0) >= 75 -> R.color.widget_warning
                    else -> R.color.widget_good
                }
                view.setImageViewBitmap(R.id.traffic_bar, segments(context, quota.percent, 100.0, quotaColor))
                val all = data.pingTasks.ifEmpty {
                    config?.let { listOf(PingTaskSnapshot(it.taskId, it.taskName, data.latency, data.loss, data.samples, data.latencyTimedOut)) }.orEmpty()
                }
                val selectedIds = config?.selectedTasks(all.map { TaskChoice(it.id, it.name) })?.map { it.id }
                val tasks = if (selectedIds == null) all.take(3) else selectedIds.mapNotNull { taskId -> all.firstOrNull { it.id == taskId } }
                view.removeAllViews(R.id.ping_rows)
                tasks.take(3).forEach { task ->
                    val row = RemoteViews(context.packageName, R.layout.widget_ping_row)
                    row.setTextViewText(R.id.ping_name, task.name)
                    row.setTextViewText(R.id.latency, if (task.timedOut) "— 超时" else DisplayValues.latency(task.latency))
                    row.setTextViewText(R.id.loss, DisplayValues.loss(task.loss))
                    val latencyColor = when {
                        task.latency == null -> R.color.widget_muted
                        task.latency < 80 -> R.color.widget_good
                        task.latency < 200 -> R.color.widget_warning
                        else -> R.color.widget_bad
                    }
                    row.setTextColor(R.id.latency, context.getColor(if (task.timedOut) R.color.widget_bad else latencyColor))
                    row.setTextColor(R.id.loss, context.getColor(when {
                        task.loss == null -> R.color.widget_muted
                        task.loss > 5 -> R.color.widget_bad
                        else -> R.color.widget_good
                    }))
                    row.setImageViewBitmap(R.id.ping_latency_bar, segments(context, task.latency, 300.0, latencyColor))
                    row.setImageViewBitmap(R.id.ping_loss_bar, segments(context, task.loss, 100.0, R.color.widget_bad, loss = true))
                    row.setContentDescription(R.id.ping_name, "${task.name}，最新延迟 ${if (task.timedOut) "超时" else DisplayValues.latency(task.latency)}，丢包率 ${DisplayValues.loss(task.loss)}")
                    view.addView(R.id.ping_rows, row)
                }
            }
            val open = if (config == null) Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            else Intent(context, MainActivity::class.java).putExtra("node_id", config.nodeId)
                .putExtra("instance_id", config.instanceId)
            open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val openIntent = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            view.setOnClickPendingIntent(R.id.card_root, openIntent)
            val refresh = Intent(context, LumaWidgetProvider::class.java).setAction(REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            view.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(context, id, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return view
        }

        private fun segments(context: Context, value: Double?, maximum: Double, color: Int, loss: Boolean = false): Bitmap {
            val bitmap = Bitmap.createBitmap(400, 16, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val count = value?.takeIf { it.isFinite() && it >= 0 }?.let {
                kotlin.math.ceil((it / maximum).coerceIn(0.0, 1.0) * 20).toInt()
            }
            repeat(20) { index ->
                val resource = when {
                    count == null -> R.color.widget_track
                    index < count -> color
                    loss -> R.color.widget_good
                    else -> R.color.widget_track
                }
                paint.color = context.getColor(resource)
                canvas.drawRoundRect(index * 20f, 0f, index * 20f + 16f, 16f, 3f, 3f, paint)
            }
            return bitmap
        }
    }
}
