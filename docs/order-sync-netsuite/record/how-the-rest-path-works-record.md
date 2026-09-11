# How the REST path works, the record

Record version of `../how-the-rest-path-works.md`. File, line and evidence for each
claim. Written 10 September 2026 from the code at gorjana-maarg `8dab480` on
`feat/netsuite-order-sync-picker` and mantle-netsuite-connector `347efa0` on
`feat/sales-order-create-rest`.

`G/` is `runtime/component/gorjana-maarg`, `C/` is `runtime/component/mantle-netsuite-connector`.

## Where things are

| Piece | File |
|---|---|
| Views | `G/entity/NetSuiteOrderViewEntities.xml`, commit `490769b` |
| Services | `G/service/co/hotwax/gorjana/netsuite/NetSuiteOrderServices.xml`, commit `8dab480` |
| Account ids as data | `G/data/NetSuiteConfigData.xml` section 12, same commit |
| Connector stubs | `C/service/co/hotwax/netsuite/NetSuiteRestServices.xml`, `create#NetSuiteSalesOrder` at `:477`, `create#NetSuiteCustomer` at `:670` |
| PRs | hotwax/gorjana-maarg#333; hotwax/mantle-netsuite-connector#398 stacked on #389 |

## The rule chain

Added 10 September, evening, after the ruling to use the Rails rule layer.

| Piece | Where |
|---|---|
| Connector view `NetSuiteOrderPushEligible`, the template's fixed part as entities, and its helper `NetSuiteOrderPushShipGroupSummary` | `C/entity/NetSuiteOrderPushViewEntities.xml` |
| `run#NetSuiteOrderPush` (rule group walk) and `run#NetSuiteOrderPushRule` (one rule) | `C/service/co/hotwax/netsuite/NetSuiteOrderPushServices.xml` |
| The query: rule conditions to entity conditions, CSV out | `C/script/co/hotwax/netsuite/order/OrderPushRuleFile.groovy` |
| Rule group `NS_ORDER_PUSH_GORJANA`, rule `NS_ORDER_PUSH_RULE_GORJANA`, MDM config `MDM_NS_SO_REST`, job `export_NetSuiteOrderPush_GORJANA` (paused) | `G/data/NetSuiteConfigData.xml` section 13 |

The operator text of a rule condition goes through `EntityConditionFactoryImpl.getComparisonOperator`,
the same call `MaargUtil.makeSqlWhere` uses at `maarg-util/src/main/groovy/co/hotwax/util/MaargUtil.groovy:203`;
`in`, `not-in`, `between`, `not-between` values through `MaargUtil.valueToCollection`. Every alias is
selected and the find is distinct: a sub-select member's columns exist in the query only when
selected, and the view's own conditions name `PAY.paymentTotal` (measured: `Unknown column
'PAY.PAYMENT_TOTAL'` when only `orderId` was selected).

The old pair, `run#NetSuiteDMOrderFeed` and `run#NetSuiteOrderFeedRule` in `C/service/co/hotwax/netsuite/OrderServices.xml:508`
and `:559`, and `C/sql/EligibleOrdersQuery.sql.ftl`, are untouched. Rails' four jobs still name them.

### Old template against the new default view, local, 10 September

Same rule (`orderDate greater-than 2026-01-01T00:00:00`), same store, both services run one after the
other. Old: 3,643 ids. New: 3,642. Difference: M121345, in the old set only. Its `NETSUITE_ORDER_ID`
row has `from_date 2026-09-10 20:34:47` (UTC, as Moqui stores it); the template compares it to the
database's `now()`, which was `2026-09-10 16:57:51` in the server's CDT, so the row was "not yet
effective" and the order was chosen again. The view's `<date-filter/>` compares in one clock. A
database running in UTC does not show this; the local one runs in CDT.

The old service also wrote its file to `runtime://datamanager/null/ExportOrderFeed_null.csv`: its
`${exportPath}` and `${dateTime}` are expanded by the `<set value=...>` before the writer sees them,
and neither is in the context. Not fixed; the old code is not touched.

### The chain end to end, local against the sandbox, 10 September 22:00 UTC

