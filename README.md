# adb-multiuser-transfer

A tiny Android app (no UI, no INTERNET permission) that lets the **adb shell** put files into, read files from, and tidy up the storage of a **secondary Android user**. On Android 9+ `adb push` cannot reach another user's storage ([multi-user testing](https://source.android.com/docs/devices/admin/multi-user-testing)); this app is a small bridge for that one job.

Only `Pictures/` and `DCIM/` are reachable. Only the adb shell (uid 2000) may call it.

## What it does (and refuses)
| | |
|---|---|
| `content write` | writes a hidden `.<name>.part`, renames it on close and media-scans it, so nothing ever sees a half-written file; a failed write leaves nothing behind |
| `content read` | reads a file |
| `call stat` | exists / size / SHA-256 (compare with your source file) |
| `call ls` | list a folder |
| `call rm` | delete **one regular file** (never a folder) and rescan |
| refuses | every caller except uid 2000 (adb shell); every path outside `Pictures/` and `DCIM/` (`..` and symlinks are canonicalised first); `query`/`insert`/`update`/`delete` |

A path may start with `Pictures/` or `DCIM/`; otherwise it is taken below `Pictures/`.

## Behaviour to know about
- Writing to an existing name **replaces** the file. Check with `stat` first if you must not overwrite.
- If the process is killed mid-write, a hidden `.<name>.part` file can remain; remove it with `rm`.
- Written files are handed to the media scanner, so they show up in gallery apps.

## Compatibility
Built with `targetSdkVersion 33` and `requestLegacyExternalStorage`. That flag only has an effect on **Android 10 and lower**: on Android 11+ scoped storage applies and the app cannot read or write `/sdcard`. Tested on a Pixel XL (Android 10) with a secondary user; other versions are untested.

## Build
Needs the Android SDK command-line tools (build-tools and a platform `android.jar`), JDK 8+ and `zip`; no Gradle.
```
ANDROID_HOME=/path/to/sdk ./build.sh      # prints the APK SHA-256
```
The script signs with a throw-away `debug.keystore` that it creates on first run (git-ignored). Sign with your own key if you distribute the APK.

## Use
```
adb install --user 10 adb-multiuser-transfer.apk
adb shell pm grant --user 10 io.github.wkliwk.adbtransfer android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant --user 10 io.github.wkliwk.adbtransfer android.permission.WRITE_EXTERNAL_STORAGE
adb shell content write --user 10 --uri content://io.github.wkliwk.adbtransfer/demo/a.jpg < a.jpg
adb shell content call  --user 10 --uri content://io.github.wkliwk.adbtransfer --method stat --arg demo/a.jpg
adb shell content call  --user 10 --uri content://io.github.wkliwk.adbtransfer --method ls   --arg demo
adb shell content call  --user 10 --uri content://io.github.wkliwk.adbtransfer --method rm   --arg demo/a.jpg
adb uninstall --user 10 io.github.wkliwk.adbtransfer     # when you are done
```
The two `pm grant` lines are required: without the storage permissions every file looks missing (`stat` returns `exists=false`).

Smoke test (writes, reads back, checks the SHA-256, lists, deletes, and checks the refusals): `./test.sh 10`

## Security
No network permission and no UI. The provider is exported so that `adb shell content` can reach it, but every call from a uid other than 2000 throws `SecurityException`. It can delete files under `Pictures/` and `DCIM/` of the chosen user, so install it only on a device you control and uninstall it when finished. Provided as is, without warranty (see LICENSE).

## License
Apache-2.0. The idea and URI scheme follow AOSP's `TradefedContentProvider`; no AOSP source is included (see `NOTICE`).
