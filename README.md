# CorText Chat — Android app (main chat only)

A minimal Android app that talks to a CorText hub. Main chat only —
nothing else. The other capabilities stay in the web UI.

- Package `com.sahand.cortextchat`, version 1.4.3 (versionCode 10).
- Three screens: **Chat list**, **Chat**, and **Settings**. No tabs,
  no sidebar.
- No third-party dependencies: Android framework + Kotlin stdlib only,
  `HttpURLConnection` for networking (same approach as SMS Cleaner).
- Design matches the CorText panel (its exact colours and fonts, both
  themes): light by default, dark via the sun/moon toggle. The fonts
  are the panel's own — DM Sans (body) + Manrope Bold (titles) +
  Vazirmatn (Persian/Arabic fallback) — bundled under
  `app/src/main/res/font/` (all OFL-licensed; DM Sans + Manrope from
  `@expo-google-fonts`, Vazirmatn from `@expo-google-fonts/vazirmatn`).
- **Chat list**: the hub's chats (pinned first, then most recent —
  the same list the web panel shows, so a chat started in either
  place appears in the other). Tap to open; long-press for
  Pin/Unpin and Delete (delete asks first); "+" starts a new chat.
- Settings has a **Model** picker (the hub's models, via
  `POST /api/mobile/models`; "Hub default" uses the hub's active
  model), a **Persona** picker (the hub's personas, via
  `GET /api/mobile/personas`; "Main chat" uses no persona), and a
  **← back button** that returns to the chat without saving.
  Picking a persona starts a NEW session with that persona — the old
  chat keeps its history in the list, untouched.
- **Streaming replies**: the AI reply appears word-by-word as it
  arrives (`POST /api/mobile/chat/stream`, encrypted SSE), instead of
  waiting for the whole reply. On a hub without the stream endpoint
  it falls back to the buffered send. Scroll anchoring: when a reply
  starts, a few lines of the question stay visible at the top and the
  reply begins below; while streaming it only follows to the bottom
  while the user is already there — scrolling up to read stops the
  auto-scroll until they scroll back down.
- **Reply sync (1.4.3)**: if a stream ends without the reply on screen
  — cut before `done` by a proxy or the network, or a model that
  answered without streaming any text — the app fetches the chat
  history from the hub instead of dropping the bubble (and re-checks a
  few times while the hub finishes), so you never have to close and
  reopen the chat to see an answer. A network error mid-stream no
  longer retries on the other scheme (that re-sent the message).

## How it connects (v2 — token-based, PROTOCOL-v2.md)

