# DimkaFtpManager

Native, local-first Android FTP / FTPS / SFTP + GitHub file deployment tool. No server or DPM required.

## Functions (v0.8.0)

- Save and test multiple FTP/FTPS/SFTP connections, navigate remote folders.
- Import FileZilla Site Manager XML on Android with a confirmation preview. Duplicate connections are merged by protocol, host, port, and username. Matching saved servers keep their IDs, names, root paths, and any passwords missing from the XML. Conflicting passwords in duplicate XML entries are reported in the preview; the last nonblank value wins. Implicit FTPS is also supported. The XML is never uploaded anywhere.
- Download remote file to `Downloads/DimkaFtpManager/Exports` or archive a folder as ZIP.
- Pick a file or ZIP in Android's document picker and upload it to the selected remote directory/file.
- Before replacement, back up existing remote files to `Downloads/DimkaFtpManager/Backups`.
- Create any number of named **GitHub ↔ FTP mappings**. Each mapping independently chooses GitHub repository/branch/file or folder, and FTP/FTPS/SFTP host/file or folder.
- Two manual directions: **GitHub → FTP** (with backup ZIP before overwrites) and **FTP → GitHub** (one Git commit for the selected files).
- File-to-file and folder-to-folder mappings only. Folder contents keep their relative paths even when GitHub and FTP have different parent directories. No delete mirroring.
- Each mapping has a compact dashboard row with status (matches/changed/error/not checked), last check timestamp and a one-tap Check button. Tap the row to open a detailed screen with a Back button, endpoint editors and Diff Check. Check All checks mappings sequentially.
- Legacy importer API remains in the code, but the primary GitHub screen now manages the saved mappings.
- Transfers run in Android WorkManager with a foreground notification and progress; failed jobs are visible in the history and can be restarted from the source action. Re-running retries at file level rather than resuming an arbitrary FTP stream byte-for-byte.

## GitHub authentication

**Recommended: Sign in with GitHub** in the app's Settings. This uses GitHub OAuth Device Flow with the registered public client ID (no client secret or backend). The app shows a short-lived code; tap **Copy code and open GitHub**, paste it on GitHub's verification page, and authorize. The app stores the OAuth access token encrypted in Android Keystore, handles polling rate limits and automatically refreshes expiring access tokens. After signing in, tap **Load my repositories** and select one; the app detects its default branch.

OAuth uses the `repo` scope, which grants broad access to the account's repositories. If you prefer per-repository restrictions, use a **fine-grained Personal Access Token** with **Contents: Read and write** permission, pasted into Settings. OAuth takes precedence if both are present; signing out of OAuth restores PAT usage.

Import requires an existing repository; for empty repositories, the app creates `.gitignore` to initialize its default branch.

OAuth Client ID is a public identifier. Do not put a Client Secret in the Android project. GitHub sign-in has not yet been tested on a physical device.

## Mappings and remote paths

In the **Связи** tab, tap **Добавить**, then select **GitHub → repository → file/folder** and **FTP → saved host → file/folder**. Rename the mapping if helpful. Tap either arrow to preview the planned file replacements and confirm the operation.

Each mapping has its own GitHub repository, default branch, path, FTP host and target path; there is no global repository-to-website pairing. Each saved server still has a root path (e.g. `/public_html`), and mapping selection is restricted to that root. Only connect servers and repositories you own or are authorized to maintain.

## Build

Android Studio: open repository, sync Gradle, run **app**. JDK 17, Android SDK 35.

**Direct APK:** https://github.com/DmitryLedentsov/dimka-ftp-manager/releases/download/android-latest/DimkaFtpManager.apk (published after a successful `master` build).

GitHub: **Releases → DimkaFtpManager — latest APK → Assets → DimkaFtpManager.apk**. This is a standalone APK file, not an artifact ZIP. The workflow also saves its original ZIP artifact under Actions, and can be triggered manually.

In **Settings → FTP / FTPS / SFTP → Import sites from FileZilla (XML)** select the Site Manager export (including nested folders). The app shows a dry-run summary, then applies only on confirmation. FileZilla export password values are merely base64-encoded, so treat the XML itself as sensitive and never commit it to Git.

CI uses Gradle installed by `gradle/actions/setup-gradle`, so no untracked binary wrapper JAR is needed.

## Full JSON backup and restore (v0.7)

Open **Настройки → Резервная копия приложения**:
- **Экспортировать всё (JSON)**: saves a portable, *unencrypted* JSON backup using Android's file picker. Includes FTP/FTPS/SFTP hosts and passwords, GitHub OAuth access/refresh tokens and PAT, mappings and comparison modes, check statuses/timestamps, ignore rules and pinned SFTP server fingerprints. No credentials are omitted.
- **Импортировать всё (JSON)**: choose a backup file, review contents and confirm overwrite. Replaces all settings and refreshes the live UI. The original Android Keystore key is NOT exported; credentials are encrypted anew on the target device.
- **Security:** JSON includes live credentials in plaintext. Do not commit it, share it or store it on a public drive. It does not include downloaded ZIP files or the Android WorkManager execution history.

## Installable APK signing

After configuring all four GitHub Actions repository secrets — `DIMKA_KEYSTORE_B64`, `DIMKA_STORE_PASSWORD`, `DIMKA_KEY_ALIAS`, `DIMKA_KEY_PASSWORD` — CI publishes a **stable-signed release APK** instead of a temporary debug APK.

