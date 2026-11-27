# Hamina Home

**Android TV media browser for SMB network shares**

Hamina lets you browse video and photo files on Windows PCs and other SMB2/3 shares over your local network, using an Android TV remote.

## Changes in 1.3.0

- New look: dark theme, rounded cards, vector icons, larger text and TV-safe margins. Focused items grow and get an accent border, so the current selection is always visible.
- New home screen with a "Continue watching" row (resume directly) and server cards. "Edit servers" mode (or the Menu key on a server) opens the edit form.
- List and Grid views with a toggle in the top bar (the choice is saved). A bottom bar shows the full name, size and date of the focused item.
- Sort, view and refresh are separate buttons in the top bar instead of hints that all said "OK". Repeated key events are filtered so one press triggers one action.
- Shares are now listed from the server (MS-SRVS) instead of guessing common names, so all disk shares show up when no share name is configured.

## Changes in 1.2.0

- Resume works: media ids are now stable (hash of server + path) instead of random UUIDs; resume info and "Last played" sorting are filled from saved positions.
- Playback streams with the real file length and reads at the requested offset, so seeking works; special characters in file names are encoded safely; playing from "all shares" servers is fixed.
- Player is re-created after Home/app switch; position is saved reliably (also when leaving with Back) and cleared when a video is watched to the end.
- Retry works after a failed first connection; Back/Retry/focus are handled by a ViewModel (focus returns to the folder you came from; state survives configuration changes).
- SMB passwords are encrypted with the Android Keystore (existing passwords are migrated on first launch); backups, cleartext traffic and storage permissions are disabled/removed.
- SMB file handles and share connections are closed; sessions are refreshed when server settings change.
- Folder cache uses JSON; natural sort ("Episode 2" before "Episode 10"); Windows system folders are hidden; unused dependencies removed.

## Included in the previous revision

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
