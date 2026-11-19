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
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Household sync over a Supabase Edge Function – end-to-end encrypted.
 *
 * The main TV invents a 10-character household code. From it, each TV derives (slow PBKDF2) two
 * independent keys: an access key (the server keeps only its SHA-256 hash, to find the household)
 * and an encryption key that never leaves the TVs. Everything in the cloud is one AES-256-GCM blob,
 * so the server can't read profiles, progress or – if shared at all – the IPTV login.
 */
class CloudSync(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secure = SecureStore(app, prefs)

    init {
        // pairing of the earlier (not end-to-end encrypted) sync: forget it, a household code replaces it
        if (prefs.contains("groupId")) prefs.edit().clear().apply()
    }

    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
    val linked: Boolean get() = secure.read("keys") != null && secure.read("code") != null
    /** The TV that created the household: only it decides about the IPTV login and can renew the code. */
    val isMain: Boolean get() = linked && prefs.getBoolean("main", false)
    var shareIptv: Boolean
        get() = prefs.getBoolean("shareIptv", false)
        set(value) = prefs.edit().putBoolean("shareIptv", value).apply()

    /** "7KQ2M-X9P4T", or null when not part of a household. */
    /** Key for the phone link on the local network (TvLink): derived from the household's AES key, never sent anywhere. */
    fun linkKey(): ByteArray? = secure.read("keys")?.let { runCatching { HouseholdKeys.decode(it).linkKey() }.getOrNull() }

    fun householdCode(): String? = secure.read("code")?.let { "${it.take(5)}-${it.drop(5)}" }

    fun status(): String = when {
        !configured -> "Supabase ist im Build noch nicht konfiguriert."
        isMain -> "Hauptgerät des Haushalts – Ende-zu-Ende-verschlüsselt."
        linked -> "Mit dem Haushalt verbunden – Ende-zu-Ende-verschlüsselt."
        else -> "Noch in keinem Haushalt."
    }

    /** Result of a sync or join – with the IPTV login if the main TV shares it and this TV needs it. */
    data class Result(val message: String, val iptv: JSONObject?)

    /** This TV becomes the main device of a new household. Returns the code to type on the other TVs. */
    suspend fun createHousehold(): String = withContext(Dispatchers.IO) {
        requireConfigured()
        if (linked) throw IOException("Dieses Gerät ist schon in einem Haushalt.")
        val code = newCode()
        val keys = HouseholdKeys.derive(code)
        val snap = withIptv(snapshot(), null)
        val res = call("create", JSONObject().put("auth", keys.authHex).put("blob", keys.seal(snap)))
        secure.write("code", code)
        secure.write("keys", keys.encoded())
        prefs.edit().putBoolean("main", true).putLong("version", res.getLong("version")).apply()
        saveSyncMark(snap, System.currentTimeMillis())
        householdCode()!!
    }

    /** Joins with the household code. The household's data replaces this TV's profiles & lists. */
    suspend fun join(code: String): Result = withContext(Dispatchers.IO) {
        requireConfigured()
        val clean = normalizeCode(code)
        if (clean.length != CODE_LENGTH) throw IOException("Der Haushalts-Code hat $CODE_LENGTH Zeichen.")
        val keys = HouseholdKeys.derive(clean)
        val res = try {
            call("pull", JSONObject().put("auth", keys.authHex))
        } catch (e: NotFound) {
            throw IOException("Diesen Haushalts-Code gibt es nicht (mehr). Bitte auf dem Hauptgerät nachsehen.")
        }
        val remote = keys.open(res.getString("blob"))
            ?: throw IOException("Der Code passt nicht zu den Daten – bitte noch einmal eingeben.")
        secure.write("code", clean)
        secure.write("keys", keys.encoded())
        prefs.edit().putBoolean("main", false).putBoolean("shareIptv", false).putLong("version", res.getLong("version")).apply()
        applySnapshot(remote)
        saveSyncMark(remote, System.currentTimeMillis())
        Result("Mit dem Haushalt verbunden.", iptvOf(remote))
    }

    suspend fun sync(): Result = withContext(Dispatchers.IO) {
        requireConfigured()
        val keys = HouseholdKeys.decode(secure.read("keys") ?: throw IOException("Dieses Gerät ist noch in keinem Haushalt."))
        val baseline = prefs.getString("lastSnapshot", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        repeat(3) {
            val local = snapshot()
            val res = try {
                call("pull", JSONObject().put("auth", keys.authHex))
            } catch (e: NotFound) {
                throw IOException("Der Haushalts-Code wurde auf dem Hauptgerät erneuert – bitte den neuen Code eingeben.")
            }
            val version = res.getLong("version")
            val remote = keys.open(res.getString("blob")) ?: throw IOException("Cloud-Daten lassen sich nicht entschlüsseln.")
            val mergedPrefs = if (baseline == null) remote else mergeSnapshots(baseline, local, remote)
            val merged = withIptv(mergedPrefs, iptvOf(remote))
            if (!sameJson(merged, remote)) {
                try {
                    call("push", JSONObject().put("auth", keys.authHex).put("blob", keys.seal(merged)).put("base_version", version))
                        .let { prefs.edit().putLong("version", it.getLong("version")).apply() }
                } catch (e: Conflict) {
                    return@repeat   // another TV was faster – fetch its state and merge again
                }
            } else {
                prefs.edit().putLong("version", version).apply()
            }
            if (!sameJson(withIptv(merged, null), withIptv(local, null))) applySnapshot(merged)
            saveSyncMark(merged, System.currentTimeMillis())
            val needsLogin = !SettingsStore(app).config().isValid
            return@withContext Result("Synchronisierung abgeschlossen.", if (needsLogin) iptvOf(merged) else null)
        }
        throw IOException("Andere Geräte synchronisieren gerade – bitte gleich noch einmal versuchen.")
    }

    /** Main TV: new code + new keys. All other TVs have to enter the new code (e.g. after selling a stick). */
    suspend fun renewCode(): String = withContext(Dispatchers.IO) {
        if (!isMain) throw IOException("Nur das Hauptgerät kann den Code erneuern.")
        sync()
        val old = HouseholdKeys.decode(secure.read("keys")!!)
        val snap = JSONObject(prefs.getString("lastSnapshot", null) ?: snapshot().toString())
        val code = newCode()
        val keys = HouseholdKeys.derive(code)
        val res = call("create", JSONObject().put("auth", keys.authHex).put("blob", keys.seal(snap)))
        runCatching { call("delete", JSONObject().put("auth", old.authHex)) }
        secure.write("code", code)
        secure.write("keys", keys.encoded())
        prefs.edit().putLong("version", res.getLong("version")).apply()
        householdCode()!!
    }

    /** Leave the household on this TV. The main TV also deletes the household from the cloud. */
    suspend fun leave(): String = withContext(Dispatchers.IO) {
        if (isMain) secure.read("keys")?.let { k -> runCatching { call("delete", JSONObject().put("auth", HouseholdKeys.decode(k).authHex)) } }
        val wasMain = isMain
        prefs.edit().clear().apply()
        if (wasMain) "Haushalt gelöscht – die Daten in der Cloud sind entfernt." else "Dieses Gerät hat den Haushalt verlassen."
    }

    /** IPTV login inside the encrypted blob: the main TV adds it only while "teilen" is switched on. */
    private fun withIptv(snap: JSONObject, remoteIptv: JSONObject?): JSONObject {
        val out = JSONObject(snap.toString()).apply { remove("iptv") }
        val iptv = if (prefs.getBoolean("main", false)) {
            SettingsStore(app).config().takeIf { shareIptv && it.isValid }
                ?.let { JSONObject().put("url", it.baseUrl).put("user", it.user).put("pass", it.pass) }
        } else remoteIptv
        if (iptv != null) out.put("iptv", iptv)
        return out
    }

    private fun iptvOf(snap: JSONObject): JSONObject? = snap.optJSONObject("iptv")

    fun iptvConfig(o: JSONObject?): XtreamConfig? = o?.let {
        runCatching { XtreamConfig(it.getString("url"), it.getString("user"), it.getString("pass")) }.getOrNull()?.takeIf { c -> c.isValid }
    }

    private fun requireConfigured() {
        if (!configured) throw IOException("SUPABASE_URL und SUPABASE_PUBLISHABLE_KEY fehlen im Build.")
    }

    private class NotFound : IOException("not found")
    private class Conflict : IOException("conflict")

    private fun call(action: String, extra: JSONObject): JSONObject {
        val connection = URL("${BuildConfig.SUPABASE_URL}/functions/v1/couchpotato-sync").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}")
        val body = JSONObject(extra.toString()).put("action", action).put("v", 3)
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            when (code) {
                in 200..299 -> return JSONObject(text)
                404 -> throw NotFound()
                409 -> throw Conflict()
                else -> throw IOException("Cloud antwortet mit HTTP $code: ${text.take(240)}")
            }
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
            names += "watched${p.suffix}"
            names += Watchlist.FILE + p.suffix
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
            // every TV has other apps installed: their order and last use stay on the device
            "appOrder", "appsCustom",
        ) || key.startsWith("launch:")
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

    private companion object {
        const val PREFS = "cloudsync"
        const val CODE_LENGTH = 10
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"   // no I, L, O, U – nothing to mix up

        /** 10 × 5 bits = 50 bits of chance; with the slow key derivation far beyond guessing. */
        fun newCode(): String {
            val bytes = ByteArray(CODE_LENGTH).also(SecureRandom()::nextBytes)
            return bytes.joinToString("") { ALPHABET[it.toInt() and 31].toString() }
        }

        fun normalizeCode(code: String): String = code.uppercase()
            .replace('O', '0').replace('I', '1').replace('L', '1')
            .replace(Regex("[^0-9A-HJKMNP-TV-Z]"), "")
    }
}

