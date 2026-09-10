# What order create does today

This is the list of everything the current process does when it creates a sales
order in NetSuite for gorjana. It exists so the new REST path misses nothing.

The current process is a CSV feed. A scheduled job in the OMS picks orders, a gorjana
script shapes them, the file goes to SFTP, a script inside NetSuite imports it, and
the NetSuite id comes back to the OMS half an hour later through NiFi and the old
OFBiz importer.

Seventy steps, in six groups.

## A. Choosing which orders go

1. Only sales orders.
2. The order has a Shopify order id.
3. The bill-to customer already has a NetSuite customer id. Without one the order is
   invisible to the feed. It is not skipped, it is never seen.
4. The order has no NetSuite order id yet.
5. Every item has a NetSuite product id.
6. The order is not already in the sync history.
7. The order date sits inside two optional system properties. Both are empty in
   production.
8. Two jobs run. The web job leaves out POS completed orders. A second job takes only
   those.
9. At most 1000 orders per run.
10. An AfterShip channel order with no return response behind it is skipped.
11. An exchange order whose return has no credit memo in NetSuite yet is skipped and
    tried again next run.
12. Required fields: date, country, customer, location, shipping method, and billing
    country for a non-POS order. An order missing one goes to a separate file and does
    not go to NetSuite.
13. A POS order whose paid amount is below the order total goes to a separate file and
    is tried again next run.

## B. Header values

14. External id is `SO_` plus the Shopify order id. An exchange order appends `-` and
    the return id.
15. Customer is the NetSuite customer id. If it is the blanket customer, the order
    facility's own blanket customer is used instead.
16. Subsidiary comes from the product store.
17. Date is the order date.
18. Order name goes to the "other reference number" field.
19. Email and phones. Phones are formatted before sending.
20. Ship-to and bill-to addresses. Bill-to falls back to ship-to. A POS completed
    order with no country takes the store's own address for both.
21. Shipping method through the shipping method mapping. A mixed cart takes the first
    method that is not POS, pickup or same-day delivery.
22. Shipping cost is the sum of shipping charges.
23. Shipping tax code is AVATAX when the order has a shipping tax line, else Not
    Taxable.
24. Sales channel description, in two custom fields.
25. HotWax order id, in a custom field.
26. Shopify order id, in two custom fields.
27. Order total, in a custom field. NetSuite balances the order to this number on
    save. Send it wrong and NetSuite adds a variance line to make it right.
28. Has customer deposit: true when an exchange payment sits on top of a payment that
    is not a gift card or store credit.
29. Order note, with line breaks and the heart glyph cleaned.
30. Exchange parent order, for an exchange.
31. Exchange from warranty: true when the return channel is one of the four AfterShip
    warranty channels.
32. Exchange from Happiness Guarantee or sellable return: forces the tax code on every
    line.
33. Happiness Guarantee employee return: the employee type, and for a store employee
    the ship-to store.
34. The credit memo id of the return behind an exchange.
35. Gift wrap option.
36. Ship booklets flag.
37. Order with kit: true when any item is a marketing package.
38. Store credit: the settled store credit amount, as a line on the store credit
    redemption item.
39. Exchange credit: the settled exchange credit. A Loop Exchange order computes it
    from the order total minus the upsell amount. Goes on every non-discount line.
40. Gift card payment total, for the customer deposit flow.
41. Valid payment total, authorized plus settled, for the partial payment check.

## C. Line values

42. Item is the NetSuite product id.
43. Quantity and unit price, with the price level set to "as given".
44. Line id is the OMS order item sequence id.
45. Closed when the item is cancelled.
46. Price level from the mapping.
47. Department from the facility. Every non-POS line is forced to department 2.
48. Location from the order facility. Every non-POS line is forced to the warehouse.
49. Item location: the inventory transfer's source facility if one exists, else the
    fulfilling facility. Blank when that facility is not in the NetSuite fulfillment
    group and the item is not yet complete.
50. Tax code is AVATAX when the line has a sales tax row, or the order is an exchange
    from warranty or Happiness Guarantee. Blank on a discount line. NetSuite
    overrides it anyway.
51. Tag: `hotwax-fulfilled` for a completed item; `pos-fulfilled` for a gift card, a
    digital item, a POS completed item, or an item that arrived already complete;
    `hotwax-fulfilled-pos-mixcart` for a completed POS item in a mixed cart.
52. A gift card or digital item gets shipping method 13063.
53. Packing category 4 for a marketing package.
54. Font, text with direction, gift wrap text and final sale, from the item's
    attributes.
55. A discount line per item, from the item's promotion adjustments, on the default
    discount item or the AfterShip POS discount item.
56. A header discount line, from a shipping adjustment with no item, on item 12637.

## D. Files and bookkeeping

57. Three CSV files: valid orders, missing required fields, partial payment.
58. Upload to SFTP.
59. One sync history row per order sent, except partial-payment POS orders.

## E. Inside NetSuite

60. A scheduled script reads the file and runs the CSV import, column by column.
61. On save, a HotWax user event balances the order to the order total with a POS Tax
    Variance line and handles gift card and POS exchange payments.
62. On save, Avalara prices the tax.
63. Several custom fields are filled by the import map or by account scripts. We do
    not have the import map.
64. A failed row is logged by a second script.

## F. After the order exists

65. A script exports the NetSuite id and the HotWax id to SFTP every 30 minutes.
66. NiFi reads that file and posts it to the old OFBiz importer, which writes the
    NetSuite id onto the OMS order. About 3,400 a day in production.
67. Line ids come back the same way.
68. A script creates the customer deposit for gift card and POS payments.
69. Scripts create the invoice: one for web, one each for POS, exchange and gift card.
70. Brokered items, fulfilled items and cancellations are separate feeds. Not part of
    create.

## What this means for the REST path

Two connector services already exist and are proved on the sandbox:
`create#NetSuiteCustomer` and `create#NetSuiteSalesOrder`. They carry the wire format
and nothing else.

Covered by those two: steps 14 to 27, 42 to 48, 55 and 56, and the transport. Any
custom field goes in their `customFields` maps.

Belongs to a new caller in gorjana-maarg: all of A, B and C. That is about 60 rules,
and 34 of them live in the gorjana script today, not in the connector. Step 3 stops
being a reason to skip and becomes a call to `create#NetSuiteCustomer` first.

Falls away: 57 and 58, the files and SFTP. 60 and 64, the CSV import and its error
log. 65 and 66, because the sales order service returns the NetSuite id at once and
the caller writes it onto the order itself. That last one removes a 30-minute round
trip through NiFi and OFBiz.

Stays as it is: 61, 62 and 63. They run on save whatever created the record. The
variance line on our test order proved it.

## Three things we do not know yet

The CSV import map inside NetSuite. It decides which column becomes which field, and
it sets some fields no OMS code writes. Someone with NetSuite access needs to open
the saved import and list it.

The HotWax user event on save. We know it balances to the order total and touches
deposits. Nobody here has read it, and it decides what the caller must send for gift
card and exchange orders.

A released RESTlet in the account called "HC Create Customer and SO". Someone
already built a customer-and-order creator inside NetSuite. We do not know who, when,
or whether anything calls it.
