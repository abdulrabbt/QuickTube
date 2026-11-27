package com.edureminder.quicktube.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import com.edureminder.quicktube.core.common.Platform
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.ui.rememberClipboardReader
import org.koin.compose.koinInject
import java.security.MessageDigest

/**
 * A link copied in another app opens the download flow by itself when QuickTube comes to the foreground — exactly
 * like pasting it (the callback routes it through [UrlRouter.routeForPasted]): a YouTube-style page opens with the
 * format sheet on top, other sites open in the in-app browser whose sheet pops when the video is found, and direct
 * media files open the sheet. Links QuickTube can't download are ignored. Always on; no setting, no new permission.
 *
 * Android 10+ only lets an app read the clipboard while its window has focus, and focus arrives after ON_RESUME, so
 * the clipboard is read whenever focus arrives: cold start and every return from another app. The same trigger the
 * Download tab's "Download copied link" card uses.
 *
 * The last handled link is kept as a hash in app-private preferences, so the same copied link never opens the flow
 * twice in a row (going back and forth, a rotation/recreation, or a re-read after a system dialog are all covered).
 * A different link does trigger again, and re-copying an older link opens it again. The clipboard itself is only read,
 * never cleared or modified, and the URL is never written to disk — just its SHA-256 fingerprint.
 *
 * A launch with an explicit intent (a tapped/shared link, bulk links, an "open downloads/player" extra) already
 * opens a destination of its own, so that focus gain is skipped via [ClipboardLaunchGuard] — the copied link is
 * still recorded as handled, then the guard clears and normal resumes auto-open as before.
 */
@Composable
fun ClipboardLinkWatcher(onCopiedLink: (String) -> Unit) {
    val readClipboard = rememberClipboardReader()
    val context = LocalContext.current
    val media = koinInject<MediaRepository>()
    // The callback is used from a long-lived effect, so a newer lambda must be seen without restarting it.
    val onLink by rememberUpdatedState(onCopiedLink)
    val prefs = remember(context) { context.getSharedPreferences(CLIPBOARD_PREFS, Context.MODE_PRIVATE) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, media) {
        if (!focused) return@LaunchedEffect
        // An explicit intent already opens its own screen: skip this one auto-open so the copied link isn't pushed
        // on top of what the user asked for. The flag is consumed on every focus gain, so it can never leak into a
        // later normal resume (and it isn't persisted, so an app restart starts clean anyway).
        val skipExplicitLaunch = ClipboardLaunchGuard.consumeSuppression()
        val url = Urls.extractUrl(readClipboard())?.let(Urls::normalize) ?: return@LaunchedEffect
        if (!isDownloadable(media, url)) return@LaunchedEffect
        val fingerprint = fingerprint(url)
        if (prefs.getString(LAST_LINK_KEY, null) == fingerprint) return@LaunchedEffect
        // Stored before navigating: if the navigation fails, the link must not reopen on every focus change. Stored
        // even when skipping, so a rotation/recreation right after the explicit launch can't pop the flow either.
        prefs.edit().putString(LAST_LINK_KEY, fingerprint).apply()
        if (skipExplicitLaunch) return@LaunchedEffect
        onLink(url)
    }
}

/**
 * Process-wide, one-shot guard between MainActivity and [ClipboardLinkWatcher].
 *
 * Opening QuickTube with an explicit intent (a tapped or shared link, bulk links, or an "open downloads/player"
 * extra) already opens that destination; the watcher would otherwise also open the copied link on the focus gain
 * the launch causes (the same video twice, or a different link pushed on top of what the user asked for).
 * MainActivity raises the flag while handling the intent; the watcher consumes it on the next focus gain and clears
 * it, so the next normal resume auto-opens again.
 *
 * It lives in the process only (never persisted): a process restart starts clean, so a launcher cold start is never
 * suppressed, and a rotation/recreation doesn't raise it again either.
 */
internal object ClipboardLaunchGuard {
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The app is being opened/foregrounded for a screen of its own; skip the next clipboard auto-open. */
    fun suppressNextAutoOpen() { pending.set(true) }

    /** True once when an auto-open must be skipped; always clears, so the guard can never leak into a later resume. */
    fun consumeSuppression(): Boolean = pending.getAndSet(false)
}

/** The rule the "Download copied link" card uses (HomeViewModel.canDownload): a site QuickTube knows, one its
 *  extractors read, or a direct media file. Everything else (a news article, a GitHub repo…) is left alone. */
private fun isDownloadable(media: MediaRepository, url: String): Boolean {
    if (url.isBlank()) return false
    return Urls.platformOf(url) != Platform.OTHER ||
        runCatching { media.canExtract(url) }.getOrDefault(false) ||
        Urls.isMediaFile(url)
}

/** SHA-256 of the normalized URL: the preferences hold no URL, just enough to recognise the same link again. */
private fun fingerprint(url: String): String =
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

private const val CLIPBOARD_PREFS = "clipboard"
private const val LAST_LINK_KEY = "last_link"
