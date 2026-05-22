package com.yakuso.psychat.common;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

public class TraceFilter implements Filter {

    private static final String TRACE_ID_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = null;
        if (request instanceof HttpServletRequest httpRequest) {
            traceId = httpRequest.getHeader("X-Trace-Id");
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        try {
            MDC.put(TRACE_ID_KEY, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
