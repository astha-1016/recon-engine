package com.reconcile.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end through HTTP with an in-memory H2 database and the ML service disabled (rule scorer). */
@SpringBootTest
@AutoConfigureMockMvc
class ApiSmokeTest {
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

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCannotUpload() throws Exception {
        mvc.perform(multipart("/api/upload/invoices").file(csv("x.csv", "a\n1\n")).header("Authorization", login("viewer")))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadReconcileAndReview() throws Exception {
        String token = login("accountant");
        String invoices = "invoice_id,customer_id,customer_name,amount,invoice_date,due_date\n"
                + "INV-2026-00001,C1,Acme Traders Pvt Ltd,1000.00,2026-07-01,2026-07-31\n";
        String txns = "transaction_id,transaction_date,amount,direction,payer_name,channel,bank_reference,reversal_reference,narration\n"
                + "T1,2026-07-10,1000.00,CR,Acme Traders Pvt Ltd,NEFT,UTR1,,NEFT PAYMENT INV-2026-00001\n";
        mvc.perform(multipart("/api/upload/invoices").file(csv("i.csv", invoices)).header("Authorization", token)).andExpect(status().isOk());
        // uploading the same file again must be harmless (idempotent)
        String again = mvc.perform(multipart("/api/upload/invoices").file(csv("i.csv", invoices)).header("Authorization", token))
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, mapper.readTree(again).get("duplicates").asInt());
        mvc.perform(multipart("/api/upload/transactions").file(csv("t.csv", txns)).header("Authorization", token)).andExpect(status().isOk());
        String run = mvc.perform(post("/api/reconcile/run").header("Authorization", token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, mapper.readTree(run).get("auto").asInt());
        String dash = mvc.perform(get("/api/dashboard").header("Authorization", token)).andReturn().getResponse().getContentAsString();
        assertEquals(0, mapper.readTree(dash).get("pendingReview").asInt());
    }
}
