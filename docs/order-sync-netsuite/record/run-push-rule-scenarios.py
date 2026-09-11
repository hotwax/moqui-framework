import json, subprocess, time, glob, os, csv, sys
B="http://localhost:8083/rest/s1/gorjana/"
ROOT="/Users/anilpatel/adev/maarg4-sd/m4gorjana"
OUT=ROOT+"/runtime/datamanager/netsuite/orderpush-probe"
def sql(q):
    r=subprocess.run(["mysql","-h127.0.0.1","-P3307","-um4gorjana","-pm4gorjana","m4gorjana","-N","-e",q],capture_output=True,text=True)
    return [l.split("\t") for l in r.stdout.strip().split("\n") if l]
def svc(name, payload):
    r=subprocess.run(["curl","-s","--max-time","600","-X","POST",B+"probeRun","-u","john.doe:moqui","-H","Content-Type: application/json","-d",json.dumps({"serviceName":name,"payloadJson":json.dumps(payload)})],capture_output=True,text=True)
    return json.loads(r.stdout)
def clear_cache():
    subprocess.run(["curl","-s","-X","POST",B+"probeClearCache","-u","john.doe:moqui","-H","Content-Type: application/json","-d",json.dumps({"cacheName":"entity.record"})],capture_output=True)
def wait_idle():
    while sql("select count(*) from data_manager_log where config_id='PROBE_NS_SO_PUSH' and status_id in ('DmlsPending','DmlsQueued','DmlsRunning')")[0][0]!="0": time.sleep(3)
def set_rule(rule_id, conditions, sorts, actions, seq=None, status="ATP_RULE_ACTIVE"):
    """conditions: list of (field, operator, value); sorts: list of field; actions: dict enum->value"""
    sql(f"delete from rule_condition where rule_id='{rule_id}'; delete from rule_action where rule_id='{rule_id}'")
    if not sql(f"select rule_id from decision_rule where rule_id='{rule_id}'"):
        sql(f"insert into decision_rule (rule_id, rule_group_id, rule_name, status_id, sequence_num, created_stamp, last_updated_stamp) values ('{rule_id}','NS_ORDER_PUSH_GORJANA','{rule_id}','{status}',{9 if seq is None else seq},utc_timestamp(3),utc_timestamp(3))")
    sql(f"update decision_rule set status_id='{status}'" + (f", sequence_num={seq}" if seq is not None else "") + f" where rule_id='{rule_id}'")
    n=0
    for f,o,v in conditions:
        n+=1; sql(f"insert into rule_condition (rule_id, condition_seq_id, condition_type_enum_id, field_name, operator, field_value, sequence_num, created_stamp, last_updated_stamp) values ('{rule_id}','{n:02d}','ENTCT_FILTER','{f}','{o}','{v}',{n},utc_timestamp(3),utc_timestamp(3))")
    for f in sorts:
        n+=1; sql(f"insert into rule_condition (rule_id, condition_seq_id, condition_type_enum_id, field_name, sequence_num, created_stamp, last_updated_stamp) values ('{rule_id}','{n:02d}','ENTCT_SORT_BY','{f}',{n},utc_timestamp(3),utc_timestamp(3))")
    m=0
    for e,v in actions.items():
        m+=1; sql(f"insert into rule_action (rule_id, action_seq_id, action_type_enum_id, field_value, created_stamp, last_updated_stamp) values ('{rule_id}','{m:02d}','{e}','{v}',utc_timestamp(3),utc_timestamp(3))")
def rule_status(rule_id, status): sql(f"update decision_rule set status_id='{status}' where rule_id='{rule_id}'")
def run(target_minutes=10):
    wait_idle(); clear_cache()
    before=set(glob.glob(OUT+"/*.csv"))
    d=svc("co.hotwax.netsuite.NetSuiteOrderPushServices.run#NetSuiteOrderPush", {"ruleGroupId":"NS_ORDER_PUSH_GORJANA","productStoreId":"STORE","dataManagerConfigId":"PROBE_NS_SO_PUSH","sourceEntityName":"co.hotwax.oms.NetSuiteEligibleOrderView","targetMinutes":target_minutes})
    files=sorted(set(glob.glob(OUT+"/*.csv"))-before)
    result={}
    for f in files:
        name=os.path.basename(f); rule=name.split("OrderPush_")[1].rsplit("_",2)[0]
        with open(f) as fh: ids=[row["orderId"] for row in csv.DictReader(fh)]
        result.setdefault(rule,[]).append(ids)
    return {"out":d.get("out"),"messages":(d.get("messages") or "").strip(),"errors":(d.get("errors") or "").strip(),"files":result}
def show(label, r, expected=None):
    print(f"== {label}")
    print("  out:", {k:r['out'].get(k) for k in ('queuedCount','queuedMinutes','busyFileCount','nextRunTime')} if r['out'] else None)
    if r["messages"]: print("  messages:", r["messages"][:300].replace("\n"," | "))
    if r["errors"]: print("  errors:", r["errors"][:300])
    for rule,files in r["files"].items():
        allids=[i for f in files for i in f]
        print(f"  {rule}: {len(allids)} orders in {len(files)} files", [len(f) for f in files], "dup within rule:", len(allids)-len(set(allids)))
    if expected is not None:
        got=sorted({i for files in r["files"].values() for f in files for i in f})
        exp=sorted(expected)
        print("  expected", len(exp), "got", len(got), "missing:", sorted(set(exp)-set(got)), "extra:", sorted(set(got)-set(exp)))
def run2(target, **kw):
    wait_idle(); clear_cache()
    before=set(glob.glob(OUT+"/*.csv"))
    p={"ruleGroupId":"NS_ORDER_PUSH_GORJANA","productStoreId":"STORE","dataManagerConfigId":"PROBE_NS_SO_PUSH","sourceEntityName":"co.hotwax.oms.NetSuiteEligibleOrderView","targetMinutes":target,"historyLogCount":0,"defaultRatePerMinute":6}
    p.update(kw)
    d=svc("co.hotwax.netsuite.NetSuiteOrderPushServices.run#NetSuiteOrderPush", p)
    files=sorted(set(glob.glob(OUT+"/*.csv"))-before); result={}
    for f in files:
        name=os.path.basename(f); rule=name.split("OrderPush_")[1].rsplit("_",2)[0]
        with open(f) as fh: ids=[row["orderId"] for row in csv.DictReader(fh)]
        result.setdefault(rule,[]).append(ids)
    return {"out":d.get("out"),"messages":(d.get("messages") or "").strip(),"errors":(d.get("errors") or "").strip(),"files":result}
