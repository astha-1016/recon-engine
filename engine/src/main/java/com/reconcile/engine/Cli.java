package com.reconcile.engine;

import com.reconcile.engine.Model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Command line for evaluating the engine on a dataset directory without any web stack:
 *   eval     --data DIR [--mode exact|rules|ml] [--ml URL] [--split test]
 *   compare  --data DIR --ml URL [--split test]        exact-only vs rules vs ML
 *   export   --data DIR --out features.csv             labelled candidate rows for ML training
 *   curve    --data DIR [--ml URL] [--split test]      review-queue size as a person makes corrections
 *   report   --data DIR --ml URL --out evaluation.json [--data2 DIR2]   everything above as JSON for the dashboard
 */
public final class Cli {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { System.err.println("commands: eval | compare | export | curve"); System.exit(2); }
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        DataSet ds = DataSet.load(Path.of(opt.getOrDefault("data", "data-generator/sample")));
        String split = opt.getOrDefault("split", "test");
        if (split.equals("all")) split = null;
        switch (args[0]) {
            case "eval" -> eval(ds, opt, split);
            case "compare" -> compare(ds, opt, split);
            case "export" -> export(ds, Path.of(opt.getOrDefault("out", "features.csv")));
            case "curve" -> curve(ds, opt, split);
            case "report" -> report(ds, opt, split);
            default -> { System.err.println("unknown command " + args[0]); System.exit(2); }
        }
    }

    private static Reconciler.Result run(DataSet ds, String mode, String ml, List<ManualDecision> manual) {
        Config cfg = mode.equals("exact") ? Config.exactOnly() : new Config();
        Scorer scorer = mode.equals("ml") ? new HttpScorer(ml) : new RuleScorer();
        return new Reconciler(cfg, scorer).run(ds.input(List.of(), manual));
    }

    private static void eval(DataSet ds, Map<String, String> opt, String split) {
        String mode = opt.getOrDefault("mode", "rules");
        long t0 = System.currentTimeMillis();
        Reconciler.Result res = run(ds, mode, opt.get("ml"), List.of());
        System.out.printf("(%d transactions reconciled in %d ms, scorer %s)%n", ds.txns.size(), System.currentTimeMillis() - t0, res.scorerVersion);
        System.out.print(Evaluator.render(Evaluator.evaluate(ds, res, split, mode)));
    }

    private static void compare(DataSet ds, Map<String, String> opt, String split) {
        String[][] arms = {{"exact", "1. Exact only (reference + amount)"}, {"rules", "2. + fuzzy + composite, rule scorer"}, {"ml", "3. Full pipeline, ML scorer"}};
        System.out.printf("%-38s %9s %9s %9s %11s %12s%n", "split=" + (split == null ? "all" : split), "precision", "recall", "auto-rate", "manual left", "status acc.");
        for (String[] a : arms) {
            if (a[0].equals("ml") && opt.get("ml") == null) continue;
            Evaluator.Report r = Evaluator.evaluate(ds, run(ds, a[0], opt.get("ml"), List.of()), split, a[1]);
            System.out.printf("%-38s %9s %9s %9s %11s %12s%n", a[1], Evaluator.pct(r.overall.precision()), Evaluator.pct(r.overall.recall()),
                    Evaluator.pct(r.autoRate()), Evaluator.pct(1 - r.effortReduction()), Evaluator.pct(r.statusAccuracy()));
        }
    }

    /** Writes every candidate the fuzzy tier considered, labelled from ground truth, for training. Truth is used for labels only. */
    private static void export(DataSet ds, Path out) throws Exception {
        Map<String, Map<String, Long>> truth = new HashMap<>();
        for (DataSet.Truth t : ds.truth) if (!t.invoiceId().isEmpty()) truth.computeIfAbsent(t.txnId(), k -> new HashMap<>()).put(t.invoiceId(), t.amount());
        Map<String, String> split = ds.txnSplit();
        List<String> header = new ArrayList<>(List.of("txn_id", "split", "label"));
        header.addAll(Features.NAMES);
        List<List<String>> rows = new ArrayList<>();
        int[] positives = {0};
        Reconciler.CandidateSink sink = (t, cands) -> {
            Map<String, Long> want = truth.get(t.id());
            for (Candidate c : cands) {
                boolean label = want != null && want.size() == c.parts.size()
                        && c.parts.stream().allMatch(p -> Objects.equals(want.get(p.invoiceId()), p.amount()));
                if (label) positives[0]++;
                List<String> row = new ArrayList<>(List.of(t.id(), split.getOrDefault(t.id(), "unknown"), label ? "1" : "0"));
                for (double v : c.features) row.add(v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v));
                rows.add(row);
            }
        };
        new Reconciler(new Config(), new RuleScorer()).withSink(sink).run(ds.input(List.of(), List.of()));
        Csv.write(out, header, rows);
        System.out.printf("Wrote %d candidate rows (%d positive) to %s%n", rows.size(), positives[0], out);
    }

    /** Oracle reviewer corrects the first N queued payments; how much work is left afterwards? */
    private static void curve(DataSet ds, Map<String, String> opt, String split) {
        List<Map<String, Object>> rows = curveRows(ds, opt, split);
        System.out.printf("scorer=%s split=%s%n", opt.get("ml") != null ? "ml" : "rules", split == null ? "all" : split);
        System.out.printf("%12s %14s %12s %18s %10s%n", "corrections", "queue after", "auto-rate", "total human touches", "precision");
        for (Map<String, Object> r : rows)
            System.out.printf("%12s %14s %12s %18s %10s%n", r.get("corrections"), r.get("queueAfter"), Evaluator.pct((Double) r.get("autoRate")),
                    r.get("humanTouches"), Evaluator.pct((Double) r.get("precision")));
    }

    private static List<Map<String, Object>> curveRows(DataSet ds, Map<String, String> opt, String split) {
        String mode = opt.get("ml") != null && !"rules".equals(opt.get("curve-scorer")) ? "ml" : "rules";
        Map<String, List<Part>> truthParts = new HashMap<>();
        for (DataSet.Truth t : ds.truth)
            if (!t.invoiceId().isEmpty() && t.amount() > 0 && (split == null || split.equals(t.split())))
                truthParts.computeIfAbsent(t.txnId(), k -> new ArrayList<>()).add(new Part(t.invoiceId(), t.amount()));
        Reconciler.Result base = run(ds, mode, opt.get("ml"), List.of());
        List<String> queue = new ArrayList<>();
        ds.txns.stream().sorted(Comparator.comparing(BankTxn::date).thenComparing(BankTxn::id)).forEach(t -> {
            Outcome o = base.outcomes.get(t.id());
            if (truthParts.containsKey(t.id()) && o.decision != Decision.AUTO) queue.add(t.id());
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (int n : new int[]{0, 10, 25, 50, 100, 200, 400}) {
            if (n > queue.size()) break;
            List<ManualDecision> manual = new ArrayList<>();
            for (String id : queue.subList(0, n)) manual.add(new ManualDecision(id, truthParts.get(id)));
            Evaluator.Report rep = Evaluator.evaluate(ds, run(ds, mode, opt.get("ml"), manual), split, "curve");
            int left = rep.reviewTxns + rep.unmatchedTxns;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("corrections", n); row.put("queueAfter", left); row.put("autoRate", rep.autoRate());
            row.put("humanTouches", n + left); row.put("precision", rep.overall.precision());
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> reportMap(Evaluator.Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", r.label); m.put("split", r.split);
        m.put("precision", r.overall.precision()); m.put("recall", r.overall.recall());
        m.put("correct", r.overall.correct); m.put("predicted", r.overall.predicted); m.put("expected", r.overall.expected);
        m.put("autoRate", r.autoRate()); m.put("manualLeft", 1 - r.effortReduction());
        m.put("reviewTxns", r.reviewTxns); m.put("unmatchedTxns", r.unmatchedTxns); m.put("invoiceTxns", r.invoiceTxns);
        m.put("statusAccuracy", r.statusAccuracy());
        return m;
    }

    private static List<Map<String, Object>> breakdown(Map<String, Evaluator.Counts> src, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        src.forEach((k, c) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(key, k); m.put("expected", c.expected); m.put("predicted", c.predicted);
            m.put("precision", c.precision()); m.put("recall", c.recall());
            out.add(m);
        });
        return out;
    }

    private static void report(DataSet ds, Map<String, String> opt, String split) throws Exception {
        String ml = opt.get("ml");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", java.time.Instant.now().toString());
        out.put("split", split == null ? "all" : split);
        out.put("transactions", ds.txns.size());
        out.put("invoices", ds.invoices.size());

        List<Map<String, Object>> comparison = new ArrayList<>();
        Evaluator.Report mlReport = null;
        String[][] arms = {{"exact", "Exact only (reference + amount)"}, {"rules", "+ fuzzy + composite, rule scorer"}, {"ml", "Full pipeline, ML scorer"}};
        for (String[] a : arms) {
            if (a[0].equals("ml") && ml == null) continue;
            Evaluator.Report r = Evaluator.evaluate(ds, run(ds, a[0], ml, List.of()), split, a[1]);
            comparison.add(reportMap(r));
            if (a[0].equals("ml")) mlReport = r;
        }
        out.put("comparison", comparison);
        Evaluator.Report detail = mlReport != null ? mlReport : Evaluator.evaluate(ds, run(ds, "rules", null, List.of()), split, "rules");
        out.put("scenarios", breakdown(detail.byScenario, "scenario"));
        out.put("channels", breakdown(detail.byChannel, "channel"));

        if (opt.get("data2") != null && ml != null) {
            DataSet other = DataSet.load(Path.of(opt.get("data2")));
            out.put("unseenDataset", reportMap(Evaluator.evaluate(other, run(other, "ml", ml, List.of()), null, "Unseen seed, all customers")));
        }
        Map<String, String> rulesOnly = new HashMap<>(opt);
        rulesOnly.put("curve-scorer", "rules");
        out.put("correctionsCurve", curveRows(ds, rulesOnly, split));
        Files.writeString(Path.of(opt.getOrDefault("out", "evaluation.json")), Json.write(out));
        System.out.println("Wrote " + opt.getOrDefault("out", "evaluation.json"));
    }
}
