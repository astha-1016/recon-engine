package com.reconcile.app;

import com.reconcile.app.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
// The repository interfaces are nested inside Repos.java; Spring Data skips nested interfaces unless told otherwise.
@EnableJpaRepositories(considerNestedRepositories = true)
public class ReconcileApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconcileApplication.class, args);
    }
}
