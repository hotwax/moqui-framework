import json, datetime, collections, re, copy, sys
orders=json.load(open("d20260903/orders.json"))
PDT=datetime.timezone(datetime.timedelta(hours=-7))
def nodes(c):
    if c is None: return []
    if isinstance(c,list): return c
    if "edges" in c: return [e["node"] for e in c["edges"]]
    return c.get("nodes",[])
def local(p): return datetime.datetime.fromisoformat(p["createdAt"].replace("Z","+00:00")).astimezone(PDT)
def ship(p):
    sl=nodes(p.get("shippingLines")); return sl[0]["title"] if sl else "(none)"
day=[p for p in orders.values() if local(p).strftime("%Y-%m-%d")=="2026-09-03"]
busy=[p for p in day if local(p).hour==11]
evening=[p for p in day if 17<=local(p).hour<=20 and p.get("sourceName")=="pos"]
ids={p["id"] for p in busy+evening}
rest=[p for p in day if p["id"] not in ids]
def pick(pred,n): return [p for p in rest if pred(p)][:n]
extra = pick(lambda p:"SHIPSI" in ship(p),3) + pick(lambda p:p.get("sourceName")=="shopify_draft_order",2) + pick(lambda p:"Warranty" in ship(p) or "Replacement" in ship(p),2) + pick(lambda p:"Pick Up" in ship(p) and p.get("sourceName")=="web",3) + pick(lambda p:"Expedited" in ship(p) or "2 Day" in ship(p),2)
sel=busy+evening+extra
print("busy", len(busy), "evening POS", len(evening), "extra", len(extra), "total", len(sel))
sku2var={}
for line in open("local_sku_variant.tsv", encoding="latin-1"):
    sku,pid,var=line.rstrip("\n").split("\t"); sku2var.setdefault(sku,(pid,var))
loc_by_name={}; loc_rows=[]
for line in open("local_locations.tsv"):
    lid,fid,name,typ=line.rstrip("\n").split("\t"); loc_rows.append((lid,fid,name,typ)); loc_by_name[name.strip().lower()]=(lid,fid,name,typ)
prodname={}
for p in day:
    for fo in nodes(p.get("fulfillmentOrders")):
        al=fo.get("assignedLocation") or {}; l=al.get("location") or {}
        if l.get("id"): prodname[l["id"].split("/")[-1]]=(l.get("name") or al.get("name") or "")
prod_locs=set(re.findall(r'gid://shopify/Location/(\d+)', json.dumps(sel)))
locmap={}; used=set(); unmatched=[]
for pl in sorted(prod_locs):
    nm=(prodname.get(pl) or "").strip().lower(); hit=loc_by_name.get(nm)
    if hit and hit[0] not in used: locmap[pl]=hit; used.add(hit[0])
    else: unmatched.append((pl,nm))
free=[r for r in loc_rows if r[3]=="RETAIL_STORE" and r[0] not in used]
wh=[r for r in loc_rows if r[3]=="WAREHOUSE"][0]
for pl,nm in unmatched:
    if nm=="": locmap[pl]=wh
    else:
        if not free: free=[r for r in loc_rows if r[3]=="RETAIL_STORE"]  # more production stores than local ones: share
        r=free.pop(0); locmap[pl]=r; used.add(r[0])
print("production locations in selection", len(prod_locs), "matched by name", len(prod_locs)-len(unmatched), "unnamed->warehouse", [pl for pl,nm in unmatched if nm==""], "renamed", [(pl,nm) for pl,nm in unmatched if nm!=""])
out=[]; dropped=collections.Counter()
for p in sel:
    q=copy.deepcopy(p); ok=True
    for li in nodes(q["lineItems"]):
        if li.get("isGiftCard"): continue
        m=sku2var.get(li.get("sku"))
        if not m: ok=False; dropped["sku not local: "+str(li.get("sku"))]+=1; break
        v=li.get("variant")
        if v: v["legacyResourceId"]=m[1]; v["id"]="gid://shopify/ProductVariant/"+m[1]
    if not ok: continue
    s=re.sub(r'gid://shopify/Location/(\d+)', lambda m:"gid://shopify/Location/"+locmap[m.group(1)][0], json.dumps(q)); q=json.loads(s)
    byid={r[0]:r for r in loc_rows}
    def fix(o):
        if isinstance(o,dict):
            if isinstance(o.get("id"),str) and o["id"].startswith("gid://shopify/Location/"):
                r=byid[o["id"].split("/")[-1]]
                if "legacyResourceId" in o: o["legacyResourceId"]=r[0]
                if "name" in o: o["name"]=r[2]
            if "assignedLocation" in o and isinstance(o["assignedLocation"],dict) and o["assignedLocation"].get("location"):
                o["assignedLocation"]["name"]=byid[o["assignedLocation"]["location"]["id"].split("/")[-1]][2]
            for v in o.values(): fix(v)
        elif isinstance(o,list):
            for v in o: fix(v)
    fix(q)
    # bare production ids in line item custom attributes (AfterShip replacement orders)
    for li in nodes(q["lineItems"]):
        attrs=li.get("customAttributes") or []
        loc=next((a for a in attrs if a["key"]=="_shopifyLocationId"), None)
        fac=next((a for a in attrs if a["key"]=="_hcShippingFacility"), None)
        if loc and loc["value"] in locmap:
            r=locmap[loc["value"]]; loc["value"]=r[0]
            if fac: fac["value"]=r[1]
    out.append({"payload": q})
print("orders written", len(out), "dropped", sum(dropped.values()))
for k,c in dropped.most_common(): print("  ", c, k)
json.dump(out, open("ShopifyOrderList_TEST_20260903_busy_hour_and_evening_pos.json","w"), indent=1)
json.dump({k:v[1] for k,v in locmap.items()}, open("locmap.json","w"), indent=1)
kinds=collections.Counter()
for e in out:
    p=e["payload"]; s=p.get("sourceName"); sh=ship(p); tags=set(p.get("tags") or [])
    k = "POS" if s=="pos" else "AfterShip" if s=="92270886913" or "AfterShip Returns" in tags else "draft" if s=="shopify_draft_order" else "other app "+s if s not in ("web","checkout_next") else "web pickup" if "Pick Up" in sh else "web same/next day" if "SHIPSI" in sh else "web expedited" if ("Expedited" in sh or "2 Day" in sh or "Overnight" in sh) else "web replacement/warranty" if ("Replacement" in sh or "Warranty" in sh) else "web standard"
    kinds[k]+=1
for k,c in kinds.most_common(): print(f"  {c:4d} {k}")
