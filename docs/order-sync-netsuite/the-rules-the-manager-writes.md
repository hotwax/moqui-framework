# The rules the fulfillment manager writes

This is the story of one person: the fulfillment manager at gorjana. She owns the question
"which orders go to NetSuite, in what order, and how many at a time". She does not write code.
She writes rules, as rows of data, and the push job follows them.

What she decides: the rules. What she does not decide: the facts. An order is offered to her
rules only when it is a sales order with a Shopify id, not yet in NetSuite, with every item
mapped to a NetSuite item, and, for an exchange, with its credit memo already in NetSuite.
Those facts live in the picker view and no rule can bend them. Payment is checked at the
moment of sending: an unpaid order waits, with a message, and nothing is sent.

The words she uses map to four small tables:

| Table | What one row is |
|---|---|
| `RuleGroup` | the whole set for one store, and the job that runs it |
| `DecisionRule` | one rule: a name, a place in the order, active or not |
| `RuleCondition` | one line of a rule: a filter (`field operator value`) or a sort field |
| `RuleAction` | one setting of a rule: files, limit, spare capacity only |

The fields she can filter or sort on: `orderDate`, `entryDate`, `statusId`, `priority`,
`grandTotal`, `salesChannelEnumId`, `shipmentMethodTypeId`, `shipGroupFacilityId`,
`facilityGroupId`, `partyId`. The operators: `equals`, `not-equals`, `in`, `not-in`,
`greater-than`, `greater-than-equal-to`, `less-than`, `less-than-equal-to`. A list is a comma
list. A sort is a field name; `-orderDate` sorts newest first.

The story below is her wishes in order. After each wish, the rows that fulfil it. The set
grows until the last step shows it complete.

## Step 1. "Warehouse orders must reach NetSuite as soon as they are approved and brokered."

The warehouse runs on NetSuite WMS. It cannot pick what NetSuite does not have. So every
order with a ship group at the warehouse goes, the moment the job runs, most urgent first.

She names the warehouse as "a facility NetSuite fulfils". That is a facility group, so
tomorrow's second warehouse is one row of data, not a rule change.

| FacilityGroup | facilityGroupId | facilityGroupTypeId | description |
|---|---|---|---|
| | NETSUITE_FULFILLMENT | FULFILLMENT | Facilities whose orders NetSuite fulfils |

| FacilityGroupMember | facilityGroupId | facilityId | fromDate |
|---|---|---|---|
| | NETSUITE_FULFILLMENT | WH | 2026-01-01 |

| RuleGroup | ruleGroupId | productStoreId | groupTypeEnumId | statusId | jobName |
|---|---|---|---|---|---|
| | NS_ORDER_PUSH_GORJANA | STORE | RG_NS_ORDER_PUSH | ATP_RG_ACTIVE | export_NetSuiteOrderPush_GORJANA |

| DecisionRule | ruleId | ruleName | sequenceNum | statusId |
|---|---|---|---|---|
| | NS_ORDER_PUSH_NS_FACILITY | Orders fulfilled by a NetSuite facility | 1 | ATP_RULE_ACTIVE |

| RuleCondition | ruleId | seq | type | fieldName | operator | fieldValue |
|---|---|---|---|---|---|---|
| | NS_ORDER_PUSH_NS_FACILITY | 01 | filter | facilityGroupId | equals | NETSUITE_FULFILLMENT |
| | NS_ORDER_PUSH_NS_FACILITY | 02 | sort | priority | | |
| | NS_ORDER_PUSH_NS_FACILITY | 03 | sort | orderDate | | |

The job runs the group every ten minutes and starts paused, until she says go.

| ServiceJob | jobName | serviceName | cronExpression | paused |
|---|---|---|---|---|
| | export_NetSuiteOrderPush_GORJANA | run#NetSuiteOrderPush | 0 0/10 * * * ? | Y |

## Step 2. "Only from go-live day. The old orders are already in NetSuite from the file feed."

