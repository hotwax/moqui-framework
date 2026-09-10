# What order create does today: the record

Same seventy steps as the human document, with the file and line for each, the
production configuration, and the sandbox evidence. Read on 9 and 10 September 2026
from the gorjana checkout at `/Users/anilpatel/adev/maarg4-sd/m4gorjana`, the
connector on `feat/rma-create-services` at `671ef53`, gorjana-maarg on
`feat/GH-303-return-lifecycle`.

Paths are relative to `runtime/component/`. `C` is `mantle-netsuite-connector`,
`G` is `gorjana-maarg`.

## Where the process runs in production

Read from gorjana production database 83 through Tathya, 9 September.

```
job         generate_CreateOrderFeed          every 30 min   web orders
job         generate_CreateOrderFeed_pos      every 30 min   POS orders
service     co.hotwax.netsuite.OrderServices.generate#NewOrdersSyncFeed
parameters  excludeShipmentMethod = POS_COMPLETED   (web job)
            filePathPattern       = netsuite/salesorder/export
            ordersCountPerFeed    = 1000
            runFeedWithHistory    = Y
            scriptPath            = component://gorjana-maarg/script/co/hotwax/gorjana/netsuite/CreateOrderScriptGorjana.groovy
            systemMessageRemoteId = GorjanaRemoteSftp
            fromOrderDate, thruOrderDate, minAgeInMinutes, isMixCartOrder: empty
```

Feed service: `C/service/co/hotwax/netsuite/OrderServices.xml:3` to `:323`.
Gorjana script: `G/script/co/hotwax/gorjana/netsuite/CreateOrderScriptGorjana.groovy`, 444 lines.
Mapping worker: `C/src/main/groovy/co/hotwax/netsuite/NetSuiteMappingWorker.groovy`.
Views: `C/entity/NesuiteViewEntities.xml`.

## A. Choosing which orders go

| # | Rule | Source |
|---|---|---|
| 1 | `OH.orderTypeId = SALES_ORDER` | `EligibleOrders` view, `NesuiteViewEntities.xml:13` |
| 2 | `SHOPIFY_OID` inner join on `OrderIdentification SHOPIFY_ORD_ID`, `idValue is-not-null` | same view |
| 3 | `PID` inner join on `PartyIdentification NETSUITE_CUSTOMER_ID` for the `BILL_TO_CUSTOMER` role | same view. Inner, so no row without it. Verified locally 9 Sep: M121534 returns no row from `OrderDetails` for this reason |
| 4 | `OID.orderId is-null` on the `NETSUITE_ORDER_ID` outer join | same view |
| 5 | `INVORD.orderId is-null`; `InvalidOrders` is any item with no `NETSUITE_PRODUCT_ID` | `NesuiteViewEntities.xml:421` |
| 6 | `NOSH.orderId is-null` on `ExternalOrderSyncHistory` | `EligibleOrdersWithHistory`, `:76`; chosen when `runFeedWithHistory=Y`, `OrderServices.xml:110` |
| 7 | `netsuite_order_sync_from_date`, `_thru_date` | `OrderServices.xml:87`; both empty in production database 83 |
| 8 | `excludeShipmentMethod` and `includeShipmentMethod` | `OrderServices.xml:118`; two jobs, see above |
| 9 | `ordersCountPerFeed` | `OrderServices.xml:133`; 1000 in production |
| 10 | AfterShip channel with no `ReturnItemResponse` returns `skipOrder` | script `:22` |
| 11 | exchange order with no `NetSuiteReturnHeaderHistory.creditMemoId` returns `skipOrder` | script `:33` |
| 12 | `requiredFields = ['date','country','customer']` plus the script's `['location','shippingMethod']`, or `['location','billingCountry','shippingMethod']` for non-POS | `OrderServices.xml:253`, `:260`; script `:436`, `:438`; missing ones written with "Missing Required Fields", `:277` |
| 13 | `oppTotal < orderTotal && salesChannel == 'POS Channel'` writes "InvalidPaymentReason" to the partial payment file and skips the history row | `OrderServices.xml:272`, `:289` |

## B. Header values

