package org.example.naeilbank.todo6;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.cleanAndMigrate;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.flyway;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.insertLedgerSeed;

@Testcontainers
class RollbackBackupRestoreIntegrationTest {

    private static final String SOURCE_DATABASE = "rollback_restore_test";
    private static final String RESTORED_DATABASE = "restored_v2";
    private static final String BACKUP_FILE = "/tmp/populated-v2.sql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(SOURCE_DATABASE)
            .withUsername("naeil")
            .withPassword("naeil_test");

    @Test
    void pgDumpBackupRestoresPopulatedV2AfterLatestMigrationWithoutDownMigration() throws Exception {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, "2");
        insertLedgerSeed(jdbc, "backup-one@example.com");
        insertLedgerSeed(jdbc, "backup-two@example.com");
        String expected = snapshot(jdbc);

        assertSuccess(POSTGRES.execInContainer("sh", "-c", String.format(
                "PGPASSWORD=naeil_test pg_dump -U naeil -d %s --no-owner --no-privileges -f %s",
                SOURCE_DATABASE,
                BACKUP_FILE
        )));

        flyway(POSTGRES, null).migrate();
        assertThat(jdbc.queryForObject(
                "select to_regclass('public.media_blobs') is not null", Boolean.class
        )).isTrue();

        assertSuccess(POSTGRES.execInContainer("createdb", "-U", "naeil", RESTORED_DATABASE));
        assertSuccess(POSTGRES.execInContainer("sh", "-c", String.format(
                "PGPASSWORD=naeil_test psql -v ON_ERROR_STOP=1 -U naeil -d %s -f %s",
                RESTORED_DATABASE,
                BACKUP_FILE
        )));
        org.testcontainers.containers.Container.ExecResult restored = POSTGRES.execInContainer(
                "psql", "-At", "-U", "naeil", "-d", RESTORED_DATABASE,
                "-c", snapshotSql()
        );
        assertSuccess(restored);
        assertThat(restored.getStdout().trim()).isEqualTo(expected);
    }

    private String snapshot(JdbcTemplate jdbc) {
        return jdbc.queryForObject(snapshotSql(), String.class);
    }

    private String snapshotSql() {
        return """
                select (select count(*) from users)::text || '|' ||
                       (select count(*) from ledger_entries)::text || '|' ||
                       (select version from flyway_schema_history
                        where success order by installed_rank desc limit 1) || '|' ||
                       (select md5(string_agg(users.id::text || ':' || users.email || ':' ||
                           ledger_entries.id::text || ':' || ledger_entries.minutes_delta::text,
                           ',' order by users.email))
                        from users join ledger_entries on ledger_entries.user_id = users.id)
                """;
    }

    private void assertSuccess(org.testcontainers.containers.Container.ExecResult result) {
        assertThat(result.getExitCode())
                .withFailMessage("container command failed: %s", result.getStderr())
                .isZero();
    }
}
