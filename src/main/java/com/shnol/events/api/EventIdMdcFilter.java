package com.shnol.events.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Removes the eventId the controller put in the MDC once the request is finished, after the exception
 * handlers have logged with it, so it can't leak into the next request on the same thread.
 */
@Component
class EventIdMdcFilter extends OncePerRequestFilter {

	static final String KEY = "eventId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(KEY);
		}
	}
}