| # | Value | Source |
|---|---|---|
| 14 | `"SO_" + externalId`; exchange appends `'-' + returnId` | script `:41`; `OrderServices.xml:198` |
| 15 | `customer = netsuiteCustomerId`; `"12345"` replaced by the facility's `FAC_BLKT_CUST` identification | `OrderServices.xml:201`; script `:165` |
| 16 | `subsidiary = productStoreExternalId` | `OrderServices.xml:201` |
| 17 | `date` formatted `MM/dd/yyyy` | `OrderServices.xml:174` |
| 18 | `orderName` | `OrderDetails` view, `:229` |
| 19 | `billingPhone`, `phone` through `MaargUtil.getValidFormattedPhoneNumber` | `OrderServices.xml:176`, `:177` |
| 20 | bill-to falls back to ship-to `:179`; POS completed with no country takes `FacilityContactDetailByPurpose PRIMARY_LOCATION` for both | `OrderServices.xml:179`; script `:341`, `:344` |
| 21 | `getShippingMethod`, mapping `NETSUITE_SHP_MTHD`; mixed cart takes the first method not in `POS_COMPLETED`, `STOREPICKUP`, `SHIPSI_SM_DAY_DLV` | worker `:118`; script `:213` |
| 22 | `shippingCost = sum SHIPPING_CHARGES` | `OrderServices.xml:173` |
| 23 | `getShippingTaxCode`: `NETSUITE_TAX_CODE/DEFAULT` if a `SHIPPING_SALES_TAX` row exists, else `-Not Taxable-` | worker `:140` |
| 24 | `salesChannel = orderSalesChannelDescription` | `OrderServices.xml:201` |
| 25 | `HCOrderId = orderId` | `OrderServices.xml:201` |
| 26 | `HCShopifySalesOrderId`, and the script's `etailOrderId` | `OrderServices.xml:201`; script `:40` |
| 27 | `HCOrderTotal = totalAdjustment + itemAmountTotal` | `OrderServices.xml:215` to `:217`, `:250` |
| 28 | `hcHasCustomerDeposit` from `EXCHANGE_PAYMENT` whose `parentRefNum` matches a payment not in `EXT_SHOP_GFT_CARD`, `SHOP_STORE_CREDIT` | script `:150` |
| 29 | `orderNote` from `CommunicationEventAndOrder ORDER_NOTE`, `\n` and `♡` replaced | script `:113` |
| 30 | `etailParentOrderId` from the first `ReturnItem.orderId` | script `:56` |
| 31 | `hcExchangeFromWarranty` for channels `AFTSHP_OL_WTY`, `AFTSHP_HG_LOSS`, `AFTSHP_HG_EMPL`, `AFTSHP_NO_POP` | script `:82` |
| 32 | `exchangeFromHGReturn` for `SELLABLE_RETURN`, `HAPPINESS_GUARANTEE` | script `:85`, used at `:378` |
| 33 | `AFTSHP_HG_EMPL`: `employeeType` from `ReturnAttribute CustomerClassification`; `shipToStore` for "Store Employee" | script `:88`, `:409` |
| 34 | `creditmemo = NetSuiteReturnHeaderHistory.creditMemoId` | script `:441` |
| 35 | `giftWrapOption`, `isGiftWrap` from `OrderAttribute "Gift Wrap Option"` | script `:392` |
| 36 | `shipBooklets` from `OrderAttribute isShipBooklet` | script `:396` |
| 37 | `orderWithKitProduct` when any product is `MARKETING_PKG_PICK` | script `:231` |
| 38 | `storeCreditAmount` from settled `SHOP_STORE_CREDIT`, plus store credit behind an exchange payment; `storeCreditItem` from `NETSUITE_ITEM_ID/StoreCreditRedemptionItemId` | script `:125`, `:426` |
| 39 | `exchangeCreditTotal` from settled `EXCHANGE_CREDIT`; Loop Exchange: `HCOrderTotal - upsell_amount`; set on every non-discount line | script `:118`, `:415`, `:429` |
| 40 | `giftCardPaymentTotal` from `ExchOrderGiftCardPayment` then `NonRefundedGiftCardPayment` | `OrderServices.xml:189`, `:192`; views `:706`, `:688` |
| 41 | `orderPaymentTotal = getValidPaymentTotal`, statuses `PAYMENT_AUTHORIZED`, `PAYMENT_SETTLED` | `OrderServices.xml:219`; worker `:247` |

## C. Line values

| # | Value | Source |
|---|---|---|
| 42 | `item = netsuiteProductId` | `OrderServices.xml:245`; `OrderItemsDetails` view `:360` |
| 43 | `price`, `quantity` | `OrderItemsDetails` |
| 44 | `orderLineId = orderItemSeqId` | `OrderServices.xml:245` |
| 45 | `closed = itemStatus == ITEM_CANCELLED` | `OrderServices.xml:225` |
| 46 | `priceLevel = NETSUITE_PRICE_LEVEL/PRICE_LEVEL` | `OrderServices.xml:226`; worker `:175` |
| 47 | `department = FacilityIdentification ORDR_ORGN_DPT`; non-POS forced to `"2"` | `OrderServices.xml:227`; script `:254` |
| 48 | `location = orderFacilityExternalId`; non-POS forced to facility `WH` external id | `OrderServices.xml:245`; script `:254` |
| 49 | `itemLocation = currentTransfer.sourceFacilityExternalId ?: facilityExternalId`; blank when the facility is not in `NETSUITE_FULFILLMENT` and the item is not complete | `OrderServices.xml:243`; `InventoryTransferSourceFacility` view `:402`; script `:332` |
| 50 | `getTaxCode`: DEFAULT when `taxAdjustments` exist; script sets DEFAULT on a `SALES_TAX` count, and for exchange from warranty or HG; blank on a discount row | worker `:159`; script `:375`, `:378`, `:430` |
| 51 | `tag` | script `:225`, `:237`, `:250`, `:264`, `:270` |
| 52 | gift card or digital: `shippingMethod = "13063"` | script `:237` |
| 53 | `packingCategory = "4"` | script `:231` |
| 54 | `optionFont`, `optionText` plus `Direction:`, `GiftWraperText`, `finalSale` from `OrderItemAttribute` | script `:300`, `:309` |
| 55 | `getDiscountItem`: `EXT_PROMO_ADJUSTMENT` summed per item; item from `NETSUITE_ITEM_ID/DEFAULT_DISCOUNT_ITEM` or `AFTSHIP_POS_DISCOUNT_ITEM`, else `12637` | worker `:186`; script `:277` |
| 56 | header discount: `EXT_SHIP_ADJUSTMENT` with `orderItemSeqId _NA_`, a copy of line 1 on item `12637` | script `:176`, `:190` |

