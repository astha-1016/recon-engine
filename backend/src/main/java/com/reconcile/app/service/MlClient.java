package com.reconcile.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcile.app.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Thin client for the FastAPI service's management endpoints (feedback, retrain, model list). */
@Component
public class MlClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String base;
    private final boolean enabled;

    public MlClient(AppProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.base = props.mlUrl().replaceAll("/+$", "");
        this.enabled = props.mlEnabled();
    }

    public boolean enabled() { return enabled; }

    public JsonNode models() throws Exception { return send("GET", "/models", null); }

    public JsonNode health() throws Exception { return send("GET", "/health", null); }

    public JsonNode train(String algorithm) throws Exception {
        return send("POST", "/train", Map.of("algorithm", algorithm, "use_feedback", true));
    }

    public void feedback(List<Map<String, Object>> rows) throws Exception {
        if (enabled && !rows.isEmpty()) send("POST", "/feedback", Map.of("rows", rows));
    }

    private JsonNode send(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json");
        b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) throw new IllegalStateException("ML service " + res.statusCode() + ": " + res.body());
        return mapper.readTree(res.body());
    }
}
