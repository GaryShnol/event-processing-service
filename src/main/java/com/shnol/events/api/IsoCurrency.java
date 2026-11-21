package com.shnol.events.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;

/** An ISO 4217 currency code known to the JDK, in upper case. */
@Target({ ElementType.FIELD, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = IsoCurrency.Validator.class)
@interface IsoCurrency {

	String message() default "must be an ISO 4217 currency code";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<IsoCurrency, String> {

		private static final Set<String> CODES = Currency.getAvailableCurrencies().stream()
				.map(Currency::getCurrencyCode)
				.collect(Collectors.toUnmodifiableSet());

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			return value == null || CODES.contains(value);
		}
	}
}
