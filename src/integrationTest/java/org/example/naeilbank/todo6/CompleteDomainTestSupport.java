package org.example.naeilbank.todo6;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

final class CompleteDomainTestSupport {

    private CompleteDomainTestSupport() {
    }

    static JdbcTemplate cleanAndMigrate(PostgreSQLContainer<?> postgres, String target) {
        Flyway flyway = flyway(postgres, target);
        flyway.clean();
        flyway.migrate();
        return new JdbcTemplate(dataSource(postgres));
    }

    static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource(postgres))
                .cleanDisabled(false)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    static List<String> publicTables(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE'
                  and table_name <> 'flyway_schema_history' order by table_name
                """, String.class);
    }

    static List<String> publicViews(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                select table_name from information_schema.views
                where table_schema = 'public' order by table_name
                """, String.class);
    }

    static int triggerCount(JdbcTemplate jdbc, String table, String trigger) {
        return jdbc.queryForObject("""
                select count(*) from pg_trigger t
                join pg_class c on c.oid = t.tgrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relname = ? and t.tgname = ?
                  and not t.tgisinternal
                """, Integer.class, table, trigger);
    }

    static void assertExactColumns(JdbcTemplate jdbc, String table, Set<String> expected) {
        List<String> actual = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = ?
                """, String.class, table);
        assertThat(actual).as("exact columns of public.%s", table).containsExactlyInAnyOrderElementsOf(expected);
    }

    static void assertForeignKey(
            JdbcTemplate jdbc,
            String table,
            String column,
            String referencedTable,
            String referencedColumn
    ) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on tc.constraint_name = kcu.constraint_name
                 and tc.constraint_schema = kcu.constraint_schema
                join information_schema.constraint_column_usage ccu
                  on tc.constraint_name = ccu.constraint_name
                 and tc.constraint_schema = ccu.constraint_schema
                where tc.constraint_schema = 'public'
                  and tc.constraint_type = 'FOREIGN KEY'
                  and tc.table_name = ? and kcu.column_name = ?
                  and ccu.table_name = ? and ccu.column_name = ?
                """, Integer.class, table, column, referencedTable, referencedColumn);
        assertThat(count).as("%s.%s foreign key", table, column).isEqualTo(1);
    }

    static Seed insertLedgerSeed(JdbcTemplate jdbc, String email) {
        UUID userId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, nickname) values (?, ?, 'preserved')", userId, email);
        jdbc.update("insert into sources (id, title) values (?, 'preserved source')", sourceId);
        jdbc.update("""
                insert into conversion_rules (id, habit_type, label, minutes_delta, source_id)
                values (?, 'activity', 'preserved rule', 7, ?)
                """, ruleId, sourceId);
        Long ledgerId = jdbc.queryForObject("""
                insert into ledger_entries (user_id, entry_date, habit_type, minutes_delta, rule_id)
                values (?, ?, 'activity', 7, ?) returning id
                """, Long.class, userId, Date.valueOf(LocalDate.of(2026, 8, 20)), ruleId);
        return new Seed(userId, ledgerId);
    }

    static String preservationDigest(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select md5(string_agg(users.id::text || ':' || users.email || ':' ||
                    ledger_entries.id::text || ':' || ledger_entries.minutes_delta::text,
                    ',' order by users.email))
                from users join ledger_entries on ledger_entries.user_id = users.id
                """, String.class);
    }

    private static DataSource dataSource(PostgreSQLContainer<?> postgres) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(postgres.getDriverClassName());
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    record Seed(UUID userId, Long ledgerId) {
    }
}
