"""Generate an isolated industrial Flyway baseline and reviewable dictionaries.

Industrial-only build inputs. Applied V1/V2 migration bytes remain immutable.
"""
from pathlib import Path
import csv
import hashlib
import io
import json
import re
from build_catalog import build

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
RES = REPO/'bemodel-server/src/main/resources'

def statements(sql):
    """Split trusted upstream SQL, respecting comments, escaped and doubled quotes."""
    buf=[]; quote=None; line=False; block=False; i=0
    while i < len(sql):
        c=sql[i]; pair=sql[i:i+2]
        if line:
            if c=='\n': line=False; buf.append('\n')
            i+=1; continue
        if block:
            if pair=='*/': block=False; i+=2
            else: i+=1
            continue
        if quote:
            buf.append(c)
            if c=='\\' and i+1<len(sql): buf.append(sql[i+1]); i+=2; continue
            if c==quote:
                if i+1<len(sql) and sql[i+1]==quote: buf.append(sql[i+1]); i+=2; continue
                quote=None
            i+=1; continue
        if pair=='--' and (i+2==len(sql) or sql[i+2].isspace()): line=True; i+=2; continue
        if pair=='/*': block=True; i+=2; continue
        if c in "'\"`": quote=c; buf.append(c)
        elif c==';':
            if ''.join(buf).strip(): yield ''.join(buf).strip()
            buf=[]
        else: buf.append(c)
        i+=1
    if quote or block: raise ValueError('Unclosed SQL literal/comment')
    if ''.join(buf).strip(): yield ''.join(buf).strip()

def q(value):
    if value is None: return 'NULL'
    if isinstance(value,int): return str(value)
    return "'"+str(value).replace('\\','\\\\').replace("'","''")+"'"

def insert(table,fields,values):
    return f"INSERT INTO {table} ({','.join(fields)}) VALUES ({','.join(q(v) for v in values)});"

def write_csv(name,fields,rows):
    out=io.StringIO(); writer=csv.DictWriter(out,fieldnames=fields,extrasaction='ignore',lineterminator='\n')
    writer.writeheader(); writer.writerows(rows)
    (ROOT/name).write_text(out.getvalue(),encoding='utf-8-sig')

