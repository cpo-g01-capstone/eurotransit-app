<#
.SYNOPSIS
    EM-21 Verification Script — Graceful Shutdown & Saga Compensation
.DESCRIPTION
    Automated test for the EM-21 implementation following the Verification Guide.
    Part 1: Graceful Shutdown (SIGTERM handling with delay injection)
    Part 2: Saga Compensation (RuntimeException injection for payment failure)
.NOTES
    Prerequisites: Docker Desktop running, JDK 21 available.
    Part 1 — inietta delay(10000), avvia bootRun, invia ordine, trigger shutdown via Actuator, valida i log per Commencing graceful shutdown e SIGTERM received
    Part 2 — inietta throw RuntimeException("Insufficient funds!"), avvia bootRun, invia ordine, valida i log per Saga Compensation: Emitting inventory-release
    Cleanup — ripristina i file originali via git checkout, rimuove log temporanei
    Run from the eurotransit-app root directory.
#>

param(
    [string]$JavaHome = "$env:USERPROFILE\.jdks\jdk-21.0.11+10",
    [int]$StartupTimeoutSeconds = 120,
    [int]$ShutdownWaitSeconds = 15
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# ── Paths ─────────────────────────────────────────────────────────────────────
$ProjectRoot      = $PSScriptRoot | Split-Path   # assumes script is in scripts/
$PipelineFile     = "$ProjectRoot\backend\orders-service\src\main\kotlin\com\eurotransit\orders\pipeline\OrderPipelineCoordinator.kt"
$AppYmlFile        = "$ProjectRoot\backend\orders-service\src\main\resources\application.yml"
$OrderUrl         = "http://localhost:8082/orders"
$ShutdownUrl      = "http://localhost:8082/actuator/shutdown"
$HealthUrl        = "http://localhost:8082/actuator/health"

$OrderBody = '{"customerId":"cust-test","routeId":"rt-89","seatClass":"STANDARD","quantity":2,"totalAmount":45.00}'

# ── Colors ────────────────────────────────────────────────────────────────────
function Write-Step   { param($m) Write-Host "`n▶ $m" -ForegroundColor Cyan }
function Write-Ok     { param($m) Write-Host "  ✅ $m" -ForegroundColor Green }
function Write-Fail   { param($m) Write-Host "  ❌ $m" -ForegroundColor Red }
function Write-Info   { param($m) Write-Host "  ℹ  $m" -ForegroundColor DarkGray }

# ── Helpers ───────────────────────────────────────────────────────────────────

function Ensure-DockerContainer {
    param([string]$Name, [string]$RunArgs)
    
    $existing = docker ps -a --filter "name=^${Name}$" --format "{{.Names}}" 2>$null
    if ($existing -eq $Name) {
        $running = docker ps --filter "name=^${Name}$" --format "{{.Names}}" 2>$null
        if ($running -eq $Name) {
            Write-Info "$Name already running"
            return
        }
        docker start $Name | Out-Null
        Write-Info "$Name started (existing container)"
        return
    }
    
    Write-Info "Creating $Name container..."
    Invoke-Expression "docker run -d --name $Name $RunArgs" | Out-Null
    Write-Ok "$Name created and started"
}

function Wait-ForPort {
    param([int]$Port, [int]$TimeoutSec = 60, [string]$Label = "service")
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect("localhost", $Port)
            $tcp.Close()
            return $true
        } catch { Start-Sleep -Seconds 2 }
    }
    return $false
}

