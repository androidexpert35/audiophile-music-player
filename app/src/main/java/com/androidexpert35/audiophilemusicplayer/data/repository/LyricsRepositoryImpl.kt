package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.LyricsCacheDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LyricsCacheEntity
import com.androidexpert35.audiophilemusicplayer.data.mapper.LrcParser
import com.androidexpert35.audiophilemusicplayer.data.remote.api.LrcLibApiService
import com.androidexpert35.audiophilemusicplayer.data.remote.dto.LrcLibLyricsDto
import com.androidexpert35.audiophilemusicplayer.data.repository.LyricsRepositoryImpl.Companion.DURATION_TOLERANCE_SECONDS
import com.androidexpert35.audiophilemusicplayer.data.repository.LyricsRepositoryImpl.Companion.NOT_FOUND_TTL_MS
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.lyrics.Lyrics
import com.androidexpert35.audiophilemusicplayer.domain.repository.LyricsRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * [LyricsRepository] implementation that combines a Room-backed cache with
 * live LRCLIB API lookups to serve synchronized and plain-text lyrics.
 *
 * ### Lookup strategy
 * 1. The local Room cache is checked first; a hit (whether lyrics or a still-fresh
 *    "not found" sentinel) avoids any network call.
 * 2. `/api/get` is queried for an exact metadata match.
 * 3. On a `404` the lookup falls back to `/api/search`, which tolerates the
 *    metadata drift that is normal for local files — a different album title, a
 *    `(Remastered 2011)` suffix, a `feat.` credit, or a duration that differs by
 *    more than the exact matcher allows. Candidates are ranked by identity and
 *    duration proximity in [pickBestMatch].
 * 4. Only a genuine "no match anywhere" outcome writes the not-found sentinel.
 *
 * ### Failure handling
 * Transport errors and non-`404` HTTP statuses are surfaced as [Resource.Error]
 * and are **never** cached. Caching them would make a temporary server-side
 * outage look like a permanent "lyrics unavailable" for every track the user
 * happened to open while it lasted. As a second safety net, not-found sentinels
 * expire after [NOT_FOUND_TTL_MS] so the library re-checks over time.
 *
 * @property lrcLibApiService Retrofit client pointing to `https://lrclib.net/api/`.
 * @property lyricsCacheDao DAO for reading cached lyrics and writing new entries.
 * @property ioDispatcher Injected IO dispatcher for all blocking work.
 * @constructor Created by Hilt via [com.androidexpert35.audiophilemusicplayer.di.RepositoryModule].
 */
