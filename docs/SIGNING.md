# Signing plan (not set up yet)

The APK currently builds with the Android debug key so it can be sideloaded.
Before any public release, the app needs a stable release key.

Why the key matters:

- Android refuses an ordinary in-place update when the new APK is signed with
  a DIFFERENT certificate than the installed one. That is a fact to plan
  around, not a problem to work around: do not uninstall an installed copy to
  get past a certificate mismatch, and never uninstall the app as a casual
  troubleshooting step. All conversations and reminders live in app-private
  storage, and uninstalling permanently deletes them.
- Once a stable release key exists and builds are signed with it, Android
  permits same-key updates to install over the top. That does not guarantee
  data survives every upgrade: application or schema bugs can still lose
  data, and data preservation across upgrades has not been tested yet.
- Whether any build is already installed on a device, and which certificate
  it carries, is currently unknown.

Planned setup, pending Mohamed's approval (creates a key, so it waits):

1. Generate one release keystore locally (`keytool -genkeypair`). The
   original file and its backup stay in private storage chosen by Mohamed
   (never in the repository, never sent over chat). Losing the key means
   updates can never install over older installs again.
2. Store the keystore (base64) and its passwords in GitHub **Secrets**, never
   in the repository. `.gitignore` already blocks `*.jks`, `*.keystore` and
   `keystore.properties`.
3. Add a release signing config to the workflow that only activates when
   those secrets exist.

Nothing on this page has been done yet. No key exists in the repo or in CI.
