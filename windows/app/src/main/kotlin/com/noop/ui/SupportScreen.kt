package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * Support — attribution + optional crypto donations. Never a paywall; the
 * whole app works without it. Ports the Android [SupportScreen], using the
 * JVM clipboard manager (`LocalClipboardManager`).
 *
 * Adapted from the Android [SupportScreen]:
 *  - `LocalContext` / `Intent` → removed (no external links on desktop)
 *  - `Toast` → removed (copy feedback via inline state)
 *  - `stringResource` → hardcoded strings
 *  - `LocalClipboardManager` kept (Compose Desktop compatible)
 */
@Composable
fun SupportScreen() {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(
        title = "Support",
        subtitle = "NOOP is free and always will be. If it's useful to you, you can chip in to help with development and testing costs. Totally optional.",
    ) {
        // --- Support the build (donate) ---
        SectionHeader("Support the build", overline = "Optional")

        NoopCard(padding = 20.dp, tint = Palette.metricRose) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphChip(Icons.Filled.Favorite, Palette.metricRose)
                    Text("Support the build", style = NoopType.headline, color = Palette.textPrimary)
                }
                Text(
                    "NOOP is free and always will be, nothing is locked. It cost real money and a lot of unpaid hours to build. If it's useful to you and you want to help with the development and testing costs, even a few quid in crypto genuinely keeps it moving.",
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
                Text(
                    "I keep this project anonymous, so crypto is the only way to chip in — no Patreon, no PayPal, no name attached. Quick, global, and private for both of us.",
                    style = NoopType.footnote,
                    color = Palette.accent,
                )
                Column {
                    donations.forEachIndexed { idx, coin ->
                        AddressRow(
                            coin = coin,
                            copied = copied == coin.symbol,
                            onCopy = {
                                clipboard.setText(AnnotatedString(coin.address))
                                copied = coin.symbol
                            },
                        )
                        if (idx < donations.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .padding(vertical = 6.dp)
                                    .background(Palette.hairline),
                            )
                        }
                    }
                }
            }
        }

        // --- Help & Contact ---
        SectionHeader("Help & Contact", overline = "Get in touch")

        NoopCard(padding = 18.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphChip(Icons.Filled.Email, Palette.accent)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Get in touch", style = NoopType.headline, color = Palette.textPrimary)
                        Text(
                            "For bug reports, feature requests, or just to say thanks.",
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                }
            }
        }

        // --- Attributions ---
        SectionHeader("Attributions", overline = "Open source")

        NoopCard(padding = 18.dp, tint = Palette.metricCyan) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                attributions.forEach { attr ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphChip(Icons.Filled.Info, Palette.metricCyan)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(attr.repo, style = NoopType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Palette.textPrimary)
                            Text(attr.note, style = NoopType.footnote, color = Palette.textTertiary)
                        }
                    }
                }
            }
        }

        // --- Thank you ---
        SectionHeader("Thank you", overline = "From the developer")
        NoopCard(padding = 18.dp, tint = Palette.accent) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphChip(Icons.Filled.VolunteerActivism, Palette.accent)
                Text(
                    "Every install, every bit of feedback, every donation — it all keeps NOOP moving forward. Thank you.",
                    style = NoopType.subhead,
                    color = Palette.textPrimary,
                )
            }
        }
    }
}

// MARK: - Glyph chip

@Composable
private fun GlyphChip(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// MARK: - Address row

@Composable
private fun AddressRow(coin: CryptoAddress, copied: Boolean, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            coin.symbol,
            style = NoopType.captionNumber.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            color = Palette.metricRose,
            modifier = Modifier.width(40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(coin.name, style = NoopType.footnote, color = Palette.textTertiary)
            Text(
                coin.address.take(24) + "…" + coin.address.takeLast(6),
                style = NoopType.mono,
                color = Palette.textSecondary,
                maxLines = 1,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = "Copy ${coin.symbol} address",
                tint = if (copied) Palette.statusPositive else Palette.textSecondary,
            )
        }
    }
}

// MARK: - Donation data

private data class CryptoAddress(val symbol: String, val name: String, val address: String)

private val donations = listOf(
    CryptoAddress("BTC", "Bitcoin", "bc1qn2gkl7wslwpws06mvazjn2uu689zlkv7kg3kf5"),
    CryptoAddress("ADA", "Cardano", "addr1qxsju3y0mlke2h6h2g6qgnq4r3jstngtyjxs0nnp5zrv28zv8p5rgzruxyjz33j9k23pffta8z639e2snjdd4vcetfqsn4vwr3"),
    CryptoAddress("ETH", "Ethereum", "0xd64D508b531c4b1297Ca4023C774e0E97aA67B7F"),
)

private data class Attribution(val repo: String, val note: String)

private val attributions = listOf(
    Attribution("my-whoop", "BLE protocol reverse-engineering"),
    Attribution("goose", "historical-data decode + offload format"),
)
