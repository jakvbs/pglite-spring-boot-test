package com.euronext.pglite.spring.test;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

/**
 * Convenience initializer for tests: enables PGlite without touching application properties.
 * Usage: @ContextConfiguration(initializers = PgliteContextInitializer.class)
 */
public class PgliteContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(applicationContext,
                "pglite.enabled=true"
        );
    }
}

