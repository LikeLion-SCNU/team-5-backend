package org.example.naeilbank.domain.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LedgerRepositoryRegistrationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withUserConfiguration(RepositoryConfiguration.class);

    @Test
    void repositoryRegistersWhenJdbcTemplateComesFromAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LedgerQueryRepository.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LedgerQueryRepository.class)
    static class RepositoryConfiguration {
    }
}
