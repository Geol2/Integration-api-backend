package com.integration.web;

import com.integration.ai.AiService;
import com.integration.repository.UserRepository;
import com.integration.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * claude.ai "커스텀 커넥터"로 모멘텀 MCP(/mcp)를 붙이기 위한 OAuth 2.1 (PKCE, 동적 클라이언트 등록).
 *
 * 상태를 저장하지 않습니다: 인가 코드·갱신 토큰을 용도(typ)가 박힌 서명 JWT로 만들어, 서버를 재시작해도 연결이 유지돼요.
 * 접근 토큰은 1시간짜리 일반 JWT라 기존 인증 필터가 그대로 사용자를 알아봅니다.
 *
 *  - GET  /.well-known/oauth-protected-resource   : 이 자원(/mcp)의 인가 서버 위치
 *  - GET  /.well-known/oauth-authorization-server : 인가 서버 메타데이터
 *  - POST /oauth/register   : 동적 클라이언트 등록(공개 클라이언트, 저장 안 함)
 *  - GET  /oauth/authorize  : 모멘텀 로그인 화면 → POST 로 확인 → redirect_uri?code=..&state=..
 *  - POST /oauth/token      : authorization_code(+PKCE) / refresh_token → 접근·갱신 토큰
 *
 * 리다이렉트 주소는 허용 목록(app.mcp.redirect-uris, 기본 claude.ai/claude.com 콜백)만 받습니다 — 다른 곳으로 코드가 새지 않도록.
 */
@RestController
public class McpOAuthController {

    private static final String TYP_CODE = "mcp_code";
    private static final String TYP_REFRESH = "mcp_refresh";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration ACCESS_TTL = Duration.ofHours(1);
    private static final Duration REFRESH_TTL = Duration.ofDays(90);

    private final JwtService jwt;
    private final AuthenticationManager authManager;
    private final UserRepository users;
    private final AiService ai;
    private final String base;
    private final List<String> redirectUris;

