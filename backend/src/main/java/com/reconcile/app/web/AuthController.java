package com.reconcile.app.web;

import com.reconcile.app.domain.Entities.UserE;
import com.reconcile.app.domain.Repos.UserRepo;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(String username, String password) {}

    public record LoginResponse(String token, String username, String role) {}

    private final UserRepo users;
    private final PasswordEncoder encoder;
    private final JwtEncoder jwt;

    public AuthController(UserRepo users, PasswordEncoder encoder, JwtEncoder jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        UserE u = users.findByUsername(req.username() == null ? "" : req.username())
                .filter(x -> encoder.matches(req.password() == null ? "" : req.password(), x.passwordHash))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong username or password"));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(u.username).issuedAt(now).expiresAt(now.plus(Duration.ofHours(8)))
                .claim("roles", List.of(u.role)).build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, u.username, u.role);
    }
}
