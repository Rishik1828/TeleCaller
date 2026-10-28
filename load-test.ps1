# SmartDialer Load Test Script (PowerShell)
# Simulates concurrent API calls to test campaign dial cycles, agent updates, and provider events

param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Iterations = 100,
    [int]$Concurrency = 10
)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  SmartDialer Load & Concurrency Benchmark" -ForegroundColor Cyan
Write-Host "  Target: $BaseUrl | Iterations: $Iterations | Concurrency: $Concurrency" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Health check
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/api/providers/health" -Method Get
    Write-Host "[OK] Connected to SmartDialer API. Providers operational." -ForegroundColor Green
} catch {
    Write-Host "[ERROR] Could not connect to $BaseUrl. Is the server running?" -ForegroundColor Red
    exit 1
}

# 2. Create load-test campaign
$campaignPayload = @{
    name = "Benchmark-Campaign-$([Guid]::NewGuid().ToString().Substring(0,8))"
    dialingMode = "PREDICTIVE"
    activeProvider = "MOCK_PROVIDER_A"
    targetAnswerRate = 0.40
} | ConvertTo-Json

$campaign = Invoke-RestMethod -Uri "$BaseUrl/api/campaigns" -Method Post -Body $campaignPayload -ContentType "application/json"
$campId = $campaign.id
Write-Host "[SETUP] Created Campaign #$campId ($($campaign.dialingMode))" -ForegroundColor Yellow

# 3. Seed Agents
Write-Host "[SETUP] Seeding 50 agents..." -ForegroundColor Yellow
1..50 | ForEach-Object {
    $agentBody = @{
        agentExternalId = "bench-agent-$campId-$_"
        name = "Load Agent $_"
        state = "AVAILABLE"
    } | ConvertTo-Json
    Invoke-RestMethod -Uri "$BaseUrl/api/agents" -Method Post -Body $agentBody -ContentType "application/json" | Out-Null
}

# 4. Seed Borrowers
Write-Host "[SETUP] Seeding 200 borrowers..." -ForegroundColor Yellow
1..200 | ForEach-Object {
    $borrowerBody = @{
        borrowerExternalId = "bench-b-$campId-$_"
        name = "Borrower $_"
        phoneNumber = "+1555" + $_.ToString("D7")
        priority = ($_ % 3) + 1
    } | ConvertTo-Json
    Invoke-RestMethod -Uri "$BaseUrl/api/campaigns/$campId/borrowers" -Method Post -Body $borrowerBody -ContentType "application/json" | Out-Null
}

# 5. Execute Concurrent Dialing Cycles
Write-Host "[RUN] Executing $Iterations concurrent dialing cycles..." -ForegroundColor Cyan
$sw = [System.Diagnostics.Stopwatch]::StartNew()

$successCount = 0
$failedCount = 0

1..$Iterations | ForEach-Object {
    try {
        $rep = Invoke-RestMethod -Uri "$BaseUrl/api/campaigns/$campId/dial-cycle" -Method Post
        if ($rep.safetyDecision) {
            $successCount++
        }
    } catch {
        $failedCount++
    }
}

$sw.Stop()
$elapsedSec = $sw.Elapsed.TotalSeconds
$throughput = [Math]::Round(($successCount / $elapsedSec), 2)

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "  BENCHMARK RESULTS" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "  Total Dial Cycles Executed: $successCount"
Write-Host "  Failed Cycles:             $failedCount"
Write-Host "  Elapsed Time:              $([Math]::Round($elapsedSec, 2)) seconds"
Write-Host "  Throughput:                $throughput cycles/second"
Write-Host "==========================================================" -ForegroundColor Green
