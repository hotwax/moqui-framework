# Test scenarios for the order push rules, record

Run 11 September 2026, 18:20 to 18:45 UTC, on the local instance (MySQL 3307, `m4gorjana`).
Human version: `../test-scenarios.md`. Anil's ask: "Prepare list of test scenarios to test
our Rule and conditions that we think business will program ... create your own file with
Orders that is good representation of a order load at certain time in the day"; the window:
"one busy hour plus the evening POS orders, go".

## Source data

Google Drive folder `1HjUo6Tx5YKwhDP2NSU8svJngt96iMTbl` (owner yash.jain@hotwaxsystems.com):
5,499 files `ShopifyOrderList_SYNC_SHOPIFY_ORDER_<uuid>_<yyyyMMddHHmmssSSS>.json`, one per
production sync run, 5 August to 3 September 2026, about 190 a day. Each file is a JSON list of
`{payload: <Shopify order>}`, the input of DataManager config `SYNC_SHOPIFY_ORDER`
(service `co.hotwax.sob.order.ShopifyOrderServices.sync#ShopifyOrder`, `shopId` in the log's
parameters). Files are readable without login: `https://drive.google.com/uc?export=download&id=<id>`;
the folder listing at `embeddedfolderview?id=<id>` gives every id.

Files up to 29 August lack `fulfillmentOrders`; from 2 September they carry it (and `risk`).
The importer since mantle-shopify-connector#541 marks `POS_COMPLETED` only from a
fulfillment order with `deliveryMethod.methodType RETAIL` and status `CLOSED`
(`prepareTransformedShopifyOrderPayload.groovy:478` to `:530`). So the day is 3 September.

3 September, 200 files, 735 distinct orders in the files, 735 created that day after
removing updates to older orders. By Pacific hour and source:

| Hour | Orders | pos | web | AfterShip (92270886913) | other |
|---|---|---|---|---|---|
| 09 | 39 | 26 | 13 | | |
| 10 | 59 | 47 | 9 | 2 | 1 |
| 11 | 118 | 89 | 15 | 6 | 8 |
| 12 | 54 | 40 | 9 | 2 | 3 |
| 13 | 105 | 84 | 11 | 5 | 5 |
| 14 | 42 | 32 | 8 | 1 | 1 |
| 15 | 60 | 41 | 15 | 2 | 2 |
| 16 | 37 | 28 | 7 | 1 | 1 |
| 17 | 38 | 24 | 8 | 4 | 2 |
| 18 | 23 | 13 | 9 | 1 | |
| 19 | 16 | 8 | 7 | 1 | |
| 20 | 12 | 4 | 7 | 1 | |

Source ids seen: `web`, `pos`, `92270886913` (AfterShip Returns), `223726927873`,
`1528379`, `3890849`, `2329312`, `shopify_draft_order`, `checkout_next`.

## The file

`build-order-load-test-file.py` (this folder) picks hour 11 (118), evening POS 17:00 to 20:59
(49) and nine extras, 176 orders, and rewrites production ids into local ones. The output
file (2.3 MB, customer names and addresses inside) is not committed; it was
`runtime/datamanager/shopify/test-orders/ShopifyOrderList_TEST_20260903_busy_hour_and_evening_pos.json`.

Translations, because the local database is the sandbox shop (`ShopifyShop` 10000,
`gorjana-sandbox.myshopify.com`), not a production copy:

- Line item `variant.legacyResourceId` and `variant.id`: the local `ShopifyShopProduct`
  row of the product with the same SKU (`GoodIdentification` type `SKU`). 37 SKUs had no
  local product; created as `FINISHED_GOOD` variants with SKU, UPCA and a `ShopifyShopProduct`
  row carrying the production variant id (`create#Product`, `create#GoodIdentification`,
  `create#ShopifyShopProduct` through the probe).
