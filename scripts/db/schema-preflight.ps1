[CmdletBinding()]
param(
    [string] $OutputPath = "docs/db/dev-schema-sanitized.sql",
    [switch] $PreflightOnly,
    [int] $ConnectTimeoutSeconds = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Test-HasValue {
    param([string] $Name)
    $value = [Environment]::GetEnvironmentVariable($Name)
    return -not [string]::IsNullOrWhiteSpace($value)
}

function Get-EnvValue {
    param([string] $Name)
    return [Environment]::GetEnvironmentVariable($Name)
}

function Write-MissingPrerequisite {
    param([string[]] $Names)
    $uniqueNames = $Names | Select-Object -Unique
    [Console]::Error.WriteLine("MISSING_PREREQUISITE " + ($uniqueNames -join " "))
    exit 2
}

function Write-MalformedPrerequisite {
    param([string[]] $Names)
    $uniqueNames = $Names | Select-Object -Unique
    [Console]::Error.WriteLine("MALFORMED_PREREQUISITE " + ($uniqueNames -join " "))
    exit 3
}

function Write-ProcessTimeout {
    param([string] $CommandName)
    [Console]::Error.WriteLine("COMMAND_TIMEOUT " + $CommandName)
    exit 5
}

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

function Quote-ShSingle {
    param([string] $Value)
    return "'" + ($Value -replace "'", "'\''") + "'"
}

function Assert-PostgresUrlShape {
    param([string] $Name)

    $value = Get-EnvValue $Name
    if ($value -notmatch "^(postgresql|postgres|jdbc:postgresql)://") {
        Write-MalformedPrerequisite @($Name)
    }
}

function Assert-HostPortShape {
    if (Test-HasValue "DEV_DB_HOST") {
        $hostValue = Get-EnvValue "DEV_DB_HOST"
        if ($hostValue -notmatch "^[A-Za-z0-9][A-Za-z0-9_.-]*$") {
            Write-MalformedPrerequisite @("DEV_DB_HOST")
        }
    }

    if (Test-HasValue "DEV_DB_PORT") {
        $portValue = Get-EnvValue "DEV_DB_PORT"
        $parsed = 0
        if (-not [int]::TryParse($portValue, [ref] $parsed) -or $parsed -lt 1 -or $parsed -gt 65535) {
            Write-MalformedPrerequisite @("DEV_DB_PORT")
        }
    }
}

function Test-AnyPasswordAvailable {
    return (Test-HasValue "DEV_DB_PASSWORD") -or (Test-HasValue "POSTGRES_PASSWORD")
}

function Get-DbPassword {
    if (Test-HasValue "DEV_DB_PASSWORD") {
        return Get-EnvValue "DEV_DB_PASSWORD"
    }

    return Get-EnvValue "POSTGRES_PASSWORD"
}

function Test-SshAgentAvailable {
    $sshAdd = Get-Command "ssh-add" -ErrorAction SilentlyContinue
    if ($null -eq $sshAdd) {
        return $false
    }

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $sshAdd.Source
    $psi.Arguments = "-l"
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false

    $process = $null
    try {
        $process = [System.Diagnostics.Process]::Start($psi)
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit([Math]::Max(1000, $ConnectTimeoutSeconds * 1000))) {
            try {
                $process.Kill($true)
            } catch {
                $process.Kill()
            }
            try {
                $process.CancelOutputRead()
                $process.CancelErrorRead()
            } catch {
            }
            $process.WaitForExit(1000) | Out-Null
            return $false
        }
        $null = $stdoutTask.GetAwaiter().GetResult()
        $null = $stderrTask.GetAwaiter().GetResult()
        return $process.ExitCode -eq 0
    } catch {
        return $false
    } finally {
        if ($null -ne $process) {
            $process.Dispose()
        }
    }
}

function Test-DefaultSshKeyAvailable {
    $sshDir = Join-Path $env:USERPROFILE ".ssh"
    foreach ($name in @("id_ed25519", "id_rsa")) {
        $path = Join-Path $sshDir $name
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            return $true
        }
    }

    return $false
}

function Test-SshKeyPrerequisite {
    if (-not (Test-HasValue "SERVER_SSH_KEY")) {
        return $true
    }

    $key = Get-EnvValue "SERVER_SSH_KEY"
    if (Test-Path -LiteralPath $key -PathType Leaf) {
        return $true
    }

    if ($key -match "BEGIN OPENSSH PRIVATE KEY|BEGIN RSA PRIVATE KEY|BEGIN EC PRIVATE KEY") {
        return $true
    }

    Write-MalformedPrerequisite @("SERVER_SSH_KEY")
}