One filter on the order date. She moves the date forward now and then, because the range of
dates the query looks at is the one cost that grows.

| RuleCondition | ruleId | seq | type | fieldName | operator | fieldValue |
|---|---|---|---|---|---|---|
| | NS_ORDER_PUSH_NS_FACILITY | 04 | filter | orderDate | greater-than | 2026-01-01T00:00:00 |

## Step 3. "Send three orders at once. NetSuite lets us run three calls in parallel."

A run writes its chosen orders into files. The queue sends every file of a run at the same
time, one order at a time inside each file. Three files are three orders in flight.

| RuleAction | ruleId | seq | actionTypeEnumId | fieldValue |
|---|---|---|---|---|
| | NS_ORDER_PUSH_NS_FACILITY | 01 | NSOP_FILE_COUNT | 3 |

The queue must run files side by side for this to mean anything. That is the MDM config,
set once by the developer, not a rule.

| DataManagerConfig | configId | importServiceName | executionModeId | priority |
|---|---|---|---|---|
| | MDM_NS_SO_REST | sync#NetSuiteOrder | DMC_ASYNC | 7 |

## Step 4. "Never queue more than the queue can send in ten minutes. Never start a new list while one is still running."

The first is a number on the job: how long one run's list should keep the queue busy. The
run knows the queue's speed from its own history. The second needs no data; a run that
finds a file still pending or running writes nothing and says so.

| ServiceJobParameter | jobName | parameterName | parameterValue |
|---|---|---|---|
| | export_NetSuiteOrderPush_GORJANA | ruleGroupId | NS_ORDER_PUSH_GORJANA |
| | export_NetSuiteOrderPush_GORJANA | productStoreId | STORE |
| | export_NetSuiteOrderPush_GORJANA | dataManagerConfigId | MDM_NS_SO_REST |
| | export_NetSuiteOrderPush_GORJANA | targetMinutes | 10 |

After a run the job sets its own next run time to when the list should be done. Her hold on
the job is `paused`; the job never touches that.

## Step 5. "Store sales must reach NetSuite too, for the books. But never ahead of the warehouse."

A sale handed over at the counter is a done deal; it can go in the evening. So a second rule,
after the first, that may take only the room the first rule left in the ten minutes. On a
busy day the warehouse fills the run and store sales wait. On a quiet evening they pour in.

She also makes rule 1 say what it means: a counter sale at a NetSuite facility is not the
warehouse's work.

| DecisionRule | ruleId | ruleName | sequenceNum | statusId |
|---|---|---|---|---|
| | NS_ORDER_PUSH_POS_COMPLETED | POS completed orders | 2 | ATP_RULE_ACTIVE |

| RuleCondition | ruleId | seq | type | fieldName | operator | fieldValue |
|---|---|---|---|---|---|---|
| | NS_ORDER_PUSH_NS_FACILITY | 05 | filter | shipmentMethodTypeId | not-equals | POS_COMPLETED |
| | NS_ORDER_PUSH_POS_COMPLETED | 01 | filter | shipmentMethodTypeId | equals | POS_COMPLETED |
| | NS_ORDER_PUSH_POS_COMPLETED | 02 | filter | orderDate | greater-than | 2026-01-01T00:00:00 |
| | NS_ORDER_PUSH_POS_COMPLETED | 03 | sort | orderDate | | |

| RuleAction | ruleId | seq | actionTypeEnumId | fieldValue |
|---|---|---|---|---|
| | NS_ORDER_PUSH_POS_COMPLETED | 01 | NSOP_FILE_COUNT | 2 |
| | NS_ORDER_PUSH_POS_COMPLETED | 02 | NSOP_ORDER_LIMIT | 300 |
| | NS_ORDER_PUSH_POS_COMPLETED | 03 | NSOP_USE_SPARE_CAPACITY | Y |

A mixed cart, one line at the counter and one line shipping from the warehouse, matches
both rules. It goes once, with rule 1, because rule 1 runs first.

