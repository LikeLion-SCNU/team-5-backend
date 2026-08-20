Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PreflightScript = Join-Path $ScriptDir "schema-preflight.ps1"
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("naeilbank-schema-preflight-" + [System.Guid]::NewGuid().ToString("N"))
$OutputPath = Join-Path $TempRoot "should-not-exist.sql"

function Invoke-Preflight {
    param(
        [string[]] $Arguments = @(),
        [int] $TimeoutSeconds = 5
    )

    function Join-ProcessArguments {
        param([string[]] $Items)

        (($Items | ForEach-Object {
            if ($_ -match '[\s"]') {
                '"' + ($_ -replace '"', '\"') + '"'
            } else {
                $_
            }
        }) -join " ")
    }

    $processArguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $PreflightScript) + $Arguments
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = "powershell"
    $psi.Arguments = Join-ProcessArguments $processArguments
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false

    return Invoke-TestProcess $psi $TimeoutSeconds
}

function Invoke-TestProcess {
    param(
        [System.Diagnostics.ProcessStartInfo] $StartInfo,
        [int] $TimeoutSeconds = 5
    )

    $process = $null

    try {
        $process = [System.Diagnostics.Process]::Start($StartInfo)
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()

        if (-not $process.WaitForExit([Math]::Max(1, $TimeoutSeconds) * 1000)) {
            try {
                $process.Kill($true)
            } catch {
                $process.Kill()
            }
            $process.WaitForExit(1000) | Out-Null
            return [pscustomobject]@{
                ExitCode = 124
                Output = "TEST_COMMAND_TIMEOUT"
            }
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()

        [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = ($stdout + $stderr)
        }
    } finally {
        if ($null -ne $process) {
            $process.Dispose()
        }
    }
}

function Assert-True {
    param(
        [bool] $Condition,
        [string] $Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Set-TestEnv {
    param(
        [hashtable] $Values
    )

    foreach ($Name in @("DEV_DATABASE_URL", "DEV_DB_HOST", "DEV_DB_PORT", "DEV_DB_NAME", "DEV_DB_USER", "DEV_DB_PASSWORD", "POSTGRES_PASSWORD", "DB_NAME")) {
        if ($Values.ContainsKey($Name)) {
            Set-Item -Path "Env:$Name" -Value $Values[$Name]
        } else {
            Remove-Item -Path "Env:$Name" -ErrorAction SilentlyContinue
        }
    }
}

$originalEnv = @{}
foreach ($Name in @("DEV_DATABASE_URL", "DEV_DB_HOST", "DEV_DB_PORT", "DEV_DB_NAME", "DEV_DB_USER", "DEV_DB_PASSWORD", "POSTGRES_PASSWORD", "DB_NAME")) {
    $originalEnv[$Name] = [Environment]::GetEnvironmentVariable($Name)
}

try {
    New-Item -ItemType Directory -Path $TempRoot | Out-Null
    Assert-True (Test-Path -LiteralPath $PreflightScript) "schema-preflight.ps1 must exist before preflight tests can run"
    $preflightSource = Get-Content -Raw -LiteralPath $PreflightScript
    Assert-True ($preflightSource -notmatch "Sanitize-SchemaDump|REDACTED_DATABASE_URL|GRANT .+\|REVOKE .+\|OWNER TO") "schema preflight must not use global SQL rewriting"
    Assert-True ($preflightSource -match "--schema-only") "schema preflight must request schema-only pg_dump"
    Assert-True ($preflightSource -match "--no-owner") "schema preflight must disable owner output"
    Assert-True ($preflightSource -match "--no-privileges") "schema preflight must disable privilege output"

    Set-TestEnv @{}
    $missing = Invoke-Preflight @("-OutputPath", $OutputPath)
    Assert-True ($missing.ExitCode -ne 0) "missing-access preflight must exit nonzero"
    Assert-True ($missing.Output -match "MISSING_PREREQUISITE") "missing-access preflight must name MISSING_PREREQUISITE"
    Assert-True ($missing.Output -match "DEV_DATABASE_URL") "missing-access preflight must name DEV_DATABASE_URL"
    Assert-True ($missing.Output -match "DEV_DB_HOST") "missing-access preflight must name DEV_DB_HOST"
    Assert-True ($missing.Output -match "SCHEMA_CONTRACT_WORKFLOW_ARTIFACT") "missing-access preflight must name SCHEMA_CONTRACT_WORKFLOW_ARTIFACT"
    Assert-True ($missing.Output -notmatch "postgresql://|jdbc:postgresql://|://[^`r`n ]+:[^`r`n ]+@") "missing-access preflight must not print connection values"
    Assert-True (-not (Test-Path -LiteralPath $OutputPath)) "missing-access preflight must not create output artifacts"

    Set-TestEnv @{ DEV_DATABASE_URL = "not-a-postgres-url" }
    $malformed = Invoke-Preflight @("-OutputPath", $OutputPath)
    Assert-True ($malformed.ExitCode -ne 0) "malformed direct URL must exit nonzero"
    Assert-True ($malformed.Output -match "MALFORMED_PREREQUISITE") "malformed direct URL must name MALFORMED_PREREQUISITE"
    Assert-True ($malformed.Output -match "DEV_DATABASE_URL") "malformed direct URL must name DEV_DATABASE_URL"
    Assert-True ($malformed.Output -notmatch "not-a-postgres-url") "malformed direct URL must not print the supplied value"
    Assert-True (-not (Test-Path -LiteralPath $OutputPath)) "malformed direct URL must not create output artifacts"

    Set-TestEnv @{ DEV_DATABASE_URL = "postgresql://user:pass@example.invalid:5432/db" }
    $direct = Invoke-Preflight @("-PreflightOnly", "-OutputPath", $OutputPath)
    Assert-True ($direct.ExitCode -eq 0) "complete direct route must pass preflight-only validation"
    Assert-True ($direct.Output -match "ROUTE_READY DEV_DATABASE_URL") "direct route must report only route name"
    Assert-True ($direct.Output -notmatch "example.invalid|user:pass|postgresql://") "direct route must not print URL parts"
    Assert-True (-not (Test-Path -LiteralPath $OutputPath)) "preflight-only direct route must not create output artifacts"

    Set-TestEnv @{ DEV_DB_HOST = "bad host"; DEV_DB_PASSWORD = "secret-value" }
    $badHost = Invoke-Preflight @("-PreflightOnly", "-OutputPath", $OutputPath)
    Assert-True ($badHost.ExitCode -ne 0) "malformed explicit host must exit nonzero"
    Assert-True ($badHost.Output -match "MALFORMED_PREREQUISITE") "malformed explicit host must name MALFORMED_PREREQUISITE"
    Assert-True ($badHost.Output -match "DEV_DB_HOST") "malformed explicit host must name DEV_DB_HOST"
    Assert-True ($badHost.Output -notmatch "bad host|secret-value") "malformed explicit host must not print supplied values"
    Assert-True (-not (Test-Path -LiteralPath $OutputPath)) "malformed explicit host must not create output artifacts"

    Set-TestEnv @{ DEV_DB_HOST = "naeil-db"; DEV_DB_PORT = "99999"; DEV_DB_PASSWORD = "secret-value" }
    $badPort = Invoke-Preflight @("-PreflightOnly", "-OutputPath", $OutputPath)
    Assert-True ($badPort.ExitCode -ne 0) "malformed explicit port must exit nonzero"
    Assert-True ($badPort.Output -match "MALFORMED_PREREQUISITE") "malformed explicit port must name MALFORMED_PREREQUISITE"
    Assert-True ($badPort.Output -match "DEV_DB_PORT") "malformed explicit port must name DEV_DB_PORT"
    Assert-True ($badPort.Output -notmatch "99999|secret-value") "malformed explicit port must not print supplied values"
    Assert-True (-not (Test-Path -LiteralPath $OutputPath)) "malformed explicit port must not create output artifacts"

    Set-TestEnv @{ DEV_DB_HOST = "naeil-db" }
    $missingHostPassword = Invoke-Preflight @("-PreflightOnly", "-OutputPath", $OutputPath)
    Assert-True ($missingHostPassword.ExitCode -ne 0) "explicit host route without password must exit nonzero"
    Assert-True ($missingHostPassword.Output -match "MISSING_PREREQUISITE") "explicit host route without password must name MISSING_PREREQUISITE"
    Assert-True ($missingHostPassword.Output -match "DEV_DB_PASSWORD") "explicit host route without password must name DEV_DB_PASSWORD"
    Assert-True ($missingHostPassword.Output -notmatch "naeil-db") "explicit host route without password must not print host value"
    Assert-True (-not (Test-Path -LiteralPath $OutputPath)) "explicit host route without password must not create output artifacts"

    Set-TestEnv @{ DEV_DB_HOST = "naeil-db"; DEV_DB_PASSWORD = "secret-value" }
    $hostRoute = Invoke-Preflight @("-PreflightOnly", "-OutputPath", $OutputPath)
    Assert-True ($hostRoute.ExitCode -eq 0) "complete explicit host route must pass preflight-only validation"
    Assert-True ($hostRoute.Output -match "ROUTE_READY DEV_DB_HOST DEV_DB_PORT_DEFAULT DEV_DB_NAME_DEFAULT DEV_DB_USER_DEFAULT DEV_DB_PASSWORD") "host route must report only prerequisite/default names"
    Assert-True ($hostRoute.Output -notmatch "naeil-db|secret-value|5432|naeil_bank_dev") "host route must not print host, password, port, or database values"
    Assert-True (-not (Test-Path -LiteralPath $OutputPath)) "preflight-only host route must not create output artifacts"

    $hangPsi = [System.Diagnostics.ProcessStartInfo]::new()
    $hangPsi.FileName = "powershell"
    $hangPsi.Arguments = "-NoProfile -Command Start-Sleep -Seconds 10"
    $hangPsi.RedirectStandardOutput = $true
    $hangPsi.RedirectStandardError = $true
    $hangPsi.UseShellExecute = $false
    $hang = Invoke-TestProcess $hangPsi 1
    Assert-True ($hang.ExitCode -eq 124) "hanging local child must be killed after test timeout"
    Assert-True ($hang.Output -match "TEST_COMMAND_TIMEOUT") "hanging local child must report stable timeout message"

    "PASS schema-preflight self-tests"
} finally {
    foreach ($Name in $originalEnv.Keys) {
        if ($null -eq $originalEnv[$Name]) {
            Remove-Item -Path "Env:$Name" -ErrorAction SilentlyContinue
        } else {
            Set-Item -Path "Env:$Name" -Value $originalEnv[$Name]
        }
    }

    if (Test-Path -LiteralPath $TempRoot) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}
