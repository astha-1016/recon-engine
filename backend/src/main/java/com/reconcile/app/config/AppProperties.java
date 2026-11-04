package com.reconcile.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String jwtSecret, String mlUrl, boolean mlEnabled, String payeeVpa, String payeeName,
                            String evaluationFile, String corsOrigins, String seedPassword, boolean requireStrongSecrets) {

    /** Placeholder values shipped in the repo. With require-strong-secrets=true the app refuses to start on them. */
    public boolean usesPlaceholderJwtSecret() {
    return jwtSecret.startsWith("change-this") || jwtSecret.startsWith("dev-only")
            || jwtSecret.startsWith("test-secret") || jwtSecret.startsWith("replace-with");
    }
    public boolean usesPlaceholderPassword() {
        return seedPassword.equals("change-me-now") || seedPassword.equals("test-password")
                || seedPassword.startsWith("replace-with") || seedPassword.startsWith("<");
    }
}
