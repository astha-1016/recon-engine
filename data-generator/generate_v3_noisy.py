#!/usr/bin/env python3
"""Dataset v3: the v2 dataset plus realistic noise, with ground truth kept correct.

Why: the clean data has amounts exact to the paisa, payer names without typos and fixed payment lags, so
97%+ recall on it says little about real bank statements. This layer makes the problem harder in ways a
real statement is hard, and every change updates the ground truth so evaluation stays valid.

Noise (all scaled by --level; level 0 returns the v2 data unchanged):
  name typos        ~25% of plain-name payers get a dropped / swapped / doubled letter
  missing invoice   ~50% of narrations that quote an invoice number lose it
  payment-lag jitter ~50% of single-payment rows move 1-6 days later (instalments and refunds are left alone)
  fee deducted      ~30% of clean single-invoice payments arrive short by a flat 10-50 rupee bank charge
                    (scenario renamed to 'fee_deducted'; the invoice must still close as PAID with an adjustment)
  amount collisions 12 invoice pairs from different customers are given the SAME amount, so amount alone
                    can no longer identify the payer
  duplicate rows    ~15 credits are listed twice (same bank reference and amount, new row id). They must NOT be
                    reconciled, or the same money would settle two invoices (scenario 'duplicate_row')
"""
import argparse
import csv
import json
import random
import re
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

from generate_dataset import money
from generate_v2 import HEADERS, build, cents

INVOICE_REF = re.compile(r'\s*INV-\d{4}-\d+')
JITTER_SCENARIOS = {'full_payment', 'multi_invoice_payment', 'short_pay', 'overpay'}


def typo(name, r):
    letters = [i for i, ch in enumerate(name) if ch.isalpha()]
    if len(name) < 5 or len(letters) < 3:
        return name
    i = r.choice(letters[1:-1]) if len(letters) > 2 else letters[0]
    kind = r.choice(['drop', 'swap', 'double'])
    if kind == 'drop':
        return name[:i] + name[i + 1:]
    if kind == 'double':
        return name[:i] + name[i] + name[i:]
    return name[:i] + name[i + 1] + name[i] + name[i + 2:] if i + 1 < len(name) else name


def is_plain_name(payer):
    return '@' not in payer and not payer.upper().startswith('X') and not any(ch.isdigit() for ch in payer)