Rule cutover set to `2026-08-15T00:00:00` on the local row so one order qualifies. `run#NetSuiteOrderPush`
chose M121117, logged `M100260` on `MDM_NS_SO_REST`; the MDM runner picked it up at 22:00:16 and finished
at 22:00:24, 1 record, 0 failed. `sync#NetSuiteOrder` got 400 "already exists" from NetSuite: sales
order `SO_6955978129452` was made there on 18 August by the sandbox instance's CSV feed (OMS order
M149172 on that instance). The id 70669247 was read back by external id and written onto M121117.
A second run of the group chose 0 orders and logged nothing.

A rule naming a field the view lacks: `Field noSuchField not found on entity
co.hotwax.netsuite.order.NetSuiteOrderPushEligible, cannot add condition`, no file written.

`sync#EligibleNetSuiteOrders` was deleted; the rule group and the queue take its place.

## The query, checked for the database

Captured from MySQL's general log on 10 September after the rework, saved next to this file as
`eligible-order-query.sql` (gorjana view) and `default-push-query.sql` (connector view). `EXPLAIN`
on the local database, 9,234 orders:

| Table | Access | Index |
|---|---|---|
| `ORDER_HEADER` | range on the rule's `orderDate`, when the account has an ORDER_DATE index; gorjana production has `idx_order_header_order_date` | |
| `ORDER_IDENTIFICATION` (Shopify id, NetSuite id) | one primary key lookup each, "Not exists" for the NetSuite id | `PRIMARY (type, order_id, from_date)` |
| `ORDER_ROLE` | primary key prefix | `PRIMARY (order_id, ...)` |
| lateral `ORDER_ITEM` + `GOOD_IDENTIFICATION` | primary key prefix, primary key | |
| lateral `ORDER_PAYMENT_PREFERENCE` + `INVOICE` (exchange credits) | ref, primary key | the local plan shows a scan on `INVOICE` because the table has 55 rows; the join is on its primary key |
| lateral `ORDER_PAYMENT_PREFERENCE` | ref | `MANUAL_REF_NUM_IDX (order_id, ...)` |
| connector view lateral `ORDER_ITEM_SHIP_GROUP` + `ORDER_ITEM` + `CARRIER_SHIPMENT_METHOD` | primary keys | |

What changed for this, and why:

- The three helper views join lateral (`sub-select="true"`, `LEFT OUTER JOIN LATERAL` on mysql8).
  Non-lateral, each was a derived table over its whole base table on every run: a `SUM` over all
  2,024,732 payment rows, a scan of all 4,295,534 order items, the ship group summary over
  4,410,154 groups. Lateral, each runs only for the orders that survive the NetSuite-id anti-join.
- Each helper is a count or a sum. A lateral that only says "a row exists" cannot be written: the
  framework moves the join key into the correlated WHERE and has nothing left to select
  (measured: `SELECT FROM ORDER_HEADER ...`, a syntax error). So `OrderUnmappedItemCountView` and
  `ExchangeCreditAwaitingMemoView` answer a count, and the picker admits the order at zero. The
  connector's `InvalidOrders` is no longer a member; its question is asked per order instead.
- The framework joins a member only when one of its fields is used. Two joins had been dropped
  silently: the cancelled-return test in the exchange helper, and the order-item join in the ship
  group summary (`itemCount` now keeps it, and the view requires it above zero).
- Aliases inside the helpers are prefixed (`XRIR`, `UOI`) so a correlated WHERE inside a lateral
  cannot resolve to the outer view's alias of the same name.
- Indexes: `NetSuiteReturnHeaderHistory.returnId` is declared in the connector's new entity file;
  the entity had only its primary key and the return lifecycle reads it by return. The picker no
  longer reads it. `ORDER_HEADER.ORDER_DATE` is not declared, because gorjana production already
  carries `idx_order_header_order_date` under its own name and a declared one would be created
  beside it.

What still grows: the rows the range on `orderDate` covers, orders since the cutover, about
3,400 a day in production. Each costs two primary key lookups before the anti-join drops it. Move
the rule's cutover forward now and then, or the scan is a year of orders every ten minutes.

## The rules and the pacing, 10 September, late evening