## D. Files and bookkeeping

| # | Step | Source |
|---|---|---|
| 57 | `DataFileWriter` for valid, `required_fields_missing`, `partial_payment` | `OrderServices.xml:143` to `:145` |
| 58 | `put#FileOnSftp` three times | `OrderServices.xml:310`, `:314`, `:318` |
| 59 | `create#ExternalOrderSyncHistory` per order unless partial-payment POS | `OrderServices.xml:289` |

Columns dropped before the CSV: `fieldsToRemove`, worker `:10`.

## E. Inside NetSuite

Script deployments read from the sandbox `4054670-sb1` on 10 September through
`probeSuiteQL`; the sandbox mirrors production.

| # | Step | Script and deployment |
|---|---|---|
| 60 | read the file, run the CSV import | `HC_importSalesOrders`, SCHEDULED |
| 61 | on save: balance to `custbody_hc_order_total`, gift card and POS exchange payment | `HC_UE_GiftCardAndPOSExchangePayment`, USEREVENT on SALESORDER, RELEASED |
| 62 | on save: price the tax | Avalara `AVA_TransactionTab_2` user event on SALESORDER; `custrecord_ava_deftaxcode = AVATAX+10214` |
| 63 | `custbody4`, `custbody17 = 2`, `custbody23 = 218.0`, `custbody14_2 = email`, `custbody_promisedate`, `custbody_pickingcategory`, `custbody_esc_*` filled | read off CSV-created order 70680566 on 9 Sep; the import map is not available to us |
| 64 | log a failed row | `HC_importSalesOrder_errorlog`, NOTSCHEDULED |

Evidence for 61: sales order 70687229 (`SO5727002`), 10 September, created by
`create#NetSuiteSalesOrder` with `custbody_hc_order_total = 96.92` while NetSuite's
own total was 90.00; the record came back with an extra line, "POS Tax Variance",
6.92, Not Taxable.

Evidence for 62: sales orders 70685728 and 70685729, 9 September, created over REST
with no tax numbers; both came back `taxTotal 6.92`, line `taxRate1 8.88`,
`custbody_avalara_status` Processed. Sixteen more on 10 September showed the line
tax code is sourced from the item on every save and overrides whatever is sent.

## F. After the order exists

| # | Step | Source |
|---|---|---|
| 65 | export NetSuite id plus HotWax id | `HC_MR_ExportedSalesOrderCSV`, MAPREDUCE, SCHEDULED |
| 66 | NiFi posts the file to OFBiz MDM `IMP_ORDER_IDENT`, service `createUpdateOrderIdentification` | production database 83: 3,504 `NETSUITE_ORDER_ID` rows on 9 Sep, arriving at :11 and :41 each hour |
| 67 | line ids | `HC_MR_ExportedSalesOrderItemCSV`, SCHEDULED |
| 68 | customer deposit | `HC_MR_CreateCustomerDeposit`, SCHEDULED |
| 69 | invoices | `HC_SC_CreateSalesOrderInvoice`, `HC_SC_CreatePOSOrderInvoice`, `HC_SC_CreatePOSMixCartOrderInvoice`, `HC_SC_CreateExchangeSalesOrderInvoice`, `HC_SC_CreateGCSalesOrderInvoice`, all SCHEDULED |
| 70 | later feeds | `generate_BrokeredOrderItemsFeed_Netsuite`, `generate_FulfilledOrderItemsFeed_Netsuite` live in production; the two cancellation feeds paused |

## The REST path so far

`C/service/co/hotwax/netsuite/NetSuiteRestServices.xml`, PR hotwax/mantle-netsuite-connector#398,
branch `feat/sales-order-create-rest`, stacked on #389.

- `create#NetSuiteSalesOrder`, commits `6de3808`, `f1ac3da`. Proved on 70687229.
- `create#NetSuiteCustomer`, commit `347efa0`. Proved on 26271450; duplicate refused
  with "This entity already exists."; upsert by `eid:` measured and not used.

## Open items with no owner yet

1. The NetSuite CSV import map for `HC_importSalesOrders`. Needed to confirm step 63.
2. The source of `HC_UE_GiftCardAndPOSExchangePayment`. Not in any local checkout.
3. "HC Create Customer and SO", RESTLET, RELEASED, and `HC_RL_ImportSalesOrder`,
   RESTLET, TESTING. Both in the account. Origin unknown.
4. The customer feed's field map is a Groovy file on the production server,
   `runtime/datamanager/Netsuite/NetsuiteScript/SyncCustomerScript.groovy`. No local copy.
