package com.noop.testcentre

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * Builds the prefilled GitHub new-issue URL for a Test Centre report (spec section 5.2), the Kotlin
 * twin of Strand/System/TestReportLink.swift. Binds bug_report.yml's id fields and self-applies the
 * "bug,test:<id>" labels. reportUrlString is pure and unit-tested; reportUri wraps it for ACTION_VIEW.
 * Encoding matches the Swift URLComponents output (comma -> %2C, brackets -> %5B/%5D, space -> %20).
 */
object TestReportLink {

    /** Percent-encode a query value the same way URLComponents does for the characters we emit.
     *  URLEncoder maps space to "+", so we fix it to "%20"; URLEncoder also encodes ":" to "%3A", but
     *  URLComponents leaves a colon literal in a query value, so we restore it so "test:sleep" matches
     *  the Swift output byte-for-byte. */
    private fun enc(v: String): String =
        URLEncoder.encode(v, "UTF-8").replace("+", "%20").replace("%3A", ":")

    /** The pure URL string (no Android types) so it is testable on the plain JVM. */
    fun reportUrlString(profile: TestDomain, title: String,
                        version: String, platform: String, osVersion: String): String {
        val items = listOf(
            "template" to "bug_report.yml",
            "labels" to "bug,${profile.githubLabel}",
            "version" to version,
            "platform" to platform,
            "os_version" to osVersion,
            "test_profile" to profile.id,
            "title" to "[${profile.id}] $title",
        )
        val query = items.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return "https://github.com/NoopApp/noop/issues/new?$query"
    }

    /** The Uri for an ACTION_VIEW intent. Not unit-tested (Uri needs an Android runtime). */
    fun reportUri(profile: TestDomain, title: String,
                  version: String, platform: String, osVersion: String): Uri =
        Uri.parse(reportUrlString(profile, title, version, platform, osVersion))

    /** Open the prefilled issue in the browser. Best-effort: a missing browser is swallowed. */
    fun openReport(context: Context, profile: TestDomain, title: String,
                   version: String, platform: String, osVersion: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW,
                reportUri(profile, title, version, platform, osVersion))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
