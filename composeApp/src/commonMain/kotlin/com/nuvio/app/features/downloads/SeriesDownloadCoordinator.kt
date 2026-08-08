package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_series_already_running
import nuvio.composeapp.generated.resources.downloads_series_finished
import nuvio.composeapp.generated.resources.downloads_series_finished_with_failures
import nuvio.composeapp.generated.resources.downloads_series_no_episodes
import nuvio.composeapp.generated.resources.downloads_series_started
import nuvio.composeapp.generated.resources.downloads_series_started_show
import org.jetbrains.compose.resources.getString
import kotlin.coroutines.coroutineContext

/** What a bulk download covers. */
enum class SeriesDownloadScope {
    /** Every episode of the season the chosen stream belongs to. */
    Season,

    /** Every released episode of the show, specials included. */
    EntireShow,
}

/**
 * Live progress of a bulk download's *resolution* phase — finding a source for each episode. Once
 * an episode is enqueued its byte progress is tracked by [DownloadsRepository] like any other
 * download; this only reports the search that happens first.
 */
data class SeriesDownloadProgress(
    val parentMetaId: String,
    val title: String,
    val scope: SeriesDownloadScope,
    val totalEpisodes: Int,
    val processedEpisodes: Int = 0,
    val enqueuedEpisodes: Int = 0,
    val skippedEpisodes: Int = 0,
    val failedEpisodes: List<String> = emptyList(),
    val currentEpisodeLabel: String? = null,
    val isFinished: Boolean = false,
) {
    val progressFraction: Float
        get() = if (totalEpisodes <= 0) 0f else (processedEpisodes.toFloat() / totalEpisodes).coerceIn(0f, 1f)
}

data class SeriesDownloadUiState(
    val jobs: Map<String, SeriesDownloadProgress> = emptyMap(),
) {
    val activeJobs: List<SeriesDownloadProgress> get() = jobs.values.filterNot { it.isFinished }

    fun progressFor(parentMetaId: String): SeriesDownloadProgress? = jobs[parentMetaId]
}

/** Outcome of asking to download a season/show, reported to the user as a toast. */
enum class SeriesDownloadStartResult {
    Started,
    AlreadyRunning,
    NoEpisodesFound,
    NothingToDownload;

    fun toastMessage(downloadScope: SeriesDownloadScope): String = runBlocking {
        when (this@SeriesDownloadStartResult) {
            Started -> when (downloadScope) {
                SeriesDownloadScope.Season -> getString(Res.string.downloads_series_started)
                SeriesDownloadScope.EntireShow -> getString(Res.string.downloads_series_started_show)
            }
            AlreadyRunning -> getString(Res.string.downloads_series_already_running)
            NoEpisodesFound, NothingToDownload -> getString(Res.string.downloads_series_no_episodes)
        }
    }
}

/**
 * Completion summary. Called off the composition, so resources are resolved the same way
 * [DownloadEnqueueResult.toastMessage] does.
 */
fun SeriesDownloadProgress.completionToastMessage(): String = runBlocking {
    if (failedEpisodes.isEmpty()) {
        getString(Res.string.downloads_series_finished, title, enqueuedEpisodes)
    } else {
        getString(
            Res.string.downloads_series_finished_with_failures,
            title,
            enqueuedEpisodes,
            failedEpisodes.size,
        )
    }
}

/**
 * Downloads every episode of a season or show.
 *
 * The user picks one stream for one episode; that release is the template. For each remaining
 * episode the coordinator searches sources and prefers a stream from the same binge group — the
 * Stremio-native marker that two files come from the same release — so a season downloads at a
 * consistent quality instead of mixing encodes. When no binge-group match exists it falls back to
 * the configured auto-play rules, then to any downloadable stream.
 *
 * Episodes are resolved one at a time. Source lookups fan out across every addon and plugin, and
 * debrid resolution is rate limited per provider, so resolving a 20-episode season in parallel
 * reliably trips provider throttling.
 */
object SeriesDownloadCoordinator {
    private const val MaxCandidatesPerEpisode = 4

    private val log = Logger.withTag("SeriesDownloads")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(SeriesDownloadUiState())
    val uiState: StateFlow<SeriesDownloadUiState> = _uiState.asStateFlow()

    private val runningJobs = mutableMapOf<String, Job>()

    fun isRunning(parentMetaId: String): Boolean = runningJobs[parentMetaId]?.isActive == true

