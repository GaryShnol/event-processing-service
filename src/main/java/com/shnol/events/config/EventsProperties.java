package com.shnol.events.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("events")
public record EventsProperties(
		@Valid @NotNull Queues queues,
		@NotNull @DurationMin(millis = 100) Duration publishTimeout,
		@NotNull @DurationMin(seconds = 1) Duration lease,
		@Min(1) int maxAttempts,
		@NotNull @Positive BigDecimal maxAmount,
		@Valid @NotNull Sweeper sweeper,
		@Valid @NotNull Paging paging,
		@Valid @NotNull Metadata metadata) {

	public record Queues(@NotBlank String events) {
	}

	public record Sweeper(
			@NotNull @DurationMin(seconds = 1) Duration interval,
			@NotNull @DurationMin(seconds = 1) Duration republishAfter,
			@Min(1) int batchSize) {
	}

	public record Paging(@Min(1) int defaultSize, @Min(1) int maxSize, @Min(1) int maxOffset) {

		@AssertTrue(message = "defaultSize must not exceed maxSize")
		public boolean isDefaultSizeWithinMax() {
			return defaultSize <= maxSize;
		}
	}

	public record Metadata(@Min(1) int maxEntries, @Min(1) int maxKeyLength, @Min(1) int maxValueLength) {
	}
}