Rulings, Anil's words: "Number of orders we can push to NetSuite from OMS is bottleneck";
warehouse orders "at priority and asap"; POS completed orders "are done deal"; "We don't want
to push next list until the existing is done"; "the rule should be, get me all orders that
are brokered to netsuite facility group, if I still have capacity left, then go get some pos
fulfilled orders ... this should be simple"; on the gap between a file finishing and the next
run: "all we do is in production we set frequency so we are happy".

| Piece | Where | Proof |
|---|---|---|
| Ship group fields on the view: `shipGroupSeqId`, `shipmentMethodTypeId`, `shipGroupFacilityId`, `facilityGroupId` (via `FacilityGroupMember`, date filtered) | `G/entity/NetSuiteOrderViewEntities.xml`, `2f83de3` | rule 1 chose exactly the ten orders re-brokered to WH |
| Rule 1 `NS_ORDER_PUSH_NS_FACILITY`: `facilityGroupId equals NETSUITE_FULFILLMENT`, sort `priority, orderDate`; rule 2 `NS_ORDER_PUSH_POS_COMPLETED`: `shipmentMethodTypeId equals POS_COMPLETED`, sort `orderDate` | `G/data/NetSuiteConfigData.xml` section 13, `2f83de3` | an order at a facility in the group that was also POS completed went with rule 1 and was not chosen by rule 2 |
| Each order once per run: `chosenOrderIds` in and out of `run#NetSuiteOrderPushRule`, passed rule to rule | `C/service/.../NetSuiteOrderPushServices.xml`, `C/script/.../OrderPushRuleFile.groovy`, `4f371e2` | same run |
| Idle check: skip while the config has a log in `DmlsPending, DmlsQueued, DmlsRunning` | `run#NetSuiteOrderPush`, `28b8f88` | a log marked running: run skipped, log count on the config unchanged at 17 |
| Self-pacing: rate = records over seconds on the last `historyLogCount` finished logs (`defaultRatePerMinute` 6 before history); minutes = queued ÷ (rate × files run at once) × (1 + `marginPercent` 20); `ServiceJob.fromDate` = now + minutes on `RuleGroup.jobName`; files at once = this run's files in `DMC_ASYNC`, 1 in `DMC_QUEUE` | same commit | 10 orders, one file, history 6/min: 2 minutes (3 before rounding to four places; `2.0000000000000004`); 11 orders, two files, `DMC_ASYNC`, history 7/min: 1 minute |
| `fromDate` holds a job | framework `ScheduledJobRunner.groovy:115` to `:178` | one-minute cron: run 01:57:16, `fromDate` 02:00:19, no run 01:58 to 02:00, run 02:01:16 |

Not built, by ruling: no event on file finish; the cron set tight per environment is enough.
Not built yet: per-rule `RuleAction` limit and file count, `MDM_NS_SO_REST` to `DMC_ASYNC`,
the evening job for POS. Anil has not ruled the starting rate, the file count, the evening
hours, or whether rule 1 needs `statusId equals ORDER_APPROVED`.

Data model, said once: a POS item sits in a ship group at the store facility with shipping
method `POS_COMPLETED`. Never look for a null ship group on an item.

## What the dynamic view cannot do, measured 10 September

Before the plain-join design, a dynamic view (`EntityDynamicView`) built as the rule is read
was tried, with `probeDynamicView` in `G/service/probe/ProbeServices.xml` (local only):

| Try | Result |
|---|---|
| `NetSuiteEligibleOrderView` as the first member | `Could not find field idValue in entity co.hotwax.oms.NetSuiteEligibleOrderView`; `EntityFindBuilder.java:439` says "currently unused" |
| a helper view as a sub-select member, find condition `orderId is-null` on it | renders, but the condition is pushed into the sub-select (`EntityFindBuilder.java:625`): 9,171 rows where the truth is 130 |
| find condition on any sub-select aggregate alias, static view included | `Invalid use of group function`; only the view's own `<entity-condition>` on it works |
| plain entity member, `join-optional`, condition `not-equals POS_COMPLETED`, then `is-null` | correct anti-join, except `EntityDynamicViewImpl.groovy:75` to `:80` drops the operator when a value is given: rendered `= 'POS_COMPLETED'`, 9,143 rows where the truth is 28 |

