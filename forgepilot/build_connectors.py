"""Generate versioned connector contracts from the existing industrial baseline.
No vendor endpoints or physical production field names are inferred.
"""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'forgepilot/catalog.json'

def build():
    raw = CATALOG.read_bytes()
    catalog = json.loads(raw)
    docs = []
    domains = catalog['domains']
    scopes = [('FP_DEMO', 'ForgePilot 演示适配器', None)] + [
        ('FP_' + d['id'].upper(), d['name'] + '连接器', d) for d in domains]
    for code, name, domain in scopes:
        demo = domain is None
        entities = [e for e in catalog['entities'] if e['availability'] == 'demo_adapter'] if demo else [
            e for e in catalog['entities'] if e['domain'] == domain['id']]
        mappings = []
        for entity in entities:
            for attr in entity['attributes']:
                mappings.append(dict(
                    entity=entity['code'], entityName=entity['name'], target=attr['code'], targetName=attr['name'],
                    collection=(entity['source_collection'] or '') if demo else '',
                    field=(attr['source_field'] or '') if demo else '',
                    type=attr['type'], unit=attr['unit'] or '', key=attr['is_key'], required=attr['required'],
                    nullable=attr['nullable'], transform='IDENTITY', enabled=True,
                    unknownPolicy=attr['unknown_policy'], sensitivity=attr['sensitivity'],
                    evidence='现有 ForgePilot 演示适配定义' if demo else '待源系统字典确认；不推断物理表或字段'))
        docs.append(dict(code=code, templateCode=code, name=name, domain='demo' if demo else domain['id'],
            systems='ForgePilot' if demo else domain['systems'], demo=demo,
            description='读取契约对应现有演示快照；配置校验不执行取数。' if demo else '厂商无关接入模板；需补齐真实接口与源字段后验收。',
            catalogRelease=catalog['release_id'], catalogSha256=hashlib.sha256(raw).hexdigest(),
            config=dict(protocol='HTTP_API', endpoint='http://127.0.0.1:8000/api/demo/ontology/snapshot' if demo else '',
                credentialRef='', namespace='forgepilot-demo' if demo else '',
                owner='演示管理员' if demo else domain['owner'], syncMode='SNAPSHOT', watermarkField='',
                cadenceMinutes=15, pageSize=200, maxRetries=3, overlapSeconds=120,
                deletionPolicy='EXPLICIT_TOMBSTONE', permissionScope='当前业务角色与组织范围' if demo else '',
                identityPolicy='tenant_id + source_system + source_record_id',
                timePolicy='保留业务有效时间、源更新时间、采集时间；缺失记 unknown',
                reconciliation='源记录数、稳定身份、数量/金额守恒、缺失字段与异常对账',
                readOnly=True), mappings=mappings))
    return dict(schemaVersion='1.0', templates=docs)

if __name__ == '__main__':
    output = ROOT / 'bemodel-server/src/main/resources/industrial/connectors.json'
    result = build()
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('templates:', len(result['templates']), 'mapping contracts:', sum(len(d['mappings']) for d in result['templates']))
