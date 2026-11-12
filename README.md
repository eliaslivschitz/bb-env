# BB Host

A stripped fork of [Mobile Harness](https://github.com/techjarves/Mobile-Harness) (MIT, see LICENSE) that hosts one thing: [bb](https://github.com/get-bb/bb) (`bb-app` on npm). It is not affiliated with bb or with Mobile Harness.

On an arm64 Android 9+ phone the app:

1. downloads the pinned, SHA-256-checked Ubuntu 20.04 "Core" bundle (Node.js 24 and Git inside) from the Mobile Harness runtime release and unpacks it under PRoot;
2. installs `git python3 make gcc-10 g++-10` with apt and `bb-app` with npm (first run only, needs network);
3. runs `bb-app --data-dir /root/.bb --server-bind-host 127.0.0.1 --no-in-app-updates`;
4. shows progress and the live log, then bb's web UI in a WebView once `GET http://127.0.0.1:38886/health` answers.

Buttons: Log, Restart, Stop/Start. A foreground service keeps bb alive in the background.

Removed from Mobile Harness: the Claude Code, DeepSeek Harness and Antigravity drivers, the agent registry and all their screens, the provider/API-key code, backups, the self-updater, the update manifest and the store/F-Droid metadata. bb itself is not modified.

Build: JDK 17+, Android SDK 36, NDK 28.2.13676358, CMake 3.22.1, `git submodule update --init`, then `./gradlew :app:assembleRelease`. Signing reads `signing/keystore.properties` and `signing/bbhost.jks` (git-ignored); without them the APK is unsigned.
