package com.noop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * User-initiated "Check for updates": a single call to the project's PUBLIC releases API (GitHub) that reads the
 * latest version and compares it to the installed one. It runs ONLY when the user taps the button —
 * there is no background polling and no auto-update. Nothing about the user is sent; it just reads a
 * version number. (Android already holds INTERNET for the opt-in AI Coach, so this adds no new
 * capability.)
 */
object UpdateCheck {

    // This fork's releases. The original NoopApp/noop repo is gone, so the check reads THIS repo's
    // GitHub Releases. The Android Release APK workflow attaches the versioned Choop APK to a
    // Release on every cut, so tapping through to the release page lands on a downloadable APK.
    //
    // Two channels (BuildConfig.CHANNEL, see the `preview` product flavor):
    //   stable  → /releases/latest — GitHub EXCLUDES pre-releases here, so the stable app is never
    //             offered a preview build.
    //   preview → /releases?per_page=… — the full list INCLUDING pre-releases; the newest
    //             non-draft version wins, so "Choop Preview" updates onto the next preview cut.
    private const val LATEST_ENDPOINT = "https://api.github.com/repos/kimchaily/noop/releases/latest"
    private const val LIST_ENDPOINT = "https://api.github.com/repos/kimchaily/noop/releases?per_page=20"

    sealed interface Result {
        data class UpToDate(val version: String) : Result
        data class Available(val version: String, val url: String, val notes: String) : Result
        object Failed : Result
    }

    /** Fetch the latest release for the channel and classify it against [currentVersion]. Pass
     *  [includePrereleases] = true on the preview channel (`BuildConfig.CHANNEL == "preview"`).
     *  Never throws — any error (offline, rate-limited, malformed) resolves to [Result.Failed] so
     *  the caller shows a calm "try again" rather than crashing. */
    suspend fun check(currentVersion: String, includePrereleases: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                if (includePrereleases) checkList(currentVersion) else checkLatest(currentVersion)
            }.getOrDefault(Result.Failed)
        }

    /** Stable channel: GitHub's `/releases/latest` (pre-releases are excluded by GitHub itself). */
    private fun checkLatest(currentVersion: String): Result {
        val body = fetch(LATEST_ENDPOINT) ?: return Result.Failed
        val json = JSONObject(body)
        val latest = json.getString("tag_name").removePrefix("v")
        val url = json.getString("html_url")
        val notes = cleanNotes(json.optString("body", ""))
        return if (isNewer(latest, currentVersion)) Result.Available(latest, url, notes)
        else Result.UpToDate(latest)
    }

    /** One GitHub release as the list endpoint reports it, reduced to what the preview selector needs. */
    data class ReleaseInfo(
        val tag: String,
        val prerelease: Boolean,
        val draft: Boolean,
        val url: String,
        val notes: String,
    ) {
        /** The numeric version the tag carries, "v" stripped (e.g. "v8.3.0-pre" → "8.3.0-pre"). */
        fun version(): String = tag.removePrefix("v")
    }

    /**
     * Preview channel: pick the newest PRE-RELEASE from [releases]. This is the safety-critical
     * filter — a preview install must NEVER be offered a STABLE release. Stable Choop and Choop
     * Preview are different apps (different applicationId), so a stable APK can't even update the
     * preview app; worse, tapping through would walk the user into installing/updating the *stable*
     * app by mistake. So we drop every release that is not marked `prerelease` (and every draft),
     * and only then take the newest by the same numeric [isNewer] compare the stable path uses.
     * Pure + unit-tested (UpdateCheckTest) — the network layer just feeds it parsed [ReleaseInfo]s.
     */
    internal fun newestPreviewRelease(releases: List<ReleaseInfo>): ReleaseInfo? {
        var best: ReleaseInfo? = null
        for (r in releases) {
            if (!r.prerelease || r.draft || r.version().isEmpty()) continue
            if (best == null || isNewer(r.version(), best.version())) best = r
        }
        return best
    }

    /** Preview channel: the release LIST (which includes stable + pre-releases); [newestPreviewRelease]
     *  keeps ONLY pre-releases, so a newer stable can never leak into the preview app's update check. */
    private fun checkList(currentVersion: String): Result {
        val body = fetch(LIST_ENDPOINT) ?: return Result.Failed
        val arr = JSONArray(body)
        val releases = ArrayList<ReleaseInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val rel = arr.optJSONObject(i) ?: continue
            releases += ReleaseInfo(
                tag = rel.optString("tag_name", ""),
                prerelease = rel.optBoolean("prerelease", false),
                draft = rel.optBoolean("draft", false),
                url = rel.optString("html_url", ""),
                notes = cleanNotes(rel.optString("body", "")),
            )
        }
        val found = newestPreviewRelease(releases) ?: return Result.UpToDate(currentVersion)
        return if (isNewer(found.version(), currentVersion)) {
            Result.Available(found.version(), found.url, found.notes)
        } else {
            Result.UpToDate(found.version())
        }
    }

    /** One GET against the GitHub API; null on any non-200 / transport problem. */
    private fun fetch(endpoint: String): String? {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * True iff [latest] is a strictly newer version than [current]. Compares dot-separated numeric
     * segments left to right — so `1.40 > 1.39` and `1.9 < 1.10`, both of which a plain string compare
     * gets WRONG. Tolerant of a leading "v" and any non-numeric suffix (e.g. the demo flavour's
     * "1.39-demo", or build metadata). Pure + unit-tested.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = segments(latest)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun segments(s: String): List<Int> =
        s.trim().removePrefix("v").removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }   // stop at "-demo" / build metadata
            .split(".")
            .mapNotNull { it.toIntOrNull() }

    /** Turn a GitHub release body into a short, readable "what's new" for an inline preview: drop the
     *  "Downloads"/footer boilerplate, strip the heaviest markdown markers, and cap the length. */
    fun cleanNotes(body: String): String {
        var s = body.substringBefore("Downloads")
        for (marker in listOf("**", "## ", "# ")) s = s.replace(marker, "")
        s = s.trim()
        return if (s.length > 700) s.take(700).trim() + "…" else s
    }
}