    /**
     * Starts resolving and enqueueing episodes in the background. Returns immediately; watch
     * [uiState] for progress.
     */
    fun downloadSeries(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seedStream: StreamItem,
        seedSeasonNumber: Int,
        seedEpisodeNumber: Int,
        downloadScope: SeriesDownloadScope,
        onFinished: (SeriesDownloadProgress) -> Unit = {},
    ): SeriesDownloadStartResult {
        if (isRunning(parentMetaId)) return SeriesDownloadStartResult.AlreadyRunning

        DownloadsRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()

        val job = scope.launch {
            runSeriesDownload(
                contentType = contentType,
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                title = title,
                logo = logo,
                poster = poster,
                background = background,
                seedStream = seedStream,
                seedSeasonNumber = seedSeasonNumber,
                seedEpisodeNumber = seedEpisodeNumber,
                downloadScope = downloadScope,
                onFinished = onFinished,
            )
        }
        runningJobs[parentMetaId] = job
        job.invokeOnCompletion { runningJobs.remove(parentMetaId) }
        return SeriesDownloadStartResult.Started
    }

    fun cancel(parentMetaId: String) {
        runningJobs.remove(parentMetaId)?.cancel()
        _uiState.update { state ->
            val existing = state.jobs[parentMetaId] ?: return@update state
            state.copy(jobs = state.jobs + (parentMetaId to existing.copy(isFinished = true, currentEpisodeLabel = null)))
        }
    }

    /** Drops a finished job from the state so its banner disappears once acknowledged. */
    fun dismiss(parentMetaId: String) {
        _uiState.update { state -> state.copy(jobs = state.jobs - parentMetaId) }
    }

    private suspend fun runSeriesDownload(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seedStream: StreamItem,
        seedSeasonNumber: Int,
        seedEpisodeNumber: Int,
        downloadScope: SeriesDownloadScope,
        onFinished: (SeriesDownloadProgress) -> Unit,
    ) {
        val meta = MetaDetailsRepository.peek(parentMetaType, parentMetaId)
            ?: MetaDetailsRepository.fetch(parentMetaType, parentMetaId)

        val episodes = meta.episodesForScope(downloadScope, seedSeasonNumber)
        if (episodes.isEmpty()) {
            log.w { "no episodes resolved for $parentMetaId scope=$downloadScope" }
            publishFinished(
                SeriesDownloadProgress(
                    parentMetaId = parentMetaId,
                    title = title,
                    scope = downloadScope,
                    totalEpisodes = 0,
                    isFinished = true,
                ),
                onFinished,
            )
            return
        }

        var progress = SeriesDownloadProgress(
            parentMetaId = parentMetaId,
            title = title,
            scope = downloadScope,
            totalEpisodes = episodes.size,
        )
        publish(progress)

        val selectionContext = buildSelectionContext(seedStream)

        for (video in episodes) {
            coroutineContext.ensureActive()

            val season = video.season
            val episode = video.episode
            if (season == null || episode == null) continue
            val label = episodeLabel(season, episode)
            progress = progress.copy(currentEpisodeLabel = label)
            publish(progress)

            val outcome = downloadEpisode(
                contentType = contentType,
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                title = title,
                logo = logo,
                poster = poster,
                background = background,
                video = video,
                season = season,
                episode = episode,
                // The episode the user picked a stream for needs no search — reuse their choice.
                seedStream = seedStream.takeIf { season == seedSeasonNumber && episode == seedEpisodeNumber },
                selectionContext = selectionContext,
            )

            progress = progress.copy(
                processedEpisodes = progress.processedEpisodes + 1,
                enqueuedEpisodes = progress.enqueuedEpisodes + if (outcome == EpisodeOutcome.Enqueued) 1 else 0,
                skippedEpisodes = progress.skippedEpisodes + if (outcome == EpisodeOutcome.Skipped) 1 else 0,
                failedEpisodes = if (outcome == EpisodeOutcome.Failed) {
                    progress.failedEpisodes + label
                } else {
                    progress.failedEpisodes
                },
            )
            publish(progress)
        }

        publishFinished(progress.copy(currentEpisodeLabel = null, isFinished = true), onFinished)
    }

    private enum class EpisodeOutcome { Enqueued, Skipped, Failed }

    private suspend fun downloadEpisode(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        video: MetaVideo,
        season: Int,
        episode: Int,
        seedStream: StreamItem?,
        selectionContext: SelectionContext,
    ): EpisodeOutcome {
        if (DownloadsRepository.hasDownloadFor(parentMetaId, season, episode)) {
            return EpisodeOutcome.Skipped
        }

        val videoId = video.id.takeIf { it.isNotBlank() }
            ?: buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = season,
                episodeNumber = episode,
                fallbackVideoId = null,
            )

        val candidates = if (seedStream != null) {
            listOf(seedStream)
        } else {
            val streams = DownloadsEpisodeStreamFetcher.fetchStreams(
                type = contentType,
                videoId = videoId,
                season = season,
                episode = episode,
            )
            orderCandidates(streams, selectionContext)
        }

        if (candidates.isEmpty()) {
            log.w { "no stream candidates for $parentMetaId ${episodeLabel(season, episode)}" }
            return EpisodeOutcome.Failed
        }

