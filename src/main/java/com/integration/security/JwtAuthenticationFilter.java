package com.integration.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Not a {@code @Component}: Spring Boot auto-registers any {@code Filter} bean as a raw
 * servlet filter in addition to whatever Spring Security does with it via
 * {@code addFilterBefore}, which would run this filter twice per request. Instead
 * {@link SecurityConfig} constructs this directly and wires it into the security chain only.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 갱신된 토큰을 실어 보내는 응답 헤더. CORS 환경에서는 노출 헤더로 선언해야 읽힙니다. */
    public static final String RENEWED_TOKEN_HEADER = "X-Renewed-Token";

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, CustomUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7);
            try {
                String email = jwtService.extractEmail(token);
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                var authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);

                // 슬라이딩 만료: 수명이 절반 이하로 남았으면 새 토큰을 헤더로 돌려줍니다.
                // 프론트(api.js)가 이 헤더를 보고 저장된 토큰을 조용히 교체합니다.
                if (jwtService.shouldRenew(token)) {
                    response.setHeader(RENEWED_TOKEN_HEADER, jwtService.generateToken(email));
                }
            } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
                // Invalid/expired/tampered token, or user deleted since it was issued.
                // Leave the context empty — anyRequest().authenticated() will 401 downstream.
            }
        }
        filterChain.doFilter(request, response);
    }
}