## Step 6. "The second distribution centre opens next month. It ships from NetSuite stock too."

No rule changes. One row of data.

| FacilityGroupMember | facilityGroupId | facilityId | fromDate |
|---|---|---|---|
| | NETSUITE_FULFILLMENT | RETAIL_WAREHOUSE | 2026-10-01 |

## Step 7. "Rush orders first. Before everything, even on a busy day."

A rule in front of rule 1. Sequence numbers decide the order; 0 runs before 1.

| DecisionRule | ruleId | ruleName | sequenceNum | statusId |
|---|---|---|---|---|
| | NS_ORDER_PUSH_EXPEDITED | Expedited orders | 0 | ATP_RULE_ACTIVE |

| RuleCondition | ruleId | seq | type | fieldName | operator | fieldValue |
|---|---|---|---|---|---|---|
| | NS_ORDER_PUSH_EXPEDITED | 01 | filter | facilityGroupId | equals | NETSUITE_FULFILLMENT |
| | NS_ORDER_PUSH_EXPEDITED | 02 | filter | shipmentMethodTypeId | in | OVERNIGHT,NEXT_DAY,SECOND_DAY |
| | NS_ORDER_PUSH_EXPEDITED | 03 | filter | orderDate | greater-than | 2026-01-01T00:00:00 |
| | NS_ORDER_PUSH_EXPEDITED | 04 | sort | orderDate | | |

| RuleAction | ruleId | seq | actionTypeEnumId | fieldValue |
|---|---|---|---|---|
| | NS_ORDER_PUSH_EXPEDITED | 01 | NSOP_FILE_COUNT | 1 |

Rule 1 needs no change: an order rule 0 took is not offered to rule 1 again.

## Step 8. "Exchange orders from AfterShip: keep them apart. When the returns team has a problem, I want to hold only those."

The same field, two ways: rule 1 leaves the channel out, a rule of its own takes it. To hold
them she sets that rule to draft; to resume, active. Nothing else moves.

| DecisionRule | ruleId | ruleName | sequenceNum | statusId |
|---|---|---|---|---|
| | NS_ORDER_PUSH_AFTERSHIP | AfterShip exchange orders | 4 | ATP_RULE_ACTIVE |

| RuleCondition | ruleId | seq | type | fieldName | operator | fieldValue |
|---|---|---|---|---|---|---|
| | NS_ORDER_PUSH_NS_FACILITY | 06 | filter | salesChannelEnumId | not-equals | AFTSHP_SALES_CHANNEL |
| | NS_ORDER_PUSH_AFTERSHIP | 01 | filter | facilityGroupId | equals | NETSUITE_FULFILLMENT |
| | NS_ORDER_PUSH_AFTERSHIP | 02 | filter | salesChannelEnumId | equals | AFTSHP_SALES_CHANNEL |
| | NS_ORDER_PUSH_AFTERSHIP | 03 | filter | orderDate | greater-than | 2026-01-01T00:00:00 |
| | NS_ORDER_PUSH_AFTERSHIP | 04 | sort | orderDate | | |

| RuleAction | ruleId | seq | actionTypeEnumId | fieldValue |
|---|---|---|---|---|
| | NS_ORDER_PUSH_AFTERSHIP | 01 | NSOP_FILE_COUNT | 1 |

## Step 9. "Three stores now ship web orders from their own stock. NetSuite must have those orders for the invoice."

A third rule, after the store sales, naming the stores. Pickup orders are not shipping
orders, so she leaves them out by name.

| DecisionRule | ruleId | ruleName | sequenceNum | statusId |
|---|---|---|---|---|
| | NS_ORDER_PUSH_SHIP_FROM_STORE | Orders shipping from a store | 3 | ATP_RULE_ACTIVE |

