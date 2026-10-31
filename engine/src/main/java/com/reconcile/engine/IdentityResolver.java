package com.reconcile.engine;

import com.reconcile.engine.Model.Alias;
import com.reconcile.engine.Model.Customer;
import com.reconcile.engine.Model.PayerKind;

import java.util.*;

/**
 * Decides which customer a payer string belongs to, in layers:
 * 1. learned alias (exact), 2. master data (phone in a UPI handle, last-4 of a masked account),
 * 3. fuzzy business-name similarity. Opaque handles with no evidence resolve to nobody.
 */
public final class IdentityResolver {

    public static final class Identity {
        public final PayerKind kind;
        public final String payerNorm;
        /** Customers considered plausible, with a 0..1 identity score. */
        public final Map<String, Double> scores = new LinkedHashMap<>();
        public final Set<String> aliasHits = new HashSet<>();
        public final Set<String> phoneHits = new HashSet<>();
        public final Set<String> last4Hits = new HashSet<>();

        Identity(PayerKind kind, String payerNorm) {
            this.kind = kind;
            this.payerNorm = payerNorm;
        }
    }

    private final double threshold;
    private final Map<String, String> customerNameNorm = new HashMap<>();
    private final Map<String, List<String>> byNormName = new LinkedHashMap<>();
    private final Map<String, List<String>> byPhone = new HashMap<>();
    private final Map<String, List<String>> byLast4 = new HashMap<>();
    private final Map<String, Map<String, Integer>> aliases = new HashMap<>();
    private final Map<String, double[]> nameFeatureCache = new HashMap<>();

    public IdentityResolver(Collection<Customer> customers, Collection<Alias> learned, double threshold) {
        this.threshold = threshold;
        for (Customer c : customers) {
            String norm = TextNorm.normalizeName(c.name());
            customerNameNorm.put(c.id(), norm);
            byNormName.computeIfAbsent(norm, k -> new ArrayList<>()).add(c.id());
            if (c.phone() != null && !c.phone().isBlank()) byPhone.computeIfAbsent(c.phone().trim(), k -> new ArrayList<>()).add(c.id());
            if (c.accountLast4() != null && !c.accountLast4().isBlank()) byLast4.computeIfAbsent(c.accountLast4().trim(), k -> new ArrayList<>()).add(c.id());
        }
        for (Alias a : learned) {
            aliases.computeIfAbsent(TextNorm.aliasKey(a.payer()), k -> new HashMap<>()).merge(a.customerId(), 1, Integer::sum);
        }
    }

    public Identity resolve(String payer) {
        PayerKind kind = TextNorm.payerKind(payer);
        Identity id = new Identity(kind, TextNorm.normalizeName(payer));

        Map<String, Integer> hit = aliases.get(TextNorm.aliasKey(payer));
        if (hit != null) {
            int total = hit.values().stream().mapToInt(Integer::intValue).sum();
            hit.forEach((cid, n) -> { id.scores.put(cid, n / (double) total); id.aliasHits.add(cid); });
            return id;
        }
        switch (kind) {
            case PHONE_VPA -> {
                for (String cid : byPhone.getOrDefault(TextNorm.phone(payer), List.of())) {
                    id.scores.put(cid, 1.0);
                    id.phoneHits.add(cid);
                }
            }
            case MASK -> {
                List<String> owners = byLast4.getOrDefault(TextNorm.maskLast4(payer), List.of());
                if (owners.size() == 1) {
                    id.scores.put(owners.get(0), 0.95);
                    id.last4Hits.add(owners.get(0));
                }
            }
            case NAME -> {
                for (Map.Entry<String, List<String>> g : byNormName.entrySet()) {
                    double s = Similarity.nameSim(id.payerNorm, g.getKey());
                    if (s >= threshold) for (String cid : g.getValue()) id.scores.put(cid, s);
                }
            }
            default -> { /* opaque VPA with no learned alias: nobody */ }
        }
        return id;
    }

    /** {levenshtein ratio, jaro-winkler, token-set} between the payer name and a customer's name; zeros for opaque payers. */
    public double[] nameFeatures(Identity id, String customerId) {
        if (id.kind != PayerKind.NAME) return new double[]{0, 0, 0};
        String cn = customerNameNorm.getOrDefault(customerId, "");
        return nameFeatureCache.computeIfAbsent(id.payerNorm + "|" + cn, k -> new double[]{
                Similarity.levRatio(id.payerNorm, cn), Similarity.jaroWinkler(id.payerNorm, cn), Similarity.tokenSet(id.payerNorm, cn)});
    }
}
