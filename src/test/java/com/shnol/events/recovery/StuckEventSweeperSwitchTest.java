package com.shnol.events.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.shnol.events.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

// IntegrationTest sets events.sweeper.enabled=false; FailureRecoveryTest covers it switched on.
class StuckEventSweeperSwitchTest extends IntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void sweeperIsNotCreatedWhenDisabled() {
		assertThat(context.getBeanProvider(StuckEventSweeper.class).getIfAvailable()).isNull();
	}
}
