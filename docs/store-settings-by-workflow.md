# OMS Store Settings & System Properties — by Business Workflow

This document explains every `ProductStoreSetting` and `SystemProperty` that the OMS
actually reads, grouped by the **business workflow** it changes. It answers, for each
one: when does it fire, what does it decide, and what happens to the retailer's staff
or customer if you leave it blank.

It was built by reading the running code of the **asbeauty** deployment
(`/Users/anilpatel/maarg-sd/asbeauty`) — both the Moqui OMS backend and the PWA
operator apps (`/Users/anilpatel/pwa-sd`) — and every claim was checked a second time
against the code. Where a real value is loaded for asbeauty, it is quoted.

Scope: only `ProductStoreSetting` and `SystemProperty`. Other config entities
(mappings, remotes, jobs) are covered in [new-client-onboarding-checklist.md](new-client-onboarding-checklist.md).

---

## How to read this

**Where the setting is enforced** — the most important thing to know:

| Badge | Meaning |
|---|---|
| 🔧 **Backend** | The OMS server reads it and changes what it does. A real workflow driver. |
| 📱 **App-only** | Only a PWA operator app (fulfillment, BOPIS, company, count) reads it. The server ignores it. You cannot see its effect in server logs. |
| 🌐 **External ERP** | Configured here, but read by the OFBiz report/feed engine that runs **outside this codebase**. We cannot verify its effect from this code, only its intent. |
| ⚪ **Inert** | No reader anywhere we can find. Seeded or shown in a screen, but drives no behavior. A "looks live but isn't" trap. |

**What happens if you leave it blank** — plain words:

| Term | Meaning |
|---|---|
| **Blocks** | Hard error. The step stops and does not finish. |
| **Silently off** | The feature just does not happen. No error is shown. |
| **Wrong default** | It keeps working, but falls back to a value that may be wrong for this retailer. |
| **No effect** | Safe default. Nothing breaks. |
| **Cosmetic** | Only changes what is displayed. |

**Required?** — REQUIRED (must set or the workflow breaks/silently misbehaves),
CONDITIONAL (only needed if the retailer uses that sub-feature), OPTIONAL (safe default).

A note on honesty: "App-only" and "External ERP" do **not** mean unimportant. They mean
the effect lives in code we can point to but not fully trace here. "Inert" is the only
label that means "does nothing" — those are listed together at the end so nobody
configures them expecting a result.

---

## 1. Order import & sync (Shopify / marketplace → OMS)

**The workflow.** A shopper checks out on Shopify. Shopify sends the order to the OMS.
The OMS turns the raw order into an OMS sales order: it sets the sales channel, the
carrier, the ship-from routing, the billing address, and decides whether to import the
order at all. Historical orders (placed before go-live) take a separate path.

