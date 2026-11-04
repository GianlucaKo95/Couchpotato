package de.koeven.couchpotato.ui

import de.koeven.couchpotato.data.Profile
import de.koeven.couchpotato.data.Profiles
import de.koeven.couchpotato.data.ScreenTime
import kotlinx.coroutines.launch

/** Profiles, PIN, kids' releases, screen time and the FSK check before playing. */
class ProfilesController(
    private val ctx: HomeContext,
    private val catalog: CatalogController,
    private val library: LibraryController,
    private val search: SearchController,
) {
    private val app get() = ctx.app

    sealed interface Gate {
        data object Allowed : Gate
        data object Checking : Gate
        data class Blocked(val reason: String) : Gate
    }

    /** Start of the app: profiles into the state, "Wer schaut?" after every start of the stick. */
    fun init() {
        val profiles = Profiles.all(app)
        ctx.update {
            it.copy(
                profile = Profiles.active(app),
                profiles = profiles,
                needsProfilePick = Profiles.firstStartSinceBoot(app) && profiles.size > 1,
                hasPin = Profiles.hasPin(app),
                kidCategories = Profiles.kidCategories(app),
                kidApps = Profiles.kidApps(app),
            )
        }
    }

    fun refresh() {
        ctx.update {
            it.copy(
                profile = Profiles.active(app),
                profiles = Profiles.all(app),
                hasPin = Profiles.hasPin(app),
                kidCategories = Profiles.kidCategories(app),
                kidApps = Profiles.kidApps(app),
            )
        }
    }

    /** Switches profile: own "Weiterschauen", history, favourites; kids see only what is released. */
    fun switchTo(id: String) {
        Profiles.setActive(app, id)
        ctx.reloadStores()
        refresh()
        ctx.update {
            it.copy(
                needsProfilePick = false,
                hiddenCategories = ctx.hidden(),
                favorites = ctx.settings.favorites(),
                movies = emptyList(), series = emptyList(), recommended = emptyList(),
            )
        }
        search.reset()
        ctx.scope.launch {
            ctx.refreshLocal()
            catalog.recomputeRows()
        }
    }

    fun showPicker() = ctx.update { it.copy(needsProfilePick = true) }
    fun closePicker() = ctx.update { it.copy(needsProfilePick = false) }

    fun add(name: String, kid: Boolean): Profile {
        val p = Profiles.add(app, name, kid)
        if (kid && Profiles.kidCategories(app).isEmpty()) suggestKidCategories()
        refresh()
        return p
    }

    fun update(p: Profile) {
        Profiles.update(app, p)
        refresh()
        if (p.id == Profiles.active(app).id) switchTo(p.id)
    }

    fun remove(id: String) {
        val wasActive = Profiles.active(app).id == id
        Profiles.remove(app, id)
        refresh()
        if (wasActive) switchTo(Profile.MAIN)
    }

    fun setPin(pin: String) {
        Profiles.setPin(app, pin)
        refresh()
    }

    fun checkPin(pin: String): Boolean = Profiles.hasPin(app) && Profiles.checkPin(app, pin)

    /** PIN needed to leave a kids profile (and to open the settings there). */
    fun pinNeeded(): Boolean = Profiles.active(app).kid && Profiles.hasPin(app)

    fun setKidCategory(key: String, allowed: Boolean) {
        Profiles.setKidCategory(app, key, allowed)
        refresh()
        if (Profiles.active(app).kid) library.applyCategoryChanges()
    }

    fun setKidApp(pkg: String, allowed: Boolean) {
        Profiles.setKidApp(app, pkg, allowed)
        refresh()
        ctx.refreshLocalLater()
    }

    /** First kids profile: pre-select categories whose names sound like kids' content. */
    private fun suggestKidCategories() {
        for ((k, list) in ctx.categoriesByKind) {
            list.filter { Profiles.looksLikeKids(it.name) }.forEach { Profiles.setKidCategory(app, "$k:${it.id}", true) }
        }
    }

    /**
     * May the active profile play this title? Adults: always. Kids: only with a known FSK up to
     * the profile's limit – or if a parent released the title with the PIN.
     */
    fun gate(kind: Char, id: Long, fsk: String?, detailsLoaded: Boolean): Gate {
        val p = Profiles.active(app)
        return decide(p.kid, p.kid && "$kind:$id" in Profiles.unlocked(app), detailsLoaded, fsk, p.maxFsk)
    }

    fun unlockTitle(kind: Char, id: Long) = Profiles.unlock(app, "$kind:$id")

    // ---------------- Bildschirmzeit (Kinder) ----------------

    /** Kids profile: daily screen time and bedtime. */
    fun checkScreenTime() {
        val st = ScreenTime.state(app)
        val msg = when (st) {
            is ScreenTime.State.LimitReached ->
                if (st.evening) "Jetzt ist Schlafenszeit. Gute Nacht und schlaf schön!"
                else "Die Bildschirmzeit für heute ist aufgebraucht. Morgen geht's weiter!"
            is ScreenTime.State.Bedtime -> "Ab ${st.at} Uhr ist Schlafenszeit. Gute Nacht!"
            is ScreenTime.State.Allowed -> null
        }
        val night = st is ScreenTime.State.Bedtime || (st is ScreenTime.State.LimitReached && st.evening)
        if (ctx.state.value.screenLock != msg) ctx.update { it.copy(screenLock = msg, screenLockNight = night) }
    }

    fun extendScreenTime(minutes: Int) {
        ScreenTime.addBonus(app, Profiles.active(app), minutes)
        checkScreenTime()
    }

    /** "heute 45 Min. geschaut" for the settings. */
    fun screenTimeInfo(p: Profile): String {
        val used = ScreenTime.usedMs(app, p) / 60_000
        return "heute $used Min. geschaut"
    }

    companion object {
        /** The FSK rule on its own: unknown FSK counts as blocked for kids, a PIN release always wins. */
        fun decide(kid: Boolean, unlocked: Boolean, detailsLoaded: Boolean, fsk: String?, maxFsk: Int): Gate {
            if (!kid) return Gate.Allowed
            if (unlocked) return Gate.Allowed
            if (!detailsLoaded) return Gate.Checking
            val age = fsk?.toIntOrNull() ?: return Gate.Blocked("FSK unbekannt")
            return if (age <= maxFsk) Gate.Allowed else Gate.Blocked("FSK $age")
        }
    }
}
