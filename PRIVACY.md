# Privacy

As of v0.2.0:

- Everything (conversations, reminders) is stored in a local SQLite database on
  the device. Nothing is sent anywhere: the app does not request the
  `INTERNET` permission.
- No analytics, no ads, no tracking, no accounts.
- `allowBackup` is off, so chats are not uploaded to any cloud backup.

When an AI provider is connected later (Mohamed chooses which one), chat
messages will be sent to that provider to generate replies. That change will be
called out in the README and in the app before it ships.
