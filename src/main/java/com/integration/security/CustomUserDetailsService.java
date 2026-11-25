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

    public CustomUserDetailsService(UserRepository users) {
        this.users = users;
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