        for (candidate in candidates.take(MaxCandidatesPerEpisode)) {
            coroutineContext.ensureActive()

            // Torrent/debrid entries carry no direct URL until resolved against the provider.
            val playable = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(candidate)) {
                when (val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(candidate, season, episode)) {
                    is DirectDebridPlayableResult.Success -> resolved.stream
                    else -> {
                        log.d { "debrid resolve failed ${episodeLabel(season, episode)} result=$resolved" }
                        continue
                    }
                }
            } else {
                candidate
            }

            // enqueueFromStream owns the rules about what is actually downloadable (HTTP only, no
            // HLS/DASH/magnet), so let it be the judge rather than duplicating them here.
            val result = DownloadsRepository.enqueueFromStream(
                contentType = contentType,
                videoId = videoId,
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                title = title,
                logo = logo,
                poster = poster,
                background = background,
                seasonNumber = season,
                episodeNumber = episode,
                episodeTitle = video.title.takeIf { it.isNotBlank() },
                episodeThumbnail = video.thumbnail,
                stream = playable,
            )

            when (result) {
                DownloadEnqueueResult.Started, DownloadEnqueueResult.Replaced -> return EpisodeOutcome.Enqueued
                DownloadEnqueueResult.MissingUrl, DownloadEnqueueResult.UnsupportedFormat -> continue
            }
        }

        log.w { "no downloadable candidate for $parentMetaId ${episodeLabel(season, episode)}" }
        return EpisodeOutcome.Failed
    }

    /**
     * Candidate order: same binge group as the user's pick first, then whatever the auto-play rules
     * would have chosen, then everything else as a last resort.
     */
    private fun orderCandidates(
        streams: List<StreamItem>,
        context: SelectionContext,
    ): List<StreamItem> {
        if (streams.isEmpty()) return emptyList()

        val bingeGroupMatch = context.preferredBingeGroup?.let {
            context.select(streams, bingeGroupOnly = true)
        }
        val autoPlayMatch = context.select(streams, bingeGroupOnly = false)

        return (listOfNotNull(bingeGroupMatch, autoPlayMatch) + streams).distinct()
    }

    private fun buildSelectionContext(seedStream: StreamItem): SelectionContext {
        val settings = PlayerSettingsRepository.uiState.value
        val debridSettings = DebridSettingsRepository.snapshot()
        return SelectionContext(
            preferredBingeGroup = seedStream.behaviorHints.bingeGroup?.trim()?.takeIf { it.isNotBlank() },
            // The user explicitly asked for a bulk download, so MANUAL — which normally means "show
            // me a picker" — degrades to taking the first acceptable stream instead of giving up.
            mode = settings.streamAutoPlayMode.takeIf { it != StreamAutoPlayMode.MANUAL }
                ?: StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = settings.streamAutoPlayRegex,
            source = settings.streamAutoPlaySource,
            selectedAddons = settings.streamAutoPlaySelectedAddons,
            selectedPlugins = settings.streamAutoPlaySelectedPlugins,
            installedAddonNames = AddonRepository.uiState.value.addons
                .enabledAddons()
                .map { it.displayTitle }
                .toSet(),
            debridEnabled = debridSettings.canResolvePlayableLinks,
            activeResolverProviderId = debridSettings.activeResolverProviderId,
        )
    }

    private fun publish(progress: SeriesDownloadProgress) {
        _uiState.update { state ->
            state.copy(jobs = state.jobs + (progress.parentMetaId to progress))
        }
    }

    private fun publishFinished(
        progress: SeriesDownloadProgress,
        onFinished: (SeriesDownloadProgress) -> Unit,
    ) {
        publish(progress)
        onFinished(progress)
    }
}

private data class SelectionContext(
    val preferredBingeGroup: String?,
    val mode: StreamAutoPlayMode,
    val regexPattern: String,
    val source: StreamAutoPlaySource,
    val selectedAddons: Set<String>,
    val selectedPlugins: Set<String>,
    val installedAddonNames: Set<String>,
    val debridEnabled: Boolean,
    val activeResolverProviderId: String?,
) {
    fun select(streams: List<StreamItem>, bingeGroupOnly: Boolean): StreamItem? =
        StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = regexPattern,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferredBingeGroup != null,
            bingeGroupOnly = bingeGroupOnly,
            debridEnabled = debridEnabled,
            activeResolverProviderId = activeResolverProviderId,
        )
}

/**
 * Episodes to attempt, in viewing order. Unaired episodes are excluded — there is nothing to fetch
 * yet, and including them would report spurious failures.
 */
private fun MetaDetails?.episodesForScope(
    downloadScope: SeriesDownloadScope,
    seedSeasonNumber: Int,
): List<MetaVideo> {
    val videos = this?.videos.orEmpty()
    return videos
        .filter { it.season != null && it.episode != null }
        .filter { downloadScope == SeriesDownloadScope.EntireShow || it.season == seedSeasonNumber }
        .filter { PlayerNextEpisodeRules.hasEpisodeAired(it.released) }
        .sortedWith(
            compareBy<MetaVideo> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE },
        )
}

private fun episodeLabel(season: Int, episode: Int): String =
    "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
