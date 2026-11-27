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
 * twice (going back and forth, a rotation/recreation, or a re-read after a system dialog are all covered). A
 * different link does trigger again. The clipboard itself is only read, never cleared or modified, and the URL is
 * never written to disk — just its SHA-256 fingerprint.
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
        val url = Urls.extractUrl(readClipboard())?.let(Urls::normalize) ?: return@LaunchedEffect
        if (!isDownloadable(media, url)) return@LaunchedEffect
        val fingerprint = fingerprint(url)
        if (prefs.getString(LAST_LINK_KEY, null) == fingerprint) return@LaunchedEffect
        // Stored before navigating: if the navigation fails, the link must not reopen on every focus change.
        prefs.edit().putString(LAST_LINK_KEY, fingerprint).apply()
        onLink(url)
    }
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
