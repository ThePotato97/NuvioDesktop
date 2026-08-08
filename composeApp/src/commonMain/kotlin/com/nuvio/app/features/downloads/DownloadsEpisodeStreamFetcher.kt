package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.toStreamItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One-shot stream lookup for a single episode.
 *
 * `PlayerStreamsRepository` and `StreamsRepository` both drive a screen: they own a single
 * `StateFlow` slot guarded by a request key, so a second caller cancels the first. Bulk season
 * downloads need to resolve many episodes without disturbing whatever the player or streams screen
 * is showing, so this returns a plain list instead of publishing state.
 *
 * Unlike the screen repositories this deliberately skips debrid cache annotation and stream badge
 * presentation — those exist to decorate a list a human is about to read.
 */
internal object DownloadsEpisodeStreamFetcher {
    private val log = Logger.withTag("DownloadsStreamFetcher")

    suspend fun fetchStreams(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        MetaDetailsRepository.findEmbeddedStreams(videoId)
            .takeIf { it.isNotEmpty() }
            ?.let { embedded ->
                log.d { "embedded streams videoId=$videoId count=${embedded.size}" }
                return embedded
            }

        return coroutineScope {
            val addonResults = addonTargets(type, videoId).map { target ->
                async {
                    runCatchingUnlessCancelled {
                        StreamParser.parse(
                            payload = httpGetText(
                                buildAddonResourceUrl(
                                    manifestUrl = target.manifest.transportUrl,
                                    resource = "stream",
                                    type = type,
                                    id = videoId,
                                ),
                            ),
                            addonName = target.addonName,
                            addonId = target.addonId,
                            addonLogo = target.manifest.logoUrl,
                        )
                    }.getOrElse { error ->
                        log.w(error) { "addon stream fetch failed addon=${target.addonName} videoId=$videoId" }
                        emptyList()
                    }
                }
            }

            val pluginScrapers = if (AppFeaturePolicy.pluginsEnabled) {
                PluginRepository.getEnabledScrapersForType(type)
            } else {
                emptyList()
            }
            val pluginResults = pluginScrapers.map { scraper ->
                async {
                    PluginRepository.executeScraper(
                        scraper = scraper,
                        tmdbId = pluginContentId(videoId = videoId, season = season, episode = episode),
                        mediaType = type,
                        season = season,
                        episode = episode,
                    ).getOrElse { error ->
                        log.w(error) { "plugin stream fetch failed plugin=${scraper.name} videoId=$videoId" }
                        emptyList()
                    }.map { result -> result.toStreamItem(scraper = scraper) }
                }
            }

            (addonResults + pluginResults).awaitAll().flatten()
        }
    }

    private fun addonTargets(type: String, videoId: String) =
        AddonRepository.uiState.value.addons
            .enabledAddons()
            .mapNotNull { addon ->
                val manifest = addon.manifest ?: return@mapNotNull null
                val supportsStream = manifest.resources.any { resource ->
                    resource.name == "stream" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { videoId.startsWith(it) })
                }
                if (!supportsStream) return@mapNotNull null
                AddonStreamTarget(
                    addonName = addon.displayTitle.ifBlank { manifest.name },
                    addonId = addon.streamAddonInstanceId(manifest.id),
                    manifest = manifest,
                )
            }

    private data class AddonStreamTarget(
        val addonName: String,
        val addonId: String,
        val manifest: AddonManifest,
    )
}