The hub (CorText) owns the mobile feature. The app talks to the hub's
`/api/mobile/*` endpoints directly, or to a **relay** on a middle
server (default port 8080) which forwards to the hub. See
`../PROTOCOL-v2.md` for the full contract. (v1's Python gateway on
the hub is gone; v1's username/password + tunnel key are gone.)

- **The token**: the ONLY credential. It is copied from the hub's
  Mobile page into the app's settings, and it is both the encryption
  key (`SHA-256(token)`) and the auth credential — the hub verifies
  it by decrypting the request body, so the token is never sent in
  plaintext. There is no username, no password, and no separate
  tunnel key.
- **Activation**: saving settings runs two checks — a plain
  `GET /api/mobile/health` (reachability + is mobile activated on
  the hub?) and an encrypted `POST /api/mobile/sessions` (does the
  hub accept this token?). Only when both pass is the app activated
  and the chat opened. "Test connection" runs the same two checks
  without leaving the screen.
- **Mode**: Direct (to the hub, port 8788) or Relay (through the
  middle server). The port is a separate field, so any port works.
- **SSL**: Auto (default) tries HTTPS first and falls back to HTTP,
  remembering which worked. It re-probes HTTPS once an hour, so when
  a certificate is added later the app upgrades automatically.
  On = HTTPS only, Off = HTTP only.
- **Encryption**: the chat is encrypted with the token
  (HMAC-SHA256 stream cipher, see `Crypto.kt`) *before* it touches
  the network — so it is private even over plain HTTP with no SSL.
  TLS is a second layer on top. The app runs the PROTOCOL-v2.md test
  vector as a self-check on startup; if it fails, it says so.
- **Timeouts**: 600s read timeout (the model can take minutes),
  10s connect timeout.

## Screens

- **Chat list** (shown on open, once configured): the hub's chats,
  pinned first. Tap to open, long-press for Pin/Unpin/Delete,
  "+" for a new chat, gear for Settings, sun/moon for the theme.
- **Settings** (shown on first launch): mode, host, port, token, SSL,
  theme, model picker, persona picker. "Save & open chat",
  "Test connection", "Log out" (clears the token and the session).
  Saving reuses the current session unless the server/token or the
  persona changed — a persona change opens a NEW session with that
  persona and keeps the old chat in the list.
- **Chat**: top bar (CorText, new chat, theme toggle, settings gear),
  message list (user right, assistant left, panel bubble colours),
  "Thinking…" while the reply is on its way, input row at the bottom
  with the panel's 8dp input + accent ➤ send button. History loads
  on open. A plain error message is shown if the hub/relay is
  unreachable. Back returns to the chat list.
- **Theme**: dark/light toggle in the top bar (and in Settings),
  persisted, switches immediately. Light is the default (panel light:
  white bg, green `#1a7f37` accent); dark is the panel dark (bg
  `#0b0d12`, lime `#c3f47a` accent, bubbles `#202936` / `#151b26`).

## Notes

- `android:usesCleartextTraffic="true"` is set on purpose: the app
  talks plain HTTP to a bare IP first. The content is still encrypted
  by the app-layer cipher (see above).
- The token is stored in plain `SharedPreferences` (app-private).
  That is deliberate: `EncryptedSharedPreferences` needs the AndroidX
  Security library, and this app has zero third-party dependencies.
  See the note in `SettingsStore.kt`.
- A self-signed certificate is NOT trusted: in Auto mode an HTTPS
  attempt that fails certificate validation falls back to HTTP. The
  chat is still encrypted by the token. A valid (e.g. Let's Encrypt)
  certificate is used automatically.
- Upgrading from v1.0.0: the old username/password/tunnel key are
  ignored. Open the app, paste the token from the hub's Mobile page,
  and save — that activates it.

## Download (GitHub Actions)

Every push to `main` builds the APK in GitHub Actions
(`.github/workflows/android.yml`) and publishes it on the
**Releases** page as `CorText-Chat-v<version>.apk` (tag `v<version>`,
from `versionName` in `app/build.gradle.kts`). It is signed with the
committed `debug.keystore`, so it installs over earlier builds.
Bump `versionCode`/`versionName` for a new release; pushing without a
bump replaces that release's APK.

## Build

On a normal machine / in Android Studio:

```
gradle assembleDebug
```

Here (the sandbox blocks the Gradle daemon's Java-to-Java TCP), use
the manual script — same approach as SMS Cleaner. Requires the
Android SDK (platform 34 + build-tools 34.0.0), JDK 17, Kotlin
2.0.0:

```
export JAVA_HOME=$HOME/android-build/jdk-17.0.21+9
export ANDROID_SDK_ROOT=$HOME/android-sdk
export KOTLINC_HOME=$HOME/android-build/kotlinc
./build-manual.sh
```

This produces `build/manual/app-debug.apk`. It is signed with the
`debug.keystore` in the project root, so rebuilds install over each
other. The keystore is committed on purpose (Actions signs with it);
it is a debug key, not a Play Store key.

Note: `d8` prints a harmless warning about "malformed kotlin.Metadata"
(kotlinc 2.0.0 is newer than the R8 bundled with build-tools 34.0.0).
The APK still builds and verifies fine — SMS Cleaner's toolchain
produces the same warning.
