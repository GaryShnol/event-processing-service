package com.shnol.events;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.UseMainMethod;
import org.springframework.context.annotation.Import;

@SpringBootTest(useMainMethod = UseMainMethod.ALWAYS)
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {
}
