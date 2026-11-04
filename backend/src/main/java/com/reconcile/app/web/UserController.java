package com.reconcile.app.web;

import com.reconcile.app.domain.Entities.AuditE;
import com.reconcile.app.domain.Entities.UserE;
import com.reconcile.app.domain.Repos.AuditRepo;
import com.reconcile.app.domain.Repos.UserRepo;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Staff accounts. Admin only. Passwords are stored as bcrypt hashes and are never returned. */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    public static final Set<String> ROLES = Set.of("ADMIN", "ACCOUNTANT", "VIEWER");
    public static final int MIN_PASSWORD = 5;

    public record UserView(Long id, String username, String role) {}
    public record NewUser(String username, String password, String role) {}
    public record RoleChange(String role) {}
    public record PasswordChange(String password) {}

    private final UserRepo users;
    private final PasswordEncoder encoder;
    private final AuditRepo audit;

    public UserController(UserRepo users, PasswordEncoder encoder, AuditRepo audit) {
        this.users = users; this.encoder = encoder; this.audit = audit;
    }

    @GetMapping
    public List<UserView> list() {
        List<UserView> out = new ArrayList<>();
        for (UserE u : users.findAll()) out.add(new UserView(u.id, u.username, u.role));
        out.sort(Comparator.comparing(UserView::username));
        return out;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@RequestBody NewUser in, Authentication auth) {
        String name = in.username() == null ? "" : in.username().trim();
        if (!name.matches("[A-Za-z0-9._-]{3,40}"))
            throw bad("Username must be 3-40 characters: letters, digits, dot, dash or underscore");
        if (users.findByUsername(name).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is taken");
        checkRole(in.role());
        checkPassword(in.password());
        UserE u = new UserE();
        u.username = name; u.role = in.role(); u.passwordHash = encoder.encode(in.password());
        users.save(u);
        log("CREATE", name, "role " + u.role, auth.getName());
        return new UserView(u.id, u.username, u.role);
    }

    @PutMapping("/{id}/role")
    public UserView changeRole(@PathVariable Long id, @RequestBody RoleChange in, Authentication auth) {
        UserE u = find(id);
        checkRole(in.role());
        if (u.username.equals(auth.getName()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot change your own role. Ask another admin.");
        if ("ADMIN".equals(u.role) && !"ADMIN".equals(in.role()) && admins() <= 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There must always be at least one admin");
        String before = u.role;
        u.role = in.role();
        u.tokenVersion++;   // their current sign-ins stop working now, not in 8 hours
        users.save(u);
        log("ROLE", u.username, before + " -> " + u.role, auth.getName());
        return new UserView(u.id, u.username, u.role);
    }

    /** An admin sets a new password for someone (for example when it is forgotten). */
    @PutMapping("/{id}/password")
    public Map<String, String> resetPassword(@PathVariable Long id, @RequestBody PasswordChange in, Authentication auth) {
        UserE u = find(id);
        if (u.username.equals(auth.getName()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Use \"Change my password\" for your own account");
        checkPassword(in.password());
        u.passwordHash = encoder.encode(in.password());
        u.tokenVersion++;   // ends every existing session of that account, including an attacker's
        users.save(u);
        log("PASSWORD_RESET", u.username, "password reset by admin; existing sign-ins ended", auth.getName());
        return Map.of("status", "password changed");
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable Long id, Authentication auth) {
        UserE u = find(id);
        if (u.username.equals(auth.getName())) throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot delete your own account");
        if ("ADMIN".equals(u.role) && admins() <= 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "There must always be at least one admin");
        users.delete(u);
        log("DELETE", u.username, "account removed", auth.getName());
        return Map.of("status", "deleted");
    }

    static void checkPassword(String p) {
        if (p == null || p.length() < MIN_PASSWORD) throw bad("Password must be at least " + MIN_PASSWORD + " characters");
    }

    private static void checkRole(String role) {
        if (role == null || !ROLES.contains(role)) throw bad("Role must be ADMIN, ACCOUNTANT or VIEWER");
    }

    private UserE find(Long id) {
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));
    }

    private long admins() {
        return users.findAll().stream().filter(x -> "ADMIN".equals(x.role)).count();
    }

    private void log(String action, String username, String detail, String actor) {
        AuditE a = new AuditE();
        a.entityType = "USER"; a.entityId = username; a.action = action; a.detail = detail; a.actor = actor;
        audit.save(a);
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
}
