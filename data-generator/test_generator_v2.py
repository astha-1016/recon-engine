import unittest
from collections import defaultdict

from generate_v2 import build, cents


class V2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data, cls.summary = build(1000, 42)

    def test_reproducible(self):
        again, _ = build(1000, 42)
        self.assertEqual(self.data['bank_transactions'], again['bank_transactions'])

    def test_unique_transaction_ids(self):
        ids = [t['transaction_id'] for t in self.data['bank_transactions']]
        self.assertEqual(len(ids), len(set(ids)))

    def test_scenario_counts(self):
        counts = defaultdict(set)
        for t in self.data['ground_truth']:
            counts[t['scenario']].add(t['transaction_id'])
        self.assertEqual(len(counts['short_pay']), 30)
        self.assertEqual(len(counts['overpay']), 20)
        self.assertEqual(len(counts['cheque_return']), 40)  # payment + return

    def test_short_pay_within_two_percent(self):
        inv = {i['invoice_id']: cents(i['amount']) for i in self.data['invoices']}
        tx = {t['transaction_id']: cents(t['amount']) for t in self.data['bank_transactions']}
        for t in self.data['ground_truth']:
            if t['scenario'] == 'short_pay':
                gap = inv[t['invoice_id']] - tx[t['transaction_id']]
                self.assertTrue(0 < gap <= inv[t['invoice_id']] * 0.0201)

    def test_overpay_creates_credit(self):
        seen = 0
        for s in self.summary['expected_final_invoices'].values():
            if 'credit' in s:
                seen += 1
                self.assertGreater(cents(s['credit']), 0)
                self.assertEqual(s['status'], 'PAID')
        self.assertEqual(seen, 20)

    def test_cheque_return_reopens_invoice(self):
        for t in self.data['ground_truth']:
            if t['event_type'] == 'CHEQUE_RETURN':
                self.assertEqual(self.summary['expected_final_invoices'][t['invoice_id']]['status'], 'OPEN')

    def test_customers_master(self):
        self.assertEqual(len(self.data['customers']), 200)
        self.assertTrue(all(len(c['phone']) == 10 for c in self.data['customers']))

    def test_net_allocations_never_exceed_invoice(self):
        inv = {i['invoice_id']: cents(i['amount']) for i in self.data['invoices']}
        net = defaultdict(int)
        for t in self.data['ground_truth']:
            if t['invoice_id']:
                net[t['invoice_id']] += cents(t['allocated_amount'])
        for iid, total in net.items():
            self.assertLessEqual(total, inv[iid])
            self.assertGreaterEqual(total, 0)


if __name__ == '__main__':
    unittest.main()