One-time setup on your OWN computer: use [PowerShell](scripts/setup-apk-signing.ps1) on Windows or [Bash](scripts/setup-apk-signing.sh) on Linux/macOS/WSL. You need JDK, GitHub CLI and `gh auth login`. The script creates a private signing keystore locally, backs up its password locally, and uploads all four secrets. The private key must NEVER be checked into Git.

**First-time migration:** all existing APKs used ephemeral Android debug keys, so the first stable-signed APK cannot install over a differently signed installation. Preserve whatever configuration is possible before uninstalling the previous APK. After installing a stable-signed APK, subsequent updates signed by the same key retain app data.

Until secrets are configured, GitHub Actions still publishes temporary-debug APKs. The latest release description explicitly shows `[temporary-debug]` or `[stable-signed]`. Once a stable APK is released, CI refuses to replace it with an ephemeral debug-signed APK.

## Limitations

- No fully automatic sync, delete mirroring or merge conflict resolver. ZIP uploads only add/replace files; they do not delete other server files.
- GitHub import/commit supports source files up to 20 MiB each and rejects files matching sensitive patterns. Inspect import preview and ignore rules before confirmation. It does not guarantee detection of every secret.
- FTP is unencrypted; use FTPS or SFTP wherever available.
- Transfers can be interrupted by Android process restrictions; operations show failed status, and you can safely run them again.
- Before configuring permanent signing, debug APKs may require reinstall because Android checks signing certificates. Stable-signed releases preserve app data across future updates; protect the signing keystore.

## Content-aware synchronization (v0.5)

- Diff Check downloads the selected FTP file(s) into private temporary storage to compute Git-style blob SHA-1 and compares it to GitHub tree blob IDs. Same-content files are never transferred. Folder comparisons list only changed or one-sided files, with per-file text diff and checkboxes. UTF-8 text diff is limited to 256 KiB and 1,400 lines; binaries and files exceeding 20 MiB are not uploaded by the sync task.
- Source-only files on the opposite side are **not deleted**. Only checked changed/new files available in the chosen source direction are transferred. FTP uploads back up overwritten files to Downloads/DimkaFtpManager/Backups. FTP → GitHub produces a single commit with only selected files.
- Before applying, WorkManager rechecks current files' hashes against the preview snapshot; FTP targets are also verified immediately before uploading. Changed GitHub blob IDs in target paths cause FTP → GitHub to abort. If files have changed between check and write, the job fails rather than silently overriding edits. FTP does not support atomic compare-and-swap, so a tiny last-moment concurrency window remains.
- Checks can be slow for large folders: FTP requires reading each file to compare exact contents. No server-side daemon. Statuses and check/sync timestamps persist on-device. A sync marks status as needing a new check until Check is run again.

## Folder comparison modes (v0.6)

Each directory-to-directory mapping stores its own comparison mode:

- **All files** (default): compare GitHub and FTP path union. Includes files only on one side.
- **Common files only**: compare only paths present on both sides; all GitHub-only and FTP-only files are excluded from both Diff Check and transfers.
- **Common + new from GitHub**: compare paths present on GitHub. Includes shared files plus GitHub-only files, while omitting FTP-only files. This is useful for production template deployments where the FTP directory contains media, cache or runtime files that are not in GitHub.

Filtering is applied **before hashing and downloading FTP content**, not only in the UI. The directory trees still have to be listed to determine which paths exist. With file-to-file mappings the mode is irrelevant.

A fresh Diff Check selects all eligible changes by default. Buttons **Select all**, **Clear selection**, and **Invert selection** work only on actionable differences; matching or oversized files are excluded. Inversion is a one-time action, not a persistent flag. A change to the mode invalidates the old check status and its selected-file snapshot, so the next transfer requires a fresh Diff Check.

Transfers remain directional: with "Common + new from GitHub", a file that exists only on GitHub can be pushed to FTP but cannot be used as a source for FTP → GitHub.

## Selective comparison scope (v0.8)

In any directory-to-directory mapping, tap **Выбрать папки и файлы** before **Diff Check**. Browse the union of GitHub and FTP folders one level at a time. Set checkboxes for whole folders (including nested content) or individual files, or use **Всё / Снять всё** to select everything or nothing. Scope rules are persisted separately for each mapping; existing mappings default to comparing everything. Nested inclusion rules can override an excluded parent, so you can ignore `uploads/` while including an individual file under it.

The selection is applied **before FTP recursion**, skipping unselected branches entirely. GitHub tree items are filtered before the diff comparison and FTP hashing. After the scope filter, the three existing modes (all/common/common+new-GitHub) still apply; afterward, individual changed files can be checked or unchecked for transfer. Every WorkManager transfer rechecks the mapped scope with a fresh snapshot and rejects stale selections. Changing the mapping endpoint resets the scope to its default; changing the scope invalidates the previous check.

Folder navigation for scope configuration loads one level of GitHub/FTP metadata at a time; it doesn't download any file contents. The selected folder's contents are only downloaded during the subsequent Diff Check.


## Accurate diff for mixed line endings (v0.8.1)

The line-by-line Diff Check now treats Windows CRLF (`\r\n`) and Unix LF (`\n`) as the same logical newline. A difference only in EOL encoding no longer shows every line as both added and removed. When the normalized text matches but raw bytes differ, the diff explicitly explains the discrepancy.

**Important:** Content hashes, changed-file status and transfer preconditions remain byte-exact. An EOL-only change can still appear as a selectable changed file; it does not silently skip an overwrite or hide a conflict.
