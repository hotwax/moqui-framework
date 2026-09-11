# How the REST path works

This is the new order sync. It creates the sales order in NetSuite through the
NetSuite REST record API and writes NetSuite's id back onto the order at once.
No file, no SFTP, no NiFi.

Built 10 and 11 September 2026. The plan is issue hotwax/mantle-netsuite-connector#400. The
code is gorjana-maarg PR #333, on top of mantle-netsuite-connector PR #398 (the wire format,
on #389) and PR #399 (the order push).

## Two layers

The connector holds the wire format. Two services, `create#NetSuiteCustomer` and
`create#NetSuiteSalesOrder`, take flat values, build NetSuite's JSON, post it, and
return the new id. They know no gorjana field.

gorjana-maarg holds the rules. Three views pick the orders. Four services read the
order, apply the rules the CSV feed and the gorjana script apply today, and call the
connector.

## The business problem the queue solves

On a busy day gorjana takes orders faster than NetSuite accepts them. The backlog is normal.
The queue has to choose which orders go first and how many at a time.

Orders brokered to a facility that NetSuite's warehouse system runs go first, as soon as
possible. The warehouse cannot pick what NetSuite does not have. POS completed orders are
done deals; the customer has the goods. They can wait for quiet hours. One list at a time:
the queue finishes the list it has before a new one is made, so no order lands in two files.

## Who chooses the orders

A rule group, the same thing Rails uses. A person sets the rules as data. The system queries
a view with each rule's conditions and writes the chosen order ids to a file. A queue then
sends them one at a time.

The two rules today:

1. Orders with a ship group at a facility in the `NETSUITE_FULFILLMENT` group, sorted by
   priority then order date. Which facilities are in the group is data; add a warehouse to
   the group and the rule follows. Counter sales at such a facility are not the warehouse's
   work; the rule leaves them to rule 2.
2. POS completed orders, only with the room the first rule left, oldest first.

An order matched by both goes with the first.

"Room" is a number. The job says how long one run's list should keep the queue busy,
ten minutes today. The run knows how fast the queue sends, from its own history. Rule 1
takes every warehouse order; its orders cost minutes. Rule 2 may take only what is left
of the ten minutes. On a busy day the warehouse fills the run and POS orders wait. On a
quiet evening rule 1 takes seconds and POS orders fill the rest. One job does both.

The view is `NetSuiteEligibleOrderView`. It answers the questions a rule cannot, because
they need joins, and every order must pass them:

1. It is a sales order with a Shopify order id and no NetSuite order id.
2. Every item has a NetSuite product id.
3. Every exchange credit on it reaches a NetSuite credit memo. The exchange credit payment
   row names the customer return invoice it spends, and that invoice carries the memo id
   once NetSuite has made it. An exchange order is not sent before its memo exists.

The fourth question, do the payments cover the total, is a sum, and a sum cannot be a
condition of a view. The per-order service asks it: authorized plus settled payment rows
against the grand total, on every channel. An unpaid order is skipped with a message and
offered again next run. A zero-value order has nothing to cover and goes.

The bill-to customer no longer needs a NetSuite customer id. The old feed never saw
such an order. The new path creates the customer first.

A rule condition may name any plain field of the view: order date, entry date, status,
priority, sales channel, store, grand total, party, Shopify order id, the ship group's
shipping method, facility and facility group. The three counts the view computes are its
own conditions and cannot be rule conditions.

## The chain

A job runs the rule group. For each active rule, the connector's `run#NetSuiteOrderPushRule`
reads the rule's conditions from the database, applies them to the view through the entity
engine, and writes the order ids to a CSV file logged against the MDM config
`MDM_NS_SO_REST`. The MDM queue calls `sync#NetSuiteOrder` once per record.

Each rule can carry three settings, as data on the rule: how many files its orders split
into, the most orders it queues in one run, and whether it takes only the room the rules
before it left. The queue runs every file of a run at the same time, one order at a time
inside each file. So three files are three orders in flight. "Room" counts the files that
run at once: on a config that runs one file at a time, the same orders take three times
the minutes.
Today rule 1 splits into three files with no limit; rule 2 into two files, at most 300 a
run. Both numbers are placeholders to tune against what NetSuite accepts.

The job paces itself. A run that finds a file still pending or running on the config writes
nothing. A run that queued orders works out how long the queue needs, from the config's own
history of finished files and the number of files running at once, and sets its own next
run time to then. So the job's cron only says how often it looks. Set it to a minute or two
in production; the gap after the last file is at most that. A person who wants to hold the
job pauses it.

`sync#NetSuiteOrder` does one order. If the order already has a NetSuite id it stops.
If its payments do not cover its total it stops with a message. If the bill-to customer
has no NetSuite id it creates the customer. Then it creates the sales order and writes
the NetSuite id onto the order.

`create#NetSuiteCustomerFromParty` sends what the customer feed sends today. The
Shopify customer id is the external id.

`create#NetSuiteSalesOrderFromOrder` builds the order. It reads the same views the
feed reads, applies the same rules, and sends every custom field in one map keyed
by NetSuite's own field id.

If NetSuite answers that the customer or the order already exists, that is a success,
not a failure. The goal is reached: the record is there. The connector reports it as a
known outcome, the service reads the id back by the external id, writes it, and the
record says "already in NetSuite as {id}; recorded". So a lost answer on an earlier run,
or an order in two files by accident, costs nothing and NetSuite never gets two orders
for one OMS order.

The rule service reads only order ids from the view, one row per order, in pages, and
stops as soon as the rule has what it wants. Orders an earlier rule of the same run took
are left out by the database, not by the service. The query is built as XML actions of
the service; only the writing of a page to the files is a script.

A failed order goes to the MDM error file. The view still lists it, so the next run
picks it up again.

## Taking an order back out

A sales order can be deleted in NetSuite only while nothing has been made from it: no
fulfillment, no invoice, no deposit, no return. After that NetSuite refuses and nothing
changes. The connector's `delete#NetSuiteSalesOrder` looks first: it asks NetSuite which
transactions were made from the order. Any found, it does not try the delete; it names
them and stops. Only an order with nothing linked is deleted. Three outcomes: deleted,
already gone, or linked. The delete is permanent. On the OMS side the order's NetSuite id
must be expired too, or the queue never offers the order again.

All fourteen rule scenarios in `test-scenarios.md` ran on 11 September against a real hour
of production orders; every rule did what its rows say.

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

On 11 September the whole chain ran on ten local orders brokered to the warehouse. The
rule group chose them into three files; the queue ran the three files together and
finished in 36 seconds; ten sales orders were created in NetSuite, two customers were
created and two found already there; every order got its NetSuite id at once. No
errors. About 50 orders a minute across three files from this machine.


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
