# The rules the fulfillment manager writes, record

Human version: `../the-rules-the-manager-writes.md`. Written 11 September 2026 on Anil's ask:
"write up the story from users point of view ... prepare data for each rule setup ... show
the data in the rule set growing"; tables in the document, XML here.

## Where the pieces live

| Piece | Entity | Seed file today |
|---|---|---|
| Rule group, rules, conditions, actions | `co.hotwax.rule.RuleGroup`, `DecisionRule`, `RuleCondition`, `RuleAction` (oms component) | `gorjana-maarg/data/NetSuiteConfigData.xml` section 13 (rules 1 and 2 only, commits `2f83de3` to `d31a239`) |
| Action types | `Enumeration` type `NS_ORDER_PUSH_ACT_TYPE`: `NSOP_FILE_COUNT`, `NSOP_ORDER_LIMIT`, `NSOP_USE_SPARE_CAPACITY` | `mantle-netsuite-connector/data/OrderPushSeedData.xml` |
| Facility group | `FacilityGroup NETSUITE_FULFILLMENT` (type `FULFILLMENT`), `FacilityGroupMember` | production data; local WH and M100049 |
| Job | `moqui.service.job.ServiceJob export_NetSuiteOrderPush_GORJANA`, parameters `ruleGroupId`, `productStoreId`, `dataManagerConfigId`, `sourceEntityName`, `targetMinutes` | `NetSuiteConfigData.xml` section 13 |
| Queue | `DataManagerConfig MDM_NS_SO_REST`, `DMC_ASYNC`, priority 7, `exportPath netsuite/orderpush` | same |
| Fields a rule may name | aliases of `co.hotwax.oms.NetSuiteEligibleOrderView` | `gorjana-maarg/entity/NetSuiteOrderViewEntities.xml` |
| Operator words | `EntityConditionFactoryImpl.groovy:455` to `:492` | framework |
| How a rule row becomes a condition | `run#NetSuiteOrderPushRule`: `ec.entity.conditionFactory.makeCondition([[(fieldName): fieldValue]], 'and', operator, 'and')`; sort rows become `order-by`; the `in`/`not-in` value is a comma list the engine splits | `mantle-netsuite-connector/service/co/hotwax/netsuite/NetSuiteOrderPushServices.xml` |
| Rule status | `ATP_RULE_ACTIVE` runs; `ATP_RULE_DRAFT` and `ATP_RULE_ARCHIVED` do not (`run#NetSuiteOrderPush` line 94, `statusId = ATP_RULE_ACTIVE`); there is no `ATP_RULE_INACTIVE` | `StatusItem` |
| Rule order | `DecisionRule.sequenceNum` ascending; 0 runs before 1 | same service |

## Which step adds which rows

| Step | Rows | Proved by |
|---|---|---|
| 1 | FacilityGroup, FacilityGroupMember WH, RuleGroup, rule NS_FACILITY with conditions 01 to 03, ServiceJob | scenario 1 |
| 2 | NS_FACILITY condition 04 | scenario 2 |
| 3 | NS_FACILITY action NSOP_FILE_COUNT 3; DataManagerConfig DMC_ASYNC | scenario 1 (three files), sandbox run 11 Sep 15:24 UTC |
| 4 | ServiceJobParameter targetMinutes 10 | scenarios 4, 12, 13 |
| 5 | rule POS_COMPLETED, its conditions and actions; NS_FACILITY condition 05 | scenarios 4, 5, 6 (run again), 8 |
| 6 | FacilityGroupMember RETAIL_WAREHOUSE | scenario 6 |
| 7 | rule EXPEDITED | scenario 10 |
| 8 | rule AFTERSHIP; NS_FACILITY condition 06 | scenario 9 |
| 9 | rule SHIP_FROM_STORE | scenario 7 |
| 10 | NS_FACILITY condition 07 | scenario 3 |
| 11 | status change only | runner, rule 2 in draft between scenarios |
| 12 | ServiceJob.paused | seed |

Rules 0, 3 and 4 and conditions 06 and 07 are the story's, not the seed's. The seed today
carries rules 1 and 2 with conditions 01 to 05 and the actions.