/** Small values (household code, keys) encrypted with a non-exportable Android Keystore AES key. */
private class SecureStore(context: Context, private val prefs: SharedPreferences) {
    private val alias = "couchpotato.cloudsync.token"

    fun read(name: String): String? {
        val encoded = prefs.getString("secure_$name", null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("secure_$name", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
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

/**
 * Keys of a household, derived from its code with PBKDF2-HMAC-SHA256 (210 000 rounds, 512 bit):
 * the first half is the access key (server sees only its hash), the second half the AES-256 key.
 * Knowing one half tells nothing about the other.
 */
private class HouseholdKeys(private val auth: ByteArray, private val enc: ByteArray) {
    val authHex: String get() = auth.joinToString("") { "%02x".format(it) }
    private val aad: ByteArray get() = sha256(auth)

    fun encoded(): String = Base64.encodeToString(auth + enc, Base64.NO_WRAP)

    /** JSON → gzip → AES-256-GCM, bound to this household. */
    fun seal(snapshot: JSONObject): String {
        val zipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(snapshot.toString().toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return Base64.encodeToString(byteArrayOf(FORMAT) + iv + cipher.doFinal(zipped), Base64.NO_WRAP)
    }

    fun linkKey(): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(enc, "HmacSHA256"))
        doFinal("couchpotato-link-v1".toByteArray(Charsets.UTF_8))
    }

    fun open(blob: String): JSONObject? = runCatching {
        val packed = Base64.decode(blob, Base64.NO_WRAP)
        require(packed.size > 1 + IV_BYTES && packed[0] == FORMAT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, packed.copyOfRange(1, 1 + IV_BYTES)))
        cipher.updateAAD(aad)
        val zipped = cipher.doFinal(packed.copyOfRange(1 + IV_BYTES, packed.size))
        JSONObject(GZIPInputStream(zipped.inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() })
    }.getOrNull()

    companion object {
        private const val ITERATIONS = 210_000
        private const val IV_BYTES = 12
        private const val FORMAT: Byte = 1

        fun derive(code: String): HouseholdKeys {
            val spec = PBEKeySpec(code.toCharArray(), "couchpotato-household-v1".toByteArray(Charsets.UTF_8), ITERATIONS, 512)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return HouseholdKeys(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
        }

        fun decode(encoded: String): HouseholdKeys {
            val b = Base64.decode(encoded, Base64.NO_WRAP)
            return HouseholdKeys(b.copyOfRange(0, 32), b.copyOfRange(32, 64))
        }

        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    }
}
