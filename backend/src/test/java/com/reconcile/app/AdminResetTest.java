package com.reconcile.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcile.app.domain.Entities.FeedbackE;
import com.reconcile.app.domain.Repos.FeedbackRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real admin reset. It has its own in-memory database (a different URL from the other tests) and its own backup folder,
 * so wiping data here cannot disturb them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:resettest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "app.backup-dir=target/test-backups"
})
class AdminResetTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired FeedbackRepo feedback;

    private String login(String user) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + user + "\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).get("token").asText();
    }

    private JsonNode json(String path, String token) throws Exception {
        return mapper.readTree(mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void resetBacksUpFirstWipesBusinessDataRetiresFeedbackAndKeepsUsers() throws Exception {
        String admin = login("admin");
        mvc.perform(post("/api/invoices").header("Authorization", admin).contentType("application/json")
                .content("{\"invoiceId\":\"INV-RST-1\",\"customerId\":\"CRST\",\"customerName\":\"Reset Traders\",\"amount\":\"1000.00\","
                        + "\"invoiceDate\":\"2026-07-01\",\"dueDate\":\"2026-07-31\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/transactions").header("Authorization", admin).contentType("application/json")
                .content("{\"transactionId\":\"TX-RST-1\",\"transactionDate\":\"2026-07-10\",\"amount\":\"1000.00\",\"direction\":\"CR\","
                        + "\"payerName\":\"Reset Payer\",\"channel\":\"NEFT\",\"bankReference\":\"UTR-RST-1\",\"reversalReference\":\"\",\"narration\":\"x\"}"))
                .andExpect(status().isCreated());
        FeedbackE f = new FeedbackE();
        f.txnId = "TX-RST-1"; f.label = 1; f.systemScore = 0.9; f.humanDecision = "APPROVED"; f.decidedBy = "admin"; f.featuresJson = "{}";
        feedback.save(f);
        assertEquals(1, json("/api/admin/summary", admin).get("invoices").asLong());

        // wrong password: nothing is touched and no backup is written
        mvc.perform(post("/api/admin/reset").header("Authorization", admin).contentType("application/json")
                .content("{\"confirm\":\"RESET\",\"password\":\"wrong\"}")).andExpect(status().isBadRequest());
        assertEquals(1, json("/api/admin/summary", admin).get("invoices").asLong());
        assertEquals(1, json("/api/admin/summary", admin).get("transactions").asLong());
        assertFalse(feedback.findAll().get(0).revoked);

        String res = mvc.perform(post("/api/admin/reset").header("Authorization", admin).contentType("application/json")
                        .content("{\"confirm\":\"RESET\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode out = mapper.readTree(res);
        assertEquals("reset", out.get("status").asText());

        // the data is gone ...
        JsonNode summary = json("/api/admin/summary", admin);
        assertEquals(0, summary.get("customers").asLong());
        assertEquals(0, summary.get("invoices").asLong());
        assertEquals(0, summary.get("transactions").asLong());
        // ... the old training labels will not be used any more (kept, but revoked) ...
        assertTrue(feedback.findAll().stream().allMatch(x -> x.revoked));
        // ... and people can still sign in: users are not business data
        login("accountant");
        json("/api/admin/summary", admin);

        // ... and the backup holds what was deleted
        Path file = Paths.get("target/test-backups", out.get("backup").asText());
        assertTrue(Files.exists(file), "backup file should exist");
        String text;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) { text = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        assertTrue(text.contains("INV-RST-1"), "backup should contain the deleted invoice");
        assertTrue(text.contains("TX-RST-1"), "backup should contain the deleted payment");
    }
}
