package com.integration.security;

import com.integration.domain.User;
import com.integration.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository users;
    private final com.integration.repository.DeviceTokenRepository devices;

    public CustomUserDetailsService(UserRepository users, com.integration.repository.DeviceTokenRepository devices) {
        this.users = users;
        this.devices = devices;
    }

    /** 기기용 토큰 확인: 끊기지 않은 토큰이면 그 사용자 이메일, 아니면 UsernameNotFoundException. 마지막 사용 시각도 갱신(1분 간격). */
    public String verifyDevice(String jti) {
        var d = devices.findByJti(jti).filter(x -> !x.isRevoked())
                .orElseThrow(() -> new UsernameNotFoundException("끊긴 기기 토큰"));
        java.time.Instant now = java.time.Instant.now();
        if (d.getLastUsedAt() == null || d.getLastUsedAt().isBefore(now.minusSeconds(60))) {
            d.setLastUsedAt(now);
            devices.save(d);
        }
        return users.findById(d.getUserId()).map(u -> u.getEmail())
                .orElseThrow(() -> new UsernameNotFoundException("사용자 없음"));
    }

    /** 이 토큰이 마지막 비밀번호 변경보다 먼저 발급됐는지(초 단위 비교). */
    public boolean issuedBeforePasswordChange(String email, java.time.Instant issuedAt) {
        return users.findByEmail(email)
                .map(u -> u.getPasswordChangedAt() != null
                        && issuedAt.isBefore(u.getPasswordChangedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))
                .orElse(false);
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = users.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user: " + email));
        // Principal username = email; authorities empty (single role app).
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPasswordHash(),
                Collections.emptyList()
        );
    }
}