function Find-AccessRoute {
    if (Test-HasValue "DEV_DATABASE_URL") {
        Assert-PostgresUrlShape "DEV_DATABASE_URL"
        return [pscustomobject]@{
            Name = "direct"
            Report = "DEV_DATABASE_URL"
        }
    }

    if (Test-HasValue "DEV_DB_HOST") {
        Assert-HostPortShape
        if (-not (Test-AnyPasswordAvailable)) {
            Write-MissingPrerequisite @("DEV_DB_PASSWORD")
        }

        $reportedNames = @("DEV_DB_HOST")
        if (Test-HasValue "DEV_DB_PORT") {
            $reportedNames += "DEV_DB_PORT"
        } else {
            $reportedNames += "DEV_DB_PORT_DEFAULT"
        }
        if (Test-HasValue "DEV_DB_NAME") {
            $reportedNames += "DEV_DB_NAME"
        } elseif (Test-HasValue "DB_NAME") {
            $reportedNames += "DB_NAME"
        } else {
            $reportedNames += "DEV_DB_NAME_DEFAULT"
        }
        if (Test-HasValue "DEV_DB_USER") {
            $reportedNames += "DEV_DB_USER"
        } else {
            $reportedNames += "DEV_DB_USER_DEFAULT"
        }
        if (Test-HasValue "DEV_DB_PASSWORD") {
            $reportedNames += "DEV_DB_PASSWORD"
        } else {
            $reportedNames += "POSTGRES_PASSWORD"
        }

        return [pscustomobject]@{
            Name = "host"
            Report = ($reportedNames -join " ")
        }
    }

    Write-MissingPrerequisite @("DEV_DATABASE_URL", "DEV_DB_HOST", "SCHEMA_CONTRACT_WORKFLOW_ARTIFACT")
}

function Convert-JdbcUrl {
    param([string] $Url)
    if ($Url -match "^jdbc:(postgresql://.+)$") {
        return $Matches[1]
    }

    return $Url
}

function Invoke-CheckedProcess {
    param(
        [string] $FileName,
        [string[]] $Arguments,
        [hashtable] $Environment = @{},
        [int] $TimeoutSeconds = $ConnectTimeoutSeconds
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FileName
    $psi.Arguments = Join-ProcessArguments $Arguments
    foreach ($key in $Environment.Keys) {
        $psi.Environment[$key] = [string] $Environment[$key]
    }
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false

    $commandName = Split-Path -Leaf $FileName
    $process = $null

    try {
        $process = [System.Diagnostics.Process]::Start($psi)
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()

        $timeoutMs = [Math]::Max(1, $TimeoutSeconds) * 1000
        if (-not $process.WaitForExit($timeoutMs)) {
            try {
                $process.Kill($true)
            } catch {
                $process.Kill()
            }
            try {
                $process.CancelOutputRead()
                $process.CancelErrorRead()
            } catch {
            }
            $process.WaitForExit(1000) | Out-Null
            Write-ProcessTimeout $commandName
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $null = $stderrTask.GetAwaiter().GetResult()

        if ($process.ExitCode -ne 0) {
            [Console]::Error.WriteLine("COMMAND_FAILED " + $commandName + " exit=" + $process.ExitCode)
            exit 4
        }

        return $stdout
    } finally {
        if ($null -ne $process) {
            $process.Dispose()
        }
    }
}

function Get-DirectSchemaDump {
    $pgDump = Get-Command "pg_dump" -ErrorAction SilentlyContinue
    if ($null -eq $pgDump) {
        Write-MissingPrerequisite @("PG_DUMP_COMMAND")
    }

    $url = Convert-JdbcUrl (Get-EnvValue "DEV_DATABASE_URL")
    return Invoke-CheckedProcess $pgDump.Source @("--schema-only", "--no-owner", "--no-privileges", $url) @{
        PGCONNECT_TIMEOUT = $ConnectTimeoutSeconds
    }
}

function Get-HostSchemaDump {
    $pgDump = Get-Command "pg_dump" -ErrorAction SilentlyContinue
    if ($null -eq $pgDump) {
        Write-MissingPrerequisite @("PG_DUMP_COMMAND")
    }

    $hostValue = Get-EnvValue "DEV_DB_HOST"
    $portValue = if (Test-HasValue "DEV_DB_PORT") { Get-EnvValue "DEV_DB_PORT" } else { "5432" }
    $dbName = if (Test-HasValue "DEV_DB_NAME") { Get-EnvValue "DEV_DB_NAME" } elseif (Test-HasValue "DB_NAME") { Get-EnvValue "DB_NAME" } else { "naeil_bank_dev" }
    $dbUser = if (Test-HasValue "DEV_DB_USER") { Get-EnvValue "DEV_DB_USER" } else { "naeil" }

    return Invoke-CheckedProcess $pgDump.Source @("--schema-only", "--no-owner", "--no-privileges", "-h", $hostValue, "-p", $portValue, "-U", $dbUser, "-d", $dbName) @{
        PGCONNECT_TIMEOUT = $ConnectTimeoutSeconds
        PGPASSWORD = Get-DbPassword
    }
}

$route = Find-AccessRoute
Write-Output ("ROUTE_READY " + $route.Report)

if ($PreflightOnly) {
    exit 0
}

$outputFullPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
$outputDirectory = Split-Path -Parent $outputFullPath
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}

if ($route.Name -eq "direct") {
    $dump = Get-DirectSchemaDump
} elseif ($route.Name -eq "host") {
    $dump = Get-HostSchemaDump
} else {
    Write-MissingPrerequisite @("SCHEMA_CONTRACT_WORKFLOW_ARTIFACT")
}

Set-Content -LiteralPath $outputFullPath -Value $dump -NoNewline
Write-Output ("SCHEMA_ARTIFACT " + $OutputPath)
