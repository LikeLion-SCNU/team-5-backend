package org.example.naeilbank.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SchemaBaselineIntegrationTest {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "consents",
            "conversion_rules",
            "deletion_logs",
            "face_simulations",
            "health_daily",
            "ledger_entries",
            "meal_items",
            "meal_records",
            "notification_logs",
            "plans",
            "protection_events",
            "refresh_tokens",
            "sources",
            "users"
    );

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("naeil_bank_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @Test
    void v1CreatesSchemaContractAndAppendOnlyLedgerTrigger() {
        JdbcTemplate jdbc = migratePublicSchema();

        List<String> tables = jdbc.queryForList("""
                select table_name
                from information_schema.tables
                where table_schema = 'public'
                  and table_type = 'BASE TABLE'
                  and table_name <> 'flyway_schema_history'
                order by table_name
                """, String.class);
        assertThat(tables).containsExactlyElementsOf(EXPECTED_TABLES.stream().sorted().toList());

        List<String> views = jdbc.queryForList("""
                select table_name
                from information_schema.views
                where table_schema = 'public'
                order by table_name
                """, String.class);
        assertThat(views).containsExactly("v_balance", "v_daily_net");

        Integer triggerCount = jdbc.queryForObject("""
                select count(*)
                from pg_trigger t
                join pg_class c on c.oid = t.tgrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public'
                  and c.relname = 'ledger_entries'
                  and t.tgname = 'trg_ledger_no_update'
                  and not t.tgisinternal
                """, Integer.class);
        assertThat(triggerCount).isEqualTo(1);

        UUID userId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)", userId, "ledger@example.com");
        jdbc.update("insert into sources (id, title) values (?, ?)", sourceId, "test source");
        jdbc.update("""
                insert into conversion_rules (id, habit_type, label, minutes_delta, source_id)
                values (?, 'activity', 'test rule', 1, ?)
                """, ruleId, sourceId);
        Long ledgerId = jdbc.queryForObject("""
                insert into ledger_entries (user_id, entry_date, habit_type, minutes_delta, rule_id)
                values (?, ?, 'activity', 1, ?)
                returning id
                """, Long.class, userId, Date.valueOf(LocalDate.of(2026, 1, 1)), ruleId);

        assertThatThrownBy(() -> jdbc.update(
                "update ledger_entries set minutes_delta = 2 where id = ?",
                ledgerId
        )).hasMessageContaining("ledger_entries is append-only");
        assertThatThrownBy(() -> jdbc.update(
                "delete from ledger_entries where id = ?",
                ledgerId
        )).hasMessageContaining("ledger_entries is append-only");
    }

    @Test
    void flywayFailsFastWhenAMigrationIsBroken(@TempDir Path tempDir) throws Exception {
        Path brokenMigration = tempDir.resolve("V1__broken_migration_gate.sql");
        Files.writeString(brokenMigration, "select definitely_missing_column from definitely_missing_table;\n");

        String schema = "broken_migration_gate_" + UUID.randomUUID().toString().replace("-", "");

        assertThatThrownBy(() -> Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("filesystem:" + tempDir.toAbsolutePath())
                .load()
                .migrate())
                .hasMessageContaining("Migration V1__broken_migration_gate.sql failed");
    }

    private JdbcTemplate migratePublicSchema() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();
        return new JdbcTemplate(dataSource());
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
