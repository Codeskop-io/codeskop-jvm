package com.codeskop.server.integrations;

import com.codeskop.server.Codeskop;
import com.codeskop.server.Events;
import com.codeskop.server.RequestRecorder;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Servlet filter (Jakarta Servlet 5+): Spring Boot 3, Spring MVC, Jetty, Tomcat, Quarkus (servlet), etc.
 *
 * <p>Spring Boot: declare it as a bean and Spring registers it for every request:
 * <pre>{@code @Bean CodeskopFilter codeskopFilter() { return new CodeskopFilter(); }}</pre>
 *
 * Records each request by route pattern ({@code /orders/{id}}), reports unhandled exceptions,
 * and attaches the authenticated principal's name as the user ID.
 */
public class CodeskopFilter implements Filter {
    /** Spring MVC stores the matched pattern here. */
    private static final String SPRING_PATTERN = "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    @Override
    public void init(FilterConfig filterConfig) {
        Codeskop.setFrameworkIfUnset("servlet");
        try {
            Class.forName("org.springframework.web.servlet.DispatcherServlet");
            Codeskop.setFrameworkIfUnset("spring");
        } catch (ClassNotFoundException ignored) {
            // plain servlet container
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse) || Codeskop.client() == null) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        RequestRecorder rec = new RequestRecorder(req.getMethod(), name -> req.getHeader(name));
        if (rec.trust(req, name -> req.getHeader(name), req::getParameter, req.getRemoteAddr())) {
            res.setStatus(403);
            res.setContentType("application/json");
            res.getOutputStream().write(RequestRecorder.BLOCKED_BODY.getBytes(StandardCharsets.UTF_8));
            rec.finish(Events.normalizePath(req.getRequestURI()), 403, null, null);
            return;
        }
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = res.getStatus();
        } catch (IOException | ServletException | RuntimeException e) {
            Throwable cause = e instanceof ServletException && e.getCause() != null ? e.getCause() : e;
            user(req);
            rec.exception(cause, route(req), "servlet");
            throw e;
        } finally {
            user(req);
            long reqBytes = req.getContentLengthLong();
            String len = res.getHeader("Content-Length");
            rec.finish(route(req), status, reqBytes >= 0 ? reqBytes : null, len == null ? null : parse(len));
        }
    }

    private static Long parse(String v) {
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return null; }
    }

    private static void user(HttpServletRequest req) {
        try {
            if (req.getUserPrincipal() != null && Codeskop.currentUserOrNull() == null) Codeskop.setUser(req.getUserPrincipal().getName());
        } catch (RuntimeException ignored) {
            // no security context
        }
    }

    static String route(HttpServletRequest req) {
        Object pattern = req.getAttribute(SPRING_PATTERN);
        if (pattern instanceof String && !((String) pattern).isEmpty()) return Events.templateRoute((String) pattern);
        return Events.normalizePath(req.getRequestURI());
    }
}
