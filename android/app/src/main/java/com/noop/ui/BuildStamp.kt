package com.noop.ui

/**
 * The tiny build stamp under the CHOOP wordmark at the top of Today (#preview-channel).
 *
 * Channel-aware, per release-engineering convention (Edge/Chrome Canary style):
 *  - stable  → just "v<versionName>" — calm, no channel word, no build internals.
 *  - preview → "v<versionName> · build <versionCode> · <branch>@<sha>" — everything needed to
 *    answer "which build am I looking at?" in a bug report. The branch/sha come from CI
 *    (BuildConfig.GIT_BRANCH / GIT_SHA, injected by the release workflow); local builds leave
 *    them blank and the segment is omitted rather than showing an empty "@".
 *
 * Pure and unit-tested (BuildStampTest); the composable caller just renders the string.
 */
fun buildStamp(
    channel: String,
    versionName: String,
    versionCode: Int,
    branch: String,
    sha: String,
): String {
    val parts = mutableListOf("v$versionName")
    if (channel == "preview") {
        parts += "build $versionCode"
        val source = when {
            branch.isNotBlank() && sha.isNotBlank() -> "$branch@$sha"
            sha.isNotBlank() -> sha
            branch.isNotBlank() -> branch
            else -> null
        }
        source?.let { parts += it }
    }
    return parts.joinToString(" · ")
}
