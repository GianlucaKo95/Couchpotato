package de.koeven.couchpotato.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.koeven.couchpotato.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cloud sync over a Supabase Edge Function. The service-role key never leaves the server. */
class CloudSync(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secure = SecureSyncToken(app, prefs)

    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
    val linked: Boolean get() = prefs.getString("groupId", null) != null && secure.read() != null

    fun status(): String = when {
        !configured -> "Supabase ist im Build noch nicht konfiguriert."
        linked -> "Mit der Couchpotato-Cloud verbunden."
        else -> "Noch nicht mit einem anderen Gerät gekoppelt."
    }

    suspend fun createPairingCode(): String = withContext(Dispatchers.IO) {
        requireConfigured()
        if (linked) sync()
        val token = secure.read() ?: newDeviceToken()
        val result = call("create", token, JSONObject())
        prefs.edit().putString("groupId", result.getString("group_id")).apply()
        secure.write(token)
        val local = snapshot()
        val pushed = call("push", token, JSONObject().put("group_id", result.getString("group_id")).put("snapshot", local))
        saveSyncMark(local, pushed.optLong("updated_at", System.currentTimeMillis()))
        result.getString("code")
    }

    suspend fun join(code: String): String = withContext(Dispatchers.IO) {
        requireConfigured()
        val token = newDeviceToken()
        val result = call("join", token, JSONObject().put("code", code.trim().uppercase()))
        val groupId = result.getString("group_id")
        prefs.edit().putString("groupId", groupId).apply()
        secure.write(token)
        val payload = result.optJSONObject("snapshot")
        if (payload != null) applySnapshot(payload)
        val current = snapshot()
        val serverAt = result.optLong("updated_at", 0L)
        if (payload == null) {
            call("push", token, JSONObject().put("group_id", groupId).put("snapshot", current))
            saveSyncMark(current, System.currentTimeMillis())
        } else {
            saveSyncMark(payload, serverAt)
        }
        "Gerät gekoppelt."
    }

    suspend fun sync(): String = withContext(Dispatchers.IO) {
        requireConfigured()
        val token = secure.read() ?: throw IOException("Dieses Gerät ist noch nicht gekoppelt.")
        val groupId = prefs.getString("groupId", null) ?: throw IOException("Dieses Gerät ist noch nicht gekoppelt.")
        val local = snapshot()
        val baseline = prefs.getString("lastSnapshot", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val remote = call("pull", token, JSONObject().put("group_id", groupId))
        val remoteSnapshot = remote.optJSONObject("snapshot")
        val remoteAt = remote.optLong("updated_at", 0L)
        val merged = when {
            remoteSnapshot == null -> local
            baseline == null -> remoteSnapshot
            else -> mergeSnapshots(baseline, local, remoteSnapshot)
        }
        if (remoteSnapshot == null || !sameJson(merged, remoteSnapshot)) {
            val pushed = call("push", token, JSONObject().put("group_id", groupId).put("snapshot", merged))
            if (!sameJson(merged, local)) applySnapshot(merged)
            saveSyncMark(merged, pushed.optLong("updated_at", System.currentTimeMillis()))
        } else {
            if (!sameJson(merged, local)) applySnapshot(merged)
            saveSyncMark(merged, remoteAt)
        }
        "Synchronisierung abgeschlossen."
    }

    private fun requireConfigured() {
        if (!configured) throw IOException("SUPABASE_URL und SUPABASE_PUBLISHABLE_KEY fehlen im Build.")
    }

    private fun call(action: String, token: String, extra: JSONObject): JSONObject {
        val connection = URL("${BuildConfig.SUPABASE_URL}/functions/v1/couchpotato-sync").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}")
        val body = JSONObject(extra.toString()).put("action", action).put("device_token", token)
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Cloud antwortet mit HTTP $code: ${text.take(240)}")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /** Only app state is included. IPTV credentials, TMDB token and request limits stay local. */
    private fun snapshot(): JSONObject {
        val names = linkedSetOf("profiles", "couchpotato", "mediaplays")
        Profiles.all(app).forEach { p ->
            names += "progress${p.suffix}"
            names += "history${p.suffix}"
        }
        val all = JSONObject()
        for (name in names) {
            val values = JSONObject()
            app.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (excluded(name, key)) return@forEach
                if (value == null) return@forEach
                val safeValue = if (name == "mediaplays" && value is String) {
                    runCatching {
                        JSONObject(value).apply {
                            if (optString("image").startsWith("file://")) remove("image")
                        }.toString()
                    }.getOrDefault(value)
                } else value
                encode(safeValue)?.let { values.put(key, it) }
            }
            all.put(name, values)
        }
        return JSONObject().put("version", 1).put("preferences", all)
    }

    /** Three-way merge avoids one device overwriting unrelated changes made on another. */
    private fun mergeSnapshots(base: JSONObject, local: JSONObject, remote: JSONObject): JSONObject {
        val basePrefs = base.optJSONObject("preferences") ?: JSONObject()
        val localPrefs = local.optJSONObject("preferences") ?: JSONObject()
        val remotePrefs = remote.optJSONObject("preferences") ?: JSONObject()
        val mergedPrefs = JSONObject()
        val files = (basePrefs.keys().asSequence() + localPrefs.keys().asSequence() + remotePrefs.keys().asSequence()).toSet()
        for (file in files) {
            val b = basePrefs.optJSONObject(file) ?: JSONObject()
            val l = localPrefs.optJSONObject(file) ?: JSONObject()
            val r = remotePrefs.optJSONObject(file) ?: JSONObject()
            val merged = JSONObject()
            val keys = (b.keys().asSequence() + l.keys().asSequence() + r.keys().asSequence()).toSet()
            for (key in keys) {
                val baseHas = b.has(key)
                val localHas = l.has(key)
                val remoteHas = r.has(key)
                val baseValue = b.opt(key)
                val localValue = l.opt(key)
                val remoteValue = r.opt(key)
                val localChanged = localHas != baseHas || canonicalJson(localValue) != canonicalJson(baseValue)
                val useLocal = localChanged // when both changed this key, the currently syncing device wins
                val hasChosen = if (useLocal) localHas else remoteHas
                if (hasChosen) merged.put(key, if (useLocal) localValue else remoteValue)
            }
            mergedPrefs.put(file, merged)
        }
        return JSONObject().put("version", 1).put("preferences", mergedPrefs)
    }

    private fun applySnapshot(snapshot: JSONObject) {
        val all = snapshot.optJSONObject("preferences") ?: return
        val names = all.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (name == PREFS || name == "netguard" || name == "tmdbgate") continue
            val values = all.optJSONObject(name) ?: continue
            val p = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val edit = p.edit()
            p.all.keys.filterNot { excluded(name, it) }.forEach(edit::remove)
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (excluded(name, key)) continue
                decode(values.optJSONObject(key))?.let { value ->
                    when (value) {
                        is String -> edit.putString(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        is Long -> edit.putLong(key, value)
                        is Float -> edit.putFloat(key, value)
                        is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }
            edit.apply()
        }
    }

    private fun excluded(file: String, key: String): Boolean = when (file) {
        // which profile is active and the device's start time belong to this TV only
        "profiles" -> key == "pin" || key == "bootedAt" || key == "active"
        "couchpotato" -> key in setOf(
            "url", "user", "pass", "tmdbToken", "syncFailures", "syncAttemptAt", "cloudSyncAttemptAt", "epgAt", "epgTitles",
        )
        else -> false
    }

    private fun encode(value: Any): JSONObject? = when (value) {
        is String -> JSONObject().put("type", "string").put("value", value)
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value)
        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
        is Set<*> -> JSONObject().put("type", "set").put("value", JSONArray(value.filterIsInstance<String>().sorted()))
        else -> null
    }

    private fun decode(value: JSONObject?): Any? {
        value ?: return null
        return when (value.optString("type")) {
            "string" -> value.optString("value")
            "boolean" -> value.optBoolean("value")
            "int" -> value.optInt("value")
            "long" -> value.optLong("value")
            "float" -> value.optDouble("value").toFloat()
            "set" -> value.optJSONArray("value")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            else -> null
        }
    }

    private fun saveSyncMark(snapshot: JSONObject, at: Long) {
        prefs.edit().putString("lastSnapshot", snapshot.toString()).putLong("lastAt", at).apply()
    }

    private fun sameJson(left: JSONObject, right: JSONObject): Boolean = canonicalJson(left) == canonicalJson(right)

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            "${JSONObject.quote(key)}:${canonicalJson(value.opt(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonicalJson(value.opt(it)) }
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun newDeviceToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    private companion object {
        const val PREFS = "cloudsync"
    }
}

/** Encrypt the per-device bearer secret with a non-exportable Android Keystore AES key. */
private class SecureSyncToken(context: Context, private val prefs: SharedPreferences) {
    private val app = context.applicationContext
    private val alias = "couchpotato.cloudsync.token"

    fun read(): String? {
        val encoded = prefs.getString("token", null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return null
            val iv = packed.copyOfRange(0, IV_BYTES)
            val cipherText = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    fun write(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("token", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object { const val IV_BYTES = 12 }
}
