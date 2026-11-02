package dev.aadityaa.orderplatform.order.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The edge of the system: accepts the caller's X-Correlation-Id (or creates one), puts it in the logging
 * context for the request, echoes it back, and exposes it to the controller. The order stores it and every
 * event it causes carries it, so one id ties the whole saga together.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID correlationId = parse(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId.toString());
        MDC.put(ATTRIBUTE, correlationId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ATTRIBUTE);
        }
    }

    private static UUID parse(String header) {
        try {
            return header == null ? UUID.randomUUID() : UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID(); // a malformed id is replaced, not trusted
        }
    }
}
