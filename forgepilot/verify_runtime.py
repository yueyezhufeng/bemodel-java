"""Read-only validation of the running industrial profile; never prints tokens."""
from pathlib import Path
import argparse
import json
import urllib.request
import urllib.error

def run(base):
    def request(path,body=None,token=None):
        headers={'Content-Type':'application/json'}
        if token: headers['Authorization']='Bearer '+token
        req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
        try:
            with urllib.request.urlopen(req,timeout=15) as response:
                return response.status,json.load(response)
        except urllib.error.HTTPError as error:
            return error.code,json.load(error)
    results=[]
    status,login=request('/api/auth/login',{'username':'admin','password':'admin123'})
    assert status==200 and login['code']==0,'Industrial demo login failed'
    token=login['data']['token']
    for endpoint,count in [('domain/list',10),('concept/list',45),('rule/list',14),('metric/list',8),('term/list',45),('datasource/list',0)]:
        status,payload=request('/api/'+endpoint,token=token)
        assert status==200 and payload['code']==0,(endpoint,status,payload)
        values=payload['data']
        assert isinstance(values,list) and len(values)==count,(endpoint,len(values),count)
        if endpoint=='concept/list':
            assert all(v['status']=='DRAFT' for v in values),'Unapproved model must stay draft'
            assert not any(v['code'] in {'PATIENT','MEDICAL_ORDER','DIAGNOSIS'} for v in values)
        results.append({'check':endpoint,'status':'pass','count':count})
    status,payload=request('/api/industrial/status',token=token)
    assert status==200 and payload['data']['applicationMode']=='forgepilot' and payload['data']['availableModes']==['forgepilot']
    status,payload=request('/api/ontology/check',{},token)
    assert status==200 and payload['code']==0 and payload['data']['blockerCount']==0,payload
    results.append({'check':'ontology_self_check','status':'pass','result':payload['data']})
    for endpoint in ['/api/flow/patients','/api/clinical/qc','/api/cs/ask','/api/lab/run','/mcp']:
        status,_=request(endpoint,token=token)
        assert status==403,(endpoint,status)
        results.append({'check':'industry_boundary '+endpoint,'status':'pass'})
    status,_=request('/api/concept/list')
    assert status==401,status
    results.append({'check':'unauthenticated_denied','status':'pass'})
    with urllib.request.urlopen(base+'/industrial-foundation/index.html',timeout=10) as response:
        assert response.headers.get('X-Frame-Options')=='SAMEORIGIN','Industrial embedded view must allow same-origin framing only'
        assert '工业供应链本体' in response.read().decode()
    results.append({'check':'same_origin_industrial_view','status':'pass'})
    return {'environment':'local synthetic industrial demo','base_url':base,'checks':results,
        'production_data':'not_integrated','business_owner_acceptance':'pending'}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--base',default='http://127.0.0.1:18081')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args();result=run(args.base)
    text=json.dumps(result,ensure_ascii=False,indent=2)
    if args.output: args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(text+'\n')
    print(text)
