# DimkaFtpManager

Native, local-first Android FTP / FTPS / SFTP + GitHub file deployment tool. No server or DPM required.

## Functions (v0.1)

- Save and test multiple FTP/FTPS/SFTP connections, navigate remote folders.
- Download remote file to `Downloads/DimkaFtpManager/Exports` or archive a folder as ZIP.
- Pick a file or ZIP in Android's document picker and upload it to the selected remote directory/file.
- Before replacement, back up existing remote files to `Downloads/DimkaFtpManager/Backups`.
- Connect GitHub with a fine-grained personal access token, browse repository files, choose files and deploy them to a configured server root.
- First import from server to a GitHub repository with ignore rules and a preview, creating a single Git commit.
- Transfers run in Android WorkManager with a foreground notification and progress; failed jobs are visible in the history and can be restarted from the source action. Re-running retries at file level rather than resuming an arbitrary FTP stream byte-for-byte.

## GitHub authentication

Create a fine-grained PAT restricted to target repositories with **Contents: Read and write** permission. Paste it into the app's **Settings** screen. Import requires an existing repository; for empty repositories, the app creates `.gitignore` to initialize the default branch.

## Remote paths

Each saved server has a **root path** (e.g. `/public_html`) used as the upload target for GitHub project files. The manual FTP browser is scoped to that root. Only add servers and repositories that you own or have permission to maintain.

## Build

Android Studio: open repository, sync Gradle, run **app**. JDK 17, Android SDK 35.

GitHub: **Actions → Android APK → latest successful run → Artifacts → DimkaFtpManager-debug-apk**. The workflow runs on pushes to `master` and can be triggered manually.

CI uses Gradle installed by `gradle/actions/setup-gradle`, so no untracked binary wrapper JAR is needed.

## Limitations

- No fully automatic sync, delete mirroring or merge conflict resolver. ZIP uploads only add/replace files; they do not delete other server files.
- GitHub import/commit supports source files up to 20 MiB each and rejects files matching sensitive patterns. Inspect import preview and ignore rules before confirmation. It does not guarantee detection of every secret.
- FTP is unencrypted; use FTPS or SFTP wherever available.
- Transfers can be interrupted by Android process restrictions; operations show failed status, and you can safely run them again.
- The debug APK is not signed for Play Store distribution; it uses the Android debug key for manual installation.
