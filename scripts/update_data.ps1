#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Verifie les mises a jour des sources de donnees, declenche le build Docker,
    et synchronise le resultat vers le NAS.

.NOTES
    Tout le travail lourd (telechargement parquet + PBF, preprocessing, import GH)
    tourne dans le container Docker via docker-entrypoint.sh.
    Ce script se contente de : verifier les ETags, trigger Docker, sync NAS.

    Planification (Task Scheduler) -- executer une seule fois register_task.ps1.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# BurntToast : notifications Windows toast
if (-not (Get-Module -ListAvailable -Name BurntToast)) {
    Install-Module BurntToast -Scope CurrentUser -Force -SkipPublisherCheck
}
Import-Module BurntToast -ErrorAction SilentlyContinue

# NotifyIcon : icone systray pendant le build
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$script:Tray = $null

# ============================================================
# CONFIGURATION
# ============================================================
$RepoRoot  = Split-Path $PSScriptRoot -Parent
$DataDir   = Join-Path $RepoRoot "data"
$EtagFile  = Join-Path $DataDir ".etags.json"
$LogFile   = Join-Path $PSScriptRoot "update_data.log"
$NasPath   = "\\FALSENAS\partage windows\routeur_panoramax"

$ParquetUrl = "https://api.panoramax.xyz/data/geoparquet/panoramax.parquet"
$PbfRegion  = if ($env:OSM_REGION) { $env:OSM_REGION } else { "france" }
$PbfUrls    = @{
    "france" = "https://download.geofabrik.de/europe/france-latest.osm.pbf"
    "centre" = "https://download.geofabrik.de/europe/france/centre-latest.osm.pbf"
}
$PbfUrl = $PbfUrls[$PbfRegion]

$ImportTimeoutSec  = 7200   # 2h max pour le build
$DockerStartupSec  = 180    # delai max pour que Docker Desktop demarre
$DockerDesktopExe  = "C:\Program Files\Docker\Docker\Docker Desktop.exe"

