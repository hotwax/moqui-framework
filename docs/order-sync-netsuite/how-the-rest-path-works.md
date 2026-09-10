# How the REST path works

This is the new order sync. It creates the sales order in NetSuite through the
NetSuite REST record API and writes NetSuite's id back onto the order at once.
No file, no SFTP, no NiFi.

Built 10 September 2026. gorjana-maarg PR #333, on top of mantle-netsuite-connector
PR #398.

## Two layers

The connector holds the wire format. Two services, `create#NetSuiteCustomer` and
`create#NetSuiteSalesOrder`, take flat values, build NetSuite's JSON, post it, and
return the new id. They know no gorjana field.

gorjana-maarg holds the rules. Three views pick the orders. Four services read the
order, apply the rules the CSV feed and the gorjana script apply today, and call the
connector.

## The picker

`NetSuiteEligibleOrderView` lists the orders to send. An order is in it when:

1. It is a sales order with a Shopify order id and no NetSuite order id.
2. Every item has a NetSuite product id.
3. It is not an exchange whose return has no credit memo in NetSuite yet.
4. It is not an AfterShip order without a return behind it.
5. It is not a POS order paid less than its total.

The bill-to customer no longer needs a NetSuite customer id. The old feed never saw
such an order. The new path creates the customer first.

The view also says whether the order is POS completed only. Those orders wait one
hour before they go, so their payment rows have time to settle.

## The four services

`sync#EligibleNetSuiteOrders` is the scheduled job. It reads the view, web orders
first, then POS completed orders older than an hour, and runs one order at a time.
Each order runs in its own transaction. An order that fails rolls back alone, stays
in the view, and is tried again next run.

`sync#NetSuiteOrder` does one order. If the order already has a NetSuite id it stops.
If the bill-to customer has no NetSuite id it creates the customer. Then it creates
the sales order and writes the NetSuite id onto the order.

`create#NetSuiteCustomerFromParty` sends what the customer feed sends today. The
Shopify customer id is the external id. If NetSuite says the customer already
exists, the service reads the id back by that external id and records it. So a lost
answer on an earlier run cannot block the order.

`create#NetSuiteSalesOrderFromOrder` builds the order. It reads the same views the
feed reads, applies the same rules, and sends every custom field in one map keyed
by NetSuite's own field id. If NetSuite says the order already exists, it reads the
id back the same way.

## What the order carries

Standard fields: external id, customer, form, subsidiary, location, department,
date, order name, email, note, shipping method, shipping cost, shipping tax code,
ship-to and bill-to addresses, and the lines.

Custom fields on the header: the OMS order id, the Shopify order id, the sales
channel, the order total, the customer deposit flag, the kit flag, the exchange
flags and ids, the gift wrap option, the store credit and gift card amounts, the
Happiness Guarantee employee fields, and the department mirror.

Custom fields on a line: the OMS line id, the fulfilment tag, the engraving font
and text, the gift wrap text, and the final sale flag. A discount line carries its
line type instead.

The field ids came from three sales orders the feed made in the sandbox and from
NetSuite's custom field catalog. The record version lists them.

## What is missing

Five feed columns have no known NetSuite field: ship booklets, the exchange credit
per line, the line's item location, the gift card line's shipping method, and the
phones. They are left out. The CSV import map inside NetSuite would settle them.

## Proved on the sandbox

Order M121345 went through. Its customer already existed, the id was read back, the
sales order became 70687326 with every field as intended. A second run sent nothing.
The scheduled job with a limit of one sent the next order, M104906, as 70687426.

Not yet proved, because no local order has it: exchange orders, discount lines, the
POS store address fallback, kit products, and the department from the facility.
AvaTax was off in the sandbox during the test, so tax was zero.
