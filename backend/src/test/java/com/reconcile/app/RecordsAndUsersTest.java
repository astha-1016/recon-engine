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
                .content("{\"username\":\"temp.user2\",\"password\":\"abcd\",\"role\":\"VIEWER\"}")).andExpect(status().isBadRequest());
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
    void resetNeedsAnAdminTheTypedWordAndThePassword() throws Exception {
        String admin = login("admin"), accountant = login("accountant");
        mvc.perform(post("/api/admin/reset").header("Authorization", accountant).contentType("application/json")
                .content("{\"confirm\":\"RESET\",\"password\":\"test-password\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/reset").header("Authorization", admin).contentType("application/json")
                .content("{\"confirm\":\"yes\",\"password\":\"test-password\"}")).andExpect(status().isBadRequest());
        // the typed word alone is not enough, and neither is the wrong password
        mvc.perform(post("/api/admin/reset").header("Authorization", admin).contentType("application/json")
                .content("{\"confirm\":\"RESET\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/reset").header("Authorization", admin).contentType("application/json")
                .content("{\"confirm\":\"RESET\",\"password\":\"not-the-password\"}")).andExpect(status().isBadRequest());
        assertTrue(getJson("/api/admin/summary", admin).has("invoices"));   // the real reset is exercised in AdminResetTest
    }

    private void createUser(String admin, String name, String pw, String role) throws Exception {
        mvc.perform(post("/api/users").header("Authorization", admin).contentType("application/json")
                .content("{\"username\":\"" + name + "\",\"password\":\"" + pw + "\",\"role\":\"" + role + "\"}")).andExpect(status().isCreated());
    }

    private long userId(String admin, String name) throws Exception {
        for (JsonNode u : getJson("/api/users", admin)) if (u.get("username").asText().equals(name)) return u.get("id").asLong();
        throw new AssertionError("no such user " + name);
    }

    @Test
    void sessionsEndWhenRoleOrPasswordChangesOrTheUserIsDeleted() throws Exception {
        String admin = login("admin");
        String pw = "a-long-password-1";
        createUser(admin, "sess.role", pw, "ADMIN");
        createUser(admin, "sess.pass", pw, "ACCOUNTANT");
        createUser(admin, "sess.del", pw, "ACCOUNTANT");
        String role = login("sess.role", pw), pass = login("sess.pass", pw), del = login("sess.del", pw);
        // all three work now
        for (String t : new String[]{role, pass, del}) mvc.perform(get("/api/dashboard").header("Authorization", t)).andExpect(status().isOk());
        assertEquals(200, mvc.perform(get("/api/users").header("Authorization", role)).andReturn().getResponse().getStatus());

        // a demoted admin loses admin powers on the very next request, not after 8 hours
        mvc.perform(put("/api/users/" + userId(admin, "sess.role") + "/role").header("Authorization", admin).contentType("application/json")
                .content("{\"role\":\"VIEWER\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/users").header("Authorization", role)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard").header("Authorization", role)).andExpect(status().isUnauthorized());
        login("sess.role", pw);   // signing in again works and carries the new role
        // an admin resetting a password kicks out whoever holds the old session
        mvc.perform(put("/api/users/" + userId(admin, "sess.pass") + "/password").header("Authorization", admin).contentType("application/json")
                .content("{\"password\":\"a-brand-new-password-2\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard").header("Authorization", pass)).andExpect(status().isUnauthorized());
        login("sess.pass", "a-brand-new-password-2");
        // deleting the user ends the session too
        mvc.perform(delete("/api/users/" + userId(admin, "sess.del")).header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard").header("Authorization", del)).andExpect(status().isUnauthorized());
        // the admin's own session is untouched by all of that
        mvc.perform(get("/api/dashboard").header("Authorization", admin)).andExpect(status().isOk());
        for (String n : new String[]{"sess.role", "sess.pass"})
            mvc.perform(delete("/api/users/" + userId(admin, n)).header("Authorization", admin)).andExpect(status().isOk());
    }

    @Test
    void changingYourOwnPasswordNeedsAMatchingConfirmationAndKeepsYouSignedIn() throws Exception {
        String admin = login("admin");
        String pw = "a-long-password-1";
        createUser(admin, "own.change", pw, "VIEWER");
        String old = login("own.change", pw);
        String body = "{\"currentPassword\":\"" + pw + "\",\"newPassword\":\"second-long-password-2\",\"confirmPassword\":\"second-long-passwurd-2\"}";
        mvc.perform(post("/api/auth/change-password").header("Authorization", old).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());                       // typo in the confirmation: nothing changes
        login("own.change", pw);
        mvc.perform(post("/api/auth/change-password").header("Authorization", old).contentType("application/json")
                .content("{\"currentPassword\":\"" + pw + "\",\"newPassword\":\"" + pw + "\",\"confirmPassword\":\"" + pw + "\"}"))
                .andExpect(status().isBadRequest());                       // same password again is refused
        String res = mvc.perform(post("/api/auth/change-password").header("Authorization", old).contentType("application/json")
                        .content(body.replace("passwurd", "password"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fresh = "Bearer " + mapper.readTree(res).get("token").asText();
        mvc.perform(get("/api/dashboard").header("Authorization", old)).andExpect(status().isUnauthorized());   // old session ended
        mvc.perform(get("/api/dashboard").header("Authorization", fresh)).andExpect(status().isOk());           // the one that changed it lives on
        mvc.perform(delete("/api/users/" + userId(admin, "own.change")).header("Authorization", admin)).andExpect(status().isOk());
    }

    @Test
    void repeatedWrongPasswordsLockThatNameButNotOthers() throws Exception {
        String admin = login("admin");
        String pw = "a-long-password-1";
        createUser(admin, "lock.me", pw, "VIEWER");
        String wrong = "{\"username\":\"lock.me\",\"password\":\"nope\"}";
        for (int i = 0; i < 5; i++)
            mvc.perform(post("/api/auth/login").contentType("application/json").content(wrong)).andExpect(status().isUnauthorized());
        // now even the right password is refused for a while
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"lock.me\",\"password\":\"" + pw + "\"}"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"LOCK.ME\",\"password\":\"" + pw + "\"}"))
                .andExpect(status().isTooManyRequests());                  // case does not dodge the lock
        login("viewer");                                                   // other accounts are unaffected
        // unknown names are throttled the same way, so the response does not reveal which names exist
        String ghost = "{\"username\":\"no.such.user\",\"password\":\"nope\"}";
        for (int i = 0; i < 5; i++)
            mvc.perform(post("/api/auth/login").contentType("application/json").content(ghost)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json").content(ghost)).andExpect(status().isTooManyRequests());
        // a lock does not stop an admin from managing the account
        mvc.perform(delete("/api/users/" + userId(admin, "lock.me")).header("Authorization", admin)).andExpect(status().isOk());
    }

    @Test
    void adminCannotChangeOwnRoleOrUseTheAdminPasswordRouteOnThemselves() throws Exception {
        String admin = login("admin");
        long adminId = userId(admin, "admin");
        mvc.perform(put("/api/users/" + adminId + "/role").header("Authorization", admin).contentType("application/json")
                .content("{\"role\":\"ADMIN\"}")).andExpect(status().isConflict());
        mvc.perform(put("/api/users/" + adminId + "/password").header("Authorization", admin).contentType("application/json")
                .content("{\"password\":\"some-other-password-3\"}")).andExpect(status().isConflict());
        login("admin");   // password unchanged
    }
}
