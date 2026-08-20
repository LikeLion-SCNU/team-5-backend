Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ArtifactPath = ".omo/evidence/schema-contract/run-32295434464/dev-schema-sanitized.sql"
$BaselinePath = "src/main/resources/db/migration/V1__canonical_baseline.sql"

function Assert-True {
    param(
        [bool] $Condition,
        [string] $Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

Assert-True (Test-Path -LiteralPath $ArtifactPath) "accepted schema artifact must exist"
Assert-True (Test-Path -LiteralPath $BaselinePath) "canonical baseline must exist"

$artifactLines = Get-Content -LiteralPath $ArtifactPath
$baselineLines = Get-Content -LiteralPath $BaselinePath

$artifactMetaCommands = @($artifactLines | Where-Object { $_ -match '^\\' })
$baselineMetaCommands = @($baselineLines | Where-Object { $_ -match '^\\' })
$normalizedArtifactLines = @($artifactLines | Where-Object { $_ -notmatch '^\\' })

Assert-True ($artifactMetaCommands.Count -eq 2) "accepted artifact must contain exactly the two pg_dump psql meta-commands"
Assert-True ($artifactMetaCommands[0] -match '^\\restrict\s+') "first artifact meta-command must be restrict"
Assert-True ($artifactMetaCommands[1] -match '^\\unrestrict\s+') "second artifact meta-command must be unrestrict"
Assert-True ($baselineMetaCommands.Count -eq 0) "baseline must not contain psql meta-commands"
Assert-True ($baselineLines.Count -eq $normalizedArtifactLines.Count) "baseline line count must equal artifact after meta-command removal"

for ($i = 0; $i -lt $baselineLines.Count; $i++) {
    if ($baselineLines[$i] -ne $normalizedArtifactLines[$i]) {
        throw "baseline differs from normalized artifact at normalized line $($i + 1)"
    }
}

"PASS baseline normalization tests"
