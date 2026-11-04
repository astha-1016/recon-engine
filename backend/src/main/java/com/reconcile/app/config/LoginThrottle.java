package com.reconcile.app.config;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing. After {@link #MAX_FAILURES} wrong attempts for a name inside {@link #WINDOW}, that name is
 * locked for {@link #LOCK}. It is keyed by name, not by address: behind the bundled nginx every request arrives from the proxy,
 * so an address key would lock everyone out together. The price is that someone who knows a username can keep it locked;
 * the lock is short and a correct password cannot unlock early, which is the intended trade for a small internal tool.
 * State is in memory, so it resets on restart and is per backend instance.
 */
@Component
public class LoginThrottle {
    public static final int MAX_FAILURES = 5;
    public static final Duration WINDOW = Duration.ofMinutes(15);
    public static final Duration LOCK = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private static final class State { int failures; Instant first; Instant lockedUntil; }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static String norm(String key) { return key == null ? "" : key.trim().toLowerCase(Locale.ROOT); }

    /** Throws 429 while the name is locked. Call before checking the password. */
    public void check(String key) {
        State s = states.get(norm(key));
        if (s == null) return;
        synchronized (s) {
            Instant now = Instant.now();
            if (s.lockedUntil != null && now.isBefore(s.lockedUntil)) {
                long mins = Math.max(1, Duration.between(now, s.lockedUntil).plusSeconds(59).toMinutes());
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many failed attempts. Try again in about " + mins + " minute" + (mins == 1 ? "" : "s") + ".");
            }
        }
    }

    public void failure(String key) {
        if (states.size() >= MAX_TRACKED) prune();
        State s = states.computeIfAbsent(norm(key), k -> new State());
        synchronized (s) {
            Instant now = Instant.now();
            if (s.first == null || now.isAfter(s.first.plus(WINDOW)) || (s.lockedUntil != null && !now.isBefore(s.lockedUntil))) {
                s.failures = 0; s.first = now; s.lockedUntil = null;
            }
            if (++s.failures >= MAX_FAILURES) s.lockedUntil = now.plus(LOCK);
        }
    }

    public void success(String key) { states.remove(norm(key)); }

    private void prune() {
        Instant now = Instant.now();
        for (Iterator<Map.Entry<String, State>> it = states.entrySet().iterator(); it.hasNext(); ) {
            State s = it.next().getValue();
            synchronized (s) {
                boolean locked = s.lockedUntil != null && now.isBefore(s.lockedUntil);
                if (!locked && (s.first == null || now.isAfter(s.first.plus(WINDOW)))) it.remove();
            }
        }
    }
}
