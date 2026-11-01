import unittest
from collections import defaultdict

from generate_v2 import build, cents
from generate_v3_noisy import build_noisy


class NoisyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data, cls.summary = build_noisy(1000, 42)
        cls.clean, _ = build(1000, 42)

    def test_level_zero_is_identical_to_v2(self):
        zero, _ = build_noisy(1000, 42, level=0)
        self.assertEqual(zero['bank_transactions'], self.clean['bank_transactions'])
        self.assertEqual(zero['ground_truth'], self.clean['ground_truth'])

    def test_reproducible(self):
        again, _ = build_noisy(1000, 42)
        self.assertEqual(again['bank_transactions'], self.data['bank_transactions'])

    def test_noise_actually_applied(self):
        self.assertGreater(self.summary['fee_deducted'], 100)
        self.assertEqual(self.summary['amount_collision_pairs'], 12)
        changed = sum(a['payer_name'] != b['payer_name']
                      for a, b in zip(sorted(self.data['bank_transactions'], key=lambda t: t['transaction_id']),
                                      sorted(self.clean['bank_transactions'], key=lambda t: t['transaction_id'])))
        self.assertGreater(changed, 50)

    def test_row_counts_unchanged(self):
        self.assertEqual(len(self.data['bank_transactions']), len(self.clean['bank_transactions']))
        self.assertEqual(len(self.data['ground_truth']), len(self.clean['ground_truth']))

    def test_fee_deducted_is_a_small_shortfall(self):
        inv = {i['invoice_id']: cents(i['amount']) for i in self.data['invoices']}
        tx = {t['transaction_id']: cents(t['amount']) for t in self.data['bank_transactions']}
        seen = 0
        for g in self.data['ground_truth']:
            if g['scenario'] == 'fee_deducted':
                seen += 1
                gap = inv[g['invoice_id']] - tx[g['transaction_id']]
                self.assertTrue(1000 <= gap <= 5000, gap)
                self.assertEqual(cents(g['allocated_amount']), tx[g['transaction_id']])
        self.assertGreater(seen, 0)

    def test_truth_never_exceeds_the_payment_or_the_invoice(self):
        inv = {i['invoice_id']: cents(i['amount']) for i in self.data['invoices']}
        tx = {t['transaction_id']: abs(cents(t['amount'])) for t in self.data['bank_transactions']}
        per_txn, per_inv = defaultdict(int), defaultdict(int)
        for g in self.data['ground_truth']:
            if not g['invoice_id']:
                continue
            a = cents(g['allocated_amount'])
            per_inv[g['invoice_id']] += a
            if a > 0:
                per_txn[g['transaction_id']] += a
        for tid, total in per_txn.items():
            self.assertLessEqual(total, tx[tid], tid)
        for iid, total in per_inv.items():
            self.assertTrue(0 <= total <= inv[iid], iid)

    def test_collisions_are_between_different_customers_with_equal_amounts(self):
        by_amount = defaultdict(set)
        for i in self.data['invoices']:
            by_amount[i['amount']].add(i['customer_id'])
        clean_by_amount = defaultdict(set)
        for i in self.clean['invoices']:
            clean_by_amount[i['amount']].add(i['customer_id'])
        multi_now = sum(len(c) > 1 for c in by_amount.values())
        multi_before = sum(len(c) > 1 for c in clean_by_amount.values())
        self.assertGreaterEqual(multi_now - multi_before, 10)

    def test_dropped_references_are_flagged_in_truth(self):
        tx = {t['transaction_id']: t for t in self.data['bank_transactions']}
        for g in self.data['ground_truth']:
            if g['invoice_id'] and g['invoice_id'] not in tx[g['transaction_id']]['narration']:
                self.assertEqual(str(g['missing_invoice_reference']), '1')

    def test_reversals_keep_their_dates_after_the_original(self):
        tx = {t['transaction_id']: t for t in self.data['bank_transactions']}
        for g in self.data['ground_truth']:
            if g['original_transaction_id']:
                self.assertGreaterEqual(tx[g['transaction_id']]['transaction_date'],
                                        tx[g['original_transaction_id']]['transaction_date'])

    def test_final_status_of_fee_invoices_is_paid_with_adjustment(self):
        exp = self.summary['expected_final_invoices']
        for g in self.data['ground_truth']:
            if g['scenario'] == 'fee_deducted':
                self.assertEqual(exp[g['invoice_id']]['status'], 'PAID')
                self.assertIn('adjustment', exp[g['invoice_id']])


if __name__ == '__main__':
    unittest.main()
