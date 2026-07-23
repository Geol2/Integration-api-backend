package com.integration.web;

import com.integration.auth.EmailVerificationService;
import com.integration.domain.User;
import com.integration.repository.UserRepository;
import com.integration.security.CurrentUserService;
import com.integration.security.JwtService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final CurrentUserService currentUser;
    private final JwtService jwtService;
    private final EmailVerificationService emailVerification;

    public AuthController(UserRepository users,
                          PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager,
                          CurrentUserService currentUser,
                          JwtService jwtService,
                          EmailVerificationService emailVerification) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.currentUser = currentUser;
        this.jwtService = jwtService;
        this.emailVerification = emailVerification;
    }

    public record RequestCodeRequest(@Email @NotBlank String email) {}

    public record SignupRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 6, max = 100) String password,
            @NotBlank String name,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "code must be 6 digits") String code) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {}

    public record UserResponse(Long id, String email, String name) {
        static UserResponse of(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getName());
        }
    }

    public record AuthResponse(String token, UserResponse user) {}

    /** Step 1 of signup: email a 6-digit verification code to the given address. */
    @PostMapping("/request-code")
    public ResponseEntity<Void> requestCode(@Valid @RequestBody RequestCodeRequest req) {
        emailVerification.requestCode(req.email());
        return ResponseEntity.noContent().build();
    }

    /** Step 2 of signup: verify the emailed code, then create the account. */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest req) {
        // Consume the code first — an invalid/expired code 400s before we touch users.
        emailVerification.verifyAndConsume(req.email(), req.code());
        if (users.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = new User(req.email(), passwordEncoder.encode(req.password()), req.name());
        users.save(user);
        // Auto-login on signup so the frontend doesn't need a second round trip.
        String token = jwtService.generateToken(user.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(token, UserResponse.of(user)));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.email(), req.password()));
        } catch (BadCredentialsException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        // This request carries no JWT of its own, so currentUser.require() would 401 here —
        // look the user up directly instead.
        User user = users.findByEmail(req.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        String token = jwtService.generateToken(user.getEmail());
        return new AuthResponse(token, UserResponse.of(user));
    }

    @GetMapping("/me")
    public UserResponse me() {
        return UserResponse.of(currentUser.require());
    }
}
