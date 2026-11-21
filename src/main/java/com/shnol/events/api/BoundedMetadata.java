package com.shnol.events.api;

import com.shnol.events.config.EventsProperties;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Metadata within the configured limits. Keys are restricted to letters, digits, '_' and '-' because
 * MongoDB treats '.' and a leading '$' in field names specially.
 */
@Target({ ElementType.FIELD, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = BoundedMetadata.Validator.class)
@interface BoundedMetadata {

	String message() default "is outside the allowed limits";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<BoundedMetadata, Map<String, String>> {

		private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]+");

		private final EventsProperties.Metadata limits;

		Validator(EventsProperties properties) {
			this.limits = properties.metadata();
		}

		@Override
		public boolean isValid(Map<String, String> metadata, ConstraintValidatorContext context) {
			if (metadata == null) {
				return true;
			}
			if (metadata.size() > limits.maxEntries()) {
				return violation(context, "must have at most %d entries".formatted(limits.maxEntries()));
			}
			for (Map.Entry<String, String> entry : metadata.entrySet()) {
				String key = entry.getKey();
				if (key.length() > limits.maxKeyLength() || !KEY.matcher(key).matches()) {
					return violation(context, "keys must be 1 to %d letters, digits, '_' or '-'".formatted(limits.maxKeyLength()));
				}
				if (entry.getValue() == null || entry.getValue().length() > limits.maxValueLength()) {
					return violation(context, "values must be present and at most %d characters".formatted(limits.maxValueLength()));
				}
			}
			return true;
		}

		private static boolean violation(ConstraintValidatorContext context, String message) {
			context.disableDefaultConstraintViolation();
			context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
			return false;
		}
	}
}
