package dev.luma.monitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.json.JSONArray

// Commit ensures the background worker can read the session/config immediately after enqueue.
@android.annotation.SuppressLint("ApplySharedPref")
class LumaStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("luma", Context.MODE_PRIVATE)
    init {
        // Preserve the original server, login and card bindings when upgrading from single-instance storage.
        if (!prefs.contains("instances")) {
            val old = prefs.getString("endpoint", null)?.let { runCatching { Endpoint.parse(it) }.getOrNull() }
            if (old != null) {
                val instance = ServerInstance(UUID.randomUUID().toString(), java.net.URI(old.origin).host, old)
                val editor = prefs.edit().putString("instances", JSONArray().put(instance.toJson()).toString())
                    .putString("selected_instance", instance.id).putString("legacy_instance", instance.id)
                    .remove("endpoint").remove("cookie")
                prefs.getString("cookie", null)?.let { editor.putString("cookie_${instance.id}", it) }
                editor.commit()
            }
        }
    }

    val instances: List<ServerInstance> get() = runCatching {
        val json = JSONArray(prefs.getString("instances", "[]"))
        (0 until json.length()).map { ServerInstance.fromJson(json.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    val selectedInstance: ServerInstance? get() = instance(prefs.getString("selected_instance", null)) ?: instances.firstOrNull()
    val endpoint: Endpoint? get() = selectedInstance?.endpoint

    fun instance(id: String?): ServerInstance? = instances.firstOrNull { it.id == id }

    fun addInstance(name: String, endpoint: Endpoint): ServerInstance {
        require(name.isNotBlank()) { "请填写实例名称" }
        require(instances.none { it.endpoint == endpoint }) { "这个服务器地址已经添加过了" }
        val added = ServerInstance(UUID.randomUUID().toString(), name.trim(), endpoint)
        val json = JSONArray()
        (instances + added).forEach { json.put(it.toJson()) }
        val editor = prefs.edit().putString("instances", json.toString())
        if (selectedInstance == null) editor.putString("selected_instance", added.id)
        editor.commit()
        return added
    }

    fun selectInstance(id: String) {
        require(instance(id) != null) { "实例不存在" }
        prefs.edit().putString("selected_instance", id).commit()
    }

    /** Native-only session storage. Passwords never pass through Luma code. */
    fun saveCookie(endpoint: Endpoint, cookie: String) {
        val instance = instances.firstOrNull { it.endpoint == endpoint } ?: return
        val editor = prefs.edit()
        if (cookie.isBlank()) editor.remove("cookie_${instance.id}") else editor.putString("cookie_${instance.id}", encrypt(cookie))
        editor.commit()
    }

    fun cookie(endpoint: Endpoint): String {
        val instance = instances.firstOrNull { it.endpoint == endpoint } ?: return ""
        val key = "cookie_${instance.id}"
        return prefs.getString(key, null)?.let {
            runCatching { decrypt(it) }.getOrElse { prefs.edit().remove(key).commit(); "" }
        }.orEmpty()
    }

    fun config(id: Int): CardConfig? = prefs.getString("config_$id", null)?.let {
        runCatching {
            val config = CardConfig.fromJson(JSONObject(it))
            if (config.instanceId == null && !config.demo) config.copy(instanceId = prefs.getString("legacy_instance", null)) else config
        }.getOrNull()
    }

    fun saveConfig(id: Int, config: CardConfig) {
        prefs.edit().putString("config_$id", config.toJson().toString()).remove("snapshot_$id").commit()
    }

    fun snapshot(id: Int): CardSnapshot = prefs.getString("snapshot_$id", null)?.let {
        runCatching { CardSnapshot.fromJson(JSONObject(it)) }.getOrNull()
    } ?: CardSnapshot()

    fun saveSnapshot(id: Int, snapshot: CardSnapshot) {
        prefs.edit().putString("snapshot_$id", snapshot.toJson().toString()).commit()
    }

    fun deleteCard(id: Int) { prefs.edit().remove("config_$id").remove("snapshot_$id").commit() }


    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("luma_session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("luma_session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
