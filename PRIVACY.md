# Privacy

QuickTube has no accounts and no servers of its own. Your downloads, history, vault and settings stay on your device.

## Firebase

The [releases published in this repository](https://github.com/abdulrabbt/QuickTube/releases) do **not** include Google
Firebase: the owner's builds are made without an `app/google-services.json` configuration, so they collect nothing. Firebase
is only added to a build that has its own `app/google-services.json`.

The official upstream QuickTube releases did include Google Firebase, which collected:

- **Crashlytics**: crash reports (stack trace, device model, Android version, app version) so crashes could be fixed.
- **Analytics**: anonymous usage counts, for example "a download started" (video or audio, HTTP or HLS) or "a screen was opened".

Those releases **never** sent the URLs you visit or download, video titles, search terms, file names or anything you type. For downloads they counted only the site's domain (for example "tiktok.com"), the country and a short reason code when one failed, so it was clear which sites needed fixing.

When Firebase is included in a build, crash reports and usage sharing are **off by default**: the app starts the Firebase
SDK with collection disabled. Nothing is collected or sent until you turn on **Settings → Privacy → "Share crash reports &
usage"** yourself, and the same switch turns them off again at any time. With the switch on, the same two things as above
would be collected, with the same limits: never URLs, titles, search terms, file names or anything you type. The choice is
kept on your device, so updating the app doesn't change it. Builds made from source without a Firebase configuration collect
nothing, whatever the switch is set to.

## Network requests

To work, the app talks directly to the sites you use (YouTube, the pages you open in the browser, the media servers they point to)
and to GitHub to update the yt-dlp engine and to check for new app versions (this request only reveals your IP address to GitHub). These requests go from your device to those services; they are not routed through us.
