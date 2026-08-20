$ErrorActionPreference = 'Stop'

function Assert-FileContains {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Pattern,
        [Parameter(Mandatory=$true)][string]$Message
    )
    $content = Get-Content -LiteralPath $Path -Raw
    if ($content -notmatch $Pattern) {
        throw $Message
    }
}

function Assert-FileNotContains {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Pattern,
        [Parameter(Mandatory=$true)][string]$Message
    )
    $content = Get-Content -LiteralPath $Path -Raw
    if ($content -match $Pattern) {
        throw $Message
    }
}

$workflow = '.github/workflows/todo10-canonical-dev-golden-vector.yml'
$exporter = 'scripts/db/export-canonical-dev-vectors.sh'
$javaTest = 'src/test/java/org/example/naeilbank/domain/conversion/CanonicalDevGoldenVectorTest.java'

foreach ($path in @($workflow, $exporter, $javaTest)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "MISSING_REQUIRED_FILE $path"
    }
}

Assert-FileContains $workflow 'workflow_dispatch:' 'WORKFLOW_MISSING_MANUAL_TRIGGER'
Assert-FileContains $workflow 'branches:\s*\[develop\]' 'WORKFLOW_NOT_DEVELOP_ONLY'
Assert-FileNotContains $workflow 'paths:' 'WORKFLOW_PUSH_MUST_RUN_ON_EVERY_DEVELOP_PUSH'
Assert-FileContains $workflow "github\.ref == 'refs/heads/develop'" 'WORKFLOW_MISSING_DEVELOP_JOB_GUARD'
Assert-FileContains $workflow 'permissions:\s*\r?\n\s+contents:\s+read' 'WORKFLOW_PERMISSIONS_NOT_READ_ONLY'
Assert-FileContains $workflow 'concurrency:' 'WORKFLOW_MISSING_CONCURRENCY'
Assert-FileContains $workflow 'ubuntu@\$SERVER_HOST' 'WORKFLOW_NOT_USING_DEPLOY_SSH_USER_PATTERN'
Assert-FileContains $workflow 'DB_NAME=naeil_bank_dev' 'WORKFLOW_NOT_TARGETING_DEV_DB'
Assert-FileContains $workflow 'timeout 15s ssh-keyscan' 'WORKFLOW_MISSING_SSH_KEYSCAN_TIMEOUT'
Assert-FileContains $workflow 'timeout 15s ssh ' 'WORKFLOW_MISSING_SSH_TIMEOUT'
Assert-FileContains $workflow 'mktemp -d' 'WORKFLOW_MISSING_EPHEMERAL_TEMP_DIR'
Assert-FileContains $workflow 'trap cleanup EXIT HUP INT TERM' 'WORKFLOW_MISSING_TEMP_CLEANUP_TRAP'
Assert-FileContains $workflow 'vector_count=' 'WORKFLOW_MISSING_COUNT_SUMMARY'
Assert-FileContains $workflow 'vector_sha256=' 'WORKFLOW_MISSING_SHA256_SUMMARY'
Assert-FileContains $workflow 'REQUIRE_CANONICAL_DEV_VECTORS=true' 'WORKFLOW_DOES_NOT_REQUIRE_VECTOR_PAYLOAD'
Assert-FileContains $workflow 'TEST-org\.example\.naeilbank\.domain\.conversion\.CanonicalDevGoldenVectorTest\.xml' 'WORKFLOW_DOES_NOT_CHECK_DEDICATED_TEST_RESULT'
Assert-FileContains $workflow 'tests="\[1-9\]\[0-9\]\*"' 'WORKFLOW_DOES_NOT_PROVE_TEST_EXECUTION'
Assert-FileContains $workflow 'skipped="0"' 'WORKFLOW_ALLOWS_SKIPPED_DEDICATED_TEST'
Assert-FileContains $workflow 'canonical_vector_test=pass' 'WORKFLOW_MISSING_PASS_SUMMARY'
Assert-FileNotContains $workflow 'actions/upload-artifact' 'WORKFLOW_MUST_NOT_UPLOAD_RAW_VECTOR_ARTIFACT'
Assert-FileNotContains $workflow 'branches:\s*\[[^\]]*main' 'WORKFLOW_MUST_NOT_RUN_ON_MAIN'
Assert-FileNotContains $workflow 'db=naeil_bank' 'WORKFLOW_MUST_NOT_TARGET_PROD_DB'
Assert-FileNotContains $workflow 'cat\s+\$payload_path' 'WORKFLOW_MUST_NOT_PRINT_RAW_PAYLOAD'
Assert-FileNotContains $workflow 'tee\s+\$payload_path' 'WORKFLOW_MUST_NOT_TEE_RAW_PAYLOAD'

