package com.integration.web;

import com.integration.domain.DeviceToken;
import com.integration.domain.User;
import com.integration.repository.DeviceTokenRepository;
import com.integration.security.CurrentUserService;
import com.integration.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 기기용 토큰 발급·목록·끊기. 웹에서 만들면 토큰을 한 번만 보여 주고, PC·파이의 ~/.momentum.env 에
 * MOMENTUM_TOKEN=... 으로 넣으면 pc-agent·동기화가 비밀번호 없이 동작합니다.
 *
 *  - POST   /api/devices       { name }  → { id, name, token }  (token 은 이때만)
 *  - GET    /api/devices                 → [{ id, name, createdAt, lastUsedAt }]
 *  - DELETE /api/devices/{id}            → 끊기(그 토큰은 즉시 무효)
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceTokenController {

    private static final Duration TTL = Duration.ofDays(3650); // 사실상 끊을 때까지
    private static final int MAX_DEVICES = 20;

    public record CreateRequest(String name) {}
    public record Created(Long id, String name, String token) {}
    public record View(Long id, String name, Instant createdAt, Instant lastUsedAt) {
        static View of(DeviceToken d) { return new View(d.getId(), d.getName(), d.getCreatedAt(), d.getLastUsedAt()); }
    }

    private final DeviceTokenRepository devices;
    private final CurrentUserService currentUser;
    private final JwtService jwt;

    public DeviceTokenController(DeviceTokenRepository devices, CurrentUserService currentUser, JwtService jwt) {
        this.devices = devices;
        this.currentUser = currentUser;
        this.jwt = jwt;
    }

    @PostMapping
    public Created create(@RequestBody CreateRequest req) {
        User u = currentUser.require();
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기기 이름을 정해 주세요.");
        if (name.length() > 60) name = name.substring(0, 60);
        if (devices.findByUserIdAndRevokedFalseOrderByCreatedAtDesc(u.getId()).size() >= MAX_DEVICES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기기는 " + MAX_DEVICES + "개까지예요. 안 쓰는 걸 먼저 끊어 주세요.");
        String jti = UUID.randomUUID().toString();
        DeviceToken d = devices.save(new DeviceToken(u.getId(), name, jti));
        String token = jwt.issueTyped(u.getEmail(), "device", TTL, Map.of("jti", jti));
        return new Created(d.getId(), d.getName(), token);
    }

    @GetMapping
    public List<View> list() {
        return devices.findByUserIdAndRevokedFalseOrderByCreatedAtDesc(currentUser.require().getId()).stream().map(View::of).toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id) {
        DeviceToken d = devices.findByIdAndUserId(id, currentUser.require().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "그 기기를 찾을 수 없어요."));
        d.setRevoked(true);
        devices.save(d);
        return ResponseEntity.noContent().build();
    }
}