- Every `gid://shopify/Location/<id>` (in `retailLocation`, `fulfillments[].location`,
  `transactions[].location`, `fulfillmentOrders...assignedLocation.location`), plus the bare
  ids in line item custom attributes `_shopifyLocationId` and the OMS facility id in
  `_hcShippingFacility` (AfterShip replacement orders): mapped to the local
  `ShopifyShopLocation` by facility name where it matched (49 of 90), else to a free local
  store in order; four local stores (M100000 to M100003) serve two production stores each.
  Map kept in the scratch `locmap.json`.
- SHIPSI same-day codes, the first token of `shippingLines[0].code`, need
  `FacilityIdentification` type `SHIPSI_FULFMENT_LOC` (`OrderTransformation.groovy:104`).
  Seeded: `633a40cfc545cdba57f5a781` → M100000, `6164b7eec545cdac2c44fa70` → M100002,
  `6905144cb5528638e702a695` → M100010.

## The import

`create#DataManagerLog` configId `SYNC_SHOPIFY_ORDER`, parameters `[shopId: 10000]`.
Log M100426: 176 records, 25 failed. Causes and fixes:

| Failed | Cause | Fix |
|---|---|---|
| 15 | `OrderAttribute polar_attr` longer than 255; production column is 1000 (Tathya 83), the importer cuts at 999 (`prepareTransformedShopifyOrderPayload.groovy:384`) | local `alter table order_attribute modify attr_value varchar(1000)` |
| 7 | `_hcShippingFacility` production facility id, FK on `order_item_ship_group.facility_id` | builder translates it |
| 3 | "Facility not found for shipping code" | the three `SHIPSI_FULFMENT_LOC` rows |

Log M100427: the 25 again, 0 failed. 176 orders, ids M121573 to M121770 (not contiguous).
Import shape: 138 POS channel + 29 web + 5 AfterShip + 2 CSR in `ORDER_CREATED`, 2 POS
`ORDER_COMPLETED`; ship groups 128 `POS_COMPLETED` at stores, 24 web `STANDARD`, 16 POS-channel
`STANDARD`, 5 AfterShip `STANDARD`, 4 `STOREPICKUP`, 1 `OVERNIGHT`, 1 `SECOND_DAY`; 15 orders
with no payment preference (all zero total); 4 orders with two ship groups.

NetSuite item ids: SuiteQL `select id, itemid from item where itemid in (...)` on the sandbox
found 176 of the 205 SKUs; `GoodIdentification NETSUITE_PRODUCT_ID` created for those
(fromDate 2026-08-25). 29 SKUs (series 263 to 269, GWP-042, GWP-043) are not in the sandbox;
their 35 orders stay out of the picker, which is scenario 11's material.

## The bed

Unbrokered ship groups (39, all at the store's default facility M100000, methods other
than `POS_COMPLETED` and `STOREPICKUP`), ordered by order id: rows 1 to 4 left at M100000
(ship from store), 5 to 9 to M100049, the rest to WH. Every imported order set
`ORDER_APPROVED` (items `ITEM_APPROVED`) except five web orders at WH kept `ORDER_CREATED`:
M121657, M121661, M121671, M121742, M121748 (M121748 has an unmapped item, so four show in
the view). Rules read through the view, cutover 2026-09-01:

- NS group, after 1 Sep: 28: M121623 M121626 M121629 M121631 M121633 M121638 M121649 M121657
  M121661 M121671 M121686 M121688 M121690 M121695 M121698 M121702 M121740 M121742 M121744
  M121749 M121751 M121752 M121753 M121754 M121761 M121762 M121763 M121769
  (23 at WH, 5 at M100049: M121623 M121626 M121629 M121631 M121633).
- NS group, after 1 Jan: 43 (the 28 plus the 15 older WH orders).
- Approved among the 28: 24.
- `POS_COMPLETED` after 1 Sep: 95; mixed carts in both rule 1 and rule 2: M121631 M121686 M121690.
- Ship from store: M121767 M121755 M121768 M121750 M121764 at M100010, M100076, M100002.
- Store pickup: M121598 M121747 M121757 M121758. AfterShip in the NS group: M121629 M121649
  M121688. Expedited at WH: M121740 (OVERNIGHT), M121754 (SECOND_DAY). Zero total: 15.

## The runs

