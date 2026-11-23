# instinct-lite

A small, free, open-source Android assistant app with an Arabic (RTL) chat screen, saved conversations and local reminders.

**Independent project.** instinct-lite is written from scratch by its author. It is not affiliated with, endorsed by, or derived from any commercial assistant product, and it contains none of their code, prompts or assets.

## Status (v0.2.0)

| Feature | State |
| --- | --- |
| Arabic RTL chat UI | Built |
| Conversations saved on the device (SQLite) | Built |
| Local reminders with a notification | Built |
| AI replies | **Not connected.** No model provider is wired in yet, so the app says so instead of faking an answer. |
| Reminder validation + error messages | Built (empty text, past time, notifications permission denied, alarm refused) |
| Logic unit tests | 7 tests, run on every GitHub build |
| Arabic RTL layout | Verified with layout renders of all three screens (renders, not on-device tests) |

Everything is stored on the device. The app has no internet permission and sends nothing anywhere.

## Get the APK

The APK is built by GitHub Actions, not on your phone.

1. Open the **Actions** tab, pick the latest successful **Build APK** run.
2. Download the `instinct-lite-debug-apk` artifact and unzip it.
3. Install `app-debug.apk` on your Android phone (allow "install unknown apps" for your browser or file manager once).

This is a debug-signed build for sideloading. Android 7.0 (API 24) or newer. A stable release key is planned but not set up yet; see `docs/SIGNING.md`.

## Build it yourself

You need JDK 17, the Android SDK (platform 34) and Gradle 8.7:

```
gradle assembleDebug     # build the APK into app/build/outputs/apk/debug/
gradle testDebugUnitTest # run the logic tests
```

## Privacy

See `PRIVACY.md`. Short version: everything stays on the device, the app has no internet permission.

## Adding an AI provider

Replies come through the `ChatProvider` interface in `app/src/main/java/dev/momolite/ChatProvider.kt`. Implement it and set it in `MainActivity`. If you add one that needs an API key, keep the key on the device (never in the repository) and add the `INTERNET` permission yourself.

## License

MIT. See `LICENSE`.
