package com.reconcile.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Maintaining data from the app: add / edit / delete invoices and payments, the lock on decided rows,
 * user management, and the admin reset guard. Own ids throughout (shared in-memory database with the other tests),
 * and the real reset is deliberately not called here because it would wipe the other tests' data.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecordsAndUsersTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private String login(String user, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).get("token").asText();
    }

    private String login(String user) throws Exception { return login(user, "test-password"); }

    private JsonNode getJson(String path, String token) throws Exception {
        return mapper.readTree(mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static String invoiceJson(String id, String amount) {
        return "{\"invoiceId\":\"" + id + "\",\"customerId\":\"CR1\",\"customerName\":\"Records Traders\",\"amount\":\"" + amount
                + "\",\"invoiceDate\":\"2026-07-01\",\"dueDate\":\"2026-07-31\"}";
    }

    private static String txnJson(String id, String amount) {
        return "{\"transactionId\":\"" + id + "\",\"transactionDate\":\"2026-07-10\",\"amount\":\"" + amount
                + "\",\"direction\":\"CR\",\"payerName\":\"Records Payer\",\"channel\":\"NEFT\",\"bankReference\":\"UTR-" + id
                + "\",\"reversalReference\":\"\",\"narration\":\"manual entry\"}";
    }

    private void settle(String token) throws Exception {
        mvc.perform(post("/api/reconcile/run").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/reconcile/run").header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    void invoiceCanBeCreatedEditedAndDeleted() throws Exception {
        String admin = login("admin"), accountant = login("accountant"), viewer = login("viewer");
        mvc.perform(post("/api/invoices").header("Authorization", admin).contentType("application/json")
                .content(invoiceJson("INV-REC-1", "1000.00"))).andExpect(status().isCreated());
        // the same invoice again is refused, a bad amount is refused with a reason
        mvc.perform(post("/api/invoices").header("Authorization", admin).contentType("application/json")
                .content(invoiceJson("INV-REC-1", "1000.00"))).andExpect(status().isConflict());
        mvc.perform(post("/api/invoices").header("Authorization", admin).contentType("application/json")
                .content(invoiceJson("INV-REC-X", "abc"))).andExpect(status().isBadRequest());
        // a viewer may not write
        mvc.perform(post("/api/invoices").header("Authorization", viewer).contentType("application/json")
                .content(invoiceJson("INV-REC-2", "1.00"))).andExpect(status().isForbidden());

        mvc.perform(put("/api/invoices/INV-REC-1").header("Authorization", accountant).contentType("application/json")
                .content(invoiceJson("INV-REC-1", "1200.50"))).andExpect(status().isOk());
        JsonNode rows = getJson("/api/invoices?q=INV-REC-1", admin);
        assertEquals(1, rows.size());
        assertEquals(120050, rows.get(0).get("amount").asLong());

        // accountants can fix a row but only an admin can delete one
        mvc.perform(delete("/api/invoices/INV-REC-1").header("Authorization", accountant)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/invoices/INV-REC-1").header("Authorization", admin)).andExpect(status().isOk());
        assertEquals(0, getJson("/api/invoices?q=INV-REC-1", admin).size());
        mvc.perform(delete("/api/invoices/INV-REC-1").header("Authorization", admin)).andExpect(status().isNotFound());
    }

    @Test
    void paymentCanBeRecordedEditedAndDeleted() throws Exception {
        String admin = login("admin"), accountant = login("accountant"), viewer = login("viewer");
        mvc.perform(post("/api/transactions").header("Authorization", accountant).contentType("application/json")
                .content(txnJson("TX-REC-1", "500.00"))).andExpect(status().isCreated());
        mvc.perform(post("/api/transactions").header("Authorization", accountant).contentType("application/json")
                .content(txnJson("TX-REC-1", "500.00"))).andExpect(status().isConflict());
        mvc.perform(post("/api/transactions").header("Authorization", viewer).contentType("application/json")
                .content(txnJson("TX-REC-9", "1.00"))).andExpect(status().isForbidden());
        assertEquals(50000, getJson("/api/transactions?q=TX-REC-1", viewer).get(0).get("amount").asLong());

        mvc.perform(put("/api/transactions/TX-REC-1").header("Authorization", accountant).contentType("application/json")
                .content(txnJson("TX-REC-1", "750.00"))).andExpect(status().isOk());
        assertEquals(75000, getJson("/api/transactions?q=TX-REC-1", viewer).get(0).get("amount").asLong());

        mvc.perform(delete("/api/transactions/TX-REC-1").header("Authorization", accountant)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/transactions/TX-REC-1").header("Authorization", admin)).andExpect(status().isOk());
        assertEquals(0, getJson("/api/transactions?q=TX-REC-1", admin).size());
    }

    @Test
    void rowsUsedByADecisionAreLockedUntilTheDecisionIsUndone() throws Exception {
        String admin = login("admin");
        mvc.perform(post("/api/invoices").header("Authorization", admin).contentType("application/json")
                .content(invoiceJson("INV-REC-L", "1000.00"))).andExpect(status().isCreated());
        mvc.perform(post("/api/transactions").header("Authorization", admin).contentType("application/json")
                .content(txnJson("TX-REC-L", "1000.00"))).andExpect(status().isCreated());
        mvc.perform(post("/api/exceptions/TX-REC-L/assign").header("Authorization", admin).contentType("application/json")
                .content("{\"parts\":[{\"invoiceId\":\"INV-REC-L\",\"amount\":\"1000.00\"}]}")).andExpect(status().isOk());

        mvc.perform(put("/api/invoices/INV-REC-L").header("Authorization", admin).contentType("application/json")
                .content(invoiceJson("INV-REC-L", "900.00"))).andExpect(status().isConflict());
        mvc.perform(delete("/api/invoices/INV-REC-L").header("Authorization", admin)).andExpect(status().isConflict());
        mvc.perform(delete("/api/transactions/TX-REC-L").header("Authorization", admin)).andExpect(status().isConflict());

        mvc.perform(post("/api/decisions/TX-REC-L/undo").header("Authorization", admin)).andExpect(status().isOk());
        settle(admin);
        mvc.perform(put("/api/invoices/INV-REC-L").header("Authorization", admin).contentType("application/json")
                .content(invoiceJson("INV-REC-L", "900.00"))).andExpect(status().isOk());
        mvc.perform(delete("/api/transactions/TX-REC-L").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(delete("/api/invoices/INV-REC-L").header("Authorization", admin)).andExpect(status().isOk());
        settle(admin);
    }

    @Test
    void adminManagesUsers() throws Exception {
        String admin = login("admin"), viewer = login("viewer");
        String pw = "a-long-password-1";
        mvc.perform(get("/api/users").header("Authorization", viewer)).andExpect(status().isForbidden());

        mvc.perform(post("/api/users").header("Authorization", admin).contentType("application/json")
                .content("{\"username\":\"temp.user1\",\"password\":\"" + pw + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/users").header("Authorization", admin).contentType("application/json")
                .content("{\"username\":\"temp.user1\",\"password\":\"" + pw + "\",\"role\":\"VIEWER\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/users").header("Authorization", admin).contentType("application/json")
                .content("{\"username\":\"temp.user2\",\"password\":\"short\",\"role\":\"VIEWER\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/users").header("Authorization", admin).contentType("application/json")
                .content("{\"username\":\"temp.user2\",\"password\":\"" + pw + "\",\"role\":\"BOSS\"}")).andExpect(status().isBadRequest());

        long id = -1, adminId = -1;
        for (JsonNode u : getJson("/api/users", admin)) {
            if (u.get("username").asText().equals("temp.user1")) id = u.get("id").asLong();
            if (u.get("username").asText().equals("admin")) adminId = u.get("id").asLong();
            assertNull(u.get("passwordHash"), "password hashes must never be returned");
        }
        assertTrue(id > 0);

        String own = login("temp.user1", pw);
        mvc.perform(put("/api/users/" + id + "/role").header("Authorization", admin).contentType("application/json")
                .content("{\"role\":\"ACCOUNTANT\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/users/" + id + "/password").header("Authorization", admin).contentType("application/json")
                .content("{\"password\":\"another-long-password-2\"}")).andExpect(status().isOk());
        String own2 = login("temp.user1", "another-long-password-2");

        mvc.perform(post("/api/auth/change-password").header("Authorization", own2).contentType("application/json")
                .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"third-long-password-3\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/change-password").header("Authorization", own2).contentType("application/json")
                .content("{\"currentPassword\":\"another-long-password-2\",\"newPassword\":\"third-long-password-3\"}")).andExpect(status().isOk());
        login("temp.user1", "third-long-password-3");

        // the only admin can neither delete nor demote themselves
        mvc.perform(delete("/api/users/" + adminId).header("Authorization", admin)).andExpect(status().isConflict());
        mvc.perform(put("/api/users/" + adminId + "/role").header("Authorization", admin).contentType("application/json")
                .content("{\"role\":\"VIEWER\"}")).andExpect(status().isConflict());

        mvc.perform(delete("/api/users/" + id).header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"temp.user1\",\"password\":\"third-long-password-3\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void resetNeedsAnAdminAndAnExplicitConfirmation() throws Exception {
        String admin = login("admin"), accountant = login("accountant");
        mvc.perform(post("/api/admin/reset").header("Authorization", accountant).contentType("application/json")
                .content("{\"confirm\":\"RESET\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/reset").header("Authorization", admin).contentType("application/json")
                .content("{\"confirm\":\"yes\"}")).andExpect(status().isBadRequest());
        assertTrue(getJson("/api/admin/summary", admin).has("invoices"));
    }
}
