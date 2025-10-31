package com.euronext.pglite.spring.test;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ContextConfiguration(initializers = PgliteContextInitializer.class)
@TestPropertySource(properties = {"pglite.enabled=true"})
public @interface PgliteTest { }

