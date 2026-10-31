#!/usr/bin/env python3
"""Dataset v2: the base generator plus the scenarios the base kit lacks.

Adds (all fictional, reproducible, using integer paise):
  short_pay      customer pays 0.5-2% less than the invoice (bank fee / TDS)
  overpay        customer pays 0.5-2% more than the invoice (creates a credit)
  cheque_return  a cheque payment that later bounces (invoice must reopen)
  phone VPAs     payers shown as <phone>@bank, resolvable via customers.csv
  customers.csv  master data: name, phone, account_last4 (for ~60% of customers)
"""
import argparse
import csv
import json
import random
from collections import Counter
from datetime import date, timedelta
from pathlib import Path

from generate_dataset import generate, money, HEADERS

CHANNELS = ['QR', 'UPI_ID', 'NEFT', 'IMPS', 'CHEQUE']
BANKS = ['okhdfc', 'oksbi', 'okaxis', 'ybl']
HEADERS = dict(HEADERS)
HEADERS['customers'] = 'customer_id name phone account_last4'


def split_of(c):
    return 'test' if c % 10 >= 8 else 'validation' if c % 10 >= 6 else 'train'


def cents(text):
    return int(text.replace('.', ''))


def build(count=1000, seed=42, short_n=30, over_n=20, return_n=20, **kw):
    data, summary = generate(count, seed, **kw)
    r = random.Random(seed + 7)
    invoices = {x['invoice_id']: x for x in data['invoices']}
    paid = {x['invoice_id'] for x in data['ground_truth'] if x['invoice_id']}
    unpaid = sorted(i for i in invoices if i not in paid)
    r.shuffle(unpaid)
    if short_n + over_n + return_n > len(unpaid):
        raise ValueError('Not enough unpaid invoices for the requested v2 scenarios.')

    customers = {}
    for inv in data['invoices']:
        cid = inv['customer_id']
        c = int(cid.split('-')[1]) - 1
        if cid not in customers:
            customers[cid] = dict(customer_id=cid, name=inv['customer_name'],
                                  phone=f'9{r.randrange(10**8, 10**9):09d}',
                                  account_last4=f'{c + 1000:04d}' if r.random() < .6 else '', _c=c)

    used = {x['transaction_id'] for x in data['bank_transactions']}
    expected = summary['expected_final_invoices']
    n = 0
    plan = [('short_pay', short_n), ('overpay', over_n), ('cheque_return', return_n)]
    cursor = 0
    for kind, how_many in plan:
        for iid in unpaid[cursor:cursor + how_many]:
            inv, cust = invoices[iid], customers[invoices[iid]['customer_id']]
            a = cents(inv['amount'])
            pct = r.randrange(50, 201)  # 0.50% .. 2.00% in basis points
            paid_amount = a - a * pct // 10000 if kind == 'short_pay' else a + a * pct // 10000 if kind == 'overpay' else a
            mode = 'CHEQUE' if kind == 'cheque_return' else r.choice(CHANNELS)
            roll = r.random()
            alias = roll < .75
            payer = (f"{cust['phone']}@{r.choice(BANKS)}" if roll < .5
                     else inv['customer_name'].upper().replace('TRADERS', 'TRD').replace('PVT LTD', 'P LTD') if roll < .75
                     else inv['customer_name'])
            n += 1
            tid, ref = f'TXN-V2{n:04d}', f'UTR{r.randrange(10**11, 10**12)}'
            assert tid not in used
            used.add(tid)
            dt = date.fromisoformat(inv['invoice_date']) + timedelta(days=r.randrange(5, 21))
            narration = f'{mode} PAYMENT' + (f' {iid}' if r.random() < .35 else '')
            split = split_of(cust['_c'])
            data['bank_transactions'].append(dict(
                transaction_id=tid, transaction_date=dt.isoformat(), amount=money(paid_amount), currency='INR',
                direction='CR', payer_name=payer, channel=mode, bank_reference=ref, reversal_reference='', narration=narration))
            truth_amount = min(paid_amount, a)
            data['ground_truth'].append(dict(
                transaction_id=tid, invoice_id=iid, customer_id=inv['customer_id'], allocated_amount=money(truth_amount),
                scenario=kind, event_type='PAYMENT', original_transaction_id='', payer_is_alias=int(alias),
                missing_invoice_reference=int(iid not in narration), evaluation_split=split))
            state = dict(status='PAID', net_paid=money(truth_amount), remaining='0.00')
            if kind == 'overpay':
                state['credit'] = money(paid_amount - a)
            if kind == 'short_pay':
                state['adjustment'] = money(a - paid_amount)
            if kind == 'cheque_return':
                n += 1
                rid = f'TXN-V2{n:04d}'
                has_ref = r.random() < .5
                data['bank_transactions'].append(dict(
                    transaction_id=rid, transaction_date=(dt + timedelta(days=r.randrange(3, 7))).isoformat(),
                    amount=money(-a), currency='INR', direction='DR', payer_name=payer, channel='CHEQUE',
                    bank_reference=f'UTR{r.randrange(10**11, 10**12)}', reversal_reference=ref if has_ref else '',
                    narration='CHEQUE CHQ RET RETURN' + (f' {ref}' if has_ref else '')))
                data['ground_truth'].append(dict(
                    transaction_id=rid, invoice_id=iid, customer_id=inv['customer_id'], allocated_amount=money(-a),
                    scenario=kind, event_type='CHEQUE_RETURN', original_transaction_id=tid, payer_is_alias=int(alias),
                    missing_invoice_reference=1, evaluation_split=split))
                state = dict(status='OPEN', net_paid='0.00', remaining=inv['amount'])
            expected[iid] = state
        cursor += how_many
    r.shuffle(data['bank_transactions'])
    data['customers'] = [{k: v for k, v in c.items() if not k.startswith('_')} for c in customers.values()]
    summary.update(version=2, transaction_count=len(data['bank_transactions']),
                   allocation_count=sum(bool(x['invoice_id']) for x in data['ground_truth']),
                   v2_scenarios=dict(short_pay=short_n, overpay=over_n, cheque_return=return_n),
                   transaction_channels=dict(Counter(x['channel'] for x in data['bank_transactions'])))
    return data, summary


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--invoices', type=int, default=1000)
    p.add_argument('--seed', type=int, default=42)
    p.add_argument('--short', type=int, default=30)
    p.add_argument('--over', type=int, default=20)
    p.add_argument('--returns', type=int, default=20)
    p.add_argument('--output', type=Path, default=Path('sample'))
    a = p.parse_args()
    data, summary = build(a.invoices, a.seed, a.short, a.over, a.returns)
    a.output.mkdir(parents=True, exist_ok=True)
    for name, rows in data.items():
        with (a.output / f'{name}.csv').open('w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=HEADERS[name].split())
            w.writeheader()
            w.writerows(rows)
    (a.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print(f"Created {summary['invoice_count']} invoices, {summary['transaction_count']} transactions, "
          f"{len(data['customers'])} customers in {a.output}.")


if __name__ == '__main__':
    main()
