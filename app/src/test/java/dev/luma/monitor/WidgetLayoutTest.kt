package dev.luma.monitor

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real RemoteViews inflation + measurement, rather than a separate mock of the UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetLayoutTest {
    private fun context(): Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Luma)

    @Test fun previewInsideAppCompatActivityUsesFrameworkImageViews() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val preview = controller.get().cardPreview()
            listOf(R.id.cpu_bar, R.id.memory_bar, R.id.disk_bar, R.id.load_bar).forEach { id ->
                assertEquals(android.widget.ImageView::class.java, preview.findViewById<View>(id).javaClass)
            }
            val rows = preview.findViewById<ViewGroup>(R.id.ping_rows)
            assertEquals(3, rows.childCount)
            repeat(rows.childCount) { index ->
                listOf(R.id.ping_latency_bar, R.id.ping_loss_bar).forEach { id ->
                    assertEquals(android.widget.ImageView::class.java, rows.getChildAt(index).findViewById<View>(id).javaClass)
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun compactCardFitsItsSmallestSupportedSize() {
        assertCardFits(false, 170, 120)
    }

    @Test fun wideCardFitsTheResponsiveBreakpoint() {
        assertCardFits(true, 280, 300)
    }

    @Test fun largerSystemFontFitsBothResponsiveSizes() {
        RuntimeEnvironment.setFontScale(1.3f)
        assertCardFits(false, 170, 120)
        assertCardFits(true, 364, 390)
    }

    @Test fun expandedCardRendersResourcesAndAllCarrierResults() {
        val config = CardConfig("demo", "DMIT · 洛杉矶", 2, "联通", demo = true)
        val card = LumaWidgetProvider.views(context(), -99, config, CardSnapshot.demo(), true).apply(context(), null)
        assertEquals("12.0%", card.findViewById<TextView>(R.id.cpu).text.toString())
        assertEquals("38.0%", card.findViewById<TextView>(R.id.memory).text.toString())
        val rows = card.findViewById<ViewGroup>(R.id.ping_rows)
        assertEquals(3, rows.childCount)
        assertEquals("— 超时", rows.getChildAt(0).findViewById<TextView>(R.id.latency).text.toString())
        assertEquals("100%", rows.getChildAt(0).findViewById<TextView>(R.id.loss).text.toString())
        assertEquals("158 ms", rows.getChildAt(1).findViewById<TextView>(R.id.latency).text.toString())
        assertEquals("159 ms", rows.getChildAt(2).findViewById<TextView>(R.id.latency).text.toString())
        assertEquals("剩余 945.1 GB", card.findViewById<TextView>(R.id.traffic_remaining).text.toString())
        val single = LumaWidgetProvider.views(context(), -99, config.copy(allTasks = false), CardSnapshot.demo(), true).apply(context(), null)
        assertEquals(1, single.findViewById<ViewGroup>(R.id.ping_rows).childCount)
    }

    @Test fun instanceListInflatesWithoutWidgetPreviewOnFirstLaunch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNull(activity.findViewById<View>(R.id.card_root))
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        fun texts(view: View): List<String> = if (view is ViewGroup) {
            (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        } else if (view is TextView) listOf(view.text.toString()) else emptyList()
        assertTrue(texts(root).contains("新增配置"))
        controller.pause().stop().destroy()
    }

    private fun assertCardFits(wide: Boolean, width: Int, height: Int) {
        val config = CardConfig("demo", "Tokyo · 长节点名称也不应该挤掉刷新和指标", 7, "香港电信探测目标", demo = true)
        val card = LumaWidgetProvider.views(context(), -99, config, CardSnapshot.demo(), wide).apply(context(), null)
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("Card needs ${card.measuredHeight} px, available $height", card.measuredHeight <= height)
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, width, height)
        if (wide) {
            val rows = card.findViewById<ViewGroup>(R.id.ping_rows)
            repeat(rows.childCount) { index ->
                val row = rows.getChildAt(index)
                val latency = row.findViewById<TextView>(R.id.latency)
                val loss = row.findViewById<TextView>(R.id.loss)
                fun right(view: View): Int {
                    var edge = view.right
                    var parent = view.parent
                    while (parent is View && parent !== row) { edge += parent.left; parent = parent.parent }
                    return edge
                }
                assertEquals("Latency value belongs above the left bar", right(row.findViewById(R.id.ping_latency_bar)), right(latency))
                assertEquals("Loss value belongs above the right bar", right(row.findViewById(R.id.ping_loss_bar)), right(loss))
                fun top(view: View): Int {
                    var edge = view.top
                    var parent = view.parent
                    while (parent is View && parent !== row) { edge += parent.top; parent = parent.parent }
                    return edge
                }
                assertEquals("The two bars align vertically", top(row.findViewById(R.id.ping_latency_bar)), top(row.findViewById(R.id.ping_loss_bar)))
            }
        }
        fun texts(view: View): List<TextView> = if (view is ViewGroup) {
            (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        } else if (view is TextView && view.text.isNotEmpty()) listOf(view) else emptyList()
        texts(card).forEach { text ->
            assertTrue("Text is clipped vertically: ${text.text}, line=${text.layout.getLineBottom(0)}, height=${text.height}, pad=${text.paddingTop + text.paddingBottom}", text.layout.getLineBottom(0) <= text.height - text.paddingTop - text.paddingBottom)
            assertEquals("Text wraps: ${text.text}", 1, text.layout.lineCount)
            var child: View = text
            while (child !== card) {
                val parent = child.parent as ViewGroup
                assertTrue("View extends past its parent: ${text.text}; child=${child.top}..${child.bottom}, parent=${parent.paddingTop}..${parent.height - parent.paddingBottom}", child.bottom <= parent.height - parent.paddingBottom && child.top >= parent.paddingTop)
                child = parent
            }
        }
        listOf(R.id.latency, R.id.loss).forEach { id ->
            assertEquals("Metric should not be ellipsized", 0, card.findViewById<TextView>(id).layout.getEllipsisCount(0))
        }
        val image = android.graphics.Bitmap.createBitmap(width * 3, height * 3, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        canvas.scale(3f, 3f)
        card.draw(canvas)
        val output = java.io.File("build/outputs/previews/widget-${if (wide) "wide" else "compact"}${if (context().resources.configuration.fontScale > 1f) "-large-font" else ""}.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
