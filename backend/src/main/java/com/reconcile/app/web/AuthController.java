package com.reconcile.app.web;

import com.reconcile.app.config.LoginThrottle;
import com.reconcile.app.domain.Entities.UserE;
import com.reconcile.app.domain.Repos.UserRepo;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(String username, String password) {}

    public record LoginResponse(String token, String username, String role) {}

    /** confirmPassword is optional so older clients keep working; when it is sent it must match. */
    public record ChangePasswordRequest(String currentPassword, String newPassword, String confirmPassword) {}

    private final UserRepo users;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;
    private final LoginThrottle throttle;
    /** Checked against when the username does not exist, so an unknown name takes as long to refuse as a wrong password. */
    private final String dummyHash;

    public AuthController(UserRepo users, PasswordEncoder encoder, JwtEncoder jwt, LoginThrottle throttle) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.throttle = throttle;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        String name = req.username() == null ? "" : req.username();
        String password = req.password() == null ? "" : req.password();
        throttle.check(name);
        UserE u = users.findByUsername(name).orElse(null);
        boolean ok = encoder.matches(password, u == null ? dummyHash : u.passwordHash) && u != null;
        if (!ok) {
            throttle.failure(name);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong username or password");
        }
        throttle.success(name);
        return new LoginResponse(issue(u), u.username, u.role);
    }

    /** Any signed-in user changes their own password. Every other session of theirs ends; this one gets a fresh token. */
    @PostMapping("/change-password")
    public Map<String, String> changePassword(@RequestBody ChangePasswordRequest req, Authentication auth) {
        UserE u = users.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));
        String key = "change-password:" + u.username;   // separate from sign-in, so guessing here cannot lock the account out of it
        throttle.check(key);
        if (req.currentPassword() == null || !encoder.matches(req.currentPassword(), u.passwordHash)) {
            throttle.failure(key);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is wrong");
        }
        throttle.success(key);
        UserController.checkPassword(req.newPassword());
        if (req.confirmPassword() != null && !req.confirmPassword().equals(req.newPassword()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The two new passwords do not match");
        if (req.newPassword().equals(req.currentPassword()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The new password must be different from the current one");
        u.passwordHash = encoder.encode(req.newPassword());
        u.tokenVersion++;
        users.save(u);
        return Map.of("status", "password changed", "token", issue(u));
    }

    private String issue(UserE u) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(u.username).issuedAt(now).expiresAt(now.plus(Duration.ofHours(8)))
                .claim("roles", List.of(u.role)).claim("ver", u.tokenVersion).build();
        return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
