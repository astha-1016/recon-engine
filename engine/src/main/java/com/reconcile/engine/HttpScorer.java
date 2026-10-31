package com.reconcile.engine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Calls the FastAPI ML service (POST /score). Uses only the JDK, so the engine stays dependency-free. */
public final class HttpScorer implements Scorer {
    private static final Pattern PROBS = Pattern.compile("\"probabilities\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern VERSION = Pattern.compile("\"model_version\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;
    private volatile String version = "unknown";

    public HttpScorer(String baseUrl) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/score");
    }

    @Override
    public double[] score(List<double[]> rows) throws Exception {
        if (rows.isEmpty()) return new double[0];
        StringBuilder sb = new StringBuilder("{\"features\":[");
        for (int i = 0; i < Features.NAMES.size(); i++) sb.append(i > 0 ? "," : "").append('"').append(Features.NAMES.get(i)).append('"');
        sb.append("],\"rows\":[");
        for (int r = 0; r < rows.size(); r++) {
            sb.append(r > 0 ? "," : "").append('[');
            double[] row = rows.get(r);
            for (int c = 0; c < row.length; c++) sb.append(c > 0 ? "," : "").append(row[c]);
            sb.append(']');
        }
        sb.append("]}");
        HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(sb.toString())).build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("ML service returned " + res.statusCode() + ": " + res.body());
        Matcher m = PROBS.matcher(res.body());
        if (!m.find()) throw new IllegalStateException("Unexpected ML response: " + res.body());
        String[] parts = m.group(1).isBlank() ? new String[0] : m.group(1).split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i].trim());
        if (out.length != rows.size()) throw new IllegalStateException("ML service returned " + out.length + " scores for " + rows.size() + " rows");
        Matcher v = VERSION.matcher(res.body());
        if (v.find()) version = v.group(1);
        return out;
    }

    @Override
    public String version() { return version; }
}
