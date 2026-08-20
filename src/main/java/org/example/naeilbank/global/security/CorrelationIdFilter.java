package org.example.naeilbank.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.naeilbank.global.exception.CorrelationId;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = CorrelationId.resolve(request);
        response.setHeader(CorrelationId.HEADER_NAME, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (!response.isCommitted()) {
                response.setHeader(CorrelationId.HEADER_NAME, correlationId);
            }
        }
    }
}