Local test state: `NETSUITE_FULFILLMENT` holds WH and M100049; ten eligible orders were
re-brokered from store M100000 to WH (M118279, M118838, M118916, M119294, M119399, M119404,
M119509, M119734, M121081, M121082); both rules' local cutover is `2026-01-01`.

## Picker rules to view conditions

Ruled by Anil, 10 September, evening: the payment rows say who pays for the order; an
exchange order is not sent until its credit memo exists; a zero-value order has no payment
row. All of #305 and the PRs beside it count as merged.

| Feed step | View | Condition |
|---|---|---|
| 1, 2, 4 | `NetSuiteEligibleOrderView` | `orderTypeId = SALES_ORDER`, `SHOPIFY_OID.idValue` not null, `OID(NETSUITE_ORDER_ID).orderId` null |
| 3 | removed | no `NETSUITE_CUSTOMER_ID` join; `sync#NetSuiteOrder` creates the customer |
| 5 | `OrderUnmappedItemCountView`, lateral | `unmappedItemCount = 0`: order items with no active `NETSUITE_PRODUCT_ID` |
| 8 | removed | the two-job split existed only to run the payment check later |
| 10 | removed | folded into 11 and 13. Also dead in production: the script compares the channel description to `"Aftership Sales Channel"`, small s, and both AfterShip enumerations say `"AfterShip Sales Channel"`. Groovy compares exactly. |
| 11 | `ExchangeCreditAwaitingMemoView`, lateral | `exchangeWaitingCount = 0`: live `EXCHANGE_CREDIT` preferences (authorized or settled) whose `customerReturnInvoiceId` is empty or names an `Invoice` with no `externalId`. The memo id lives on the customer return invoice's `externalId` under #305; `OrderPaymentPreference.customerReturnInvoiceId` is the oms component's field from commit `5d8c4318`, 7 September. `NetSuiteReturnHeaderHistory` is not read. |
| 13 | `OrderValidPaymentTotalView`, lateral | `paymentTotal >= grandTotal or grandTotal = 0`, on every channel, not POS only. Production, one week to 10 September: paid in full on every channel; the shortfalls were 26 POS orders and 36 unlinked AfterShip exchanges. |

Steps 6, 7 and 9 (sync history, date window, 1000 cap) are the rule's own conditions and
nothing else. Both date properties are empty in production.

Local proof, 10 September, after the rework: 74 eligible. Against the previous rule set,
76: the two out are web orders paid 49.50 of 51.98, which the old POS-only rule let
through. Two POS orders with a refunded `EXCHANGE_CREDIT` row were held until the helper
was limited to live preferences; both are original orders, no return behind them.
M112950, an exchange order, entered the picker when its `EXCHANGE_CREDIT` preference was
pointed at a temporary invoice carrying an `externalId`, and left it when the `externalId`
was cleared; the fixture was removed after.

The payload reads the memo id the same way: the order's `EXCHANGE_CREDIT` preferences by
id, the first with a `customerReturnInvoiceId`, that invoice's `externalId`.

## Header mapping, feed step to NetSuite field

Field ids read back from sandbox sales orders 70682121 (POS, 4 September), 70682027
(web) and 70678919 (exchange, AfterShip), all made by the CSV feed, and from
`select scriptid, name from customfield` in SuiteQL.

