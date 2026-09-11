# Test scenarios for the order push rules

The rules decide which orders go to NetSuite, in what order, and how many at a time.
A business user programs them as rows: a condition (`facilityGroupId equals NETSUITE_FULFILLMENT`),
a sort (`priority`, `orderDate`), and a setting (three files, limit 300, spare capacity only).
These scenarios check that every rule a retailer would write does what it says.

## The test bed

176 real orders from production, 3 September 2026: the busiest hour (11:00 to 11:59
Pacific, 118 orders) and the evening POS sales (17:00 to 20:59, 49 orders), plus nine
orders from other hours for the rarer kinds (same-day delivery, draft orders,
replacements, expedited). They were imported through the normal Shopify order import.

Where they sit, after brokering by hand:

| Kind | Orders | Where |
|---|---|---|
| POS sales handed over at the counter (`POS_COMPLETED`) | 95 | the selling store |
| Web, CSR and POS-shipped orders brokered to the e-commerce warehouse | 23 | WH |
| The same, brokered to a second warehouse in the NetSuite group | 5 | M100049 |
| Web orders shipping from a store | 15 | a store |
| Store pickup | 4 | the pickup store |
| Held back by the picker's facts (an item with no NetSuite id, 29 new SKUs) | 35 | any |

Five web orders at WH stay `ORDER_CREATED`; every other order is `ORDER_APPROVED`.
Three orders are mixed carts: one line handed over at the counter, one line shipping from WH.

NetSuite facility group `NETSUITE_FULFILLMENT` holds WH and M100049.
Every run uses the no-op MDM config, so nothing reaches the sandbox.

## The scenarios

Each scenario names the rule rows a business user would write, then what must happen.

1. Warehouse orders first. Rule: `facilityGroupId equals NETSUITE_FULFILLMENT`,
   `orderDate greater-than 2026-09-01`, sort `priority, orderDate`, three files.
   Expect the 28 orders at WH and M100049, none from a store, spread over three files.
2. Cutover date. Same rule with `orderDate greater-than 2026-01-01`.
   Expect the 15 older WH orders to join; with `2026-09-01` they are left out.
3. Approved only. Add `statusId equals ORDER_APPROVED` to rule 1.
   Expect 24; the four `ORDER_CREATED` orders at WH stay behind.
4. POS only when there is room. Rule 2: `shipmentMethodTypeId equals POS_COMPLETED`,
   spare capacity only. With `targetMinutes` 10 and an empty queue, expect POS orders
   after the warehouse orders. With `targetMinutes` 0.2, expect the POS rule skipped
   with a message.
5. POS limit and file count. Rule 2 with limit 30 and two files.
   Expect exactly 30 POS orders, 15 in each file, the oldest first.
6. A second warehouse joins the group. Add a store to `NETSUITE_FULFILLMENT`.
   Expect that store's shipping orders picked by rule 1 with no rule change.
7. Ship from store as a third rule. Rule 3: `shipmentMethodTypeId not-equals POS_COMPLETED`,
   `shipGroupFacilityId in <three store ids>`, after the POS rule.
   Expect those stores' shipping orders, and no pickup orders.
8. One order, two rules. The three mixed carts match rule 1 (WH line) and rule 2 (counter line).
   Expect each once, in rule 1's files.
9. Channel rule. Rule 1 with `salesChannelEnumId not-equals AFTSHP_SALES_CHANNEL`.
   Expect the four AfterShip orders left out; a separate rule for that channel picks them.
10. Expedited first. Rule 0 before rule 1: `shipmentMethodTypeId in OVERNIGHT,SECOND_DAY`.
    Expect the expedited WH orders in rule 0's file, not again in rule 1's.
11. The picker's facts hold. In every scenario, no order with an unmapped item, and no order
    already in NetSuite, appears in any file.
12. Do not push while a list is running. Queue a file on the config, run the job.
    Expect the run to skip with a message and no new file.
13. Self-pacing. After a run that queues N orders, expect the job's `fromDate` moved
    ahead by N over the measured rate, with the margin.
14. Unpaid orders stop at the sender. Push one order with no payment and a total above zero
    through `sync#NetSuiteOrder`. Expect "waits for payment", no error, nothing sent.
    A zero-total replacement order passes.

## Results, 11 September 2026

All fourteen ran on the local instance against the test bed above. Every rule did what its
rows say. Three things are worth knowing:

1. When a store joins `NETSUITE_FULFILLMENT` (scenario 6), its POS sales go through rule 1 too.
   The rule reads "any order at a NetSuite facility", and a counter sale at that store is one.
2. Spare capacity depends on how the MDM config runs files. With one file at a time
   (`DMC_QUEUE`), 28 warehouse orders at 6 a minute take 4.7 minutes, so a 10 minute target
   leaves room for 31 POS orders; with three files at once (`DMC_ASYNC`) it leaves room for all.
   The message names the minutes filled either way.
3. A run with `historyLogCount` 0 failed before today. It now means "use the default rate",
   fixed in the connector.

The record version lists every run, the order ids chosen, and the scripts that built the bed.

## What is not decided by these tests

The real cutover date, the file counts, the POS limit, and `targetMinutes` are business
numbers; the scenarios use placeholders. Whether an order that ships partly from a store
and partly from WH should go to NetSuite as a whole is a business question (scenario 8
shows what the rules do today: it goes once, with rule 1).