| RuleCondition | ruleId | seq | type | fieldName | operator | fieldValue |
|---|---|---|---|---|---|---|
| | NS_ORDER_PUSH_SHIP_FROM_STORE | 01 | filter | shipGroupFacilityId | in | M100010,M100076,M100002 |
| | NS_ORDER_PUSH_SHIP_FROM_STORE | 02 | filter | shipmentMethodTypeId | not-in | POS_COMPLETED,STOREPICKUP |
| | NS_ORDER_PUSH_SHIP_FROM_STORE | 03 | filter | orderDate | greater-than | 2026-01-01T00:00:00 |
| | NS_ORDER_PUSH_SHIP_FROM_STORE | 04 | sort | orderDate | | |

| RuleAction | ruleId | seq | actionTypeEnumId | fieldValue |
|---|---|---|---|---|
| | NS_ORDER_PUSH_SHIP_FROM_STORE | 01 | NSOP_FILE_COUNT | 1 |

If the list of stores keeps growing, she asks for a facility group `SHIP_FROM_STORE` and
writes `facilityGroupId equals SHIP_FROM_STORE` instead. Then adding a store is one row.

## Step 10. "Never send an order my customer service team is still fixing."

An order under repair is not approved. One filter on the warehouse rule; the same line goes
on any rule she wants held to it.

| RuleCondition | ruleId | seq | type | fieldName | operator | fieldValue |
|---|---|---|---|---|---|---|
| | NS_ORDER_PUSH_NS_FACILITY | 07 | filter | statusId | equals | ORDER_APPROVED |

## Step 11. "Black Friday week: the warehouse only. Store sales can wait until Monday."

No new rows. She sets the store sales rule to draft on Thursday and back to active on Monday.
The warehouse rule never notices.

| DecisionRule | ruleId | statusId (Thursday) | statusId (Monday) |
|---|---|---|---|
| | NS_ORDER_PUSH_POS_COMPLETED | ATP_RULE_DRAFT | ATP_RULE_ACTIVE |

## Step 12. "Stop everything. NetSuite is down for maintenance."

One field on the job. When NetSuite is back she clears it, and the next run picks up
where it left off; nothing was lost, because nothing was sent.

| ServiceJob | jobName | paused |
|---|---|---|
| | export_NetSuiteOrderPush_GORJANA | Y |

## The complete set

Rules, in the order they run:

| seq | ruleId | takes | sort | files | limit | spare only |
|---|---|---|---|---|---|---|
| 0 | NS_ORDER_PUSH_EXPEDITED | NetSuite facility, OVERNIGHT/NEXT_DAY/SECOND_DAY, after cutover | orderDate | 1 | | |
| 1 | NS_ORDER_PUSH_NS_FACILITY | NetSuite facility, not a counter sale, not AfterShip, approved, after cutover | priority, orderDate | 3 | | |
| 2 | NS_ORDER_PUSH_POS_COMPLETED | counter sales, after cutover | orderDate | 2 | 300 | Y |
| 3 | NS_ORDER_PUSH_SHIP_FROM_STORE | shipping orders at three stores, after cutover | orderDate | 1 | | |
| 4 | NS_ORDER_PUSH_AFTERSHIP | NetSuite facility, AfterShip channel, after cutover | orderDate | 1 | | |

Around them: facility group `NETSUITE_FULFILLMENT` with WH and RETAIL_WAREHOUSE; the job
every ten minutes with `targetMinutes` 10; the MDM config running files side by side.

Every row above was run on 11 September 2026 against a real hour of production orders;
`test-scenarios.md` says what each did.

## What she cannot ask for today

- "Only orders with this product": rules see the order and its ship group, not its items.
- "Only between 6 pm and 6 am": time of day is the job's schedule, not a rule.
- "No more than 200 calls a minute to NetSuite": the queue's speed is measured, not capped;
  the file count is her only lever.
- "Orders over $1,000 first": possible today as a sort, `-grandTotal`, or a filter on
  `grandTotal`; not in the set above because she has not asked.
- "Customers of type wholesale": `partyId` is on the view, the customer's type is not.