def generate():
    catalog=build()
    body=json.dumps(catalog,ensure_ascii=False,indent=2)+'\n'
    (ROOT/'catalog.json').write_text(body)
    (RES/'industrial').mkdir(exist_ok=True)
    (RES/'industrial/catalog.json').write_text(body)
    frozen=ROOT/'baseline'
    platform=(frozen/'platform.sql').read_text()
    auth=[sql+';' for sql in statements((frozen/'auth-seed.sql').read_text())]
    baseline=list(statements(platform))
    migration=RES/'db/industrial'; migration.mkdir(exist_ok=True)
    (migration/'V1__industrial_platform.sql').write_text(platform)
    seed=['-- ForgePilot industrial candidate model. No live source connections, no approval impersonation.']
    seed.extend(auth)
    for order,d in enumerate(catalog['domains']):
        seed.append(insert('bm_domain',['code','name','description','sort'],[d['id'],d['name'],d['systems']+'；责任角色：'+d['owner'],order]))
    type_map={'string':'STRING','integer':'NUMBER','datetime':'DATE','date':'DATE','date_or_datetime':'DATE','enum':'ENUM','boolean':'ENUM','array':'STRING','object':'STRING'}
    for e in catalog['entities']:
        seed.append(insert('bm_concept',['code','name','domain_code','definition','owner','status','version'],
            [e['code'],e['name'],e['domain'],e['definition'],e['owner'],'DRAFT',1]))
        for a in e['attributes']:
            definition=f"{a['definition']}；单位：{a['unit'] or '不适用'}；源：{e['source_collection'] or '待接入'}；{a['unknown_policy']}"
            seed.append(insert('bm_attribute',['concept_code','attr_code','attr_name','data_type','is_key','definition','sort'],
                [e['code'],a['code'],a['name'],type_map[a['type']],int(a['is_key']),definition,a['sort']]))
        seed.append(insert('bm_term',['term','concept_code','source_product','term_type'],[e['name'],e['code'],'ForgePilot','STANDARD']))
    for r in catalog['relations']:
        seed.append(insert('bm_relation',['from_concept','to_concept','relation_name','description','is_transitive','is_symmetric','is_functional','is_inverse_functional'],
            [r['from_entity'],r['to_entity'],r['name'],f"{r['description']}；{r['from_cardinality']} → {r['to_cardinality']}",0,0,
             int(r['to_cardinality'] in ('1','0..1')),int(r['from_cardinality'] in ('1','0..1'))]))
    for r in catalog['rules']:
        seed.append(insert('bm_rule',['rule_code','name','concept_code','rule_type','expression','severity','owner','status','version','engine'],
            [r['id'],r['name'],r['entities'][0],'CONSTRAINT',r['expression'],'HIGH',r['owner'],'DRAFT',1,'CONTRACT']))
    for metric in catalog['metrics']:
        seed.append(insert('bm_metric',['metric_code','name','definition','formula','concept_code','owner'],
            [metric['id'],metric['name'],metric['data_status']+'；单位：'+metric['unit'],metric['formula'],metric['entity'],metric['owner']]))
    # Dedicated JSON contracts retain types, cardinalities, units, sources and action side effects.
    # Do not register SQLite JSON/API locators as executable MySQL SQL mappings.
    seed.append('CREATE TABLE fp_model_contract (release_id VARCHAR(64) PRIMARY KEY, status VARCHAR(16) NOT NULL, sha256 CHAR(64) NOT NULL, contract_json JSON NOT NULL);')
    seed.append(insert('fp_model_contract',['release_id','status','sha256','contract_json'],
        [catalog['release_id'],'DRAFT',hashlib.sha256(body.encode()).hexdigest(),json.dumps(catalog,ensure_ascii=False,separators=(',',':'))]))
    (migration/'V2__industrial_ontology.sql').write_text('\n'.join(seed)+'\n')
    write_csv('objects.csv',['code','name','domain','definition','identity','owner','source_system','source_collection','availability'],catalog['entities'])
    attributes=[dict(entity=e['code'],source_collection=e['source_collection'],identity=a['is_key'],**a) for e in catalog['entities'] for a in e['attributes']]
    write_csv('attributes.csv',['entity','code','name','type','unit','required','identity','source_collection','source_field','mapping_status','definition','unknown_policy','sensitivity'],attributes)
    write_csv('relations.csv',['id','from_entity','to_entity','name','from_cardinality','to_cardinality','transitive','authority','permission','status'],catalog['relations'])
    write_csv('questions.csv',['id','question','domain','entities','rules','data_status','business_acceptance'],catalog['questions'])
    write_csv('rules.csv',['id','name','domain','entities','expression','source_code','status','owner'],catalog['rules'])
    write_csv('metrics.csv',['id','name','entity','unit','formula','authority','data_status','owner','execution_enabled','zero_denominator'],catalog['metrics'])
    rows=[]
    for e in catalog['entities']:
        mapped=sum(a['mapping_status']=='adapter_defined' for a in e['attributes'])
        rows.append(dict(entity=e['code'],name=e['name'],source=e['source_collection'] or e['source_system'],
            mapped_attributes=mapped,total_attributes=len(e['attributes']),
            status='adapter_defined_not_production' if e['source_collection'] else 'source_contract_pending',
            owner=e['owner'],next_step='按源快照验证字段、主键、时间、状态与组织权限' if mapped else '取得源字典与只读样本，由owner确认后实现适配器'))
    write_csv('coverage.csv',['entity','name','source','mapped_attributes','total_attributes','status','owner','next_step'],rows)
    print(f"Generated industrial baseline: {len(baseline)} DDL statements; {len(catalog['entities'])} DRAFT concepts; no live source registrations or runtime SQL mappings")

if __name__=='__main__': generate()
