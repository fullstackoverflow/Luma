package dev.luma.monitor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstanceStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("luma", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
    } else emptyList()

    @Test fun addDialogCreatesRecordAndClickOpensItsServerThenBackReturnsToList() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        fun content() = views(activity.findViewById(android.R.id.content))
        content().filterIsInstance<TextView>().single { it.text.toString() == "新增配置" }.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val fields = views(dialog.window!!.decorView).filterIsInstance<TextInputLayout>()
        fields.single { it.hint.toString() == "实例名称" }.editText!!.setText("Home")
        fields.single { it.hint.toString() == "Komari 服务根地址" }.editText!!.setText("https://home.example.com")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals("Home", LumaStore(context).instances.single().name)
        assertTrue(content().filterIsInstance<WebView>().isEmpty())
        val record = content().filterIsInstance<MaterialCardView>().single()
        assertTrue(views(record).filterIsInstance<TextView>().any { it.text.toString() == "https://home.example.com" })
        record.performClick()
        assertEquals("https://home.example.com/", shadowOf(content().filterIsInstance<WebView>().single()).lastLoadedUrl)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, content().filterIsInstance<MaterialCardView>().size)
        assertTrue(content().filterIsInstance<WebView>().isEmpty())
        controller.pause().stop().destroy()
    }

    @Test fun recordsPersistAndSwitchingPreservesCardBindingsAndSessions() {
        val store = LumaStore(context)
        val first = store.addInstance("Home", Endpoint.parse("https://home.example.com"))
        val second = store.addInstance("Work", Endpoint.parse("https://work.example.com"))
        val card = CardConfig("node", "Node", 1, "Ping", instanceId = first.id)
        store.saveConfig(10, card)
        store.saveSnapshot(10, CardSnapshot(latency = 12.0))
        prefs.edit().putString("cookie_${first.id}", "first-encrypted-session")
            .putString("cookie_${second.id}", "second-encrypted-session").commit()
        store.selectInstance(second.id)

        val reloaded = LumaStore(context)
        assertEquals(listOf(first, second), reloaded.instances)
        assertEquals(second.endpoint, reloaded.endpoint)
        assertEquals(first.endpoint, reloaded.instance(reloaded.config(10)?.instanceId)?.endpoint)
        assertEquals(card, reloaded.config(10))
        assertEquals(12.0, reloaded.snapshot(10).latency!!, 0.0)
        assertEquals("first-encrypted-session", prefs.getString("cookie_${first.id}", null))
        assertEquals("second-encrypted-session", prefs.getString("cookie_${second.id}", null))
    }

    @Test fun oldServerMigratesOnceAndOldCardsKeepOriginalServer() {
        val endpoint = Endpoint.parse("https://old.example.com")
        val card = CardConfig("node", "Node", 1, "Ping")
        prefs.edit().putString("endpoint", endpoint.baseUrl).putString("cookie", "encrypted-session")
            .putString("config_7", card.toJson().toString()).commit()
        val store = LumaStore(context)
        val migrated = store.instances.single()
        assertEquals(endpoint, migrated.endpoint)
        assertEquals("old.example.com", migrated.name)
        assertEquals("encrypted-session", prefs.getString("cookie_${migrated.id}", null))
        val added = store.addInstance("New", Endpoint.parse("https://new.example.com"))
        store.selectInstance(added.id)
        assertEquals(migrated.id, store.config(7)?.instanceId)
        assertEquals(2, LumaStore(context).instances.size)
        assertFalse(prefs.contains("cookie"))
    }

    @Test fun duplicateAddressAndEmptyNameAreRejected() {
        val store = LumaStore(context)
        store.addInstance("Home", Endpoint.parse("https://home.example.com"))
        assertThrows(IllegalArgumentException::class.java) {
            store.addInstance("Duplicate", Endpoint.parse("https://home.example.com/"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.addInstance("  ", Endpoint.parse("https://other.example.com"))
        }
        assertEquals(1, store.instances.size)
    }
}