| Setting | Fires at → decides | If left blank | Set via / asbeauty | Code |
|---|---|---|---|---|
| **newOrderSync.launchDate** 🔧 SystemProperty · **REQUIRED** | A live order arrives. Splits pre-launch (historical) orders from live orders using this cut-over timestamp. | **Blocks.** Live order sync stops for that shop with "SystemProperty [newOrderSync.launchDate] not found". Not auto-created. | Backend order-sync setup screen ("Set to Now"). Per shop. e.g. `2025-03-01 00:00:00` | `shopify-oms-bridge/script/co/hotwax/sob/order/syncShopifyOrder.groovy:23-32` |
| **orderSyncHistory.lastSyncDate** 🔧 SystemProperty · CONDITIONAL | The one-time bulk history-migration job runs. This is the cursor (from-date) of the next window; it advances itself. | **Blocks** the history job only if no `fromDate` is passed either. Live sync never uses it. | Auto-written by the job; per shop. Job ships paused. | `shopify-oms-bridge/service/co/hotwax/sob/order/ShopifyOrderSyncHistoryServices.xml:62-70,115` |
| **SAVE_BILL_TO_INF** 🔧 ProductStoreSetting · CONDITIONAL | Order is built. Decides whether the Shopify billing address (+email/phone) is carried onto the OMS order. | **Silently off.** Order imports with ship-to only, no billing address. Only matters for invoicing/tax. | company app → ProductStoreDetails ("yes/no"). asbeauty = **N** | `shopify-oms-bridge/script/.../prepareTransformedShopifyOrderPayload.groovy:413-437` |
| **SHOPIFY_OIG_CHECK** 🔧 ProductStoreSetting · CONDITIONAL | Right after order create. Decides whether to regroup order items into item-groups (needs the `SHOPIFY_OIG_ASSOC` mapping too). | **Silently off.** Items stay as imported, no grouping. | Data-load. Value `Y`. asbeauty does not set it. | `shopify-oms-bridge/service/.../ShopifyOrderServices.xml:93-108` |
| **${productStoreId}.skip.order.import.tags** 🔧 SystemProperty · OPTIONAL | Order arrives. A comma-list of Shopify tags; an order carrying any of them is dropped and never imported. | **No effect.** No tag filter runs; every order imports. | Data-load (`ShopifyServiceConfig`, per-store key). e.g. `wholesale,draft` | `shopify-oms-bridge/script/.../prepareTransformedShopifyOrderPayload.groovy:225-232` |
| **country.geo.id.default** 🔧 SystemProperty · OPTIONAL | Order phone numbers are parsed. Default country for numbers with no country code. | **Wrong default**, but safe: code hard-falls back to `USA`/`US`. | Data-load (`general`). e.g. `USA` | `shopify-oms-bridge/script/.../prepareTransformedShopifyOrderPayload.groovy:46-54` |
| **order.decimals** 🔧 SystemProperty · OPTIONAL | Only when a multi-qty line is exploded into unit items — proration scale. | **No effect.** Inline default `2`. | Data-load (`arithmetic`). `2` | `shopify-oms-bridge/script/.../explodeShopifyOrderItems.groovy:21,68` |
| **order.rounding** 🔧 SystemProperty · OPTIONAL | Same explosion path — rounding mode. | **No effect.** Falls back to HALF_UP. ⚠️ The seeded value `ROUND_HALF_UP` is *not* a valid Java constant, so the seed itself is a no-op (see Traps). | Data-load (`arithmetic`). | `shopify-oms-bridge/script/.../explodeShopifyOrderItems.groovy:23-29` |
| **DEFAULT_CARRIER** 🔧 ProductStoreSetting · CONDITIONAL | Order is built. Fallback carrier before shipping-line → carrier mapping is applied. | **Wrong default.** Carrier becomes `_NA_` when no mapping matches. Legacy; explicit carrier mappings are preferred. | Data-load. e.g. `UPS` | `shopify-oms-bridge/script/.../prepareTransformedShopifyOrderPayload.groovy:451-455` |
| **PRE_SLCTD_FAC_TAG** 🔧 ProductStoreSetting · CONDITIONAL | Order is built. The order tag that turns on per-item facility/method routing from Shopify line-item properties. Master switch for the three `ORD_ITM_*` settings below. | **Silently off.** All items use default store routing. | company app → ProductStoreDetails (textarea). e.g. `PRESELECTED_FACILITY` | `prepareTransformedShopifyOrderPayload.groovy:496-522` |
| **ORD_ITM_PICKUP_FAC** 🔧 ProductStoreSetting · CONDITIONAL | Only when `PRE_SLCTD_FAC_TAG` matched. Which line-item property carries the pickup facility id. | **Silently off.** Item stays on ship routing. | company app → ProductStoreDetails. | `prepareTransformedShopifyOrderPayload.groovy:510-513,544-552` |
| **ORD_ITM_SHIP_FAC** 🔧 ProductStoreSetting · CONDITIONAL | Only when `PRE_SLCTD_FAC_TAG` matched. Which line-item property carries the ship-from facility id. | **Wrong default.** No per-item override; item uses default facility routing. | company app → ProductStoreDetails. | `prepareTransformedShopifyOrderPayload.groovy:514-517,553-559` |
| **ORD_ITM_SHIP_METH** 🔧 ProductStoreSetting · CONDITIONAL | Only when `PRE_SLCTD_FAC_TAG` matched. Which line-item property carries the shipment method (matched to the shop's carrier mapping). | **Silently off.** Order/group default method applies. | company app → ProductStoreDetails. | `prepareTransformedShopifyOrderPayload.groovy:518-521,560-569` |
| **SHOPIFY_INV_SYNC** 🔧 ProductStoreSetting · CONDITIONAL *(audit-found)* | A transfer-order shipment fulfills. Gates whether that shipment syncs inventory back to Shopify (value must be `Y`). | **Silently off.** Transfer shipment does not push inventory to Shopify. | Data-load. | `shopify-oms-bridge/service/.../ShopifyTransferOrderServices.xml:248` |

---

## 2. Order approval & payment capture

**The workflow.** After import, the OMS decides whether to approve the order so it can
route to a warehouse. Two gates matter: was payment received, and is the order fraud-risky.
Later, when the first item ships, the OMS can tell Shopify to capture the payment.

| Setting | Fires at → decides | If left blank | Set via / asbeauty | Code |
|---|---|---|---|---|
| **APPR_WO_PMNT_CHK** 🔧 ProductStoreSetting · OPTIONAL | At approval. Decides if the "payment must be received first" gate is skipped. | **No effect** (safe). Order waits in created/hold until payment lands (COD still approves). Set `Y` if Shopify already captured payment. | company app → ProductStoreDetails toggle. asbeauty = **Y** (both stores); base default = N | `oms/service/co/hotwax/oms/order/OrderServices.xml:1599-1617` |
| **CAPTURE_PAYMENT_TAG** 🔧 ProductStoreSetting · CONDITIONAL | First physical item ships. Its **value is the note text** written on the order to signal "capture payment" to Shopify. | **Silently off.** Logs an error, writes no note, so Shopify is never told to capture — an authorized payment can stay uncaptured. | company app → ProductStoreDetails (textarea). asbeauty not set (orders pre-captured). | `oms/service/co/hotwax/oms/order/OrderServices.xml:1324-1349` |
| **AUTO_ACPT_RISK_REC** 🔧 ProductStoreSetting · OPTIONAL | At approval, when Shopify flags the order CANCEL or INVESTIGATE. Decides whether to auto-cancel. | **No effect** (safe). CANCEL-flagged orders still approve but a "Review High-Risk Order" task is raised for a human. Set `Y` to auto-cancel. | **Data-load only — no PWA screen.** asbeauty not set. | `oms/service/co/hotwax/oms/order/OrderRiskServices.xml:157-183` |

---

## 3. Order routing / brokering / available-to-promise (ATP)

**The workflow.** The OMS decides which facility ships each order (brokering), and how
much stock it can promise online (ATP). Most routing lives in the order-routing rules,
not in these settings — but a few settings tune pre-order and back-order behavior.

| Setting | Fires at → decides | If left blank | Set via / asbeauty | Code |
|---|---|---|---|---|
| **HOLD_PRORD_PHYCL_INV** 🔧 ProductStoreSetting · CONDITIONAL | Online ATP is computed for a SKU. If that SKU has a pre-order/back-order queue, return ATP 0 so pre-sold units are not double-promised. | **Silently off.** Physical ATP is shown even while a pre-order queue exists (risk of overselling). ⚠️ Enum says "default true" but code treats absence as false — doc drift. | company app → ProductStoreDetails ("Inventory and preorder"). asbeauty not set. Value `true`. | `oms/service/co/hotwax/oms/product/InventoryServices.xml:123-127,167-182` |
| **PRE_ORDER_GROUP_ID** 📱 ProductStoreSetting · App-only | Names the facility group holding the pre-order (virtual) inventory pool. | Written by the company app; no backend reader in this codebase. Consumed by external multi-channel inventory. | company app → ProductStoreDetails. | `company/src/views/ProductStoreDetails.vue:293` |
| **REL_PREORD_ROUGRP_ID** 📱 ProductStoreSetting · App-only *(audit-found)* | Routing group id used when releasing pre-orders back into routing. Companion to `PRE_ORDER_GROUP_ID`. | App-only; no backend reader here. | company app → ProductStoreDetails:294. | `company/src/views/ProductStoreDetails.vue:294` |
| **EXCLUDE_ODR_BKR_DAYS** 📱 ProductStoreSetting · App-only *(audit-found)* | Fulfillment app maps it to the reject param that excludes a facility from re-brokering for N days. | App gate only. | company + fulfillment apps. | `fulfillment/src/store/productStore.ts:67`; consumed at `oms OrderServices.xml:1148-1160` |
| **BRK_SHPMNT_THRESHOLD** 📱 ProductStoreSetting · App-only *(audit-found)* | Threshold governing when an order is broken into multiple shipments. | App/brokering config only. | company app → AddConfigurations. | `company/src/views/AddConfigurations.vue:121` |
| **STORE.is.backorder.enabled** ⚪ SystemProperty · Inert | Per-store back-order flag (`PreorderConfig`). | **Inert here.** Seeded (two conflicting rows: `Y` and blank), no reader in OMS or PWA. Legacy OFBiz flag. | Data-load only. asbeauty seeds `Y`. | seed only: `ofbiz-oms-udm/data/EB_Ext_ConfigurationData.xml:11,27` |

---

## 4. Fulfillment: pick / pack / ship / reject

**The workflow.** A warehouse worker picks the items, packs them, and ships. The
fulfillment PWA app drives most of this, so **most fulfillment settings are App-only** —
the server just executes the parameters the app sends it. A few settings do run on the
server (carrier fallback, weight UOM, packing-slip identifier, customer notification).

**Server-enforced (🔧):**

| Setting | Fires at → decides | If left blank | Set via / asbeauty | Code |
|---|---|---|---|---|
| **FULFILL_NOTIF** 🔧 ProductStoreSetting · OPTIONAL | A packed shipment syncs to Shopify. `Y` → Shopify emails the customer the shipping/tracking notice. | **Wrong default.** Customer is silently *not* emailed the shipment notice. No error. | company app → ProductStoreDetails ("yes/no"). asbeauty sets via UI. | `shopify-oms-bridge/service/.../ShipmentServices.xml:100-110` |
| **PRDT_IDEN_PREF** 🔧 ProductStoreSetting · OPTIONAL | Packing slip is generated; also shown to the packer. JSON picking which identifier (SKU/UPC) to print. | **Wrong default.** Identifier column falls back/blank on the slip. | fulfillment app + company app. e.g. `{"primaryId":"SKU","secondaryId":"productId"}` | `poorti/template/pdf/PackingSlipContent.xsl-fo.ftl:6-7` |
| **PICK_LST_PROD_IDENT** 🔧 ProductStoreSetting · OPTIONAL | Picklist **CSV** is downloaded. Which identifier prints per pick line. | **Wrong default.** Falls back to product internal name instead of the chosen id. | Data-load. e.g. `SKU` | `poorti/service/.../FulfillmentServices.xml:1408-1419` |
| **DEFAULT_CARRIER** 🔧 ProductStoreSetting · OPTIONAL | (Fires at import, seeds the carrier the packer later ships with — see §1.) | Carrier `_NA_`. | Data-load. | `prepareTransformedShopifyOrderPayload.groovy:451-455` |
| **shipment.default.weight.uom** 🔧 SystemProperty · OPTIONAL | A package is built. Weight UOM when the facility has no default. | **Wrong default.** Package weight UOM null, conversion skipped — carrier weight may be wrong. | Data-load (`shipment`). `WT_lb` | `poorti/service/.../FulfillmentServices.xml:87-95,512` |
| **UNIGATE_ENABLED** 🔧 SystemProperty · CONDITIONAL *(audit-found)* | Wave/ship time. Per-store flag turning on the Unigate carrier-gateway for labels/rates/tracking. | Legacy carrier path used instead (safe). If set `Y`, the `UNIGATE_CONFIG` remote must exist or shipping calls **block**. | Data-load (`productStoreId`). | `poorti/service/.../ShippingServices.xml:1580-1586`, `FulfillmentServices.xml:336-350` |
| **SHP_RATE_QUERY_DATE** 🔧 ProductStoreSetting · OPTIONAL *(audit-found)* | Checkout carrier-rate response. Supplies the query date/time used to build the rate. | Uses current time (testing/override knob). | Data-load. | `shopify-delivery/service/.../InventoryServices.xml:566` |
| **${carrierPartyId}.trackingUrl** 🔧 SystemProperty · CONDITIONAL *(audit-found)* | Order ships. Per-carrier URL template expanded with the tracking number, used in the shipped notification and feed. | **Silently blank** tracking link for that carrier. | Data-load (per carrier, systemResourceId = carrier id). Also read in PWA. | `ofbiz-oms-usl/service/.../OrderServices.xml:1297`; `fulfillment/src/store/carrier.ts:893` |

**App-only (📱) — the fulfillment/BOPIS app reads these; the server never branches on them.** They are enforced entirely client-side, so their effect will not appear in server logs.

| Setting | What it does in the app | If blank (app default) | Reads at |
|---|---|---|---|
| **FULFILL_FORCE_SCAN** 📱 | Packer must barcode-scan each item before packing (hides the Pack button). | N — pack without scanning. | `fulfillment/src/views/Settings.vue:130`, `InProgress.vue:48,205` |
| **FULFILL_PART_ODR_REJ** 📱 | Allows rejecting one item on its own (app sends `maySplit=Y`). | N — rejecting one item rejects the whole order. | `fulfillment/src/views/Settings.vue:149,528`; server reads only the `maySplit` param at `oms OrderServices.xml:948` |
| **FF_COLLATERAL_REJ** 📱 | Rejecting one product cascades to the same product on other orders (app sends `cascadeRejectByProduct=Y`). | N — only the flagged item. | `fulfillment/.../Settings.vue:162`; server reads `cascadeRejectByProduct` param `OrderServices.xml:995-1039` |
| **AFFECT_QOH_ON_REJ** 📱 | Rejection also lowers physical QOH (app sends `updateQOH=true`). | N — only ATP is reduced, physical QOH untouched. | `fulfillment/.../Settings.vue:175`; server reads `updateQOH` param `OrderServices.xml:1076,1216-1218` |
| **FF_DOWNLOAD_PICKLIST** 📱 | Downloads the CSV picklist instead of the PDF. (The CSV path honors `PICK_LST_PROD_IDENT`.) | N — fetches the PDF picklist. | `fulfillment/src/store/order.ts:1159-1173` |
| **DISABLE_SHIPNOW** 📱 *(audit-found)* | Hides the "Ship Now" action. | Shown. | `fulfillment/src/views/OrderDetail.vue:167`, `Completed.vue:52` |
| **DISABLE_UNPACK** 📱 *(audit-found)* | Hides the "Unpack" action on packed shipments. | Shown. | `fulfillment/src/views/OrderDetail.vue:203`, `Completed.vue:159` |
| **USE_RES_FACILITY_ID** 📱 *(audit-found)* | Open-orders queue filters by reservation facility vs facility. | facilityId. | `fulfillment/src/views/OpenOrders.vue:307` |
| **SHOW_SHIPPING_ORDERS** 📱 *(audit-found)* | Whether ship-from-store orders appear in the fulfillment queue. | Off. | `company/src/views/ProductStoreDetails.vue:321` |
| **RECEIVE_FORCE_SCAN** 📱 *(audit-found)* | Forces barcode scan during PO/transfer receiving. | Off. | `company/src/views/ProductStoreDetails.vue:313` |
| **PRINT_PACKING_SLIPS / PRINT_PICKLISTS / ENABLE_TRACKING** 📱 | BOPIS app toggles for packing-slip generation, picklist printing, tracking capture. | Off. | `accxui/apps/bopis/src/store/productStore.ts`, `company ProductStoreDetails.vue:322-324` |
| **shipment.default.boxtype** 📱 SystemProperty *(corrected)* | Fulfillment app's default box type when adding a box at pack. | App fetches it; server hardcodes `YOURPACKNG` and never reads the property. | `fulfillment/.../InProgress.vue:933`, `OrderDetail.vue:923` |

---

## 5. BOPIS (buy online, pick up in store)

**The workflow.** A shopper chooses "pick up in store". The order is flagged as pickup at
import, routed to a store, and a store associate prepares it, then hands it over when the
customer arrives. The customer-facing BOPIS/reroute app drives most associate actions, so
several BOPIS settings are App-only.

| Setting | Fires at → decides | If left blank | Set via / asbeauty | Code |
|---|---|---|---|---|
| **storepickup.item.property.name** 🔧 SystemProperty · CONDITIONAL | Order import. Which Shopify line-item property name marks an item as store-pickup. Matching items → STOREPICKUP, carrier `_NA_`, pickup facility. | **Wrong default.** Only the hardcoded name `pickupstore` is detected. A retailer using a different property name gets pickup items treated as ship-to-home. | Data-load (`ShopifyServiceConfig`). No screen. e.g. `_pickupstore` | `prepareTransformedShopifyOrderPayload.groovy:524,574-577` |
| **BOPIS_SHIP_MTHDS** 🔧 ProductStoreSetting · CONDITIONAL | Shopify calls the OMS rate endpoint for a pickup-only cart. `Y` → also offer normal delivery rates next to "In-Store Pick Up". | **Wrong default** for the shopper: only "In-Store Pick Up" is offered, no delivery option. | Data-load. asbeauty not set. Value `Y`. | `shopify-delivery/service/.../InventoryServices.xml:201-221` |
| **RECEIVE_BY_FULFILL** 🔧 ProductStoreSetting · CONDITIONAL | A ship-to-store transfer order (replenishment) is approved. `true` → approval **blocks** unless an outbound shipment already shipped. | **No effect** (safe). Shipment check skipped; transfer approves freely. | Data-load only. asbeauty/base = `false`. | `oms/service/.../TransferOrderServices.xml:458-477` |
| **BOPIS_PART_ODR_REJ** 📱 ProductStoreSetting · App-only | BOPIS app: associate may reject only some items of a pickup order. | Whole-order reject: one rejected item cancels the whole pickup order. Conservative but working. | BOPIS Settings.vue + company app. | `accxui/apps/bopis/src/components/RejectOrderItemModal.vue:81-114` |
| **HANDOVER_PROOF** 📱 ProductStoreSetting · App-only | BOPIS app: shows a "Proof of Delivery" button (signature/photo) at handover. | Silently off — no proof captured; handover still recorded. | BOPIS Settings.vue + company app. Base default `false`. | `accxui/apps/bopis/src/store/productStore.ts:91-93` |
| **CUST_ALLOW_CNCL** 📱 ProductStoreSetting · App-only | Customer self-service page: offer "cancel before fulfillment". | Silently off — option not offered. | BOPIS Settings.vue + company app. | `accxui/apps/bopis/src/store/productStore.ts:44-45,99-103` |
| **CUST_PCKUP_UPDATE / CUST_DLVRADR_UPDATE / CUST_DLVRMTHD_UPDATE** 📱 ProductStoreSetting · App-only *(audit-found)* | Customer self-service edit page: allow changing pickup location / delivery address / delivery method before fulfillment. | Each option not offered. | company onboarding + BOPIS app. | `company/src/views/ProductStoreOnboarding.vue:2149-2161` |
| **DEFULT_PKG_BOPIS_ORD** 🌐 ProductStoreSetting · External | Default box type for pickup-order shipments in the legacy OFBiz packing flow. | No default box applied. No reader in this codebase (OMS or PWA); consumed by external OFBiz packing. | company app (textarea). asbeauty `BOPISPACKNG`. | seed: `ofbiz-oms-udm/data/EK_Ext_StoreProductStoreData.xml:43` |

---

## 6. Returns, refunds & appeasements

**The workflow.** A customer returns goods or gets a refund. Shopify sends a refund/return
event. The OMS decides: is this a real return (goods come back and restock) or an
"appeasement" (money back, no goods). Separately, nightly accounting feeds export returns
and appeasements to finance, with optional go-live date floors.

| Setting | Fires at → decides | If left blank | Set via / asbeauty | Code |
|---|---|---|---|---|
| **RTN_RSTCK_FAC** 🔧 ProductStoreSetting · CONDITIONAL | A refund/return is processed and the Shopify location maps to `_NA_` (or none). The **fallback restock facility** for returned goods. | **Blocks** that return: "RTN_RSTCK_FAC is missing" and the return does not finish — *only* when the location is unresolved. If Shopify's location maps to a real facility, this is never needed. | company app → ProductStoreDetails ("Returns and cancellation"). Seeded blank. e.g. `STORE` | `shopify-oms-bridge/script/.../processShopifyRefund.groovy:78-82`, `createShopifyCompletedReturn.groovy:110-114,159-164` |
| **returns.since.return.date** 🔧 SystemProperty · OPTIONAL | Nightly returns finance feed. Excludes returns with returnDate on/before this cutoff. | **No effect** — feed includes all eligible returns. Set to go-live date to skip already-booked history. | Data-load (`USL_FINANCIAL_FEED`). Seeded blank. | `ofbiz-oms-usl/service/.../FinancialFeedServices.xml:893,931` |
| **returns.since.entry.date** 🔧 SystemProperty · OPTIONAL | Nightly returns feed. Same, on OMS entry date. | No effect (all included). | Data-load. Seeded blank. | `FinancialFeedServices.xml:905,933` |
| **returns.since.completed.date** 🔧 SystemProperty · OPTIONAL | Nightly returns feed. Same, on completion datetime. | No effect (all included). | Data-load. Seeded blank. | `FinancialFeedServices.xml:899,937` |
| **appeasement.since.return.date** 🔧 SystemProperty · OPTIONAL | Nightly appeasements feed. Excludes appeasements older than this. | No effect (all included). | Data-load. Seeded blank. | `FinancialFeedServices.xml:36,63` |
| **appeasement.since.entry.date** 🔧 SystemProperty · OPTIONAL | Nightly appeasements feed. Same, on OMS entry date. | No effect (all included). | Data-load. Seeded blank. | `FinancialFeedServices.xml:42,65` |
| **sales.since.date** 🔧 SystemProperty · OPTIONAL *(audit-found)* | Nightly **sales** finance feed. Start-of-window date filter (sibling of the returns/appeasement floors). | No effect (all included). Set to go-live date or the first feed exports the full sales history. | Data-load (`USL_FINANCIAL_FEED`). | `FinancialFeedServices.xml:511` |
| **RETURN_DEADLINE_DAYS** 📱 ProductStoreSetting · App-only | The return window (days after purchase a return is allowed). | Silently off in OMS; the returns app uses its own default. | company app → ProductStoreDetails ("Returns and cancellation"). e.g. `30` | `company/src/views/ProductStoreDetails.vue:284` |

> Note: the six `*.since.*` feed floors default to blank on purpose. Blank means "send
> everything." At go-live, set them to the cut-over date, or the first feed run exports the
> retailer's entire history to finance.

---

## 7. Inventory, QOH & cycle counts

**The workflow.** Store staff do cycle counts in the Inventory Count app to correct stock.
The count app is a PWA, so its settings are App-only. One QOH-visibility setting is an
overloaded trap (see Traps).

| Setting | Fires at → decides | If left blank | Set via | Code |
|---|---|---|---|---|
| **INV_FORCE_SCAN** 📱 ProductStoreSetting · App-only *(audit-found)* | Counter records a product. Whether a barcode scan is required to add/identify the item. | Add by search/tap allowed. (Parsed into app state; see Traps — enforcement is thin.) | Inventory Count app → Settings. | `inventory-count/src/stores/productStore.ts:365,378` |
| **BARCODE_IDEN_PREF** 📱 ProductStoreSetting · App-only *(audit-found)* | Counter scans a barcode. Which identifier the scan is matched against. | Falls back to default identifier handling. | Inventory Count app → Settings. e.g. `SKU` | `inventory-count/src/stores/productStore.ts:375`, `views/Settings.vue:284` |
| **INV_CNT_VIEW_QOH** ⚪ ProductStoreSetting · Inert (as a *setting*) | *Intended:* show/hide system QOH on the counting screen (blind vs informed count). | **Trap.** The count app gates QOH by a **SecurityPermission** of the same name, not by this ProductStoreSetting. Setting the value alone changes nothing a counter sees. | company app prefills a toggle; real control is the permission. asbeauty seed `false`. | setting seed `ofbiz-oms-udm/data/EK_Ext_StoreProductStoreData.xml:41`; permission checked `poorti/.../InventoryCountServices.xml:991` |

---

## 8. Product catalog sync & imagery

**The workflow.** Shopify products sync into the OMS. The OMS derives a product type,
optionally tracks the Shopify category, and stores identifiers. Two real settings tune this;
the imagery/category properties are legacy OFBiz and inert here (see Traps).

| Setting | Fires at → decides | If left blank | Set via / asbeauty | Code |
|---|---|---|---|---|
| **UPDATE_PRODUCT_TYPE** 🔧 ProductStoreSetting · CONDITIONAL | A Shopify product update syncs. Whether an **existing** product's OMS product type may be overwritten from Shopify. (Name is inverted: `true` = *skip* the overwrite.) | **Wrong default.** Every sync re-derives and overwrites the product type — a manual re-classification in OMS gets silently reverted. Sync never errors. | Data-load. asbeauty = **true** (both stores). | `shopify-oms-bridge/script/.../syncShopifyProduct.groovy:607-609,651,888` |
| **PROD_CAT_ATTR** 🔧 ProductStoreSetting · CONDITIONAL | A Shopify product syncs. `Y` → track the Shopify category and store it as a `Category` product attribute. | **Silently off.** Category is not stored; a category change alone does not trigger an update. Rest of sync runs normally. | Data-load. asbeauty does not set it (feature off). Value `Y`. | `syncShopifyProduct.groovy:95-96,687-699` |

---

## 9. Customer communication & order-status links

**The workflow.** The OMS emails the customer on order events (ready for pickup, shipped,
cancelled). Each email can carry a self-service "update / reroute your order" link, signed
with a short-lived token.

| Setting | Fires at → decides | If left blank | Set via / asbeauty | Code |
|---|---|---|---|---|
| **reRouteOrder.baseUrl** 🔧 SystemProperty · CONDITIONAL | Any customer order-event email is built. Base URL of the self-service reroute app; the link is `<baseUrl>/<token>?oms=<omsUrl>`. | **Silently off** in email (link omitted; the email still sends). Called directly over REST it **blocks**. Seed default is a shared HotWax app, so it is effectively always present. | Data-load (`HWCApp`). e.g. `https://reroute-fulfillment.hotwax.io` | `oms/service/.../email/EmailServices.xml:200-203,354-397` |
| **order.update.url.jwt.token.expireTime** 🔧 SystemProperty · OPTIONAL | Same email. How long (seconds) the order-update link stays valid. | **Wrong default**, safe: hard-coded 48h fallback; a bad value logs a warning and also uses 48h. | Data-load (`HWCApp`). `172800` (48h) | `EmailServices.xml:363-366,384-392` |
| **FULFILL_NOTIF** 🔧 ProductStoreSetting · OPTIONAL | (Also here: it decides whether Shopify sends the *shipment* email — see §4.) | Customer not emailed the shipment notice. | company app. | `shopify-oms-bridge/service/.../ShipmentServices.xml:100-110` |
| **RF_SHIPPING_METHOD** 📱 ProductStoreSetting · App-only *(audit-found)* | Shipping method used when an order is rerouted (pairs with `reRouteOrder.baseUrl`). | App/reroute default. | company app → ProductStoreOnboarding:2153. | `company/src/views/ProductStoreOnboarding.vue:2153` |
| **${carrierPartyId}.trackingUrl** 🔧 SystemProperty · CONDITIONAL | (Also here: builds the tracking link in the shipped notification — see §4.) | Blank tracking link. | Data-load per carrier. | `ofbiz-oms-usl/.../OrderServices.xml:1297` |

---

## 10. Outbound feeds & ERP / finance handoff

**The workflow.** Scheduled jobs export data to the retailer's ERP and finance systems:
sales-order items with PO/promised-date updates, and operational reports (unfillable,
pre-order, back-order, variance). Delivery is by FTP or email.

**Important:** the finance feeds (§6 `*.since.*`, `sales.since.date`) run **inside this OMS**
(`ofbiz-oms-usl`). But the report/feed **delivery** configs below (`*.send.to.config`,
`*.ftp.export.folder.name`, `*.send.to.email.addresses`, and the `FTP_CONFIG` block) are
read by the **external OFBiz report-export engine** (the `hwmapps` app), which is *not* in
this codebase. We can show they are configured and what they intend, but not verify their
runtime effect from here. Treat them as 🌐 External. The behavior of one such family was
confirmed against an equivalent reader (`PreorderFeeds.java`) that reads the folder-name
only when the channel is FTP.

| Setting | Intent → decides | asbeauty | Notes | Seed |
|---|---|---|---|---|
| **ftp.export.ftpResourceName** 🌐 | Which credential block (`FTP_CONFIG`) all report FTP uploads authenticate against. | `FTP_CONFIG` | ⚠️ Seeded `FTP_CONFIG` in one file, blank in two others — a data-load **ordering conflict** that can reset it to blank. | `ofbiz-oms-udm/data/EB_Ext_ConfigurationData.xml:21` |
| **updated.po.salesorderitem.send.to.config** 🌐 | Deliver the updated-PO-sales-item feed by FTP or EMAIL (outbound half of the PO promised-date round-trip). | `FTP` | Core ERP handoff at asbeauty. Paired import: `IMP_ORD_ITEM_UPD`. | `EB_Ext_ConfigurationData.xml:23` |
| **updated.po.salesorderitem.ftp.export.folder.name** 🌐 | FTP subfolder for that feed. | `updateSalesItemFeed` | ⚠️ Blank in `ED_Ext` — load-order conflict with `EB_Ext`. | `EB_Ext_ConfigurationData.xml:22` |
| **update.salesitem.podetail.send.to.config** 🌐 | Deliver the sales-item PO-detail feed by FTP/EMAIL (second PO round-trip; import `IMP_ORD_ITM_PO_DTL`). | `FTP` | | `EB_Ext_ConfigurationData.xml:25` |
| **update.salesitem.podetail.ftp.export.folder.name** 🌐 | FTP subfolder for the PO-detail feed. | `updateSalesItemPoDetail/` | Consistent across seed files (no conflict). | `EB_Ext_ConfigurationData.xml:24` |
| **updated.promised.date.items.send.to.config** 🌐 | Deliver the changed-promised-date feed by FTP/EMAIL. | blank | Inactive at asbeauty. Enable per client. | `ED_Ext_ExtCommerceData.xml:258` |
| **updated.promised.date.items.ftp.export.folder.name** 🌐 | FTP subfolder for that feed. | blank | Unused while channel blank. | `ED_Ext_ExtCommerceData.xml:259` |
| **unfillable.items.report.send.to.config** 🌐 | Deliver the unfillable-items report by FTP/EMAIL. | `EMAIL` | **Active** at asbeauty. Recipients = a separate `.send.to.email.addresses`. | `EA_Ext_CommonSystemPropertyData.xml:86` |
| **unfillable.items.report.ftp.export.folder.name** 🌐 | FTP subfolder (only if channel = FTP). | blank | Unused while delivery = EMAIL. | `EA_Ext_CommonSystemPropertyData.xml:94` |
| **unfillable.hold.report.send.to.config** 🌐 | Deliver the unfillable-on-hold report by FTP/EMAIL. | `EMAIL` | **Active** at asbeauty. | `EA_Ext_CommonSystemPropertyData.xml:92` |
| **unfillable.hold.report.ftp.export.folder.name** 🌐 | FTP subfolder (only if channel = FTP). | blank | Unused while delivery = EMAIL. | `EA_Ext_CommonSystemPropertyData.xml:101` |
| **updated.preorder.report.send.to.config** + **...ftp.export.folder.name** 🌐 | Deliver / folder for the updated-pre-order report. | blank | Inactive at asbeauty. | `EA_Ext_CommonSystemPropertyData.xml:111-112` |
| **updated.backorder.report.send.to.config** + **...ftp.export.folder.name** 🌐 | Deliver / folder for the updated-back-order report. | blank | Inactive at asbeauty. | `EA_Ext_CommonSystemPropertyData.xml:117-118` |
| **variance.record.report.send.to.config** + **...ftp.export.folder.name** + **...send.to.email.addresses** 🌐 | Deliver / folder / recipients for the cycle-count variance report. | `EMAIL` / blank / blank | Channel EMAIL but **recipients blank** → report emails nobody until filled in. | `EA_Ext_CommonSystemPropertyData.xml:90,99,105` |

**The `FTP_CONFIG` credential block** (all 🌐 External, read by the OFBiz feed engine):
`ftp.server.hostname / username / password / port / archive.dir / importPath`, plus
`instance.downloadDir`, `ftp.fileNameRegex`, `report.csv.file.output.location`, and the
failure-alert list `ftp.put.file.fail.sendTo.email.addresses`. Seeded blank at asbeauty
(filled per environment). Source: `ofbiz-oms-udm/data/EB_Ext_ConfigurationData.xml:12-19`,
`EA_Ext_CommonSystemPropertyData.xml:82,85`.

---

## Reads-true gaps & traps

These look configurable but do **not** do what their name or screen implies. Do not
configure them expecting a result; flag them for cleanup.

**Inert — no reader anywhere (⚪):**

| Setting | Why it is a trap | Evidence |
|---|---|---|
| **RATE_SHOPPING** ProductStoreSetting | Seeded `Y` for asbeauty and shown as a company toggle, but **no code reads it**. Rate-shopping is actually gated by `UNIGATE_ENABLED` + the `AUTO_SHIPPING_LABEL` facility group. | no reader; gating at `poorti/.../FulfillmentServices.xml:328-357` |
| **REJ_ITM_CC_CRT** ProductStoreSetting | "Create cycle count for rejected items" toggle in the company app, but the reject service creates **no** cycle count. Seeded `false`. | `oms/.../OrderServices.xml:1070-1232` creates none |
| **DIS_REJ_NOTI_ON_CNCL** ProductStoreSetting | "Suppress reject notification on cancel" — seeded `N`, read by nothing. Docs say it should become backend default behavior. | seed only |
| **shipment.default.dimension.uom** SystemProperty | Seeded `LEN_in` but package code hardcodes `LEN_in` and never reads the property. | hardcode `poorti/.../FulfillmentServices.xml:182` |
| **AUTO_REJ_IDLE_ORD** ProductStoreSetting | Company app writes an idle-hours number, but no job/service in this codebase consumes it (likely an out-of-scope routing job). App-only at best. | `company/.../ProductStoreDetails.vue:286` |
| **STORE.is.backorder.enabled** SystemProperty | Legacy per-store back-order flag; two conflicting seed rows; no reader. | seed only |
| **reactivate.product.from.receipt** SystemProperty | Seeded `Y`; consumed only by external OFBiz receiving, not in this codebase. | seed only |

**Overloaded / misleading:**

| Setting | Trap |
|---|---|
| **INV_CNT_VIEW_QOH** | Exists as *both* a ProductStoreSetting and a SecurityPermission. The count app enforces QOH visibility via the **permission**; the setting value alone does nothing at runtime. Set the permission, not (just) the setting. |
| **UPDATE_PRODUCT_TYPE** | Name is inverted — `true` means *skip* the product-type overwrite. Enum comment vs behavior is easy to misread. |
| **HOLD_PRORD_PHYCL_INV** | Enum comment says "default true"; code treats an absent row as **false**. Set the row explicitly if you want the hold. |
| **order.rounding** | The seeded value `ROUND_HALF_UP` is not a valid Java `RoundingMode` constant, so `valueOf` always throws and the code silently uses `HALF_UP`. The seed is a no-op; use a real constant name (e.g. `HALF_UP`) if you need to change it. |

---

## Method & confidence

- **Source of truth: code**, read in the asbeauty deployment — Moqui backend
  (`/Users/anilpatel/maarg-sd/asbeauty/runtime/component`) and PWA operator apps
  (`/Users/anilpatel/pwa-sd`). Real values are quoted from asbeauty's loaded data
  (`asbeauty-maarg/data/ASBeauty*.xml`) and seed files.
- **No live system was booted.** Every "if blank" behavior comes from reading the actual
  null/default branch, not from running it. Each setting's requiredness and failure mode
  was checked a second time against the code.
- **Coverage:** ~85 distinct settings across the two entities, plus a completeness audit
  that found 19 more the first pass missed (marked *audit-found*). This is believed
  complete for these two entities in the asbeauty component set; a different client's
  component set (e.g. extra integrations) could add a few more.
- **Honesty limits:** 🌐 External and 📱 App-only settings are enforced in code we can
  point to but do not fully execute here (the OFBiz report engine and the PWA apps). Their
  *intent* is from the code and screen; their end-to-end effect should be confirmed with a
  live run if it is business-critical.