`run-push-rule-scenarios.py` (this folder) writes the rule rows by SQL, calls
`run#NetSuiteOrderPush` through the probe with `dataManagerConfigId PROBE_NS_SO_PUSH`
(no-op import, `DMC_QUEUE`) and reads the CSV files under
`runtime/datamanager/netsuite/orderpush-probe/`. `run2` passes `historyLogCount 0,
defaultRatePerMinute 6` for a known rate.

| # | Rule rows | Result |
|---|---|---|
| 1 | group = NETSUITE_FULFILLMENT, orderDate > 2026-09-01, sort priority, orderDate, 3 files | 28 in files of 10, 9, 9; missing 0, extra 0 |
| 2 | same, orderDate > 2026-01-01 | 43 in 15, 14, 14; exact |
| 3 | + statusId = ORDER_APPROVED | 24 in 8, 8, 8; exact |
| 4b | + rule 2 POS spare, rate 6, target 0.2 | rule 2 "skipped: the rules before it filled 0.2 minutes"; 28 queued, 4.67 min |
| 4c | target 3 | skipped, "filled 3 minutes" (28 orders one file at a time = 4.67 min) |
| 4a | target 10 | 31 POS in 16, 15 (spare (10 − 4.67) × 6 = 32); with the probe's own history (145,000 a minute) all 92 |
| 5 | rule 2 limit 30, 2 files | 30 in 15, 15; equal to the 30 oldest POS orders not in rule 1 |
| 6 | Boulder M100010 added to the group | 31: the 28 + M121755, M121768 and POS sale M121727 at Boulder |
| 7 | rule 3: method ≠ POS_COMPLETED, ≠ STOREPICKUP, shipGroupFacilityId in M100010, M100076, M100002 | 5: exactly the ship-from-store five; no pickup, no POS |
| 8 | rules 1 and 2 active | mixed carts M121631 M121686 M121690 in rule 1's files, not in rule 2's; overlap 0; rule 2 chose 92 |
| 9 | rule 1 + channel ≠ AFTSHP, rule 4 channel = AFTSHP | 25 + 3 (M121629 M121649 M121688); union exact |
| 10 | rule 0 (sequence 0) method in OVERNIGHT, SECOND_DAY, NEXT_DAY | rule 0: M121740 M121754; rule 1: 26; no repeat |
| 11 | every run | 0 orders with an unmapped item, 0 with a live NETSUITE_ORDER_ID, in any file |
| 12 | one PROBE log set DmlsRunning by hand | "skipped: 1 file(s) still pending or running on PROBE_NS_SO_PUSH", busyFileCount 1, no file |
| 13 | after run 10 | ServiceJob fromDate 18:36:26.620 = nextRunTime 1789151786620, one minute after the run at the probe's rate |
| 14 | M121749 (449.98, three settled preferences) set PAYMENT_CANCELLED, `sync#NetSuiteOrder` | "Order M121749 waits for payment: 0 of 449.98 authorized or settled; nothing sent.", no error, no NETSUITE_ORDER_ID, no call to the sandbox; preferences restored |

Runner mistakes on the way, not code findings: `ATP_RULE_INACTIVE` is not a status
(`ATP_RULE_DRAFT` is), so an update to it failed silently and rule 2 stayed active in the
first S1; `seq=0` was falsy in the runner, so rule 0 first ran last.

Code finding: `run#NetSuiteOrderPush` with `historyLogCount` 0 failed
(`limit="historyLogCount"` renders `LIMIT ALL`). Fixed in connector `d1d4a7a` on
`feat/order-push-rule-view`: 0 reads no history, rate = `defaultRatePerMinute`.

## Local state left behind

Seeded rules restored (both active, cutover `2026-01-01T00:00:00`); the three extra rules
deleted; Boulder removed from the group; `PROBE_NS_SO_PUSH` logs all `DmlsFinished`;
job `export_NetSuiteOrderPush_GORJANA` still paused, its `fromDate` at 18:36 UTC.
The 176 orders, 37 products, 176 NetSuite product ids, three SHIPSI rows and the wider
`attr_value` column stay for the next round.