function Wait-ForSpringBoot {
    param([int]$TimeoutSec = 120)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        try {
            $r = Invoke-WebRequest -Uri $HealthUrl -TimeoutSec 2 -ErrorAction SilentlyContinue
            if ($r.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Start-BootRun {
    Write-Info "Starting orders-service (bootRun)..."
    $env:JAVA_HOME = $JavaHome
    $process = Start-Process -FilePath "$ProjectRoot\gradlew.bat" `
        -ArgumentList ":backend:orders-service:bootRun" `
        -WorkingDirectory $ProjectRoot `
        -PassThru -NoNewWindow `
        -RedirectStandardOutput "$ProjectRoot\scripts\bootrun-stdout.log" `
        -RedirectStandardError "$ProjectRoot\scripts\bootrun-stderr.log"
    return $process
}

function Stop-BootRunGraceful {
    Write-Info "Sending shutdown via Actuator..."
    try {
        Invoke-WebRequest -Method POST -Uri $ShutdownUrl -ContentType "application/json" -TimeoutSec 30 -ErrorAction SilentlyContinue | Out-Null
    } catch { }
}

function Get-LogContent {
    $stdout = ""; $stderr = ""
    if (Test-Path "$ProjectRoot\scripts\bootrun-stdout.log") {
        $stdout = Get-Content "$ProjectRoot\scripts\bootrun-stdout.log" -Raw -ErrorAction SilentlyContinue
    }
    if (Test-Path "$ProjectRoot\scripts\bootrun-stderr.log") {
        $stderr = Get-Content "$ProjectRoot\scripts\bootrun-stderr.log" -Raw -ErrorAction SilentlyContinue
    }
    return "$stdout`n$stderr"
}

function Cleanup-Logs {
    Remove-Item "$ProjectRoot\scripts\bootrun-stdout.log" -ErrorAction SilentlyContinue
    Remove-Item "$ProjectRoot\scripts\bootrun-stderr.log" -ErrorAction SilentlyContinue
}

function Restore-SourceFiles {
    Write-Info "Restoring source files via git checkout..."
    Push-Location $ProjectRoot
    git checkout -- $PipelineFile $AppYmlFile 2>$null
    Pop-Location
}

# ══════════════════════════════════════════════════════════════════════════════
#  MAIN
# ══════════════════════════════════════════════════════════════════════════════

Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Yellow
Write-Host "║   EM-21 Verification: Graceful Shutdown & Saga Compensation ║" -ForegroundColor Yellow
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Yellow

$part1Pass = $false
$part2Pass = $false

try {
    # ── Prerequisites ─────────────────────────────────────────────────────────
    Write-Step "Checking prerequisites"

    # JDK 21
    if (-not (Test-Path "$JavaHome\bin\java.exe")) {
        Write-Fail "JDK 21 not found at $JavaHome. Set -JavaHome parameter."
        exit 1
    }
    $javaVer = & "$JavaHome\bin\java.exe" -version 2>&1 | Select-Object -First 1
    Write-Ok "JDK: $javaVer"

    # Docker
    docker info 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "Docker is not running. Start Docker Desktop first."
        exit 1
    }
    Write-Ok "Docker is running"

    # PostgreSQL
    Ensure-DockerContainer -Name "ordersdb" -RunArgs `
        "-e POSTGRES_DB=ordersdb -e POSTGRES_USER=app -e POSTGRES_PASSWORD=app -p 5432:5432 postgres:16"

    # Kafka
    Ensure-DockerContainer -Name "kafka" -RunArgs `
        "-p 9092:9092 -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk apache/kafka:latest"

    # Wait for ports
    Write-Info "Waiting for PostgreSQL (5432)..."
    if (-not (Wait-ForPort -Port 5432 -TimeoutSec 30 -Label "PostgreSQL")) {
        Write-Fail "PostgreSQL not reachable on port 5432"; exit 1
    }
    Write-Ok "PostgreSQL ready"

    Write-Info "Waiting for Kafka (9092)..."
    if (-not (Wait-ForPort -Port 9092 -TimeoutSec 30 -Label "Kafka")) {
        Write-Fail "Kafka not reachable on port 9092"; exit 1
    }
    Write-Ok "Kafka ready"

    # ══════════════════════════════════════════════════════════════════════════
    #  PART 1: Graceful Shutdown
    # ══════════════════════════════════════════════════════════════════════════
    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Magenta
    Write-Host "  PART 1: Graceful Shutdown (SIGTERM Handling)" -ForegroundColor Magenta
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Magenta

    # Step 1.1 — Inject delay + enable shutdown endpoint
    Write-Step "Step 1.1: Injecting delay(10000) into onOrderPlaced"

    $pipelineContent = Get-Content $PipelineFile -Raw
    $modified = $pipelineContent -replace `
        '(log\.info\("Mock: Reserved inventory for order \{}", orderId\)\s*\r?\n\s*)(withContext\(NonCancellable\))', `
        ('$1' + "            // --> TEST: delay for SIGTERM testing <--`r`n            delay(10000)`r`n`r`n            " + '$2')

    # Add delay import if missing
    if ($modified -notmatch 'import kotlinx\.coroutines\.delay') {
        $modified = $modified -replace `
            '(import kotlinx\.coroutines\.coroutineScope)', `
            ('$1' + "`r`nimport kotlinx.coroutines.delay")
    }
    Set-Content -Path $PipelineFile -Value $modified -NoNewline
    Write-Ok "delay(10000) injected"

    # Enable shutdown actuator
    $ymlContent = Get-Content $AppYmlFile -Raw
    $ymlModified = $ymlContent -replace `
        '(include: health, prometheus)', `
        'include: health, prometheus, shutdown'
    $ymlModified = $ymlModified -replace `
        '(  endpoint:\r?\n    health:)', `
        "  endpoint:`r`n    shutdown:`r`n      enabled: true`r`n    health:"
    Set-Content -Path $AppYmlFile -Value $ymlModified -NoNewline
    Write-Ok "Shutdown actuator endpoint enabled"

    # Step 1.2 — Start service
    Write-Step "Step 1.2: Starting orders-service"
    Cleanup-Logs
    $proc1 = Start-BootRun

    Write-Info "Waiting for Spring Boot to start (timeout: ${StartupTimeoutSeconds}s)..."
    if (-not (Wait-ForSpringBoot -TimeoutSec $StartupTimeoutSeconds)) {
        Write-Fail "Spring Boot did not start in time"
        if (-not $proc1.HasExited) { $proc1.Kill() }
        Restore-SourceFiles; exit 1
    }
    Write-Ok "orders-service is running (PID: $($proc1.Id))"

    # Step 1.3 — Send order + shutdown
    Write-Step "Step 1.3: Sending order and triggering shutdown"

    # Send order in background
    $orderJob = Start-Job -ScriptBlock {
        param($url, $body)
        try {
            Invoke-WebRequest -Method POST -Uri $url -Headers @{"Content-Type"="application/json"} -Body $body -TimeoutSec 30
        } catch { $_ }
    } -ArgumentList $OrderUrl, $OrderBody

    Start-Sleep -Seconds 1
    Write-Info "Order sent. Triggering Actuator shutdown..."
    Stop-BootRunGraceful

    # Wait for process to exit
    Write-Info "Waiting for graceful shutdown (max ${ShutdownWaitSeconds}s)..."
    $proc1.WaitForExit($ShutdownWaitSeconds * 1000) | Out-Null
    if (-not $proc1.HasExited) {
        Write-Info "Process still running, force-killing..."
        $proc1.Kill()
    }
    $orderJob | Wait-Job -Timeout 10 | Out-Null
    $orderJob | Remove-Job -Force -ErrorAction SilentlyContinue

    # Step 1.4 — Validate logs
    Write-Step "Step 1.4: Validating graceful shutdown logs"
    Start-Sleep -Seconds 2
    $logs = Get-LogContent

    $check1 = $logs -match "Commencing graceful shutdown"
    $check2 = $logs -match "SIGTERM received\. Canceling application CoroutineScope"

    if ($check1) { Write-Ok "Found: 'Commencing graceful shutdown'" }
    else         { Write-Fail "Missing: 'Commencing graceful shutdown'" }

    if ($check2) { Write-Ok "Found: 'SIGTERM received. Canceling application CoroutineScope'" }
    else         { Write-Fail "Missing: 'SIGTERM received. Canceling application CoroutineScope'" }

    $part1Pass = $check1 -and $check2

    # Restore files
    Write-Step "Cleanup Part 1"
    Restore-SourceFiles
    Cleanup-Logs
    Write-Ok "Source files restored"

    Start-Sleep -Seconds 3

    # ══════════════════════════════════════════════════════════════════════════
    #  PART 2: Saga Compensation
    # ══════════════════════════════════════════════════════════════════════════
    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Magenta
    Write-Host "  PART 2: Saga Compensation (Payment Failure)" -ForegroundColor Magenta
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Magenta

    # Step 2.1 — Inject RuntimeException
    Write-Step "Step 2.1: Injecting RuntimeException in onInventoryReserved"

    $pipelineContent = Get-Content $PipelineFile -Raw
    $modified2 = $pipelineContent -replace `
        '(log\.info\("Mock: Authorized payment for order \{}", orderId\)\s*\r?\n\s*)(withContext\(NonCancellable\) \{\s*\r?\n\s*val updatedOrder = order\.copy\(status = OrderStatus\.PAID\))', `
        ('$1' + "            // --> TEST: Simulate payment failure <--`r`n            throw RuntimeException(""Insufficient funds!"")`r`n`r`n            " + '$2')
    Set-Content -Path $PipelineFile -Value $modified2 -NoNewline
    Write-Ok "RuntimeException injected"

    # Enable shutdown for clean stop
    $ymlContent2 = Get-Content $AppYmlFile -Raw
    $ymlMod2 = $ymlContent2 -replace `
        '(include: health, prometheus)', `
        'include: health, prometheus, shutdown'
    $ymlMod2 = $ymlMod2 -replace `
        '(  endpoint:\r?\n    health:)', `
        "  endpoint:`r`n    shutdown:`r`n      enabled: true`r`n    health:"
    Set-Content -Path $AppYmlFile -Value $ymlMod2 -NoNewline

    # Step 2.2 — Start + send order
    Write-Step "Step 2.2: Starting orders-service and sending order"
    Cleanup-Logs
    $proc2 = Start-BootRun

    Write-Info "Waiting for Spring Boot to start..."
    if (-not (Wait-ForSpringBoot -TimeoutSec $StartupTimeoutSeconds)) {
        Write-Fail "Spring Boot did not start in time"
        if (-not $proc2.HasExited) { $proc2.Kill() }
        Restore-SourceFiles; exit 1
    }
    Write-Ok "orders-service is running (PID: $($proc2.Id))"

    Write-Info "Sending order..."
    try {
        Invoke-WebRequest -Method POST -Uri $OrderUrl -Headers @{"Content-Type"="application/json"} `
            -Body $OrderBody -TimeoutSec 10 | Out-Null
    } catch { }
    Write-Ok "Order sent"

    # Wait for pipeline to process
    Write-Info "Waiting 5s for pipeline to process..."
    Start-Sleep -Seconds 5

    # Step 2.3 — Validate logs
    Write-Step "Step 2.3: Validating saga compensation logs"
    $logs2 = Get-LogContent

    $checkA = $logs2 -match "Pipeline Stage 1"
    $checkB = $logs2 -match "Pipeline Stage 2"
    $checkC = $logs2 -match "Insufficient funds!"
    $checkD = $logs2 -match "Saga Compensation: Emitting inventory-release"

    if ($checkA) { Write-Ok "Found: 'Pipeline Stage 1' processed normally" }
    else         { Write-Fail "Missing: 'Pipeline Stage 1'" }

    if ($checkB) { Write-Ok "Found: 'Pipeline Stage 2' triggered" }
    else         { Write-Fail "Missing: 'Pipeline Stage 2'" }

    if ($checkC) { Write-Ok "Found: 'Insufficient funds!' exception thrown" }
    else         { Write-Fail "Missing: 'Insufficient funds!' exception" }

    if ($checkD) { Write-Ok "Found: 'Saga Compensation: Emitting inventory-release'" }
    else         { Write-Fail "Missing: 'Saga Compensation: Emitting inventory-release'" }

    $part2Pass = $checkA -and $checkB -and $checkC -and $checkD

    # Stop and cleanup
    Write-Step "Cleanup Part 2"
    Stop-BootRunGraceful
    Start-Sleep -Seconds 3
    if (-not $proc2.HasExited) { $proc2.Kill() }
    Restore-SourceFiles
    Cleanup-Logs
    Write-Ok "Source files restored"

} catch {
    Write-Fail "Unexpected error: $_"
    Restore-SourceFiles
    Cleanup-Logs
} finally {
    # Kill any leftover Java processes from bootRun
    Get-Process -Name java -ErrorAction SilentlyContinue |
        Where-Object { $_.StartTime -gt (Get-Date).AddMinutes(-10) } |
        Stop-Process -Force -ErrorAction SilentlyContinue
}

# ── Final Report ──────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Yellow
Write-Host "║                    VERIFICATION RESULTS                     ║" -ForegroundColor Yellow
Write-Host "╠══════════════════════════════════════════════════════════════╣" -ForegroundColor Yellow

if ($part1Pass) {
    Write-Host "║  Part 1: Graceful Shutdown       ✅ PASS                   ║" -ForegroundColor Green
} else {
    Write-Host "║  Part 1: Graceful Shutdown       ❌ FAIL                   ║" -ForegroundColor Red
}

if ($part2Pass) {
    Write-Host "║  Part 2: Saga Compensation       ✅ PASS                   ║" -ForegroundColor Green
} else {
    Write-Host "║  Part 2: Saga Compensation       ❌ FAIL                   ║" -ForegroundColor Red
}

Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Yellow
Write-Host ""

if ($part1Pass -and $part2Pass) {
    Write-Host "  All verifications passed! Safe to push." -ForegroundColor Green
    exit 0
} else {
    Write-Host "  Some verifications failed. Check the output above." -ForegroundColor Red
    exit 1
}
