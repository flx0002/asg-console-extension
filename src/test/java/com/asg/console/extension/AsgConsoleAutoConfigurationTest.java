package com.asg.console.extension;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AsgConsoleAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsgConsoleAutoConfiguration.class));

    @Test
    void autoConfigurationLoadsWithoutError() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }
}
