package de.koeven.couchpotato.player

/**
 * Sleep timer of the player: off → 15 → 30 → 60 → 90 min (→ "nach dieser Folge" for series) → off.
 * Pure logic, no Android – so it can be tested.
 */
class SleepTimer(private val clock: () -> Long = System::currentTimeMillis) {
    /** Wall clock when playback stops, 0 = off. */
    var at = 0L; private set
    /** Stop at the end of the current episode. */
    var endOfEpisode = false

    val active: Boolean get() = endOfEpisode || at > 0

    fun label(): String? = when {
        endOfEpisode -> "☾ nach dieser Folge"
        at > 0 -> "☾ ${minutesLeft()} Min."
        else -> null
    }

    /** Minutes left, rounded up: "15 Min." right after setting 15. */
    private fun minutesLeft(): Long = ((at - clock() + 59_999) / 60_000).coerceAtLeast(1)

    private var step = 0                    // the step that was picked last

    /**
     * Moves to the next step and returns it: minutes, 0 = off, -1 = after this episode.
     * A running timer counts as the step it was set to, so pressing again goes to the next longer one.
     */
    fun cycle(episode: Boolean): Int {
        val steps = buildList {
            add(0); add(15); add(30); add(60); add(90)
            if (episode) add(-1)
        }
        val current = if (active) step else 0
        val idx = steps.indexOf(current).coerceAtLeast(0)
        val next = steps[(idx + 1) % steps.size]
        step = next
        endOfEpisode = next == -1
        at = if (next > 0) clock() + next * 60_000L else 0L
        return next
    }

    /** True once when the time is up (the timer is switched off then). */
    fun due(): Boolean {
        if (at in 1..clock()) { at = 0; return true }
        return false
    }

    companion object {
        fun message(step: Int) = when {
            step == -1 -> "Sleep-Timer: nach dieser Folge"
            step > 0 -> "Sleep-Timer: $step Minuten"
            else -> "Sleep-Timer aus"
        }
    }
}
