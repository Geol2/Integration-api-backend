package com.integration.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final String allowedOrigin;
    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    public SecurityConfig(@Value("${app.cors.allowed-origin}") String allowedOrigin,
                           JwtService jwtService,
                           CustomUserDetailsService userDetailsService) {
        this.allowedOrigin = allowedOrigin;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // Stateless JWT sent via Authorization header, not a cookie — no CSRF exposure.
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                // 서버-대-서버 내부 엔드포인트(n8n → Web Push). JWT 대신 X-Internal-Key로
                // 자체 검증하므로 여기서 permitAll로 열되, 컨트롤러에서 키를 확인합니다.
                .requestMatchers("/internal/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                // Error dispatch must be reachable, else parse/validation errors surface as 401.
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            )
            // Return 401 instead of redirecting to a login page for unauthenticated API calls.
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            // Allow the H2 console (served in a frame) during development.
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
            .addFilterBefore(new JwtAuthenticationFilter(jwtService, userDetailsService),
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // 브라우저는 기본적으로 소수의 응답 헤더만 JS에 노출합니다. 갱신 토큰 헤더를
        // 여기 명시하지 않으면 cross-origin 환경에서 프론트가 읽지 못해 슬라이딩 만료가
        // 조용히 동작하지 않습니다.
        config.setExposedHeaders(List.of(JwtAuthenticationFilter.RENEWED_TOKEN_HEADER));
        config.setAllowCredentials(true); // required so the browser sends the session cookie
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