# ============================================================
# LOGGING
# ============================================================
function Log($msg) {
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $msg"
    Write-Host $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

# ============================================================
# FONCTIONS
# ============================================================
function Get-RemoteHeaders($url) {
    (Invoke-WebRequest -Uri $url -Method Head -UseBasicParsing).Headers
}

function Load-Etags {
    if (Test-Path $EtagFile) {
        $j = Get-Content $EtagFile -Raw | ConvertFrom-Json
        return @{ parquet = $j.parquet; pbf = $j.pbf }
    }
    return @{ parquet = ""; pbf = "" }
}

function Save-Etags($parquet, $pbf) {
    @{ parquet = $parquet; pbf = $pbf } | ConvertTo-Json | Set-Content $EtagFile -Encoding UTF8
    Log "ETags sauvegardes : parquet=$parquet pbf=$pbf"
}

function Download-File($url, $dest) {
    Log "Telechargement : $(Split-Path $dest -Leaf)..."
    $wc = New-Object System.Net.WebClient
    try { $wc.DownloadFile($url, $dest) }
    finally { $wc.Dispose() }
    $size = (Get-Item $dest).Length
    Log "  -> OK ($([Math]::Round($size/1MB, 1)) MB)"
}

function Test-DockerRunning {
    try { docker info 2>$null | Out-Null; return $LASTEXITCODE -eq 0 }
    catch { return $false }
}

function Start-DockerIfNeeded {
    if (Test-DockerRunning) {
        Log "Docker est deja en cours d'execution."
        return $false   # pas demarre par nous
    }
    Log "Demarrage de Docker Desktop..."
    Start-Process -FilePath $DockerDesktopExe
    $deadline = (Get-Date).AddSeconds($DockerStartupSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep 5
        if (Test-DockerRunning) {
            Log "Docker Desktop pret."
            return $true   # demarre par nous -> a arreter apres
        }
    }
    throw "Docker Desktop n'a pas demarre dans les ${DockerStartupSec}s."
}

function Stop-DockerIfStartedByUs($weStartedIt) {
    if (-not $weStartedIt) { return }
    Log "Arret de Docker Desktop..."
    Stop-Process -Name "Docker Desktop" -Force -ErrorAction SilentlyContinue
}

function Show-Tray($status) {
    if (-not $script:Tray) {
        $script:Tray = New-Object System.Windows.Forms.NotifyIcon
        $script:Tray.Icon = [System.Drawing.SystemIcons]::Information
        $script:Tray.Visible = $true
    }
    $script:Tray.Text = "GraphHopper - $status"
    [System.Windows.Forms.Application]::DoEvents()
}

function Hide-Tray {
    if ($script:Tray) {
        $script:Tray.Visible = $false
        $script:Tray.Dispose()
        $script:Tray = $null
    }
}

function Notify($title, $message, $sound = $null) {
    try {
        if ($sound) {
            New-BurntToastNotification -Text $title, $message -Sound $sound
        } else {
            New-BurntToastNotification -Text $title, $message
        }
    } catch {
        Log "Notification impossible : $_"
    }
}

function Wait-GraphHopperReady($timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    Log "Attente que GraphHopper soit pret (timeout ${timeoutSec}s)..."
    while ((Get-Date) -lt $deadline) {
        try {
            if ((Invoke-WebRequest "http://localhost:8989/info" -UseBasicParsing -TimeoutSec 10).StatusCode -eq 200) {
                Log "GraphHopper est pret."
                return
            }
        } catch {}
        Start-Sleep 30
    }
    throw "Timeout : GraphHopper n'a pas repondu apres ${timeoutSec}s."
}

# ============================================================
# 1. VERIFICATION DES ETAGS
# ============================================================
Log "=== Verification des mises a jour ==="
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

$parquetHeaders = Get-RemoteHeaders $ParquetUrl
$parquetEtag    = $parquetHeaders["ETag"]
$parquetLastMod = $parquetHeaders["Last-Modified"]
$pbfLastMod     = (Get-RemoteHeaders $PbfUrl)["Last-Modified"]

$stored = Load-Etags

$parquetUpdated = $parquetEtag -ne $stored.parquet
$pbfUpdated     = $pbfLastMod  -ne $stored.pbf

if (-not $parquetUpdated) {
    Log "Parquet inchange -- aucun recalcul necessaire. Fin du script."
    exit 0
}

Log "Nouveau parquet : $parquetEtag"
if ($pbfUpdated) { Log "Nouveau PBF     : $pbfLastMod" }

Notify "GraphHopper - Build demarre" "Nouvelles donnees detectees, build en cours..."
Show-Tray "Nettoyage du cache..."

# ============================================================
# 2. NETTOYAGE DU CACHE LOCAL
# ============================================================
Log "Nettoyage du cache..."
Remove-Item -Recurse -Force "$DataDir\graph-cache"          -ErrorAction SilentlyContinue
Remove-Item -Force          "$DataDir\panoramax_coverage.*"  -ErrorAction SilentlyContinue

# ============================================================
# 3. TELECHARGEMENTS (Windows natif, evite le bridge WSL2)
# ============================================================
Show-Tray "Telechargement parquet + PBF..."
Remove-Item -Force "$DataDir\panoramax.parquet"          -ErrorAction SilentlyContinue
Remove-Item -Force "$DataDir\panoramax.parquet.lastmod"  -ErrorAction SilentlyContinue
Download-File $ParquetUrl "$DataDir\panoramax.parquet"
if ($parquetLastMod) {
    $parquetDate = [DateTime]::Parse($parquetLastMod).ToString("yyyy-MM-dd")
    Set-Content "$DataDir\panoramax.parquet.lastmod" $parquetDate -Encoding ascii -NoNewline
    Log "Date Last-Modified parquet : $parquetDate"
}
if ($pbfLastMod -ne $stored.pbf) {
    Remove-Item -Force "$DataDir\$PbfRegion-latest.osm.pbf" -ErrorAction SilentlyContinue
    Download-File $PbfUrl "$DataDir\$PbfRegion-latest.osm.pbf"
}

# ============================================================
# 4. BUILD DANS DOCKER (fichiers deja presents, pas de telechargement)
# ============================================================
Log "=== Demarrage du build Docker ==="
Set-Location $RepoRoot
$weStartedDocker = Start-DockerIfNeeded

$env:DOCKER_CPUS = [Math]::Max(1, [Math]::Floor($env:NUMBER_OF_PROCESSORS / 2))
Log "CPU alloues au container : $($env:DOCKER_CPUS) / $($env:NUMBER_OF_PROCESSORS)"
Show-Tray "Preprocessing + import GraphHopper..."
docker compose up -d
Remove-Item Env:\DOCKER_CPUS -ErrorAction SilentlyContinue

try {
    Show-Tray "Import GraphHopper en cours..."
    Wait-GraphHopperReady $ImportTimeoutSec
} catch {
    Log "ERREUR pendant le build : $_"
    docker compose logs --tail 80 graphhopper | ForEach-Object { Log "  [docker] $_" }
    docker compose stop
    Stop-DockerIfStartedByUs $weStartedDocker
    Notify "GraphHopper - Erreur build" "$_" -sound "Alarm"
    Hide-Tray
    exit 1
}

Log "Build termine -- arret du container."
Show-Tray "Sync vers le NAS..."
docker compose stop
Stop-DockerIfStartedByUs $weStartedDocker

# ============================================================
# 5. SYNCHRONISATION VERS LE NAS
# ============================================================
# Les ETags sont sauvegardés avant la sync NAS : le build est terminé,
# on évite un re-téléchargement/rebuild si le NAS est temporairement inaccessible.
Save-Etags $parquetEtag $pbfLastMod

Log "=== Sync vers le NAS ($NasPath) ==="
try {
    [System.IO.Directory]::CreateDirectory($NasPath) | Out-Null

    @{
        parquet      = $parquetEtag
        pbf          = $pbfLastMod
        built_at     = (Get-Date -Format "o")
        region       = $PbfRegion
    } | ConvertTo-Json | Set-Content "$NasPath\.version.json" -Encoding UTF8

    robocopy $DataDir $NasPath /E /PURGE /Z /MT:8 /NFL /NDL /NJH /NJS
    if ($LASTEXITCODE -ge 8) { throw "robocopy data : code $LASTEXITCODE" }

    Log "=== Termine avec succes ==="
    Notify "GraphHopper - Build termine" "Graph-cache synchronise vers le NAS avec succes."
} catch {
    Log "ERREUR sync NAS : $_"
    Notify "GraphHopper - Erreur NAS" "Sync NAS echouee : $_" -sound "Alarm"
    Hide-Tray
    exit 1
}
Hide-Tray
