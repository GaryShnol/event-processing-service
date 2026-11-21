package com.shnol.events;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.UseMainMethod;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(useMainMethod = UseMainMethod.ALWAYS, webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
// The sweeper would re-publish events that tests plant with old timestamps and race their assertions.
// Tests of recovery turn it back on.
@TestPropertySource(properties = "events.sweeper.enabled=false")
public abstract class IntegrationTest {
}
