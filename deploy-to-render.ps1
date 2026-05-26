# Phone Tunnel — one-shot Render deploy script.
#
# Run this once. It will:
#   1. Prompt for your Render API key (https://dashboard.render.com/u/settings#api-keys)
#   2. Prompt for your Cloudflare TURN API Token
#   3. Create the web service on Render's free plan in Singapore
#   4. Set CF_APP_ID + CF_API_TOKEN env vars
#   5. Wait for the first build/deploy to complete
#   6. Print the public URL you paste into the Phone Tunnel app
#
# Re-running creates a new service. If a service named "phonetunnel-relay"
# already exists, this script will fail with a clear error — delete the old
# one in the Render dashboard first.

param(
    [string]$Region = "singapore",   # change to ohio / frankfurt / oregon if closer
    [string]$ServiceName = "phonetunnel-relay"
)

$ErrorActionPreference = "Stop"

# Hardcoded — public, not a secret
$CfAppId = "cc3c9f8241f98f8f8b02185632f3549c"
$RepoUrl = "https://github.com/maninder0063/phonetunnel-relay"
$Api = "https://api.render.com/v1"

function Read-Secret($prompt) {
    $sec = Read-Host -Prompt $prompt -AsSecureString
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try { [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr) }
    finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

Write-Host ""
Write-Host "Phone Tunnel - Render deploy" -ForegroundColor Cyan
Write-Host "----------------------------" -ForegroundColor Cyan
Write-Host "Repo:    $RepoUrl"
Write-Host "Region:  $Region"
Write-Host "Name:    $ServiceName"
Write-Host ""

$RenderKey = Read-Secret "Render API key (rnd_...)"
if (-not $RenderKey) { throw "API key cannot be empty." }
$CfToken = Read-Secret "Cloudflare TURN API Token"
if (-not $CfToken) { throw "Cloudflare token cannot be empty." }

$headers = @{
    "Authorization" = "Bearer $RenderKey"
    "Content-Type"  = "application/json"
    "Accept"        = "application/json"
}

# ---------------------------------------------------------------------------
# 1. Look up owner ID (Render needs to know which workspace owns the service)
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Resolving Render workspace..." -ForegroundColor Yellow
$ownersRaw = Invoke-RestMethod -Method Get -Uri "$Api/owners" -Headers $headers
$owner = if ($ownersRaw -is [array]) { $ownersRaw[0].owner } else { $ownersRaw.owner }
if (-not $owner) { throw "Could not resolve Render owner. Is the API key valid?" }
$OwnerId = $owner.id
Write-Host "  Owner: $($owner.name) ($OwnerId)" -ForegroundColor Gray

# ---------------------------------------------------------------------------
# 2. Create the web service
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Creating service '$ServiceName'..." -ForegroundColor Yellow
$body = @{
    type      = "web_service"
    name      = $ServiceName
    ownerId   = $OwnerId
    repo      = $RepoUrl
    branch    = "main"
    autoDeploy = "yes"
    serviceDetails = @{
        env             = "docker"
        plan            = "free"
        region          = $Region
        healthCheckPath = "/health"
        envSpecificDetails = @{
            dockerfilePath = "./Dockerfile"
        }
    }
    envVars = @(
        @{ key = "CF_APP_ID";    value = $CfAppId }
        @{ key = "CF_API_TOKEN"; value = $CfToken }
    )
} | ConvertTo-Json -Depth 8

try {
    $created = Invoke-RestMethod -Method Post -Uri "$Api/services" -Headers $headers -Body $body
} catch {
    $resp = $_.Exception.Response
    if ($resp) {
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $errBody = $reader.ReadToEnd()
        throw "Render rejected the service creation:`n$errBody"
    }
    throw
}

$service = $created.service
$serviceId = $service.id
$serviceUrl = $service.serviceDetails.url
Write-Host "  Service ID:  $serviceId" -ForegroundColor Gray
Write-Host "  URL (live once build finishes):  $serviceUrl" -ForegroundColor Gray

# ---------------------------------------------------------------------------
# 3. Poll for first deploy completion
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Waiting for first build to finish (typically 5-8 min)..." -ForegroundColor Yellow
Write-Host "  (You can watch live logs at: https://dashboard.render.com/web/$serviceId)" -ForegroundColor Gray

$startedAt = Get-Date
$lastStatus = ""
while ($true) {
    Start-Sleep -Seconds 15
    $elapsedSec = [int]((Get-Date) - $startedAt).TotalSeconds
    if ($elapsedSec -gt 900) { Write-Warning "Build is taking longer than 15 min — check Render dashboard for issues."; break }

    try {
        $deploys = Invoke-RestMethod -Method Get -Uri "$Api/services/$serviceId/deploys?limit=1" -Headers $headers
    } catch {
        Write-Host "  poll error: $($_.Exception.Message), retrying..." -ForegroundColor DarkGray
        continue
    }
    $deploy = if ($deploys -is [array] -and $deploys.Count -gt 0) { $deploys[0].deploy } else { $null }
    if (-not $deploy) { Write-Host "  no deploy entry yet [+${elapsedSec}s]" -ForegroundColor DarkGray; continue }
    $status = $deploy.status
    if ($status -ne $lastStatus) {
        Write-Host "  [${elapsedSec}s] status: $status" -ForegroundColor Gray
        $lastStatus = $status
    }
    if ($status -eq "live") { break }
    if ($status -in @("build_failed", "update_failed", "canceled", "deactivated")) {
        Write-Host ""
        Write-Host "Deploy ended in status '$status'. See logs:" -ForegroundColor Red
        Write-Host "  https://dashboard.render.com/web/$serviceId/logs" -ForegroundColor Yellow
        exit 1
    }
}

# ---------------------------------------------------------------------------
# 4. Sanity-check /health
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "Verifying /health endpoint..." -ForegroundColor Yellow
try {
    $health = Invoke-WebRequest -Uri "$serviceUrl/health" -TimeoutSec 30 -UseBasicParsing
    if ($health.StatusCode -eq 200) { Write-Host "  /health -> $($health.Content.Trim())" -ForegroundColor Green }
    else { Write-Warning "Unexpected status code: $($health.StatusCode)" }
} catch {
    Write-Warning "Could not reach /health yet (still warming up?). Try again in 30s."
}

# ---------------------------------------------------------------------------
# 5. Done
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " DONE." -ForegroundColor Cyan
Write-Host ""
Write-Host " Your signaling server is live at:" -ForegroundColor Cyan
Write-Host ""
Write-Host "    $serviceUrl" -ForegroundColor White -BackgroundColor DarkGreen
Write-Host ""
Write-Host " Paste that URL into the Phone Tunnel app's Settings on BOTH phones," -ForegroundColor Cyan
Write-Host " then pair as usual." -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
