package com.shnol.events.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;

/**
 * An instant from 1970 to 9999. Jackson accepts years far outside that, which overflow the conversion to a
 * MongoDB date when the event is stored.
 */
@Target({ ElementType.FIELD, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StorableTimestamp.Validator.class)
@interface StorableTimestamp {

	String message() default "must be from 1970-01-01T00:00:00Z to 9999-12-31T23:59:59.999Z";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<StorableTimestamp, Instant> {

		private static final Instant MIN = Instant.EPOCH;
		private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999999Z");

		@Override
		public boolean isValid(Instant value, ConstraintValidatorContext context) {
			return value == null || (!value.isBefore(MIN) && !value.isAfter(MAX));
		}
	}
}
