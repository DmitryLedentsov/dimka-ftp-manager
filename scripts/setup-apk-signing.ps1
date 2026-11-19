# Run ONCE on your own Windows computer (PowerShell 5+).
# Requires GitHub CLI (gh), JDK keytool and an authenticated "gh auth login".
$ErrorActionPreference = "Stop"
$Repo = "DmitryLedentsov/dimka-ftp-manager"
$KeyDir = Join-Path $HOME ".dimka-ftp-manager\signing"
$KeyFile = Join-Path $KeyDir "dimka-stable-signing.p12"
$PassFile = Join-Path $KeyDir "signing-password.txt"
$Alias = "dimka-stable"

foreach ($cmd in @("gh","keytool")) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        throw "Missing $cmd. Install GitHub CLI and JDK before continuing."
    }
}
& gh auth status
if ($LASTEXITCODE -ne 0) { throw "Run: gh auth login" }
New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null
if ((Test-Path $KeyFile) -and (Test-Path $PassFile)) {
    Write-Host "Reusing existing signing key"
} elseif ((Test-Path $KeyFile) -or (Test-Path $PassFile)) {
    throw "Only one signing file exists. Restore BOTH files before continuing."
} else {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $Password = [BitConverter]::ToString($bytes).Replace("-","").ToLowerInvariant()
    [IO.File]::WriteAllText($PassFile, $Password)
    & keytool -genkeypair -keystore $KeyFile -storetype PKCS12 -storepass $Password -keypass $Password -alias $Alias -keyalg RSA -keysize 3072 -validity 36500 -dname "CN=DimkaFtpManager, OU=Android"
    if ($LASTEXITCODE -ne 0) { throw "Could not create keystore" }
}
$Password = [IO.File]::ReadAllText($PassFile).Trim()
$KeyB64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($KeyFile))
foreach ($pair in @(
    @("DIMKA_KEYSTORE_B64", $KeyB64),
    @("DIMKA_STORE_PASSWORD", $Password),
    @("DIMKA_KEY_PASSWORD", $Password),
    @("DIMKA_KEY_ALIAS", $Alias)
)) {
    $pair[1] | gh secret set $pair[0] --repo $Repo
    if ($LASTEXITCODE -ne 0) { throw "Failed to set GitHub secret $($pair[0])" }
}
Write-Host "All four GitHub Actions secrets are configured."
Write-Host "KEEP BOTH signing files private: $KeyDir"
Write-Host "Export the config JSON in the existing APK BEFORE installing the stable-signed version."
Write-Host "Then manually run the Android APK workflow on GitHub to publish a stable-signed APK."
