package de.koeven.couchpotato.ui

import de.koeven.couchpotato.data.AppEntry
import de.koeven.couchpotato.data.HomeRows
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.MediaPlay
import de.koeven.couchpotato.data.Profile
import de.koeven.couchpotato.data.ProgressEntry
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SeriesItem
import de.koeven.couchpotato.data.VodItem
import de.koeven.couchpotato.data.WatchNextSource

/** One tile in "Weiterschauen": either IPTV (own player) or another app's Watch Next entry. */
sealed interface ContinueItem {
    val key: String
    val sortTime: Long

    data class Iptv(val entry: ProgressEntry, val newEpisodes: Boolean = false, val sort: Long = 0L) : ContinueItem {
        override val key: String get() = entry.item.key
        override val sortTime: Long get() = maxOf(entry.updatedAt, sort)
    }

    data class External(val program: WatchNextSource.Program, val appLabel: String?) : ContinueItem {
        override val key: String get() = program.key
        override val sortTime: Long get() = program.lastEngagement
    }

    /** Something another app reported while playing (ARD Mediathek, Disney+ …). */
    data class Media(val play: MediaPlay, val appLabel: String?) : ContinueItem {
        override val key: String get() = play.key
        override val sortTime: Long get() = play.updatedAt
    }

    /** A series you watched got new episodes since (provider's "last modified" is newer). */
    data class NewEpisodes(val series: SearchEntry, val watchedAt: Long) : ContinueItem {
        override val key: String get() = "new:${series.id}:${series.added}"
        override val sortTime: Long get() = series.added * 1000
    }

    /** Streaming app whose progress we can't read (Netflix …): a tile that opens the app. */
    data class AppTile(val app: AppEntry, val lastUsed: Long) : ContinueItem {
        override val key: String get() = "app:${app.packageName}"
        override val sortTime: Long get() = lastUsed
    }
}

data class HomeState(
    val screenLock: String? = null,      // kids: screen time used up / bedtime
    val screenLockNight: Boolean = false, // "Gute Nacht" version (bedtime, or limit used up from 19:00)
    val continueWatching: List<ContinueItem> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val movies: List<VodItem> = emptyList(),
    val series: List<SeriesItem> = emptyList(),
    val configured: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val tick: Int = 0,          // bumps whenever local data (progress) was re-read
    val externalStatus: String = "",
    val favorites: List<LiveChannel> = emptyList(),
    val nowPlaying: Map<Long, String> = emptyMap(),
    val liveInExternalApp: Boolean = false,
    val indexStatus: String = "",
    val searchApps: List<SearchApp> = emptyList(),
    val rowOrder: List<String> = HomeRows.ALL,
    val hiddenCategories: Set<String> = emptySet(),
    val appsCustomOrder: Boolean = false,
    val syncInfo: String = "",
    val imageInfo: String = "",
    val loadProviderImages: Boolean = true,
    val recommended: List<SearchEntry> = emptyList(),
    val recommendedPersonal: Boolean = false,
    val loadMovieDetails: Boolean = true,
    val tmdbOn: Boolean = false,
    val profile: Profile = Profile(Profile.MAIN, "Ich", 0, kid = false),
    val profiles: List<Profile> = emptyList(),
    val needsProfilePick: Boolean = false,
    val hasPin: Boolean = false,
    val kidCategories: Set<String> = emptySet(),
    val kidApps: Set<String> = emptySet(),
    val allApps: List<AppEntry> = emptyList(),
)

/** An installed app in the "search in other apps" row; [acceptsQuery] = search term is handed over. */
data class SearchApp(val app: AppEntry, val acceptsQuery: Boolean)

