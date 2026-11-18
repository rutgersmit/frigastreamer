# Installs the latest FrigaStreamer release via ADB on all connected Android devices.
# Requires: gh (logged in: gh auth login) and adb (Android platform-tools).
#
#   powershell -ExecutionPolicy Bypass -File scripts\install.ps1 [-Tag v0.2.0] [-Repo owner/repo]
param(
    [string]$Tag = "",
    [string]$Repo = $env:REPO
)
$ErrorActionPreference = "Stop"
$Package = "com.rutgersmit.frigastreamer"
$Activity = "com.rutgersmit.frigastreamer/.ui.MainActivity"

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "'gh' (GitHub CLI) not found. Install it from https://cli.github.com and run 'gh auth login'." }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "'adb' not found. Install Android platform-tools and add the folder to PATH." }
gh auth status *> $null
if ($LASTEXITCODE -ne 0) { Fail "gh is not logged in. Run 'gh auth login' first." }

if (-not $Repo) {
    $Repo = (gh repo view --json nameWithOwner -q .nameWithOwner 2>$null)
    if (-not $Repo) { $Repo = "rutgersmit/frigastreamer" }
}

$Work = Join-Path ([System.IO.Path]::GetTempPath()) ("frigastreamer-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $Work | Out-Null
try {
    Write-Host "Downloading release $(if ($Tag) { $Tag } else { '(latest)' }) from $Repo ..."
    if ($Tag) { gh release download $Tag --repo $Repo --pattern "FrigaStreamer-*.apk" --dir $Work }
    else { gh release download --repo $Repo --pattern "FrigaStreamer-*.apk" --dir $Work }
    if ($LASTEXITCODE -ne 0) { Fail "Download failed. Is there a release yet? (gh release list --repo $Repo)" }

    $apks = @(Get-ChildItem -Path $Work -Filter "FrigaStreamer-*.apk")
    if ($apks.Count -ne 1) { Fail "Expected exactly one APK in the release, found: $($apks.Count)." }
    $Apk = $apks[0].FullName
    Write-Host "APK: $($apks[0].Name)"

    adb start-server *> $null
    $lines = adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne "" }
    $devices = @($lines | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] })
    $unauth = @($lines | Where-Object { $_ -match "\tunauthorized$" })
    if ($unauth.Count -gt 0) { Write-Host "Note: $($unauth.Count) device(s) 'unauthorized': confirm the USB debugging prompt on the screen." -ForegroundColor Yellow }
    if ($devices.Count -eq 0) { Fail "No connected devices. Use USB or 'adb pair' + 'adb connect <ip>:<port>' (see README)." }

    $rc = 0
    foreach ($d in $devices) {
        Write-Host "== ${d}: installing ..."
        $out = adb -s $d install -r $Apk 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) {
            Write-Host ($out.Trim().Split("`n") | Select-Object -Last 1)
            adb -s $d shell am start -n $Activity *> $null
            if ($LASTEXITCODE -eq 0) { Write-Host "   started" } else { Write-Host "   installed, but launching failed" }
        } else {
            Write-Host $out -ForegroundColor Red
            if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
                Write-Host "   Different signing key. Uninstall first: adb -s $d uninstall $Package (settings will be lost)"
            }
            $rc = 1
        }
    }
    exit $rc
} finally {
    Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
}
