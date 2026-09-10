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

## Picker rules to view conditions

| Feed step | View | Condition |
|---|---|---|
| 1, 2, 4 | `NetSuiteEligibleOrderView` | `orderTypeId = SALES_ORDER`, `SHOPIFY_OID.idValue` not null, `OID(NETSUITE_ORDER_ID).orderId` null |
| 3 | removed | no `NETSUITE_CUSTOMER_ID` join; `sync#NetSuiteOrder` creates the customer |
| 5 | `NetSuiteEligibleOrderView` | `INVORD.orderId` null, the connector's `InvalidOrders` sub-select |
| 8 | `posOnly` alias | case on a sub-select counting ship groups with a method other than `POS_COMPLETED` = 0 |
| 10 | `NetSuiteEligibleOrderView` | `salesChannelEnumId != AFTSHP_SALES_CHANNEL or RH.returnId not null` |
| 11 | `ExchangeAwaitingCreditMemoView` | `ReturnItemResponse.replacementOrderId` not null, `ReturnHeader.statusId != RETURN_CANCELLED`, `NetSuiteReturnHeaderHistory.creditMemoId` null |
| 13 | `OrderValidPaymentTotalView` | sum of `OrderPaymentPreference.maxAmount` in `PAYMENT_AUTHORIZED, PAYMENT_SETTLED`; the main view requires `paymentTotal >= grandTotal` on POS orders |

Steps 6, 7 and 9 (sync history, date window, 1000 cap) are the batch's `limit`
parameter and nothing else. Both date properties are empty in production.

Local proof, 10 September: 79 eligible rows; 37 of them have no NetSuite customer id;
rules 10, 11 and 13 leak 0 rows. `posOnly = Y` proved on M112950 with one temporary
`NetSuiteReturnHeaderHistory` row carrying a credit memo id, then removed.

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
