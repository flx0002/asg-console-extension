package com.asg.console.extension;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * ASG console extension entry point.
 *
 * <p>Registered via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports};
 * auto-assembled whenever the asg-console-extension jar is on the classpath of AISecGw-console.
 *
 * <p>All ASG-specific beans (controllers, services, scheduled tasks, JPA repositories)
 * live under {@code com.asg.console.extension} so that the upstream codebase stays untouched.
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.asg.console.extension")
@EnableJpaRepositories(basePackages = "com.asg.console.extension.repository")
@EntityScan(basePackages = "com.asg.console.extension.model")
public class AsgConsoleAutoConfiguration {
}
