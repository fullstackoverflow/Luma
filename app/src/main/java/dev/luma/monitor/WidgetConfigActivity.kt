package dev.luma.monitor

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class WidgetConfigActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: LumaStore
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var nodePicker: Spinner
    private var selectedInstanceId: String? = null
    private lateinit var taskButton: MaterialButton
    private var selectedTaskIds = emptyList<Int>()
    private var loadedTaskKey: String? = null
    private var tasksLoaded = false
    private var resumedOnce = false
    private lateinit var intervalPicker: Spinner
    private lateinit var demoSwitch: MaterialSwitch
    private lateinit var message: TextView
    private var nodes = emptyList<NodeChoice>()
    private var tasks = emptyList<TaskChoice>()
    private var generation = 0
    private val intervals = listOf(15, 30, 60, 180)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || info?.provider != ComponentName(this, LumaWidgetProvider::class.java)) {
            finish(); return
        }
        store = LumaStore(this)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val existing = store.config(widgetId)
        selectedInstanceId = existing?.instanceId ?: store.selectedInstance?.id
        val root = column()
        val content = column(24)
        root.add(ScrollView(this).apply { addView(content) })
        content.add(label("你的桌面卡片", 28f, bold = true), 16)
        content.add(label("选一个节点，查看资源与网络状态。", 15f, muted = true), 6)
        content.add(cardPreview(), 24, previewHeight())
        content.add(label("上方为样式预览，数值为示例。", 12f, muted = true), 8)

        val form = panel()
        demoSwitch = MaterialSwitch(this).apply {
            text = "使用示例数据"
            isChecked = existing?.demo == true
            setTextColor(getColor(R.color.ink))
        }
        form.add(demoSwitch)
        form.add(label("示例模式不连接服务器，卡片会明确标注。", 12f, muted = true), 2)
        form.add(label("服务器实例", 13f, muted = true), 20)
        val instances = store.instances
        val instancePicker = Spinner(this).apply {
            adapter = adapter(if (instances.isEmpty()) listOf("请先在 Luma 新增配置") else instances)
            isEnabled = instances.isNotEmpty()
            setSelection(instances.indexOfFirst { it.id == selectedInstanceId }.coerceAtLeast(0))
        }
        form.add(instancePicker, 4, dp(48))
        form.add(label("服务器节点", 13f, muted = true), 20)
        nodePicker = Spinner(this); form.add(nodePicker, 4, dp(48))
        form.add(label("显示线路", 13f, muted = true), 16)
        taskButton = button("加载中…", outlined = true) { selectTasks() }
        form.add(taskButton, 4)
        form.add(label("后台刷新", 13f, muted = true), 16)
        intervalPicker = Spinner(this).apply {
            adapter = adapter(intervals.map { if (it < 60) "$it 分钟" else "${it / 60} 小时" })
            setSelection(intervals.indexOf(existing?.interval ?: 30).coerceAtLeast(0))
        }
        form.add(intervalPicker, 4, dp(48))
        message = label("", 13f, muted = true)
        form.add(message, 12)
        form.add(button("保存卡片") { save() }, 8)
        content.add(form, 20)
        content.add(label("延迟为最近探测结果，丢包率统计最近 1 小时。", 12f, muted = true), 20)
        installRoot(root)
        instancePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = instances.getOrNull(position)?.id
                if (selectedInstanceId != selected) {
                    selectedInstanceId = selected
                    generation++
                    loadNodes()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        demoSwitch.setOnCheckedChangeListener { _, checked ->
            nodePicker.isEnabled = !checked
            if (checked) loadDemoTasks() else loadNodes()
        }
        if (demoSwitch.isChecked) {
            nodePicker.isEnabled = false
            loadDemoTasks()
        } else loadNodes()
    }

    override fun onResume() {
        super.onResume()
        if (resumedOnce && ::demoSwitch.isInitialized && !demoSwitch.isChecked) loadNodes()
        resumedOnce = true
    }

    private fun loadNodes() {
        if (demoSwitch.isChecked) return
        val instanceId = selectedInstanceId
        val endpoint = store.instance(instanceId)?.endpoint
        if (endpoint == null) { message.text = "先打开 Luma 配置服务地址，或开启示例模式。"; return }
        val request = ++generation
        val preferredNode = nodes.getOrNull(nodePicker.selectedItemPosition)?.uuid ?: store.config(widgetId)?.nodeId
        nodePicker.onItemSelectedListener = null
        message.setOnClickListener(null)
        nodePicker.isEnabled = false; taskButton.isEnabled = false
        nodes = emptyList(); tasks = emptyList()
        tasksLoaded = false
        nodePicker.adapter = adapter(listOf("加载中…")); taskButton.text = "等待选择节点"
        message.text = "正在读取 Komari 节点…"
        executor.execute {
            val result = runCatching { KomariClient(endpoint, store.cookie(endpoint)).nodes() }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != generation || demoSwitch.isChecked || instanceId != selectedInstanceId) return@runOnUiThread
                result.fold(onSuccess = { loaded ->
                    nodes = loaded
                    if (nodes.isEmpty()) { message.text = "没有可访问的节点，请检查网页登录状态。"; return@fold }
                    nodePicker.onItemSelectedListener = null
                    nodePicker.adapter = adapter(nodes)
                    nodePicker.isEnabled = true
                    val selected = nodes.indexOfFirst { it.uuid == preferredNode }.coerceAtLeast(0)
                    nodePicker.setSelection(selected)
                    nodePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { nodes.getOrNull(position)?.let { loadTasks(it) } }
                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }
                }, onFailure = { showLoadError(it) })
            }
        }
    }

    private fun loadTasks(node: NodeChoice) {
        val instanceId = selectedInstanceId
        val endpoint = store.instance(instanceId)?.endpoint ?: return
        val request = ++generation
        message.setOnClickListener(null)
        tasks = emptyList(); taskButton.isEnabled = false
        tasksLoaded = false
        taskButton.text = "加载中…"
        message.text = "正在读取该节点绑定的探测任务…"
        executor.execute {
            val result = runCatching { KomariClient(endpoint, store.cookie(endpoint)).tasks(node.uuid) }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != generation || demoSwitch.isChecked || instanceId != selectedInstanceId) return@runOnUiThread
                result.fold(onSuccess = { loaded ->
                    tasks = loaded
                    tasksLoaded = true
                    val key = "$instanceId/${node.uuid}"
                    val existing = store.config(widgetId)?.takeIf { it.instanceId == instanceId && it.nodeId == node.uuid }
                    selectedTaskIds = if (loadedTaskKey == key) selectedTaskIds.filter { id -> tasks.any { it.id == id } }
                        else existing?.selectedTasks(tasks)?.map { it.id }.orEmpty()
                    if (selectedTaskIds.isEmpty()) selectedTaskIds = tasks.take(3).map { it.id }
                    loadedTaskKey = key
                    updateTaskButton()
                    message.text = if (tasks.isEmpty()) "此节点没有绑定 Ping 任务，请在 Komari 后台配置。" else "数据来自服务端，不需要保存账号密码。"
                }, onFailure = { showLoadError(it) })
            }
        }
    }

    private fun updateTaskButton() {
        taskButton.isEnabled = tasks.isNotEmpty()
        taskButton.text = if (tasks.isEmpty()) "未绑定探测任务" else tasks.filter { it.id in selectedTaskIds }
            .joinToString("、") { it.name }.ifBlank { "选择线路（最多 3 条）" }
    }

    private fun loadDemoTasks() {
        generation++
        message.setOnClickListener(null)
        tasks = listOf(TaskChoice(1, "电信"), TaskChoice(2, "联通"), TaskChoice(3, "移动"))
        tasksLoaded = true
        selectedTaskIds = store.config(widgetId)?.takeIf { it.demo }?.selectedTasks(tasks)?.map { it.id }
            ?: tasks.map { it.id }
        loadedTaskKey = "demo"
        updateTaskButton()
        message.text = "将保存带有示例标识的卡片。"
    }

    private fun selectTasks() {
        val available = tasks.toList()
        if (available.isEmpty()) return
        val checked = BooleanArray(available.size) { available[it].id in selectedTaskIds }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("选择线路（最多 3 条）")
            .setMultiChoiceItems(available.map { it.name }.toTypedArray(), checked, null)
            .setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                val enabled = dialog.listView.isItemChecked(position)
                if (enabled && checked.count { it } >= 3 && !checked[position]) {
                    dialog.listView.setItemChecked(position, false)
                    android.widget.Toast.makeText(this, "最多选择 3 条线路", android.widget.Toast.LENGTH_SHORT).show()
                } else checked[position] = enabled
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (checked.none { it }) {
                    android.widget.Toast.makeText(this, "请至少选择一条线路", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (tasks != available) { dialog.dismiss(); return@setOnClickListener }
                selectedTaskIds = available.filterIndexed { index, _ -> checked[index] }.map { it.id }
                updateTaskButton()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun save() {
        val config = if (demoSwitch.isChecked) {
            val selected = tasks.first { it.id in selectedTaskIds }
            CardConfig("demo", "Tokyo · 01", selected.id, selected.name, demo = true, taskIds = selectedTaskIds)
        } else {
            val node = nodes.getOrNull(nodePicker.selectedItemPosition)
            val selected = tasks.filter { it.id in selectedTaskIds }
            if (node == null || !nodePicker.isEnabled || !tasksLoaded || (tasks.isNotEmpty() && selected.isEmpty())) {
                message.text = "请先选择一个节点和显示线路。"; return
            }
            CardConfig(node.uuid, node.name, selected.firstOrNull()?.id ?: -1, selected.firstOrNull()?.name.orEmpty(),
                intervals[intervalPicker.selectedItemPosition], 1,
                instanceId = selectedInstanceId, taskIds = selected.map { it.id })
        }
        store.saveConfig(widgetId, config)
        WidgetScheduler.cancel(this, widgetId)
        WidgetScheduler.schedule(this, widgetId)
        LumaWidgetProvider.render(this, widgetId)
        WidgetScheduler.refresh(this, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private fun adapter(items: List<*>) = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun showLoadError(error: Throwable) {
        if (error is LoginRequired) {
            message.text = "登录已过期，点击这里重新登录。"
            message.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java).putExtra("instance_id", selectedInstanceId))
            }
        } else {
            message.text = "${if (error is java.io.IOException) "无法连接服务端，请检查网络。" else error.message ?: "读取失败"} 点击重试。"
            message.setOnClickListener { loadNodes() }
        }
    }

    override fun onDestroy() { generation++; executor.shutdownNow(); super.onDestroy() }
}
