package com.asg.console.extension;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * ASG console extension entry point.
 *
 * <p>Registered via {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports};
 * auto-assembled whenever the asg-console-extension jar is on the classpath of AISecGw-console.
 *
 * <p>All ASG-specific beans (controllers, services, scheduled tasks, plugin spec overrides)
 * live under {@code com.asg.console.extension} so that the upstream codebase stays untouched.
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.asg.console.extension")
public class AsgConsoleAutoConfiguration {
}
