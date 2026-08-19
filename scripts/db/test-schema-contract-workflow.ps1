Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$WorkflowPath = ".github/workflows/schema-contract.yml"
$RemoteScriptPath = "scripts/db/schema-contract-remote.sh"
$DeployWorkflowPath = ".github/workflows/deploy.yml"

function Assert-True {
    param(
        [bool] $Condition,
        [string] $Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function ConvertTo-PgpassFieldModel {
    param([string] $Value)

    return ($Value -replace "\\", "\\") -replace ":", "\:"
}

function Test-PgpassSecretModel {
    param([string] $Value)

    if ($Value.Length -eq 0) {
        return $false
    }

    foreach ($char in $Value.ToCharArray()) {
        $code = [int][char]$char
        if (($code -ge 0 -and $code -le 31) -or $code -eq 127) {
            return $false
        }
    }

    return $true
}

Assert-True (Test-Path -LiteralPath $WorkflowPath) "schema-contract workflow must exist"
Assert-True (Test-Path -LiteralPath $RemoteScriptPath) "remote schema script must exist"
Assert-True (Test-Path -LiteralPath $DeployWorkflowPath) "deploy workflow must exist"
Assert-True (-not (Test-Path -LiteralPath "scripts/db/sanitize-schema.sh")) "sanitizer script must be removed"
Assert-True (-not (Test-Path -LiteralPath "scripts/db/test-sanitize-schema.sh")) "sanitizer test must be removed"
Assert-True (-not (Test-Path -LiteralPath "scripts/db/fixtures")) "sanitizer fixtures must be removed"

$workflow = Get-Content -Raw -LiteralPath $WorkflowPath
$remoteScript = Get-Content -Raw -LiteralPath $RemoteScriptPath
$deployWorkflow = Get-Content -Raw -LiteralPath $DeployWorkflowPath

Assert-True ($workflow -match "workflow_dispatch:") "workflow must keep manual dispatch"
Assert-True ($workflow -match "(?m)^\s*push:") "schema workflow must run after initial schema-tool publish"
Assert-True ($workflow -match "branches:\s*\[develop\]") "schema push trigger must be develop-only"
Assert-True ($workflow -match "paths:") "schema push trigger must be path-filtered"
Assert-True ($workflow -match "'\.github/workflows/schema-contract\.yml'") "schema push trigger must include workflow path"
Assert-True ($workflow -match "'scripts/db/schema-contract-remote\.sh'") "schema push trigger must include remote script path"
Assert-True ($workflow -notmatch "sanitize-schema|fixtures|test-sanitize") "workflow must not reference removed sanitizer files"
Assert-True ($workflow -match "github\.ref == 'refs/heads/develop'") "schema workflow must be develop-only"
Assert-True ($workflow -match "permissions:\s*\r?\n\s*contents:\s*read") "schema workflow must use least contents permission"
Assert-True ($workflow -match "concurrency:") "schema workflow must define concurrency"
Assert-True ($workflow -match "retention-days:\s*1") "schema artifact must have one-day retention"
Assert-True ($workflow -match "timeout 15s ssh") "remote ssh operations must be hard-time bounded"
Assert-True ($workflow -match "timeout 15s ssh-keyscan") "ssh-keyscan must be hard-time bounded"
Assert-True ($workflow -match "DB_NAME=naeil_bank_dev") "schema workflow must target develop DB"
Assert-True ($workflow -match "view_v_daily_net=true") "schema workflow must match psql boolean output"
Assert-True ($workflow -match "view_v_balance=true") "schema workflow must match psql boolean output"
Assert-True ($workflow -match "schema-contract-verdict\.txt") "schema workflow must record assertion verdict"
Assert-True ($workflow -match "assertions=%s") "schema workflow must write machine-readable assertion status"
Assert-True ($workflow -match "assertion_status=fail") "schema workflow must retain artifact when schema assertions fail"
Assert-True ($workflow -match "grep -qx 'assertions=pass'") "schema workflow must fail after artifact upload when assertions fail"
Assert-True ($workflow -notmatch "POSTGRES_PASSWORD|SERVER_DB_ENV_PATH") "workflow must not mention DB password or dotenv path"
Assert-True ($workflow -notmatch "SERVER_USER") "schema workflow must not invent a SERVER_USER secret"
Assert-True ($deployWorkflow.Contains('ubuntu@${{ secrets.SERVER_HOST }}')) "deploy workflow must use ubuntu SSH user"
Assert-True ($workflow -match '"ubuntu@\$SERVER_HOST"') "schema workflow must reuse deploy SSH user"
Assert-True ($workflow -notmatch "echo\s+[`"']?\$\{\{\s*secrets\.SERVER_SSH_KEY") "workflow must not echo SSH key"
Assert-True ($workflow -match "Cleanup runner credentials") "workflow must remove runner SSH key"

Assert-True ($remoteScript -notmatch "SERVER_DB_ENV_PATH|read_dotenv_key|source |\.[[:space:]]") "remote script must not parse/source dotenv"
Assert-True ($remoteScript -match 'docker ps --filter "network=\$DOCKER_NETWORK" -q') "remote script must search running containers on app_default"
Assert-True ($remoteScript -match "docker inspect") "remote script must inspect container aliases"
Assert-True ($remoteScript -match 'case " \$aliases " in') "remote script must match DB container alias"
Assert-True ($remoteScript -match "die_missing DB_CONTAINER") "remote script must fail closed when DB container is absent"
Assert-True ($remoteScript -match "die_malformed DB_CONTAINER") "remote script must fail closed when DB container is ambiguous"
Assert-True ($remoteScript -match 'docker exec "\$db_container_id" sh -c') "remote script must read password inside DB container"
Assert-True ($remoteScript -match 'printf "%s" "\$POSTGRES_PASSWORD"') "remote script must expand password only inside container shell"
Assert-True ($remoteScript -match '> "\$temp_dir/password"') "remote script must redirect password directly to temp file"
Assert-True ($remoteScript -notmatch "-e PGPASSWORD|POSTGRES_PASSWORD=.*docker|docker run .*POSTGRES_PASSWORD") "remote script must not put password into docker env, argv, or metadata"
Assert-True ($remoteScript -match 'chmod 600 "\$pgpass_path"') "pgpass file must be chmod 600"
Assert-True ($remoteScript -match '--mount "type=bind,src=\$pgpass_path,dst=/tmp/schema-contract.pgpass,readonly"') "pgpass must be mounted read-only"
Assert-True ($remoteScript -match "-e PGPASSFILE=/tmp/schema-contract.pgpass") "container must see only PGPASSFILE path"
Assert-True ($remoteScript -match "trap cleanup EXIT HUP INT TERM") "remote script must cleanup temp files on all paths"
Assert-True ($remoteScript -match 'rm -f "\$temp_dir/password"') "intermediate password file must be removed after pgpass creation"
Assert-True ($remoteScript -match "validate_pgpass_secret_file") "remote script must validate password bytes before pgpass creation"
Assert-True ($remoteScript -match "tr -d '\\000-\\037\\177'") "remote script must reject newline, carriage return, NUL and control bytes"
Assert-True ($remoteScript -match 'timeout "\$SCHEMA_CONTRACT_TIMEOUT_SECONDS" docker run --rm -i') "remote docker run must be hard-time bounded and keep stdin open"
Assert-True ($remoteScript -match 'timeout "\$SCHEMA_CONTRACT_TIMEOUT_SECONDS" docker exec') "remote docker exec must be hard-time bounded"
Assert-True ($remoteScript -match "pg_dump --schema-only --no-owner --no-privileges") "remote dump must be schema-only with owner and privilege output disabled"
Assert-True ($remoteScript -match "psql -X -v ON_ERROR_STOP=1") "remote assertions must use psql with ON_ERROR_STOP"
Assert-True ($remoteScript -match 'DB_HOST="\$\{DB_HOST:-naeil-db\}"') "remote script must validate DB host constant/default"
Assert-True ($remoteScript -match 'DB_USER="\$\{DB_USER:-naeil\}"') "remote script must validate DB user constant/default"
Assert-True ($remoteScript -match 'DB_NAME="\$\{DB_NAME:-naeil_bank_dev\}"') "remote script must validate develop DB constant/default"
Assert-True ($remoteScript -notmatch "set -x") "remote script must not enable shell tracing"

Assert-True (Test-PgpassSecretModel "safeFixture123") "model must allow ordinary password fixture"
Assert-True (Test-PgpassSecretModel "safe:colon") "model must allow colon before pgpass escaping"
Assert-True (Test-PgpassSecretModel "safe\backslash") "model must allow backslash before pgpass escaping"
Assert-True ((ConvertTo-PgpassFieldModel "safe:colon") -eq "safe\:colon") "model must escape colon for pgpass"
Assert-True ((ConvertTo-PgpassFieldModel "safe\backslash") -eq "safe\\backslash") "model must escape backslash for pgpass"
Assert-True (-not (Test-PgpassSecretModel "bad`nline")) "model must reject newline"
Assert-True (-not (Test-PgpassSecretModel "bad`rline")) "model must reject carriage return"
Assert-True (-not (Test-PgpassSecretModel ("bad" + [char]1 + "control"))) "model must reject other control characters"

"PASS schema-contract workflow tests"