Assert-FileContains $exporter 'DB_NAME="\$\{DB_NAME:-naeil_bank_dev\}"' 'EXPORTER_MISSING_DEV_DB_DEFAULT'
Assert-FileContains $exporter 'DB_USER="\$\{DB_USER:-naeil\}"' 'EXPORTER_MISSING_DEV_DB_USER_DEFAULT'
Assert-FileContains $exporter 'DB_ALIAS="\$\{DB_ALIAS:-naeil-db\}"' 'EXPORTER_MISSING_CONTAINER_ALIAS'
Assert-FileContains $exporter 'UNSAFE_DATABASE_TARGET' 'EXPORTER_MISSING_DB_TARGET_GUARD'
Assert-FileContains $exporter 'UNSAFE_DATABASE_USER' 'EXPORTER_MISSING_DB_USER_GUARD'
Assert-FileContains $exporter 'UNSAFE_DATABASE_NETWORK' 'EXPORTER_MISSING_DB_NETWORK_GUARD'
Assert-FileContains $exporter 'UNSAFE_DATABASE_ALIAS' 'EXPORTER_MISSING_DB_ALIAS_GUARD'
Assert-FileContains $exporter 'timeout "\$\{TIMEOUT_SECONDS\}s" docker ps' 'EXPORTER_MISSING_DOCKER_PS_TIMEOUT'
Assert-FileContains $exporter 'timeout "\$\{TIMEOUT_SECONDS\}s" docker inspect' 'EXPORTER_MISSING_DOCKER_INSPECT_TIMEOUT'
Assert-FileContains $exporter 'timeout "\$\{TIMEOUT_SECONDS\}s" docker exec' 'EXPORTER_MISSING_DOCKER_EXEC_TIMEOUT'
Assert-FileContains $exporter 'PGPASSWORD="\$\{POSTGRES_PASSWORD:\?\}"' 'EXPORTER_PASSWORD_ONLY_EXPANDED_INSIDE_CONTAINER'
Assert-FileContains $exporter 'jsonb_build_object' 'EXPORTER_MISSING_JSONL_OUTPUT'
Assert-FileContains $exporter 'logical_key_hash' 'EXPORTER_MISSING_OPAQUE_LOGICAL_KEY_HASH'
Assert-FileContains $exporter 'condition_json' 'EXPORTER_MISSING_CONDITION_JSON'
Assert-FileContains $exporter 'source_active' 'EXPORTER_MISSING_SOURCE_ACTIVE'
Assert-FileContains $exporter 'EMPTY_CANONICAL_VECTOR_SET' 'EXPORTER_MISSING_EMPTY_SET_GUARD'
Assert-FileContains $exporter 'UNSUPPORTED_CANONICAL_RULE_SET' 'EXPORTER_MISSING_UNSUPPORTED_SET_GUARD'
Assert-FileContains $exporter 'AMBIGUOUS_CANONICAL_RULE_SET' 'EXPORTER_MISSING_AMBIGUOUS_SET_GUARD'
Assert-FileContains $exporter 'group by habit_type::text, lower\(trim\(unit\)\) having count\(\*\) > 1' 'EXPORTER_AMBIGUITY_MUST_USE_SELECTOR'
Assert-FileContains $exporter "condition_json <> '\{\}'::jsonb" 'EXPORTER_MISSING_EMPTY_CONDITION_GUARD'
Assert-FileContains $exporter 'lower\(trim\(r\.unit\)\)' 'EXPORTER_MISSING_NORMALIZED_UNIT'
Assert-FileNotContains $exporter 'SERVER_DB_ENV_PATH|source\s+|^\s*\.\s+\.env|set -x|SELECT\s+\*|title|doi_url|summary_ko|scope_ko|limitations_ko|authors|journal' 'EXPORTER_CONTAINS_FORBIDDEN_SECRET_OR_RAW_FIELDS'

Assert-FileContains $javaTest 'Assumptions\.assumeTrue' 'JAVA_TEST_MUST_SKIP_WHEN_PAYLOAD_OPTIONAL'
Assert-FileContains $javaTest 'REQUIRE_CANONICAL_DEV_VECTORS' 'JAVA_TEST_MISSING_REQUIRE_ENV'
Assert-FileContains $javaTest 'CANONICAL_DEV_VECTOR_PATH' 'JAVA_TEST_MISSING_VECTOR_PATH_ENV'
Assert-FileContains $javaTest 'ExactConversionEngine' 'JAVA_TEST_MISSING_ENGINE_INVOCATION'
Assert-FileContains $javaTest 'PER_1000_STEPS' 'JAVA_TEST_MISSING_UNIT_BASIS_CASE'
Assert-FileContains $javaTest 'minutesDelta \* 60L' 'JAVA_TEST_MISSING_INDEPENDENT_SECONDS_EXPECTATION'
Assert-FileContains $javaTest 'CANONICAL_VECTOR_COUNT=' 'JAVA_TEST_MISSING_COUNT_RECEIPT'
Assert-FileContains $javaTest 'CANONICAL_VECTOR_SHA256=' 'JAVA_TEST_MISSING_HASH_RECEIPT'
Assert-FileContains $javaTest 'EMPTY_CANONICAL_VECTOR_SET' 'JAVA_TEST_MISSING_EMPTY_SET_GUARD'
Assert-FileContains $javaTest 'UNSUPPORTED_CANONICAL_' 'JAVA_TEST_MISSING_UNSUPPORTED_SET_GUARD'
Assert-FileContains $javaTest 'AMBIGUOUS_CANONICAL_RULE_SET' 'JAVA_TEST_MISSING_AMBIGUOUS_SET_GUARD'
Assert-FileContains $javaTest 'MISSING_CANONICAL_CATEGORY_COVERAGE' 'JAVA_TEST_MISSING_EXACT_CATEGORY_COVERAGE'
Assert-FileContains $javaTest 'DUPLICATE_CANONICAL_SELECTOR' 'JAVA_TEST_MISSING_SELECTOR_DUPLICATE_GUARD'
Assert-FileContains $javaTest 'condition\.size\(\) != 0' 'JAVA_TEST_MISSING_EMPTY_CONDITION_ASSERTION'
Assert-FileNotContains $javaTest '@ParameterizedTest|@TestFactory|DynamicTest' 'JAVA_TEST_MUST_NOT_USE_ROW_VALUES_AS_DISPLAY_NAMES'

Write-Output 'todo10_canonical_dev_golden_vector_contract=pass'
