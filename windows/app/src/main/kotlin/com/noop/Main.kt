package com.noop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.noop.ui.AppRoot
import com.noop.ui.NoopTheme
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.SwingUtilities

// MARK: - Main — the Compose Desktop application entry point
//
// The desktop twin of the Android `MainActivity` (which hosts a single [AppRoot] composable inside
// a `ComponentActivity`). On the JVM there is no Activity or Fragment — Compose Desktop's
// `application { }` block IS the window host — so this `main()` function plays the role that
// `MainActivity.onCreate()` plays on Android:
//
//   1. Resolve the SQLite database path under %APPDATA%\NOOP\noop_whoop.db (Windows convention).
//   2. Initialise the process-wide state ([NoopApplication.init]) — this opens the database, builds
//      the [WhoopRepository] + [DeviceRegistry], creates the [DesktopAppViewModel], and loads the
//      persisted appearance / theme / chart-style preferences so [NoopTheme] resolves the correct
//      palette before the first composition (no flash).
//   3. Open the single application [Window] and render `NoopTheme { AppRoot(viewModel) }`.
//   4. On window close, tear everything down ([NoopApplication.shutdown] closes the DB connection
//      and releases file handles) and exit the application.
//
// The initialisation is routed through `SwingUtilities.invokeAndWait` (the synchronous variant of
// `invokeLater`) so the database + ViewModel are guaranteed to be ready on the Swing EDT before the
// Compose window composes — there is no race between the window opening and the init completing.

fun main() {
    // 1. Resolve the database path: %APPDATA%\NOOP\noop_whoop.db (Windows convention)
    val dbPath = resolveDbPath()

    // 2. Initialise process-wide state on the Swing EDT. invokeAndWait blocks the calling (main)
    //    thread until the EDT has run the init, so by the time `application` opens the window the
    //    database is open, the ViewModel is created, and the preferences are loaded — no race, no
    //    flash, no null-ViewModel window.
    var initError: String? = null
    SwingUtilities.invokeAndWait {
        runCatching { NoopApplication.init(dbPath) }
            .onFailure { err ->
                err.printStackTrace()
                initError = err.message ?: err.javaClass.simpleName
            }
    }

    // 3. Launch the Compose Desktop application window.
    application {
        val windowState = rememberWindowState(
            width = 1280.dp,
            height = 800.dp,
        )

        // Version counter that bumps when a backup-restore swaps the database + ViewModel.
        // The key() around AppRoot reads the fresh ViewModel from NoopApplication after reinit.
        var viewModelVersion by remember { mutableIntStateOf(0) }

        Window(
            onCloseRequest = {
                // 4. Tear down process-wide resources (closes the DB connection, checkpoints the
                //    WAL, releases file handles) before exiting.
                NoopApplication.shutdown()
                exitApplication()
            },
            title = "NOOP — WHOOP Companion",
            state = windowState,
        ) {
            // Enforce the minimum window size (1024 x 720) on the underlying Swing JFrame so the
            // sidebar + content never collapse below a usable layout. Compose Desktop's Window has
            // no direct min-size parameter, so this is set on the platform window.
            window.minimumSize = Dimension(1024, 720)

            // Set the application window icon — a 256×256 programmatic rendering matching the
            // SVG design in resources/drawable/app_icon.svg (dark circle + "N" monogram + pulse).
            window.iconImage = createAppIcon()

            // The process-wide ViewModel is guaranteed non-null here (init ran via invokeAndWait
            // before `application`), but the null guard keeps the window safe if init failed —
            // in that case an error screen is shown instead of a blank window.
            val appViewModel = NoopApplication.viewModel
            if (appViewModel != null) {
                androidx.compose.runtime.key(viewModelVersion) {
                    val currentViewModel = NoopApplication.viewModel
                    if (currentViewModel != null) {
                        NoopTheme {
                            AppRoot(
                                viewModel = currentViewModel,
                                dbPath = dbPath,
                                onReinit = { viewModelVersion++ },
                            )
                        }
                    }
                }
            } else {
                // Init failed — show a user-friendly error screen instead of a blank window.
                NoopTheme {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Text(
                            text = "Failed to start: ${initError ?: "Unknown error"}\n\n" +
                                "Check the database at:\n$dbPath",
                            color = androidx.compose.ui.graphics.Color.Red,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Database path resolution

/**
 * Resolve the absolute path to the SQLite database file: `%APPDATA%\NOOP\noop_whoop.db`.
 *
 * Uses the Windows standard %APPDATA% (Roaming) directory, which is the convention for
 * desktop application data. Falls back to `<user.home>/AppData/Roaming/NOOP/` if the
 * environment variable is not set. The directory is created if it does not exist.
 * The database file itself is created by [DesktopDatabase.init] on first open.
 */
private fun resolveDbPath(): String {
    val appData = System.getenv("APPDATA")
        ?: File(System.getProperty("user.home"), "AppData/Roaming").absolutePath
    val dir = File(appData, "NOOP")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "noop_whoop.db").absolutePath
}

// MARK: - App Icon

/**
 * Programmatically render the NOOP application icon as a 256×256 [BufferedImage].
 *
 * This mirrors the SVG design in `resources/drawable/app_icon.svg`: a dark circular
 * background (#1a1a2e), a red "N" monogram (#e94560), and a teal pulse line (#0f3460)
 * across the lower third. Rendering at 256px gives a crisp icon at all common taskbar
 * and title-bar sizes (16, 32, 48, 64, 128, 256).
 *
 * Called once at window creation to set `window.iconImage`; no external file I/O needed.
 */
private fun createAppIcon(): BufferedImage {
    val size = 256
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

    // Background: filled circle
    g.color = Color(0x1a, 0x1a, 0x2e)
    g.fill(Ellipse2D.Double(0.0, 0.0, size.toDouble(), size.toDouble()))

    // "N" monogram centred vertically (slightly above centre to leave room for pulse line)
    g.color = Color(0xe9, 0x45, 0x60)
    g.font = Font("SansSerif", Font.BOLD, 130)
    val fm = g.fontMetrics
    val text = "N"
    val textW = fm.stringWidth(text)
    val textX = (size - textW) / 2
    val textY = (size - fm.height) / 2 + fm.ascent - 10
    g.drawString(text, textX, textY)

    // Pulse line across the lower third
    g.color = Color(0x0f, 0x34, 0x60)
    g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    val baseY = 200
    g.drawLine(36, baseY, 76, baseY)
    g.drawLine(76, baseY, 90, baseY - 22)
    g.drawLine(90, baseY - 22, 104, baseY + 28)
    g.drawLine(104, baseY + 28, 118, baseY)
    g.drawLine(118, baseY, 220, baseY)

    g.dispose()
    return image
}