    public McpOAuthController(JwtService jwt, AuthenticationManager authManager, UserRepository users, AiService ai,
                              @Value("${app.mcp.base-url:https://api.geol2.com}") String base,
                              @Value("${app.mcp.redirect-uris:https://claude.ai/api/mcp/auth_callback,https://claude.com/api/mcp/auth_callback}") String redirectUris) {
        this.jwt = jwt;
        this.authManager = authManager;
        this.users = users;
        this.ai = ai;
        this.base = base.replaceAll("/$", "");
        this.redirectUris = Arrays.stream(redirectUris.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    // ---------- 메타데이터 ----------

    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    public Map<String, Object> protectedResource() {
        return Map.of(
                "resource", base + "/mcp",
                "authorization_servers", List.of(base),
                "bearer_methods_supported", List.of("header"),
                "resource_name", "Momentum");
    }

    @GetMapping({"/.well-known/oauth-authorization-server", "/.well-known/openid-configuration"})
    public Map<String, Object> authorizationServer() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", base);
        m.put("authorization_endpoint", base + "/oauth/authorize");
        m.put("token_endpoint", base + "/oauth/token");
        m.put("registration_endpoint", base + "/oauth/register");
        m.put("response_types_supported", List.of("code"));
        m.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("token_endpoint_auth_methods_supported", List.of("none", "client_secret_post", "client_secret_basic"));
        m.put("scopes_supported", List.of("momentum"));
        return m;
    }

    // ---------- 동적 클라이언트 등록 ----------

    @PostMapping("/oauth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) Map<String, Object> req) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("client_id", "mcp-" + UUID.randomUUID());
        r.put("client_id_issued_at", Instant.now().getEpochSecond());
        r.put("token_endpoint_auth_method", "none");
        r.put("grant_types", List.of("authorization_code", "refresh_token"));
        r.put("response_types", List.of("code"));
        if (req != null) {
            if (req.get("redirect_uris") != null) r.put("redirect_uris", req.get("redirect_uris"));
            if (req.get("client_name") != null) r.put("client_name", req.get("client_name"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(r);
    }

    // ---------- 로그인(인가) ----------

    @GetMapping(value = "/oauth/authorize", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> authorizeForm(@RequestParam Map<String, String> p) {
        String bad = checkAuthorizeParams(p);
        if (bad != null) return html(HttpStatus.BAD_REQUEST, page(null, bad, false));
        return html(HttpStatus.OK, page(p, null, true));
    }

    @PostMapping(value = "/oauth/authorize", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> authorize(@RequestParam Map<String, String> p) {
        String bad = checkAuthorizeParams(p);
        if (bad != null) return html(HttpStatus.BAD_REQUEST, page(null, bad, false));
        String email = p.getOrDefault("email", "").strip();
        try {
            authManager.authenticate(new UsernamePasswordAuthenticationToken(email, p.getOrDefault("password", "")));
        } catch (AuthenticationException e) {
            return html(HttpStatus.UNAUTHORIZED, page(p, "이메일 또는 비밀번호가 맞지 않아요.", true));
        }
        String canonical = users.findByEmail(email).map(u -> u.getEmail()).orElse(email);
        if (!ai.isEnabledFor(canonical)) {
            return html(HttpStatus.FORBIDDEN, page(p, "이 계정은 Claude 기능을 쓸 수 없어요.", true));
        }
        String code = jwt.issueTyped(canonical, TYP_CODE, CODE_TTL, Map.of(
                "cid", p.get("client_id"), "ru", p.get("redirect_uri"), "cc", p.get("code_challenge")));
        UriComponentsBuilder to = UriComponentsBuilder.fromUriString(p.get("redirect_uri")).queryParam("code", code);
        if (p.get("state") != null) to.queryParam("state", p.get("state"));
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(to.encode().build().toUriString())).build();
    }

    private String checkAuthorizeParams(Map<String, String> p) {
        if (!"code".equals(p.get("response_type"))) return "response_type=code 만 지원해요.";
        if (isBlank(p.get("client_id"))) return "client_id 가 없어요.";
        if (!redirectUris.contains(p.get("redirect_uri"))) return "허용되지 않은 redirect_uri 예요.";
        if (isBlank(p.get("code_challenge")) || !"S256".equals(p.getOrDefault("code_challenge_method", "S256")))
            return "PKCE(S256) code_challenge 가 필요해요.";
        return null;
    }

    // ---------- 토큰 ----------

    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam Map<String, String> p) {
        String grant = p.getOrDefault("grant_type", "");
        try {
            String email;
            if ("authorization_code".equals(grant)) {
                Claims c = jwt.parseTyped(p.getOrDefault("code", ""), TYP_CODE);
                if (!Objects.equals(c.get("ru", String.class), p.get("redirect_uri"))) return oauthError("invalid_grant", "redirect_uri 가 달라요.");
                if (p.get("client_id") != null && !Objects.equals(c.get("cid", String.class), p.get("client_id")))
                    return oauthError("invalid_grant", "client_id 가 달라요.");
                String verifier = p.getOrDefault("code_verifier", "");
                if (verifier.length() < 43 || !s256(verifier).equals(c.get("cc", String.class))) return oauthError("invalid_grant", "PKCE 확인 실패");
                email = c.getSubject();
            } else if ("refresh_token".equals(grant)) {
                email = jwt.parseTyped(p.getOrDefault("refresh_token", ""), TYP_REFRESH).getSubject();
            } else {
                return oauthError("unsupported_grant_type", grant);
            }
            if (users.findByEmail(email).isEmpty() || !ai.isEnabledFor(email)) return oauthError("invalid_grant", "계정을 쓸 수 없어요.");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("access_token", jwt.generateToken(email, ACCESS_TTL));
            r.put("token_type", "Bearer");
            r.put("expires_in", ACCESS_TTL.toSeconds());
            r.put("refresh_token", jwt.issueTyped(email, TYP_REFRESH, REFRESH_TTL, Map.of()));
            r.put("scope", "momentum");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r);
        } catch (JwtException | IllegalArgumentException e) {
            return oauthError("invalid_grant", "코드나 토큰이 올바르지 않거나 만료됐어요.");
        }
    }

    // ---------- 공통 ----------

    private static String s256(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> oauthError(String code, String desc) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("error", code, "error_description", desc));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static ResponseEntity<String> html(HttpStatus st, String body) {
        return ResponseEntity.status(st).contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header("X-Frame-Options", "DENY").cacheControl(CacheControl.noStore()).body(body);
    }

    private static final List<String> CARRY = List.of("response_type", "client_id", "redirect_uri", "code_challenge",
            "code_challenge_method", "state", "scope", "resource");

    private static String page(Map<String, String> p, String error, boolean form) {
        StringBuilder hidden = new StringBuilder();
        if (p != null) for (String k : CARRY) {
            String v = p.get(k);
            if (v != null) hidden.append("<input type=\"hidden\" name=\"").append(k).append("\" value=\"")
                    .append(HtmlUtils.htmlEscape(v)).append("\">");
        }
        String email = p == null ? "" : HtmlUtils.htmlEscape(p.getOrDefault("email", ""));
        return """
                <!doctype html><html lang="ko"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>모멘텀 연결</title>
                <style>
                body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0b0d12;color:#e8e8ea;font-family:system-ui,sans-serif}
                .box{width:100%%;max-width:360px;padding:24px;border:1px solid #222831;border-radius:14px;background:#11141b}
                h1{font-size:18px;margin:0 0 6px;color:#4dff7a} p{font-size:13px;color:#9aa0aa;margin:0 0 16px;line-height:1.5}
                input{width:100%%;box-sizing:border-box;margin:0 0 10px;padding:10px 12px;border-radius:8px;border:1px solid #2a2f3a;background:#0b0d12;color:#e8e8ea;font-size:15px}
                button{width:100%%;padding:11px;border:0;border-radius:8px;background:#4dff7a;color:#000;font-weight:700;font-size:15px;cursor:pointer}
                .err{color:#ff6b6b;font-size:13px;margin:0 0 12px}
                </style></head><body><div class="box">
                <h1>모멘텀 연결</h1>
                <p>Claude가 이 계정의 모멘텀에 대화를 저장할 수 있게 연결해요. 모멘텀 계정으로 로그인하세요.</p>
                %s%s</div></body></html>
                """.formatted(
                error == null ? "" : "<p class=\"err\">" + HtmlUtils.htmlEscape(error) + "</p>",
                !form ? "" : """
                        <form method="post" action="/oauth/authorize">%s
                        <input name="email" type="email" autocomplete="username" placeholder="이메일" value="%s" required>
                        <input name="password" type="password" autocomplete="current-password" placeholder="비밀번호" required autofocus>
                        <button type="submit">로그인하고 연결</button></form>
                        """.formatted(hidden, email));
    }
}
