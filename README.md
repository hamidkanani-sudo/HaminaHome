# Hamina Home

**Android TV media browser for SMB network shares**

Hamina lets you browse video and photo files on Windows PCs and other SMB2/3 shares over your local network, using an Android TV remote.

## Included in this revision

- Android TV Leanback launcher entry with a dedicated TV banner and Hamina launcher icon.
- Hamina splash artwork as the launch window background.
- Add, edit and delete saved SMB server connections (long-press a server row for edit/delete).
- D-pad selectable rows; OK/Enter opens the selected server, folder or media item.
- Retry action is clickable after a network error.
- Back navigates one folder upward, then returns to the server list.
- Video playback remains in the built-in Media3 player.
- Photo files open in a full-screen viewer, streamed from SMB into the app cache.
- Directory listing cache and sorting from the original project are retained.

## Build an APK without Android Studio

This project includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`.

1. Create a GitHub repository named `HaminaHome` (or any name) under your account.
2. Upload the contents of this folder to the repository root (the `app`, `gradle`, `.github`, and root Gradle files should be directly inside the repository).
3. Open the repository's **Actions** tab and allow GitHub Actions if prompted.
4. Open **Build Hamina APK** and select **Run workflow**, or push a commit to `main` / `master`.
5. When the workflow succeeds, open that run and download the **Hamina-debug-apk** artifact. It contains `app-debug.apk`, which is signed for installation as a debug build.

The workflow installs JDK 17, Android SDK 35 and Gradle 8.9 on GitHub's build runner. It deliberately invokes the installed Gradle distribution directly, so a Gradle wrapper JAR is not required for the cloud build.

## Build locally (optional)

Install JDK 17, Android SDK 35 and Gradle 8.9, then run:

```bash
./build-apk.sh
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## First run

- The app opens on the Servers screen.
- Select **+ Add Server** to save an SMB connection.
- Enter the server IP/hostname, share name, and credentials if required.
- Long-press a saved server to edit or delete that connection. Deleting a saved connection does not delete files from the remote share.

## Important notes

- `minSdk` is Android 8.0 (API 26); `compileSdk` / `targetSdk` are 35.
- The APK produced by the workflow is a debug APK. It is suitable for testing and sideloading, but it is not a Play Store release build.
- No Android build could be executed in the editing environment because the Android SDK and Gradle executable are not installed there. The GitHub Actions workflow is included so the project can be built and verified in the cloud.
- The source still needs a real-device test against the user's SMB server and Android TV remote. In particular, SMB authentication, video playback and image decoding should be checked on the target TV box.

## Tech stack

- Kotlin, Jetpack Compose and AndroidX
- Media3 / ExoPlayer
- smbj (SMB2/3)
- DataStore and coroutines
- Coil for image display

---

**Hamina Home** — your media, fast and simple on the big screen.