| Step | NetSuite field | Source in the service |
|---|---|---|
| 14 | `externalId` | `'SO_' + OrderDetails.orderExternalId`, plus `'-' + returnId` on an exchange |
| 15 | `entity.id` | `OrderDetails.netsuiteCustomerId`; `12345` swapped for the facility's `FAC_BLKT_CUST` |
| 16 | `subsidiary.id` | `OrderDetails.productStoreExternalId` |
| 17 | `tranDate` | `OrderDetails.date`, `yyyy-MM-dd` |
| 18 | `otherRefNum` | `OrderDetails.orderName` |
| 19 | `email` | shipping email, else billing email; phones left out |
| 20 | `shippingAddress`, `billingAddress` | `OrderDetails`; bill-to falls back to ship-to; POS completed with no country takes `FacilityContactDetailByPurpose PRIMARY_LOCATION`; an order imported complete defaults to `US` |
| 21 | `shipMethod.id` | `NETSUITE_SHP_MTHD` mapping; mixed cart takes the first method not in `POS_COMPLETED, STOREPICKUP, SHIPSI_SM_DAY_DLV` |
| 22 | `shippingCost` | sum of `SHIPPING_CHARGES` |
| 23 | `shippingTaxCode.id` | `withTaxId` when a `SHIPPING_SALES_TAX` row exists, else `shippingWithoutTaxId` (-8) |
| — | `customForm.id` | `NETSUITE_ORDER_SYNC/salesOrderFormId` = 163 |
| — | `location.id`, `department.id` | the first line's |
| 24 | `custbody_hc_sales_channel`, `custbody_shopify_source_name` | `OrderDetails.orderSalesChannelDescription` |
| 25 | `custbody_hc_order_id` | `orderId` |
| 26 | `custbody_hc_shopify_order_id`, `custbody_celigo_etail_order_id` | `OrderDetails.orderExternalId` |
| 27 | `custbody_hc_order_total` | sum of all adjustments plus price times quantity, 2 decimals, the feed's own sum |
| 28 | `custbody_hc_has_customer_deposit` | an `EXCHANGE_PAYMENT` whose `parentRefNum` matches a payment not in `EXT_SHOP_GFT_CARD, SHOP_STORE_CREDIT` |
| 29 | `memo` | `CommunicationEventAndOrder ORDER_NOTE`, newlines to spaces, `♡` to `♥` |
| 30 | `custbody_celigo_etail_parent_order_id` | the return's first item's order `externalId` |
| 31 | `custbody_hc_exchange_from_warranty`, `custbody_hc_return_channel` | return channel in `AFTSHP_OL_WTY, AFTSHP_HG_LOSS, AFTSHP_HG_EMPL, AFTSHP_NO_POP` |
| 33 | `custbody_hg_employee_type`, `custbody_hg_ship_to_store` | `ReturnAttribute CustomerClassification`; the destination facility's `externalId` as the store id |
| 34 | `custbody_hc_exchange_credit_memo_id` | `NetSuiteReturnHeaderHistory.creditMemoId` |
| 35 | `custbody_gift_wrap_option`, `custbody_pickinggiftwrapped` | `OrderAttribute Gift Wrap Option` |
| 37 | `custbody_hc_order_with_kit`, `custbody_pickingcategory` | any product of type `MARKETING_PKG_PICK`; category id from `NETSUITE_ORDER_SYNC/kitPickingCategoryId` = 4 |
| 38 | `custbody_hc_storecredit_item`, `custbody_hc_storecredit_payment` | settled `SHOP_STORE_CREDIT` plus exchange payments backed by store credit; item `NETSUITE_ITEM_ID/StoreCreditRedemptionItemId` |
| 39 | `custbody_hc_exc_credit_amt` | settled `EXCHANGE_CREDIT`; a Loop Exchange order takes order total minus `upsell_amount` |
| 40 | `custbody_hc_giftcard_payment` | `getExchOrderGiftCardPaymentTotal` then `getGiftCardPaymentTotal` |
| 47 | `custbody4` | the first line's department |

## Line mapping

| Step | NetSuite field | Source |
|---|---|---|
| 42 | `item.id` | `OrderItemsDetails.netsuiteProductId` |
| 43 | `quantity`, `rate`, `price.id` -1 | the OMS line; -1 is NetSuite's Custom price level, rate as given |
| 44 | `custcol_hc_order_line_id` | `orderItemSeqId` |
| 45 | `isClosed` | `itemStatus = ITEM_CANCELLED` |
| 47 | `department.id` | POS: `FacilityIdentification ORDR_ORGN_DPT`; else `ecommDepartmentId` = 2 |
| 48 | `location.id` | POS: `orderFacilityExternalId`; else facility `WH`'s `externalId` |
| 50 | `taxCode.id` | `withTaxId` when a `SALES_TAX` row exists or the exchange is warranty or Happiness Guarantee, else `withoutTaxId`; NetSuite overrides it |
| 51 | `custcol_hc_item_tag` | `hotwax-fulfilled` completed; `pos-fulfilled` digital, arrived complete, or POS completed; `hotwax-fulfilled-pos-mixcart` completed POS in a mixed cart |
| 54 | `custcol_custoption_text1_font_family`, `custcol_custoption_text1`, `custcol_nm_giftwarp_text`, `custcol_nm_final_sale` | `OrderItemAttribute font, text (+ direction), Gift Wrap, Final Sale` |
| 55 | discount line | `EXT_PROMO_ADJUSTMENT` sum per item on `NETSUITE_ITEM_ID/DEFAULT_DISCOUNT_ITEM`, or `AFTSHIP_POS_DISCOUNT_ITEM` for a POS AfterShip exchange; `custcol_hc_orderline_type_id = HC_DISCOUNT_<seq>` |
| 56 | header discount line | `EXT_SHIP_ADJUSTMENT` with `orderItemSeqId _NA_`, `HC_DISCOUNT__NA_` |

