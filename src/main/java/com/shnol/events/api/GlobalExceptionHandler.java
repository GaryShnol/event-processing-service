package com.shnol.events.api;

import com.mongodb.MongoTimeoutException;
import com.shnol.events.application.EventConflictException;
import com.shnol.events.application.EventNotFoundException;
import com.shnol.events.application.InvalidQueryException;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * Maps failures to RFC 7807 problem details. Validation problems carry an "errors" list naming each
 * failing field; nothing internal (exception messages from libraries, stack traces) reaches the client.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	record FieldError(String field, String message) {
	}

	@ExceptionHandler
	ProblemDetail handleNotFound(EventNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler
	ProblemDetail handleConflict(EventConflictException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler
	ProblemDetail handleInvalidQuery(InvalidQueryException e) {
		return badRequest(List.of(new FieldError(e.parameter(), e.getMessage())));
	}

	@ExceptionHandler
	ProblemDetail handleDatabaseUnavailable(DataAccessResourceFailureException e) {
		return databaseUnavailable(e);
	}

	// With an operation timeout set, the driver reports every timeout, server selection included, as a MongoTimeoutException
	// subtype that Spring Data translates to UncategorizedMongoDbException instead of a resource failure.
	@ExceptionHandler
	ProblemDetail handleUncategorizedDatabaseError(UncategorizedMongoDbException e) {
		return e.getCause() instanceof MongoTimeoutException ? databaseUnavailable(e) : handleUnexpected(e);
	}

	@ExceptionHandler
	ProblemDetail handleUnexpected(Exception e) {
		log.error("Unexpected error", e);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
				.map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
				.toList();
		return ResponseEntity.badRequest().body(badRequest(errors));
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldError> errors = e.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new FieldError(result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
				.toList();
		return ResponseEntity.badRequest().body(badRequest(errors));
	}

	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		return ResponseEntity.badRequest().body(badRequest(List.of(new FieldError(e.getPropertyName(), "has an invalid value"))));
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		if (e.getCause() instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
			String field = jackson.getPath().stream()
					.map(reference -> reference.getPropertyName() != null ? reference.getPropertyName() : "[" + reference.getIndex() + "]")
					.collect(Collectors.joining("."));
			return ResponseEntity.badRequest().body(badRequest(List.of(new FieldError(field, "has an invalid value"))));
		}
		return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body"));
	}

	private static ProblemDetail databaseUnavailable(Exception e) {
		log.error("MongoDB is unavailable", e);
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable");
	}

	private static ProblemDetail badRequest(List<FieldError> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
		problem.setProperty("errors", errors);
		return problem;
	}
}
