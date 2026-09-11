# How the REST path works

This is the new order sync. It creates the sales order in NetSuite through the
NetSuite REST record API and writes NetSuite's id back onto the order at once.
No file, no SFTP, no NiFi.

Built 10 September 2026. gorjana-maarg PR #333, on top of mantle-netsuite-connector
PR #398 (the wire format) and the connector's order push services.

## Two layers

The connector holds the wire format. Two services, `create#NetSuiteCustomer` and
`create#NetSuiteSalesOrder`, take flat values, build NetSuite's JSON, post it, and
return the new id. They know no gorjana field.

gorjana-maarg holds the rules. Three views pick the orders. Four services read the
order, apply the rules the CSV feed and the gorjana script apply today, and call the
connector.

## Who chooses the orders

A rule group, the same thing Rails uses. A person sets the rule conditions as data: from
which date, which store, which channel. The system queries a view with those conditions and
writes the chosen order ids to a file. A queue then sends them one at a time.

The view is `NetSuiteEligibleOrderView`. It answers the questions a rule cannot, because
they need joins or counts:

1. It is a sales order with a Shopify order id and no NetSuite order id.
2. Every item has a NetSuite product id.
3. Its payments cover its total. Authorized plus settled payment rows, on every channel.
   A zero-value order has no payment rows and nothing to cover.
4. Every exchange credit on it reaches a NetSuite credit memo. The exchange credit payment
   row names the customer return invoice it spends, and that invoice carries the memo id
   once NetSuite has made it. An exchange order is not sent before its memo exists.

The payment rows say who pays for the order. An exchange order that is not yet linked to
its return has no payment that covers it, or one with no invoice, and waits either way.

The bill-to customer no longer needs a NetSuite customer id. The old feed never saw
such an order. The new path creates the customer first.

A rule condition may name any field of the view: order date, entry date, status,
priority, sales channel, store, grand total, payment total, party, Shopify order id,
unmapped item count, exchange waiting count.

## The chain

A job runs the rule group every ten minutes. For each active rule, the connector's
`run#NetSuiteOrderPushRule` reads the rule's conditions from the database, applies them
to the view through the entity engine, and writes the order ids to a CSV file logged
against the MDM config `MDM_NS_SO_REST`. The MDM queue calls `sync#NetSuiteOrder` once
per record.

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

A failed order goes to the MDM error file. The view still lists it, so the next run
picks it up again.

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
The batch then sent M104906 as 70687426.

The rule chain went through end to end. The rule chose M121117, the queue ran the sync,
NetSuite answered that the order already existed there, made by the sandbox feed on
18 August, and the id 70669247 was read back and written onto the order. A second run
of the rule group chose nothing.

The connector's default view was checked against the old SQL template on 3,642 local
orders: the same set, except one order the template wrongly re-chose because it compares
a UTC timestamp to the database's local clock. The view uses the entity engine's date
filter and does not have that fault.

Not yet proved, because no local order has it: exchange orders, discount lines, the
POS store address fallback, kit products, and the department from the facility.
AvaTax was off in the sandbox during the test, so tax was zero.