## The complete set as seed XML

```xml
<entity-facade-xml type="seed">
    <!-- Step 1 and 6: which facilities NetSuite fulfils -->
    <org.apache.ofbiz.product.facility.FacilityGroup facilityGroupId="NETSUITE_FULFILLMENT"
            facilityGroupTypeId="FULFILLMENT" description="Facilities whose orders NetSuite fulfils"/>
    <org.apache.ofbiz.product.facility.FacilityGroupMember facilityGroupId="NETSUITE_FULFILLMENT"
            facilityId="WH" fromDate="2026-01-01 00:00:00"/>
    <org.apache.ofbiz.product.facility.FacilityGroupMember facilityGroupId="NETSUITE_FULFILLMENT"
            facilityId="RETAIL_WAREHOUSE" fromDate="2026-10-01 00:00:00"/>

    <!-- Step 1: the group and its job -->
    <co.hotwax.rule.RuleGroup ruleGroupId="NS_ORDER_PUSH_GORJANA" productStoreId="STORE"
            groupName="NetSuite sales order push" groupTypeEnumId="RG_NS_ORDER_PUSH"
            statusId="ATP_RG_ACTIVE" sequenceNum="1" jobName="export_NetSuiteOrderPush_GORJANA"
            description="Chooses the sales orders the REST sync creates in NetSuite"/>

    <!-- Step 7: rush orders before everything -->
    <co.hotwax.rule.DecisionRule ruleId="NS_ORDER_PUSH_EXPEDITED" ruleGroupId="NS_ORDER_PUSH_GORJANA"
            ruleName="Expedited orders" statusId="ATP_RULE_ACTIVE" sequenceNum="0">
        <ruleConditions conditionSeqId="01" conditionTypeEnumId="ENTCT_FILTER" fieldName="facilityGroupId"
                operator="equals" fieldValue="NETSUITE_FULFILLMENT" sequenceNum="1"/>
        <ruleConditions conditionSeqId="02" conditionTypeEnumId="ENTCT_FILTER" fieldName="shipmentMethodTypeId"
                operator="in" fieldValue="OVERNIGHT,NEXT_DAY,SECOND_DAY" sequenceNum="2"/>
        <ruleConditions conditionSeqId="03" conditionTypeEnumId="ENTCT_FILTER" fieldName="orderDate"
                operator="greater-than" fieldValue="2026-01-01T00:00:00" sequenceNum="3"/>
        <ruleConditions conditionSeqId="04" conditionTypeEnumId="ENTCT_SORT_BY" fieldName="orderDate" sequenceNum="4"/>
        <ruleActions actionSeqId="01" actionTypeEnumId="NSOP_FILE_COUNT" fieldValue="1" sequenceNum="1"/>
    </co.hotwax.rule.DecisionRule>

    <!-- Steps 1, 2, 3, 5, 8, 10: the warehouse rule -->
    <co.hotwax.rule.DecisionRule ruleId="NS_ORDER_PUSH_NS_FACILITY" ruleGroupId="NS_ORDER_PUSH_GORJANA"
            ruleName="Orders fulfilled by a NetSuite facility" statusId="ATP_RULE_ACTIVE" sequenceNum="1">
        <ruleConditions conditionSeqId="01" conditionTypeEnumId="ENTCT_FILTER" fieldName="facilityGroupId"
                operator="equals" fieldValue="NETSUITE_FULFILLMENT" sequenceNum="1"/>
        <ruleConditions conditionSeqId="02" conditionTypeEnumId="ENTCT_SORT_BY" fieldName="priority" sequenceNum="8"/>
        <ruleConditions conditionSeqId="03" conditionTypeEnumId="ENTCT_SORT_BY" fieldName="orderDate" sequenceNum="9"/>
        <ruleConditions conditionSeqId="04" conditionTypeEnumId="ENTCT_FILTER" fieldName="orderDate"
                operator="greater-than" fieldValue="2026-01-01T00:00:00" sequenceNum="2"/>
        <ruleConditions conditionSeqId="05" conditionTypeEnumId="ENTCT_FILTER" fieldName="shipmentMethodTypeId"
                operator="not-equals" fieldValue="POS_COMPLETED" sequenceNum="3"/>
        <ruleConditions conditionSeqId="06" conditionTypeEnumId="ENTCT_FILTER" fieldName="salesChannelEnumId"
                operator="not-equals" fieldValue="AFTSHP_SALES_CHANNEL" sequenceNum="4"/>
        <ruleConditions conditionSeqId="07" conditionTypeEnumId="ENTCT_FILTER" fieldName="statusId"
                operator="equals" fieldValue="ORDER_APPROVED" sequenceNum="5"/>
        <ruleActions actionSeqId="01" actionTypeEnumId="NSOP_FILE_COUNT" fieldValue="3" sequenceNum="1"/>
    </co.hotwax.rule.DecisionRule>

    <!-- Step 5: counter sales, only with the room left -->
    <co.hotwax.rule.DecisionRule ruleId="NS_ORDER_PUSH_POS_COMPLETED" ruleGroupId="NS_ORDER_PUSH_GORJANA"
            ruleName="POS completed orders" statusId="ATP_RULE_ACTIVE" sequenceNum="2">
        <ruleConditions conditionSeqId="01" conditionTypeEnumId="ENTCT_FILTER" fieldName="shipmentMethodTypeId"
                operator="equals" fieldValue="POS_COMPLETED" sequenceNum="1"/>
        <ruleConditions conditionSeqId="02" conditionTypeEnumId="ENTCT_FILTER" fieldName="orderDate"
                operator="greater-than" fieldValue="2026-01-01T00:00:00" sequenceNum="2"/>
        <ruleConditions conditionSeqId="03" conditionTypeEnumId="ENTCT_SORT_BY" fieldName="orderDate" sequenceNum="3"/>
        <ruleActions actionSeqId="01" actionTypeEnumId="NSOP_FILE_COUNT" fieldValue="2" sequenceNum="1"/>
        <ruleActions actionSeqId="02" actionTypeEnumId="NSOP_ORDER_LIMIT" fieldValue="300" sequenceNum="2"/>
        <ruleActions actionSeqId="03" actionTypeEnumId="NSOP_USE_SPARE_CAPACITY" fieldValue="Y" sequenceNum="3"/>
    </co.hotwax.rule.DecisionRule>

    <!-- Step 9: three stores ship web orders -->
    <co.hotwax.rule.DecisionRule ruleId="NS_ORDER_PUSH_SHIP_FROM_STORE" ruleGroupId="NS_ORDER_PUSH_GORJANA"
            ruleName="Orders shipping from a store" statusId="ATP_RULE_ACTIVE" sequenceNum="3">
        <ruleConditions conditionSeqId="01" conditionTypeEnumId="ENTCT_FILTER" fieldName="shipGroupFacilityId"
                operator="in" fieldValue="M100010,M100076,M100002" sequenceNum="1"/>
        <ruleConditions conditionSeqId="02" conditionTypeEnumId="ENTCT_FILTER" fieldName="shipmentMethodTypeId"
                operator="not-in" fieldValue="POS_COMPLETED,STOREPICKUP" sequenceNum="2"/>
        <ruleConditions conditionSeqId="03" conditionTypeEnumId="ENTCT_FILTER" fieldName="orderDate"
                operator="greater-than" fieldValue="2026-01-01T00:00:00" sequenceNum="3"/>
        <ruleConditions conditionSeqId="04" conditionTypeEnumId="ENTCT_SORT_BY" fieldName="orderDate" sequenceNum="4"/>
        <ruleActions actionSeqId="01" actionTypeEnumId="NSOP_FILE_COUNT" fieldValue="1" sequenceNum="1"/>
    </co.hotwax.rule.DecisionRule>

    <!-- Step 8: AfterShip exchanges apart, so they can be held alone -->
    <co.hotwax.rule.DecisionRule ruleId="NS_ORDER_PUSH_AFTERSHIP" ruleGroupId="NS_ORDER_PUSH_GORJANA"
            ruleName="AfterShip exchange orders" statusId="ATP_RULE_ACTIVE" sequenceNum="4">
        <ruleConditions conditionSeqId="01" conditionTypeEnumId="ENTCT_FILTER" fieldName="facilityGroupId"
                operator="equals" fieldValue="NETSUITE_FULFILLMENT" sequenceNum="1"/>
        <ruleConditions conditionSeqId="02" conditionTypeEnumId="ENTCT_FILTER" fieldName="salesChannelEnumId"
                operator="equals" fieldValue="AFTSHP_SALES_CHANNEL" sequenceNum="2"/>
        <ruleConditions conditionSeqId="03" conditionTypeEnumId="ENTCT_FILTER" fieldName="orderDate"
                operator="greater-than" fieldValue="2026-01-01T00:00:00" sequenceNum="3"/>
        <ruleConditions conditionSeqId="04" conditionTypeEnumId="ENTCT_SORT_BY" fieldName="orderDate" sequenceNum="4"/>
        <ruleActions actionSeqId="01" actionTypeEnumId="NSOP_FILE_COUNT" fieldValue="1" sequenceNum="1"/>
    </co.hotwax.rule.DecisionRule>

    <!-- Step 3: the queue sends every file of a run at once -->
    <co.hotwax.datamanager.DataManagerConfig configId="MDM_NS_SO_REST"
            importServiceName="co.hotwax.gorjana.netsuite.NetSuiteOrderServices.sync#NetSuiteOrder"
            executionModeId="DMC_ASYNC" priority="7" exportPath="netsuite/orderpush"
            description="Creates sales orders in NetSuite through the REST record API, one order per record"/>

    <!-- Steps 1, 4, 12: the job, paused until go-live -->
    <moqui.service.job.ServiceJob jobName="export_NetSuiteOrderPush_GORJANA"
            description="Queue eligible gorjana orders for the NetSuite REST sync"
            serviceName="co.hotwax.netsuite.NetSuiteOrderPushServices.run#NetSuiteOrderPush"
            cronExpression="0 0/10 * * * ?" paused="Y">
        <parameters parameterName="ruleGroupId" parameterValue="NS_ORDER_PUSH_GORJANA"/>
        <parameters parameterName="productStoreId" parameterValue="STORE"/>
        <parameters parameterName="dataManagerConfigId" parameterValue="MDM_NS_SO_REST"/>
        <parameters parameterName="sourceEntityName" parameterValue="co.hotwax.oms.NetSuiteEligibleOrderView"/>
        <parameters parameterName="targetMinutes" parameterValue="10"/>
    </moqui.service.job.ServiceJob>
</entity-facade-xml>
```

