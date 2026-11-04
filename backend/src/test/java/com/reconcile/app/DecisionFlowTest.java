package com.reconcile.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcile.app.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Manual decisions, undo, and unapplied credits, end to end through HTTP (in-memory H2, ML service disabled).
 * Uses its own invoice and transaction ids so it does not collide with ApiSmokeTest in the shared context.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DecisionFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private String login(String user) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + user + "\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).get("token").asText();
    }

    private MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes());
    }

    private JsonNode getJson(String path, String token) throws Exception {
        return mapper.readTree(mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** Runs reconciliation twice: the second call waits for any background run started by a decision. */
    private void settle(String token) throws Exception {
        mvc.perform(post("/api/reconcile/run").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/reconcile/run").header("Authorization", token)).andExpect(status().isOk());
    }

    private JsonNode creditOf(String txnId, String token) throws Exception {
        for (JsonNode c : getJson("/api/credits", token)) if (c.get("txnId").asText().equals(txnId)) return c;
        return null;
    }

    private void load(String token) throws Exception {
        String invoices = "invoice_id,customer_id,customer_name,amount,invoice_date,due_date\n"
                + "INV-2026-09001,C9,Delta Traders Pvt Ltd,1000.00,2026-07-01,2026-07-31\n"
                + "INV-2026-09002,C9,Delta Traders Pvt Ltd,700.00,2026-07-05,2026-08-04\n";
        String txns = "transaction_id,transaction_date,amount,direction,payer_name,channel,bank_reference,reversal_reference,narration\n"
                + "TX-D1,2026-07-10,1010.00,CR,zz.unknown@exampleupi,QR,UTR-D1,,QR PAYMENT\n";
        mvc.perform(multipart("/api/upload/invoices").file(csv("i.csv", invoices)).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(multipart("/api/upload/transactions").file(csv("t.csv", txns)).header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    void creditCanBeAppliedAndRemoved() throws Exception {
        String token = login("accountant");
        load(token);
        // a person allocates 1000.00 of a 1010.00 payment: the surplus becomes an unapplied credit
        mvc.perform(post("/api/exceptions/TX-D1/assign").header("Authorization", token).contentType("application/json")
                .content("{\"parts\":[{\"invoiceId\":\"INV-2026-09001\",\"amount\":\"1000.00\"}]}")).andExpect(status().isOk());
        settle(token);
        JsonNode credit = creditOf("TX-D1", token);
        assertNotNull(credit, "the surplus should be listed as a credit");
        assertEquals(1000, credit.get("amount").asLong());
        assertEquals("C9", credit.get("customerId").asText());

        // apply 5.00 of it to the customer's other invoice
        mvc.perform(post("/api/credits/TX-D1/apply").header("Authorization", token).contentType("application/json")
                .content("{\"invoiceId\":\"INV-2026-09002\",\"amount\":\"5.00\"}")).andExpect(status().isOk());
        settle(token);
        assertEquals(500, creditOf("TX-D1", token).get("amount").asLong());
        assertEquals(1, getJson("/api/credits/applications", token).size());

        // more than is left must be refused
        mvc.perform(post("/api/credits/TX-D1/apply").header("Authorization", token).contentType("application/json")
                .content("{\"invoiceId\":\"INV-2026-09002\",\"amount\":\"5.01\"}")).andExpect(status().isBadRequest());

        // removing the application gives the credit back
        long id = getJson("/api/credits/applications", token).get(0).get("id").asLong();
        mvc.perform(delete("/api/credits/applications/" + id).header("Authorization", token)).andExpect(status().isOk());
        settle(token);
        assertEquals(1000, creditOf("TX-D1", token).get("amount").asLong());
    }

    @Test
    void decisionCanBeUndone() throws Exception {
        String token = login("accountant");
        load(token);
        mvc.perform(post("/api/exceptions/TX-D1/assign").header("Authorization", token).contentType("application/json")
                .content("{\"parts\":[{\"invoiceId\":\"INV-2026-09001\",\"amount\":\"1000.00\"}]}")).andExpect(status().isOk());
        boolean listed = false;
        for (JsonNode d : getJson("/api/decisions", token)) listed |= d.get("txnId").asText().equals("TX-D1");
        assertTrue(listed, "the decision should be listed");

        mvc.perform(post("/api/decisions/TX-D1/undo").header("Authorization", token)).andExpect(status().isOk());
        for (JsonNode d : getJson("/api/decisions", token)) assertNotEquals("TX-D1", d.get("txnId").asText());
        // undoing twice is a clear error, not a silent no-op
        mvc.perform(post("/api/decisions/TX-D1/undo").header("Authorization", token)).andExpect(status().isNotFound());
        settle(token);
    }

    @Test
    void viewerCannotDecideApplyCreditsOrUndo() throws Exception {
        String viewer = login("viewer");
        mvc.perform(post("/api/decisions/TX-D1/undo").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/credits/TX-D1/apply").header("Authorization", viewer).contentType("application/json")
                .content("{\"invoiceId\":\"x\",\"amount\":\"1.00\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/credits/applications/1").header("Authorization", viewer)).andExpect(status().isForbidden());
        // but a viewer may look
        mvc.perform(get("/api/credits").header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(get("/api/decisions").header("Authorization", viewer)).andExpect(status().isOk());
    }

    @Test
    void placeholderSecretsAreRecognised() {
        AppProperties weak = new AppProperties("change-this-secret-it-must-be-at-least-32-bytes", "http://ml", false, "m@b", "M", "e.json", "http://x", "change-me-now", true);
        assertTrue(weak.usesPlaceholderJwtSecret());
        assertTrue(weak.usesPlaceholderPassword());
        AppProperties strong = new AppProperties("Xk3v9Qz7LmN2pR8sT5wY1aB4cD6eF0gH", "http://ml", false, "m@b", "M", "e.json", "http://x", "a-real-password-9!", true);
        assertFalse(strong.usesPlaceholderJwtSecret());
        assertFalse(strong.usesPlaceholderPassword());
    }
}
