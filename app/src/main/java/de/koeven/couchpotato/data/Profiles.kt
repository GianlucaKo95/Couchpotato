package de.koeven.couchpotato.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A viewer profile. Kids profiles only see released categories and titles up to [maxFsk]. */
data class Profile(
    val id: String,
    val name: String,
    val color: Int,          // index into the avatar colours
    val kid: Boolean,
    val maxFsk: Int = 6,     // 0, 6, 12, 16
) {
    /** Suffix for this profile's own data ("" for the first profile = the data from before profiles). */
    val suffix: String get() = if (id == MAIN) "" else "_$id"

    companion object {
        const val MAIN = "main"
        val FSK_STEPS = listOf(0, 6, 12, 16)
    }
}

/**
 * Profiles like on Netflix: each has its own "Weiterschauen", history ("Für dich"), favourites
 * and searches. Shared: login, catalogue, apps, hidden categories.
 * Kids: only categories/apps released for kids; leaving the profile and the settings need the parents' PIN.
 */
object Profiles {
    private const val PREFS = "profiles"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Profile> {
        val raw = prefs(context).getString("list", null)
        val list = runCatching {
            val arr = JSONArray(raw ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(o.getString("id"), o.getString("name"), o.optInt("color"), o.optBoolean("kid"), o.optInt("maxFsk", 6))
            }
        }.getOrDefault(emptyList())
        return list.ifEmpty { listOf(Profile(Profile.MAIN, "Ich", 0, kid = false)) }
    }

    private fun saveAll(context: Context, list: List<Profile>) {
        val arr = JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("kid", it.kid).put("maxFsk", it.maxFsk)
        })
        prefs(context).edit().putString("list", arr.toString()).apply()
    }

    fun active(context: Context): Profile {
        val all = all(context)
        val id = prefs(context).getString("active", null)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun setActive(context: Context, id: String) = prefs(context).edit().putString("active", id).apply()

    /** Data suffix of the active profile (used by the stores). */
    fun suffix(context: Context): String = active(context).suffix

    fun add(context: Context, name: String, kid: Boolean): Profile {
        val list = all(context)
        val p = Profile(
            id = "p" + System.currentTimeMillis().toString(36),
            name = name.trim().ifEmpty { if (kid) "Kinder" else "Profil ${list.size + 1}" },
            color = (list.maxOfOrNull { it.color } ?: 0) + 1,
            kid = kid,
        )
        saveAll(context, list + p)
        return p
    }

    fun update(context: Context, profile: Profile) =
        saveAll(context, all(context).map { if (it.id == profile.id) profile else it })

    /** The first profile can't be removed (it holds the data from before profiles existed). */
    fun remove(context: Context, id: String) {
        if (id == Profile.MAIN) return
        saveAll(context, all(context).filterNot { it.id == id })
        if (prefs(context).getString("active", null) == id) setActive(context, Profile.MAIN)
        // its own data
        for (name in listOf("progress", "history")) {
            context.deleteSharedPreferences("${name}_$id")
        }
    }

    // ---------------- parents' PIN ----------------

    fun hasPin(context: Context): Boolean = prefs(context).getString("pin", null) != null

    fun setPin(context: Context, pin: String) = prefs(context).edit().putString("pin", hash(pin)).apply()

    fun checkPin(context: Context, pin: String): Boolean = prefs(context).getString("pin", null) == hash(pin)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("couchpotato:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---------------- what kids may see ----------------

    /** Categories released for kids, as "m:<id>", "s:<id>", "l:<id>". */
    fun kidCategories(context: Context): Set<String> = prefs(context).getStringSet("kidCats", null)?.toSet() ?: emptySet()

    fun setKidCategory(context: Context, key: String, allowed: Boolean) {
        val set = kidCategories(context).toMutableSet()
        if (allowed) set += key else set -= key
        prefs(context).edit().putStringSet("kidCats", set).apply()
    }

    fun kidApps(context: Context): Set<String> = prefs(context).getStringSet("kidApps", null)?.toSet() ?: emptySet()

    fun setKidApp(context: Context, pkg: String, allowed: Boolean) {
        val set = kidApps(context).toMutableSet()
        if (allowed) set += pkg else set -= pkg
        prefs(context).edit().putStringSet("kidApps", set).apply()
    }

    /** Titles a parent released with the PIN although their FSK is unknown/too high ("m:<id>"). */
    fun unlocked(context: Context): Set<String> = prefs(context).getStringSet("unlocked", null)?.toSet() ?: emptySet()

    fun unlock(context: Context, key: String) =
        prefs(context).edit().putStringSet("unlocked", unlocked(context) + key).apply()

    /** Category names that sound like kids' content – suggested when the first kids profile is created. */
    private val KID_WORDS = Regex(
        """kid|kinder|child|family|familie|animation|anime|cartoon|zeichentrick|disney|pixar|dreamworks|junior|toggo|kika|nick""",
        RegexOption.IGNORE_CASE,
    )

    fun looksLikeKids(categoryName: String): Boolean = KID_WORDS.containsMatchIn(categoryName)
}
