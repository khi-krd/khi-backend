package ak.dev.khi_backend.khi_app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Puts a per-request correlation id in MDC and echoes it back on the response.
 *
 * Accepts an inbound {@code X-Trace-Id} or {@code X-Request-Id} when it looks
 * like a sane token (alphanumerics and dashes, at most 64 chars) and generates a
 * UUID otherwise, so a hostile or malformed value can never reach the logs or
 * the response headers. Both {@code X-Trace-Id} and {@code X-Request-Id} are
 * echoed so clients written against either convention can correlate.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String TRACE_ID = "traceId";
    public static final String REQUEST_ID = "requestId";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9\\-]{1,64}");

    /**
     * Runs before the Spring Security filter chain: auth failures (expired
     * token, missing role) are answered inside that chain, and without this
     * ordering their responses would carry no correlation id at all.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String id = firstValid(
                request.getHeader(HEADER_TRACE_ID),
                request.getHeader(HEADER_REQUEST_ID)
        );
        if (id == null) {
            id = UUID.randomUUID().toString();
        }

        MDC.put(TRACE_ID, id);
        MDC.put(REQUEST_ID, id);
        response.setHeader(HEADER_TRACE_ID, id);
        response.setHeader(HEADER_REQUEST_ID, id);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
            MDC.remove(REQUEST_ID);
        }
    }

    private static String firstValid(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && VALID_ID.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return null;
    }
}