def build_noisy(count=1000, seed=42, short_n=30, over_n=20, return_n=20, level=1.0, **kw):
    data, summary = build(count, seed, short_n, over_n, return_n, **kw)
    if level <= 0:
        return data, summary
    r = random.Random(seed + 13)
    invoices = {i['invoice_id']: i for i in data['invoices']}
    txns = {t['transaction_id']: t for t in data['bank_transactions']}
    expected = summary['expected_final_invoices']

    truth_by_txn = defaultdict(list)
    for g in data['ground_truth']:
        if g['invoice_id']:
            truth_by_txn[g['transaction_id']].append(g)
    linked = set()  # payments that a refund / reversal / cheque return points at, and the reversals themselves
    for g in data['ground_truth']:
        if g['event_type'] != 'PAYMENT':
            linked.add(g['transaction_id'])
            linked.add(g['original_transaction_id'])

    def single_full_payment(tid):
        rows = truth_by_txn.get(tid, [])
        if len(rows) != 1 or tid in linked or txns[tid]['direction'] != 'CR':
            return None
        g = rows[0]
        if g['scenario'] != 'full_payment' or cents(g['allocated_amount']) != cents(invoices[g['invoice_id']]['amount']):
            return None
        return g

    # 1) amount collisions: same amount, different customers, both paid in full
    candidates = sorted(t for t in txns if single_full_payment(t))
    r.shuffle(candidates)
    touched, pairs = set(), 0
    wanted = int(round(12 * level))
    i = 0
    while pairs < wanted and i + 1 < len(candidates):
        a_tid, b_tid = candidates[i], candidates[i + 1]
        i += 2
        ga, gb = single_full_payment(a_tid), single_full_payment(b_tid)
        if ga['customer_id'] == gb['customer_id']:
            continue
        amount = invoices[ga['invoice_id']]['amount']
        invoices[gb['invoice_id']]['amount'] = amount
        txns[b_tid]['amount'] = amount
        gb['allocated_amount'] = amount
        expected[gb['invoice_id']]['net_paid'] = amount
        touched.update([a_tid, b_tid])
        pairs += 1

    # 2) bank charge deducted from otherwise clean full payments
    for tid in sorted(candidates):
        g = single_full_payment(tid)
        if g is None or tid in touched or r.random() >= 0.30 * level:
            continue
        fee = r.randrange(1000, 5001)  # Rs 10.00 .. Rs 50.00
        new_amount = cents(txns[tid]['amount']) - fee
        txns[tid]['amount'] = money(new_amount)
        g['allocated_amount'] = money(new_amount)
        g['scenario'] = 'fee_deducted'
        st = expected[g['invoice_id']]
        st.update(status='PAID', net_paid=money(new_amount), remaining='0.00', adjustment=money(fee))

    # 3) typos, dropped invoice numbers, payment-lag jitter
    scenario_of = {tid: rows[0]['scenario'] for tid, rows in truth_by_txn.items()}
    for tid in sorted(txns):
        t = txns[tid]
        if t['direction'] != 'CR' or t['channel'] == 'BANK':
            continue
        if is_plain_name(t['payer_name']) and r.random() < 0.25 * level:
            t['payer_name'] = typo(t['payer_name'], r)
        if INVOICE_REF.search(t['narration']) and r.random() < 0.50 * level:
            t['narration'] = INVOICE_REF.sub('', t['narration']).strip()
            for g in truth_by_txn.get(tid, []):
                g['missing_invoice_reference'] = 1
        if tid not in linked and scenario_of.get(tid) in (JITTER_SCENARIOS | {'fee_deducted'}) and r.random() < 0.50 * level:
            t['transaction_date'] = (date.fromisoformat(t['transaction_date']) + timedelta(days=r.randrange(1, 7))).isoformat()

    # 4) duplicate bank rows: the same credit imported twice
    dup_candidates = sorted(t for t in truth_by_txn if t not in linked and txns[t]['direction'] == 'CR')
    r.shuffle(dup_candidates)
    dups = 0
    for k, tid in enumerate(dup_candidates[:int(round(15 * level))], start=1):
        copy = dict(txns[tid])
        copy['transaction_id'] = f'TXN-DUP{k:04d}'
        data['bank_transactions'].append(copy)
        data['ground_truth'].append(dict(
            transaction_id=copy['transaction_id'], invoice_id='', customer_id='', allocated_amount='0.00',
            scenario='duplicate_row', event_type='UNMATCHED', original_transaction_id=tid, payer_is_alias=0,
            missing_invoice_reference=1, evaluation_split=truth_by_txn[tid][0]['evaluation_split']))
        dups += 1
    if dups:
        r.shuffle(data['bank_transactions'])

    summary.update(version=3, noise_level=level, amount_collision_pairs=pairs, duplicate_rows=dups,
                   transaction_count=len(data['bank_transactions']),
                   fee_deducted=sum(1 for g in data['ground_truth'] if g['scenario'] == 'fee_deducted'))
    return data, summary


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--invoices', type=int, default=1000)
    p.add_argument('--seed', type=int, default=42)
    p.add_argument('--short', type=int, default=30)
    p.add_argument('--over', type=int, default=20)
    p.add_argument('--returns', type=int, default=20)
    p.add_argument('--level', type=float, default=1.0, help='0 = clean (same as v2), 1 = default noise, 2 = harsher')
    p.add_argument('--output', type=Path, default=Path('sample_noisy'))
    a = p.parse_args()
    data, summary = build_noisy(a.invoices, a.seed, a.short, a.over, a.returns, a.level)
    a.output.mkdir(parents=True, exist_ok=True)
    for name, rows in data.items():
        with (a.output / f'{name}.csv').open('w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=HEADERS[name].split())
            w.writeheader()
            w.writerows(rows)
    (a.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print(f"Created {summary['invoice_count']} invoices, {summary['transaction_count']} transactions in {a.output} "
          f"(noise level {a.level}: {summary.get('fee_deducted', 0)} fee-deducted payments, "
          f"{summary.get('amount_collision_pairs', 0)} amount-collision pairs, {summary.get('duplicate_rows', 0)} duplicate rows).")


if __name__ == '__main__':
    main()
