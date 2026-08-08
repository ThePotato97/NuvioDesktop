package com.nuvio.app.features.downloads

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test

/**
 * Measures how much of the download path is spent on its own bookkeeping rather than on the
 * transfer, against a real stream URL.
 *
 * Set `NUVIO_BENCHMARK_URL` to a direct media link to run it; the test no-ops otherwise. The URL is
 * deliberately not committed — debrid links carry account-bound tokens.
 *
 * Both strategies run through identical harness code so only the parameters under test differ, and
 * they alternate so a drifting link speed shows up as disagreement between rounds rather than as a
 * fake result.
 */
class DownloadsThroughputBenchmark {

    private val bytesPerRun = 48L * 1024 * 1024
    private val rounds = 2

    @Test
    fun compareProgressReportingStrategies() {
        val url = System.getenv("NUVIO_BENCHMARK_URL")?.trim().orEmpty()
        if (url.isEmpty()) {
            println("NUVIO_BENCHMARK_URL not set — skipping download throughput benchmark.")
            return
        }

        val before = mutableListOf<Result>()
        val after = mutableListOf<Result>()

        repeat(rounds) { round ->
            // Alternate so link-speed drift cannot masquerade as a speed-up.
            before += run("before", url, bufferBytes = 8 * 1024, buffered = false, progressIntervalMs = 0L, persistIntervalMs = 0L)
            after += run("after", url, bufferBytes = 256 * 1024, buffered = true, progressIntervalMs = 250L, persistIntervalMs = 5_000L)
            println("round ${round + 1}: before=${"%.1f".format(before.last().mbPerSecond)} MB/s  " +
                "after=${"%.1f".format(after.last().mbPerSecond)} MB/s")
        }

        val beforeBest = before.maxOf { it.mbPerSecond }
        val afterBest = after.maxOf { it.mbPerSecond }

        println("")
        println("sample              : ${bytesPerRun / (1024 * 1024)} MB per run, $rounds rounds each")
        println("before (8KB/chunk)  : ${"%.1f".format(beforeBest)} MB/s best, " +
            "${before.sumOf { it.persistCount }} database writes total")
        println("after  (256KB/250ms): ${"%.1f".format(afterBest)} MB/s best, " +
            "${after.sumOf { it.persistCount }} database writes total")
        println("speed-up            : ${"%.2f".format(afterBest / beforeBest)}x")
        println("")
    }

    private fun run(
        label: String,
        url: String,
        bufferBytes: Int,
        buffered: Boolean,
        progressIntervalMs: Long,
        persistIntervalMs: Long,
    ): Result {
        val target = File.createTempFile("nuvio-benchmark-$label-", ".bin")
        val databaseFile = File.createTempFile("nuvio-benchmark-db-", ".json")
        var persistCount = 0
        var lastPersistMs = 0L

        // Stands in for DownloadsRepository.persist(): serialise the whole item list and write it.
        // Sized to a downloaded season, which is when that cost bites hardest.
        fun persist() {
            persistCount++
            databaseFile.writeText(benchmarkJson.encodeToString(sampleDownloadItems))
        }

        try {
            val request = HttpRequest.newBuilder(URI(url))
                .header("User-Agent", "NuvioDesktop")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} from benchmark URL" }

            var downloaded = 0L
            val started = System.nanoTime()

            response.body().use { input ->
                val rawOutput = FileOutputStream(target)
                val output = if (buffered) BufferedOutputStream(rawOutput, bufferBytes) else rawOutput
                output.use {
                    val buffer = ByteArray(bufferBytes)
                    var lastProgressMs = 0L
                    while (downloaded < bytesPerRun) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        it.write(buffer, 0, read)
                        downloaded += read.toLong()

                        val now = System.currentTimeMillis()
                        if (now - lastProgressMs >= progressIntervalMs) {
                            lastProgressMs = now
                            if (now - lastPersistMs >= persistIntervalMs) {
                                lastPersistMs = now
                                persist()
                            }
                        }
                    }
                    it.flush()
                }
            }

            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            return Result(
                elapsedMs = elapsedMs,
                mbPerSecond = (downloaded.toDouble() / (1024 * 1024)) / (elapsedMs.coerceAtLeast(1) / 1000.0),
                persistCount = persistCount,
            )
        } finally {
            target.delete()
            databaseFile.delete()
        }
    }

    private data class Result(
        val elapsedMs: Long,
        val mbPerSecond: Double,
        val persistCount: Int,
    )

    private companion object {
        val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val benchmarkJson = Json { encodeDefaults = true }

        /** A downloaded season: the list size that DownloadsRepository.persist() re-serialises. */
        val sampleDownloadItems: List<DownloadItem> = (1..20).map { episode ->
            DownloadItem(
                id = "benchmark_$episode",
                contentType = "series",
                parentMetaId = "tt14688458",
                parentMetaType = "series",
                videoId = "tt14688458:1:$episode",
                title = "Benchmark Show",
                episodeTitle = "Episode $episode",
                seasonNumber = 1,
                episodeNumber = episode,
                streamTitle = "Benchmark.Show.S01E$episode.1080p.WEB-DL.GROUP",
                providerName = "BenchmarkProvider",
                sourceUrl = "https://example.invalid/benchmark/$episode.mp4",
                fileName = "Benchmark Show S01E$episode.mp4",
                status = DownloadStatus.Completed,
                downloadedBytes = 1_200_000_000L,
                totalBytes = 1_200_000_000L,
                createdAtEpochMs = 1_700_000_000_000L,
                updatedAtEpochMs = 1_700_000_000_000L,
            )
        }
    }
}
