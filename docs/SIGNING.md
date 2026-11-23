# Signing plan (not set up yet)

The APK currently builds with the Android debug key so it can be sideloaded.
Before any public release, the app needs a stable release key so updates install
over the existing app without wiping data.

Planned setup, pending Mohamed's approval (creates a key, so it waits):

1. Generate one release keystore locally (`keytool -genkeypair`). Mohamed keeps
   the original file plus an offline backup. Losing it means updates can never
   install over older installs again.
2. Store the keystore (base64) and its passwords in GitHub **Secrets**, never in
   the repository. `.gitignore` already blocks `*.jks`, `*.keystore` and
   `keystore.properties`.
3. Add a release signing config to the workflow that only activates when those
   secrets exist.

Nothing on this page has been done yet. No key exists in the repo or in CI.
