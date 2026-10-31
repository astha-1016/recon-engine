package com.reconcile.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String jwtSecret, String mlUrl, boolean mlEnabled, String payeeVpa, String payeeName,
                            String evaluationFile, String corsOrigins, String seedPassword) {}