Left out, no field id known: 36 ship booklets, 39 per line, 49 item location, 52
gift card shipping method 13063, and both phones. None of the three sandbox orders
carried them, and the catalog has no HC field with those names.

Uncertain choices, each marked in the service: `custbody_hg_employee_type` and
`custbody_hg_ship_to_store` were chosen over `custbody48` and `custbody47`, which
carry the same labels; `custcol_nm_final_sale` over `custcol7 Final_Sale`. The
exchange order 70678919 was on form 189 "HC Sales Order Form"; the service sends
163 for every order.

## Customer

From `co.netsuite.customer.CustomerView` (`C/entity/NesuiteViewEntities.xml:485`),
the feed's rules in `C/service/co/hotwax/netsuite/CustomerServices.xml:208` to `:216`:
`externalId` = Shopify customer id, `isPerson` true, names cut to 13 characters or
`X`, phone through `MaargUtil.getValidFormattedPhoneNumber`, `taxable` true, form
`NETSUITE_ORDER_SYNC/customerFormId` = 90, `custentity1` = Ecomm department,
`custentity_hc_customer_id`, `custentity_hc_shop_cust_id`. Not sent: the entity
status, which the form defaults to CUSTOMER-Closed Won (customer 26271450, made by
REST on 10 September, has it); `defaultOrderPriority` 5, which the connector stub
does not declare.

## Transactions and retries

- The batch runs each order with `requireNewTransaction(true)`, the shape of
  `sync#EligibleNetSuiteReturns` on PR #305.
- `sync#NetSuiteOrder` has `semaphore="fail"` on `orderId`.
- The customer service is called with `transaction="force-new"`, so its
  `PartyIdentification` row commits even when the sales order fails after it.
- Both connector calls run with `transaction="force-new"` and `ignore-error="true"`.
  Measured on the first run: without force-new a 400 from NetSuite marked the
  caller's transaction rollback-only and the read-back could not run.
- Read-back on "already exists": `GET /record/v1/customer/eid:{externalId}` and
  `GET /record/v1/salesOrder/eid:{externalId}` through `call#NetSuiteAPI`.

## Sandbox evidence, 10 September 2026

| Run | Result |
|---|---|
| `sync#NetSuiteOrder` M121345, first run | customer 400 "This entity already exists"; then error "Cannot get connection, transaction not in operable status" → fixed with force-new |
| second run | customer read back as 25011665, written on party M102520; then error `isMixCartOrder` not on `OrderHeader` → derived from the items' methods |
| third run | sales order 70687326, SO5727003, `source: REST Web Services`, form 163, ship method 2830, `price -1`, line tax 10214 (item sourced), tag `pos-fulfilled`, line id 01, `custbody_hc_order_total 100.00`; `NETSUITE_ORDER_ID` row on M121345 at 20:34:47 |
| fourth run | "already has NetSuite sales order 70687326; nothing sent"; not in the picker |
| `sync#EligibleNetSuiteOrders` limit 1 | Candidates 1, Processed 1; M104906 → 70687426 at 20:35:27 |

Line department was blank on 70687326: facility M100000 has no `ORDR_ORGN_DPT`
identification locally. `custbody_avalara_status` was null: AvaTax's 24-hour window
from 9 September 17:00 had ended.