Condition `sequenceNum` on the warehouse rule is set so the filters read in story order and
the sorts come last; the engine does not care about filter order, only sort order.

## Step 11 and 12 as data changes

```sql
-- Thursday before Black Friday
update decision_rule set status_id = 'ATP_RULE_DRAFT' where rule_id = 'NS_ORDER_PUSH_POS_COMPLETED';
-- Monday
update decision_rule set status_id = 'ATP_RULE_ACTIVE' where rule_id = 'NS_ORDER_PUSH_POS_COMPLETED';
-- NetSuite maintenance
update service_job set paused = 'Y' where job_name = 'export_NetSuiteOrderPush_GORJANA';
```

In practice these are `update#co.hotwax.rule.DecisionRule` and
`update#moqui.service.job.ServiceJob` calls from a screen; the SQL shows the row.

## Facts behind "what she cannot ask for today"

- Item-level: the view has no item alias; `OrderUnmappedItemView` is an anti-join, not a filter.
- Time of day: the cron on the job; `targetMinutes` is a length, not a clock.
- Concurrency cap: `run#NetSuiteOrderPush` measures the rate from `DataManagerLog`; no
  parameter caps calls a minute. Files at once is `NSOP_FILE_COUNT` per rule.
- `grandTotal` and `priority` are aliases on the view (`OH.grandTotal`, `OH.priority`), so a
  filter or a sort on them works; untested.
- `partyId` is on the view (`OrderRole BILL_TO_CUSTOMER`); no customer classification alias.