@Singleton
class LyricsRepositoryImpl @Inject constructor(
    private val lrcLibApiService: LrcLibApiService,
    private val lyricsCacheDao: LyricsCacheDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LyricsRepository {

    override suspend fun getLyrics(
        trackTitle: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int,
    ): Resource<Lyrics> = withContext(ioDispatcher) {
        val key = buildCacheKey(trackTitle, artistName, albumName, durationSeconds)

        runCatching {
            // 1. Check the local cache — avoids network on both hits and fresh misses.
            lyricsCacheDao.getByKey(key)
                ?.takeUnless { it.isExpiredMiss() }
                ?.let { return@runCatching it.toResource() }

            // 2. Exact lookup, then 3. fuzzy search fallback.
            val match = fetchExactMatch(trackTitle, artistName, albumName, durationSeconds)
                ?: searchBestMatch(trackTitle, artistName, durationSeconds)

            if (match == null) {
                // 4. Cache the "not found" sentinel so future calls skip the network.
                lyricsCacheDao.upsert(buildNotFoundEntity(key))
                return@runCatching Resource.Success(EMPTY_LYRICS)
            }

            lyricsCacheDao.upsert(
                LyricsCacheEntity(
                    cacheKey = key,
                    syncedLyricsRaw = match.syncedLyricsRaw,
                    plainLyrics = match.plainLyrics,
                    isInstrumental = match.isInstrumental,
                    notFound = false,
                    fetchedAtMs = System.currentTimeMillis(),
                )
            )

            Resource.Success(match.toLyrics())
        }.getOrElse { throwable ->
            Resource.Error(
                ResourceError.NetworkError(
                    throwable.message ?: "Lyrics fetch failed"
                )
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Remote lookups
    // ---------------------------------------------------------------------------

    /**
     * Queries `/api/get` for an exact metadata match.
     *
     * @return The matched lyrics, or `null` when LRCLIB has no exact match (`404`)
     *   or the track metadata is too incomplete to query with.
     * @throws IOException on any other non-successful HTTP status, so the caller
     *   reports an error instead of caching a false "no lyrics" result.
     */
    private suspend fun fetchExactMatch(
        trackTitle: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int,
    ): LyricsMatch? {
        // The endpoint rejects a blank artist with a 400; searching by title is
        // the only option for files with no artist tag.
        if (trackTitle.isMetadataUnknown() || artistName.isMetadataUnknown()) return null

        val response = lrcLibApiService.getLyrics(
            artistName = artistName,
            trackName = trackTitle,
            albumName = albumName.takeUnless { it.isMetadataUnknown() }.orEmpty(),
            duration = durationSeconds,
        )
        if (response.code() == NOT_FOUND_CODE) return null
        if (!response.isSuccessful) throw IOException(response.httpErrorMessage("get"))

        val dto = response.body() ?: return null
        return dto.takeIf { it.hasContent() }?.toMatch(keepSynced = true)
    }

    /**
     * Falls back to `/api/search`, which matches loosely enough to survive the
     * metadata drift typical of local files.
     *
     * Two attempts are made: title plus artist, then — if that yields nothing —
     * title alone, which rescues tracks whose artist tag disagrees with LRCLIB's
     * spelling. Both use the noise-stripped title, so `Yellow (Remastered 2011)`
     * is searched as `Yellow`.
     *
     * @return The best-ranked candidate, or `null` when no plausible match exists.
     * @throws IOException on a non-successful, non-`404` HTTP status.
     */
    private suspend fun searchBestMatch(
        trackTitle: String,
        artistName: String,
        durationSeconds: Int,
    ): LyricsMatch? {
        val searchTitle = trackTitle.stripTitleNoise()
        if (searchTitle.isBlank() || trackTitle.isMetadataUnknown()) return null
        val searchArtist = artistName.takeUnless { it.isMetadataUnknown() }?.stripFeaturedArtists()

        val attempts = buildList {
            if (!searchArtist.isNullOrBlank()) add(searchArtist)
            add(null)
        }

        for (artist in attempts) {
            val response = lrcLibApiService.searchLyrics(
                trackName = searchTitle,
                artistName = artist,
            )
            if (response.code() == NOT_FOUND_CODE) continue
            if (!response.isSuccessful) throw IOException(response.httpErrorMessage("search"))

            val best = response.body()
                .orEmpty()
                .pickBestMatch(searchTitle, searchArtist, durationSeconds)
            if (best != null) return best
        }
        return null
    }

    /**
     * Ranks search candidates and converts the winner into a [LyricsMatch].
     *
     * Candidates whose title or artist do not correspond to the local track are
     * discarded outright — showing another song's words is worse than showing
     * none. Among the survivors a synced candidate is preferred, but only when
     * its duration lands within [DURATION_TOLERANCE_SECONDS]: timestamps from a
     * different edition would drift audibly against the local file. Otherwise the
     * closest candidate is returned with its synced track dropped, so the user
     * still gets correct plain lyrics rather than mistimed ones.
     */
    private fun List<LrcLibLyricsDto>.pickBestMatch(
        searchTitle: String,
        searchArtist: String?,
        durationSeconds: Int,
    ): LyricsMatch? {
        val plausible = filter { it.hasContent() && it.matchesIdentity(searchTitle, searchArtist) }
        if (plausible.isEmpty()) return null

        plausible
            .filter {
                !it.syncedLyrics.isNullOrBlank() &&
                    it.durationDeltaTo(durationSeconds) <= DURATION_TOLERANCE_SECONDS
            }
            .minByOrNull { it.durationDeltaTo(durationSeconds) }
            ?.let { return it.toMatch(keepSynced = true) }

        return plausible
            .minByOrNull { it.durationDeltaTo(durationSeconds) }
            ?.toMatch(keepSynced = false)
    }

    // ---------------------------------------------------------------------------
    // Matching helpers
    // ---------------------------------------------------------------------------

    /** `true` when the candidate carries usable lyrics, in any form. */
    private fun LrcLibLyricsDto.hasContent(): Boolean =
        !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank() || instrumental == true

    /**
     * Absolute difference in whole seconds between the candidate and the local
     * file. Candidates with no reported duration sort last.
     */
    private fun LrcLibLyricsDto.durationDeltaTo(durationSeconds: Int): Int =
        duration?.let { abs(it.toInt() - durationSeconds) } ?: Int.MAX_VALUE

    /**
     * `true` when the candidate plausibly *is* the local track.
     *
     * Both sides are normalised before comparison, and containment (rather than
     * equality) is accepted so `Yellow` still matches LRCLIB's
     * `Coldplay - Yellow (Official Video)` entries.
     */
    private fun LrcLibLyricsDto.matchesIdentity(searchTitle: String, searchArtist: String?): Boolean {
        val candidateTitle = trackName?.normalizeForMatch().orEmpty()
        val expectedTitle = searchTitle.normalizeForMatch()
        if (expectedTitle.isBlank() || candidateTitle.isBlank()) return false
        if (!candidateTitle.overlapsWith(expectedTitle)) return false

        val expectedArtist = searchArtist?.normalizeForMatch()
        if (expectedArtist.isNullOrBlank()) return true
        val candidateArtist = artistName?.normalizeForMatch().orEmpty()
        return candidateArtist.isNotBlank() && candidateArtist.overlapsWith(expectedArtist)
    }

    /** `true` when either normalised string contains the other. */
    private fun String.overlapsWith(other: String): Boolean =
        contains(other) || other.contains(this)

    /**
     * Reduces a tag value to comparable form: lowercase, accent-insensitive
     * punctuation removed, whitespace collapsed.
     */
    private fun String.normalizeForMatch(): String = lowercase()
        .replace(NON_ALPHANUMERIC_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    /**
     * Strips edition/version decoration that local tags carry but LRCLIB titles
     * usually do not — `(Remastered 2011)`, `[Live]`, `- 2009 Remaster`,
     * `feat. Someone`, and the `Pt.` in numbered series titles.
     */
    private fun String.stripTitleNoise(): String = this
        .replace(TITLE_BRACKET_NOISE_REGEX, " ")
        .replace(TITLE_DASH_NOISE_REGEX, "")
        .stripFeaturedArtists()
        .replace(PART_NUMBER_REGEX, "")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    /** Drops a trailing `feat.` / `ft.` / `featuring` credit. */
    private fun String.stripFeaturedArtists(): String =
        replace(FEATURED_ARTIST_REGEX, "").trim()

    /** `true` for blank values and the scanner's unknown-metadata sentinels. */
    private fun String.isMetadataUnknown(): Boolean =
        isBlank() || UNKNOWN_METADATA_VALUES.any { it.equals(trim(), ignoreCase = true) }

    // ---------------------------------------------------------------------------
    // Cache helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds a stable, lowercase lookup key from the four track-identity fields.
     *
     * Lowercasing normalises minor capitalisation differences between MediaStore
     * metadata and LRCLIB's search results.
     */
    private fun buildCacheKey(
        trackTitle: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int,
    ): String = "${trackTitle.lowercase()}|${artistName.lowercase()}|" +
        "${albumName.lowercase()}|$durationSeconds"

    /**
     * Builds a sentinel cache entity representing a known-missing lyrics entry.
     *
     * Cached "not found" rows are served instantly on future requests for the same
     * key, eliminating redundant network round-trips.
     */
    private fun buildNotFoundEntity(key: String) = LyricsCacheEntity(
        cacheKey = key,
        syncedLyricsRaw = null,
        plainLyrics = null,
        isInstrumental = false,
        notFound = true,
        fetchedAtMs = System.currentTimeMillis(),
    )

    /**
     * `true` for a not-found sentinel old enough to be worth re-checking.
     *
     * LRCLIB is community-contributed, so a track with no lyrics today may well
     * have them next month; this also lets the cache heal itself after any
     * lookup regression. Positive hits never expire.
     */
    private fun LyricsCacheEntity.isExpiredMiss(): Boolean =
        notFound && System.currentTimeMillis() - fetchedAtMs > NOT_FOUND_TTL_MS

    /** Converts a cached entity back into a [Resource]. */
    private fun LyricsCacheEntity.toResource(): Resource<Lyrics> {
        if (notFound) return Resource.Success(EMPTY_LYRICS)
        return Resource.Success(
            LyricsMatch(
                syncedLyricsRaw = syncedLyricsRaw,
                plainLyrics = plainLyrics,
                isInstrumental = isInstrumental,
            ).toLyrics()
        )
    }

    // ---------------------------------------------------------------------------
    // Internal model
    // ---------------------------------------------------------------------------

    /**
     * A resolved lyrics payload, decoupled from the DTO so a candidate can be
     * accepted for its plain lyrics while its (mistimed) synced track is dropped.
     *
     * @property syncedLyricsRaw Raw LRC string to parse, or `null` when unusable.
     * @property plainLyrics Unformatted lyrics text, or `null`.
     * @property isInstrumental `true` when LRCLIB reports the track has no vocals.
     */
    private data class LyricsMatch(
        val syncedLyricsRaw: String?,
        val plainLyrics: String?,
        val isInstrumental: Boolean,
    ) {
        /** Parses the LRC payload into the domain model. */
        fun toLyrics() = Lyrics(
            lines = syncedLyricsRaw
                ?.takeIf { it.isNotBlank() }
                ?.let { LrcParser.parse(it) }
                ?: emptyList(),
            plainLyrics = plainLyrics,
            isInstrumental = isInstrumental,
        )
    }

    /**
     * @param keepSynced `false` to discard the synced track, used when the
     *   candidate's duration is too far from the local file for its timestamps
     *   to line up.
     */
    private fun LrcLibLyricsDto.toMatch(keepSynced: Boolean) = LyricsMatch(
        syncedLyricsRaw = syncedLyrics.takeIf { keepSynced },
        plainLyrics = plainLyrics,
        isInstrumental = instrumental ?: false,
    )

    /** Builds a diagnostic message for a failed LRCLIB call. */
    private fun retrofit2.Response<*>.httpErrorMessage(endpoint: String): String =
        "LRCLIB /$endpoint failed with HTTP ${code()}"

    private companion object {
        /** HTTP status code returned by LRCLIB when no track match was found. */
        const val NOT_FOUND_CODE = 404

        /**
         * Maximum duration difference, in seconds, for synced lyrics to be trusted
         * from a fuzzy search result. Beyond this the LRC timestamps belong to a
         * different edition and would visibly drift against the local file.
         */
        const val DURATION_TOLERANCE_SECONDS = 6

        /** How long a cached "not found" result stays authoritative. */
        val NOT_FOUND_TTL_MS = TimeUnit.DAYS.toMillis(14)

        /** Shared sentinel value returned for both cached and live "not found" outcomes. */
        val EMPTY_LYRICS = Lyrics(lines = emptyList(), plainLyrics = null, isInstrumental = false)

        /** Placeholder values written by the scanners when a tag is missing. */
        val UNKNOWN_METADATA_VALUES = listOf("<unknown>", "unknown", "unknown artist", "unknown album")

        /** Bracketed edition/version decoration, e.g. `(Remastered 2011)`, `[Live]`. */
        val TITLE_BRACKET_NOISE_REGEX = Regex(
            """\s*[(\[][^)\]]*\b(remaster(ed)?|remix|live|version|edit|mono|stereo|deluxe|bonus|anniversary|explicit|official\s+video|audio)\b[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE
        )

        /** Dash-separated edition suffix, e.g. `Yellow - 2009 Remaster`. */
        val TITLE_DASH_NOISE_REGEX = Regex(
            """\s+-\s+[^-]*\b(remaster(ed)?|remix|live|version|edit|mono|stereo|deluxe|bonus|anniversary)\b.*$""",
            RegexOption.IGNORE_CASE
        )

        /** Trailing featured-artist credit, bracketed or not. */
        val FEATURED_ARTIST_REGEX = Regex(
            """\s*[(\[]?\b(feat\.?|ft\.?|featuring)\b.*$""",
            RegexOption.IGNORE_CASE
        )

        /**
         * The `Pt.` / `Part` / `Parte` marker in a numbered series title, kept only
         * when a number follows so ordinary words like `Part of Me` survive.
         *
         * LRCLIB indexes these series by bare number — `Veleno 7`, not
         * `Veleno pt.7` — and its search returns *zero* results for the spelled-out
         * form, so dropping the marker is what makes the track findable at all.
         */
        val PART_NUMBER_REGEX = Regex(
            """\b(?:parte|part|pt)\.?\s*(?=\d)""",
            RegexOption.IGNORE_CASE
        )

        /** Everything that is not a letter or digit, in any script. */
        val NON_ALPHANUMERIC_REGEX = Regex("""[^\p{L}\p{N}]+""")

        /** Runs of whitespace to collapse into a single space. */
        val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
