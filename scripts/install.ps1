# Installeert de nieuwste FrigaStreamer-release via ADB op alle verbonden Android-apparaten.
# Vereist: gh (ingelogd: gh auth login) en adb (Android platform-tools).
#
#   powershell -ExecutionPolicy Bypass -File scripts\install.ps1 [-Tag v0.2.0] [-Repo eigenaar/repo]
param(
    [string]$Tag = "",
    [string]$Repo = $env:REPO
)
$ErrorActionPreference = "Stop"
$Package = "io.github.rutgersmit.frigastreamer"
$Activity = "io.github.rutgersmit.frigastreamer/.ui.MainActivity"

function Fail($msg) { Write-Host "FOUT: $msg" -ForegroundColor Red; exit 1 }

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "'gh' (GitHub CLI) niet gevonden. Installeer via https://cli.github.com en voer 'gh auth login' uit." }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "'adb' niet gevonden. Installeer Android platform-tools en zet de map in PATH." }
gh auth status *> $null
if ($LASTEXITCODE -ne 0) { Fail "gh is niet ingelogd. Voer eerst 'gh auth login' uit." }

if (-not $Repo) {
    $Repo = (gh repo view --json nameWithOwner -q .nameWithOwner 2>$null)
    if (-not $Repo) { Fail "Kan de repository niet bepalen. Draai vanuit de repository of geef -Repo eigenaar/repo." }
}

$Work = Join-Path ([System.IO.Path]::GetTempPath()) ("frigastreamer-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $Work | Out-Null
try {
    Write-Host "Release $(if ($Tag) { $Tag } else { '(nieuwste)' }) downloaden uit $Repo ..."
    if ($Tag) { gh release download $Tag --repo $Repo --pattern "frigate-viewer-*.apk" --dir $Work }
    else { gh release download --repo $Repo --pattern "frigate-viewer-*.apk" --dir $Work }
    if ($LASTEXITCODE -ne 0) { Fail "Download mislukt. Bestaat er al een release? (gh release list --repo $Repo)" }

    $apks = @(Get-ChildItem -Path $Work -Filter "frigate-viewer-*.apk")
    if ($apks.Count -ne 1) { Fail "Verwacht precies één APK in de release, gevonden: $($apks.Count)." }
    $Apk = $apks[0].FullName
    Write-Host "APK: $($apks[0].Name)"

    adb start-server *> $null
    $lines = adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne "" }
    $devices = @($lines | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] })
    $unauth = @($lines | Where-Object { $_ -match "\tunauthorized$" })
    if ($unauth.Count -gt 0) { Write-Host "Let op: $($unauth.Count) apparaat/apparaten 'unauthorized': bevestig de USB-debugging-vraag op het scherm." -ForegroundColor Yellow }
    if ($devices.Count -eq 0) { Fail "Geen verbonden apparaten. Gebruik USB of 'adb pair' + 'adb connect <ip>:<poort>' (zie README)." }

    $rc = 0
    foreach ($d in $devices) {
        Write-Host "== ${d}: installeren ..."
        $out = adb -s $d install -r $Apk 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) {
            Write-Host ($out.Trim().Split("`n") | Select-Object -Last 1)
            adb -s $d shell am start -n $Activity *> $null
            if ($LASTEXITCODE -eq 0) { Write-Host "   gestart" } else { Write-Host "   installatie gelukt, starten mislukt" }
        } else {
            Write-Host $out -ForegroundColor Red
            if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
                Write-Host "   Andere ondertekeningssleutel. Verwijder eerst: adb -s $d uninstall $Package (instellingen gaan verloren)"
            }
            $rc = 1
        }
    }
    exit $rc
} finally {
    Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
}
