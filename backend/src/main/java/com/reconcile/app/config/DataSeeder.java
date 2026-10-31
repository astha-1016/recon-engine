package com.reconcile.app.config;

import com.reconcile.app.domain.Entities.UserE;
import com.reconcile.app.domain.Repos.UserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates one user per role on first start. Change SEED_PASSWORD before deploying anywhere real. */
@Component
public class DataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepo users;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public DataSeeder(UserRepo users, PasswordEncoder encoder, AppProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        if (users.count() > 0) return;
        for (String[] u : new String[][]{{"admin", "ADMIN"}, {"accountant", "ACCOUNTANT"}, {"viewer", "VIEWER"}}) {
            UserE e = new UserE();
            e.username = u[0];
            e.role = u[1];
            e.passwordHash = encoder.encode(props.seedPassword());
            users.save(e);
        }
        log.warn("Seeded users admin / accountant / viewer with the SEED_PASSWORD value. Change it for any real deployment.");
    }
}
