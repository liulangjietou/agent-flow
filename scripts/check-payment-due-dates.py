"""F16 隔离固定包：真实财务链路、V116 升级、付款中断与独立恢复。"""
import argparse,copy,datetime,hashlib,json,os,re,shutil,socket,subprocess,tempfile,threading,time,traceback,uuid,zipfile
from decimal import Decimal
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from urllib.parse import urlencode

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--artifacts',type=Path,required=True,help='固定基线、新版及前端文件摘要清单')
parser.add_argument('--java',required=True,help='Java 17 可执行文件')
args=parser.parse_args();JAVA=args.java;Q=args.artifacts.resolve().parent
M=json.loads(args.artifacts.read_text());D=Path(tempfile.mkdtemp(prefix='agentflow-due-runtime-',dir='/fyoung/tmp'))
PACK=json.loads((ROOT/'agentflow-server/src/main/resources/process-template-examples/employee-finance.json').read_text())
DATE=datetime.datetime.now(datetime.timezone.utc).date().isoformat()
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,v):Path(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def now(seconds=0):return (datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=seconds)).isoformat()
def money(value):return {'value':str(Decimal(value).quantize(Decimal('.01'))),'currency':'CNY'}
def db_url(directory):return 'jdbc:h2:file:'+str(directory/'database')+';DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;REUSE_SPACE=FALSE'
for dest,key in [('server.jar','jar'),('baseline.jar','baselineJar')]:shutil.copy2(M[key],D/dest)
assert sha(D/'server.jar')==M['jarSha256'] and sha(D/'baseline.jar')==M['baselineSha256']
shutil.copytree(M['webDist'],D/'web-dist')
with zipfile.ZipFile(D/'server.jar') as z:
 names=[n for n in z.namelist() if re.fullmatch('BOOT-INF/lib/h2-[^/]+.jar',n)];assert len(names)==1;(D/'h2.jar').write_bytes(z.read(names[0]))
for name in ['SnapshotH2.java','CompareH2Schema.java']:
 shutil.copy2(ROOT/'scripts/payment-due-dates-support'/name,D/name)
shutil.copy2(__file__,D/'runtime-driver.py')
for jar,output in [('baseline.jar','baseline-openapi.json'),('server.jar','fixed-openapi.json')]:
 with zipfile.ZipFile(D/jar) as archive:(D/output).write_bytes(archive.read('BOOT-INF/classes/api/openapi.json'))
assert M['baselineSchema']==116 and M['targetSchema']==117
info={'directory':str(D),'backendPort':port(),'providerPort':port(),'sourceCommit':M['sourceCommit'],'jarSha256':M['jarSha256'],'boot':0,'stage':'PREPARED','entities':{},'people':{},'appointments':{},'processes':{},'cases':{},'preservedRuntime':87821}
result={'status':'RUNNING','syntheticOnly':True,'browserAcceptance':False,'postgresAcceptance':False,'checks':{},'boots':[]}
app=None;peer_dir=D;lock=threading.RLock();http=[];peer={'calls':[],'receipts':{},'bankVersion':'v1'};hold={'operation':None,'id':None,'entered':False};release=threading.Event();release.set()
BASE='http://127.0.0.1:'+str(info['backendPort'])+'/api/v1'
def save():
 with lock:write(Q/'f16-due-date-runtime-state.json',info);write(D/'runtime.json',info);write(D/'result.json',result)
def save_peer():
 with lock:write(peer_dir/'provider-state.json',peer)
def progress(stage):info['stage']=stage;save();print(json.dumps({'stage':stage,'boot':info['boot'],'directory':str(D)}),flush=True)
def checked(name,value=True):result['checks'][name]=value;save();print(json.dumps({'check':name,'status':'PASS'}),flush=True)
def wait(fn,label,seconds=65):
 end=time.monotonic()+seconds;last=time.monotonic()
 while time.monotonic()<end:
  assert app.poll() is None,'Owned application stopped during '+label
  value=fn()
  if value:return value
  if time.monotonic()-last>12:print(json.dumps({'waiting':label,'boot':info['boot']}),flush=True);last=time.monotonic()
  time.sleep(.25)
 raise AssertionError('Timeout: '+label)
def start(tag,workers=False,baseline=False,directory=None):
 global app
 assert app is None or app.poll() is not None;directory=directory or D
 settings={name:False for f in (ROOT/'agentflow-server/src/main/java').rglob('*.java') for name in re.findall(r'@ConditionalOnProperty\(name\s*=\s*"([^\"]*worker-enabled)"',f.read_text())}
 settings.update({'server.address':'127.0.0.1','spring.datasource.hikari.maximum-pool-size':7,'spring.datasource.hikari.minimum-idle':1,'agentflow.sla.reminders-enabled':False,'agentflow.timers.enabled':False,'agentflow.events.enabled':False,'agentflow.notifications.proxy-reminders-enabled':False,'agentflow.advances.overdue.reminders-enabled':False,'agentflow.assist.enabled':False,'agentflow.finance-gateway.tenants.demo.endpoint':'http://127.0.0.1:'+str(info['providerPort'])+'/finance','agentflow.finance-gateway.tenants.demo.allow-unauthenticated-loopback':True,'agentflow.finance-gateway.tenants.demo.timeout-seconds':20})
 for name in ['agentflow.expenses.precheck','agentflow.advance-requests.precheck','agentflow.budgets','agentflow.vouchers.preparation','agentflow.vouchers','agentflow.payments.request','agentflow.payments']:
  settings[name+'-worker-enabled' if name.endswith(('precheck','preparation','request')) else name+'.worker-enabled']=workers
 settings['agentflow.payments.payee-review-worker-enabled']=workers
 for name in ['agentflow.vouchers.preparation','agentflow.vouchers','agentflow.payments.request','agentflow.payments']:
  prefix=name+'-' if name.endswith(('preparation','request')) else name+'.';settings[prefix+'lease-seconds']=15;settings[prefix+'poll-delay-ms']=250
 env={k:v for k,v in os.environ.items() if not k.startswith(('AGENTFLOW_','SPRING_','SERVER_'))}
 env.update(AGENTFLOW_DATASOURCE_URL=db_url(directory),AGENTFLOW_DATASOURCE_DRIVER='org.h2.Driver',AGENTFLOW_DATASOURCE_USERNAME='sa',AGENTFLOW_DATASOURCE_PASSWORD='',AGENTFLOW_ATTACHMENT_DIRECTORY=str(directory/'attachments'),SERVER_PORT=str(info['backendPort']),AGENTFLOW_DEMO_AUTH='true',AGENTFLOW_FINANCE_GATEWAY_ENABLED='true',SPRING_APPLICATION_JSON=json.dumps(settings),TMPDIR='/fyoung/tmp')
 info['boot']+=1;jar=directory/('baseline.jar' if baseline else 'server.jar');write(D/('boot-'+str(info['boot'])+'-settings.json'),settings)
 with (D/('boot-'+str(info['boot'])+'.log')).open('x') as out:app=subprocess.Popen([JAVA,'-Xmx768m','-Djava.io.tmpdir=/fyoung/tmp','-Djdk.httpclient.HttpClient.log=errors','-jar',str(jar)],cwd=directory,env=env,stdout=out,stderr=subprocess.STDOUT)
 info.update(backendPid=app.pid,appJar=str(jar),appDirectory=str(directory));progress('STARTING');started=time.monotonic();last=started
 while True:
  assert time.monotonic()-started<120,'Readiness deadline exceeded';assert app.poll() is None,'Owned app exited before readiness'
  try:
   with urlopen(BASE.replace('/api/v1','')+'/actuator/health/readiness',timeout=1) as r:
    if json.load(r)['status']=='UP':break
  except OSError:pass
  if time.monotonic()-last>15:print(json.dumps({'waiting':'readiness','tag':tag,'pid':app.pid}),flush=True);last=time.monotonic()
  time.sleep(.3)
 result['boots'].append({'boot':info['boot'],'tag':tag,'baseline':baseline,'workers':workers,'pid':app.pid,'directory':str(directory),'readySeconds':round(time.monotonic()-started,2)});progress('RUNNING')
def stop(crash=False):
 assert app is not None and app.poll() is None and app.pid!=87821
 assert info['appJar'] in subprocess.check_output(['ps','-p',str(app.pid),'-o','command=']).decode(errors='replace')
 app.kill() if crash else app.terminate();app.wait(timeout=40);info['lastExitCode']=app.returncode;progress('STOPPED')
class Client:
 def __init__(self):
  self.tokens={}
  for actor in ['admin','alice','manager','finance','cashier','bob']:self.tokens[actor]=self.call('POST','/auth/login',actor,{'tenantId':'demo','username':actor,'password':'demo'})['token']
 def call(self,method,path,actor='admin',body=None,expected=200,key=None):
  headers={'Content-Type':'application/json'}
  if actor in self.tokens:headers['Authorization']='Bearer '+self.tokens[actor]
  raw=None if body is None else json.dumps(body,ensure_ascii=False,separators=(',',':')).encode()
  if body is not None and not path.startswith('/auth/'):headers['Idempotency-Key']=key or str(uuid.uuid4())
  try:response=urlopen(Request(BASE+path,method=method,headers=headers,data=raw),timeout=25)
  except HTTPError as error:response=error
  with response:
   content=response.read();value=json.loads(content) if content else None
   if not path.startswith('/auth/'):
    record={'at':now(),'boot':info['boot'],'method':method,'path':'/api/v1'+path,'actor':actor,'status':response.status,'rawRequest':raw.decode() if raw else None,'rawResponse':content.decode(),'response':copy.deepcopy(value),'cacheControl':response.headers.get('Cache-Control'),'replayed':response.headers.get('Idempotency-Replayed'),'idempotencyKey':headers.get('Idempotency-Key')}
    with lock:http.append(record);write(D/'http.json',http)
   assert response.status in (expected if isinstance(expected,tuple) else (expected,)),(method,path,response.status,value)
   return value

class Finance(BaseHTTPRequestHandler):
 # 响应已有精确 Content-Length，使用持久 HTTP/1.1，避免 1.0 关闭连接与客户端复用竞争。
 protocol_version='HTTP/1.1'
 def log_message(self,*_):pass
 def reply(self,value,status=200):
  raw=json.dumps(value,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers()
  try:self.wfile.write(raw)
  except (BrokenPipeError,ConnectionResetError):pass
 def do_POST(self):
  try:
   request=json.loads(self.rfile.read(int(self.headers['Content-Length'])));op=self.path.rsplit('/',1)[-1];data=request['data']
   assert self.path=='/finance/'+op and request['tenantId']=='demo' and request['contractVersion']==1
   with lock:peer['calls'].append({'at':now(),'operation':op,'request':request,'idempotencyKey':self.headers.get('Idempotency-Key')});save_peer()
   if op=='catalog':
    value={'employeeId':data['employeeId'],'sourceVersion':'synthetic-f16-catalog-v1','validUntil':now(3600),'legalEntities':[{'id':v['id'],'name':v['name'],'baseCurrency':'CNY','paperReceiptRequired':False,'sourceVersion':'synthetic-f16-paper-false','timeZone':'Asia/Shanghai'} for v in info['entities'].values()],'categories':[{'code':c['code'],'name':c['name'],'units':c['units']} for c in PACK['configuration']['categories']],'costCenters':[{'legalEntityId':v['id'],'code':'IT','name':'研发'} for v in info['entities'].values()],'projects':[],'cities':[{'code':'SH','name':'上海'}]}
   elif op=='employee-account':
    value={'snapshot':{'legalEntityId':data['legalEntityId'],'employeeId':data['employeeId'],'accountReference':'synthetic-f16-payee','maskedAccount':'****1234','accountDigest':'a'*64,'sourceVersion':'v1'},'validUntil':now(3600)}
   elif op=='exchange-rate':
    assert data['fromCurrency']==data['toCurrency']=='CNY';value={'fromCurrency':'CNY','toCurrency':'CNY','rate':1,'source':'synthetic-f16-rate','rateDate':data['rateDate']}
   elif op=='expense-policy':
    managed=data['managedPolicy'];line=data['line'];rule=next(r for r in managed['definition']['rules'] if line['categoryCode'] in r['match']['categoryCodes']);selection=managed['selection'];assert data['legalEntityId'] in rule['match']['legalEntityIds']
    value={'policy':{'policyId':selection['policyId'],'version':selection['policyVersion'],'assessedGross':line['claimedGross'],'allowedGross':line['claimedGross'],'decision':'WITHIN_LIMIT','taxRuleReference':'synthetic-f16-tax','evidenceReference':'synthetic-f16-assessment','exceptionReasons':[],'managedPolicy':{'selection':selection,'ruleKey':rule['key'],'factSourceReference':'synthetic-f16-facts'}},'deductibleTax':line['claimedTax'],'priorRequestRequired':False,'validUntil':now(3600)}
   elif op=='debit-accounts':
    assert data['currency']=='CNY' and data['cashierId']=='cashier';version=peer['bankVersion']
    value={'request':data,'sourceVersion':'synthetic-f16-bank-catalog-'+version,'observedAt':now(),'validUntil':now(600),'accounts':[{'reference':'synthetic-f16-bank-'+suffix,'displayName':'F16 合成账户 '+suffix+' '+version,'maskedAccount':'****'+('5678' if suffix=='x' else '6789'),'currency':'CNY','sourceVersion':version} for suffix in ['x','y']]}
   elif op=='accounting-period':
    date=datetime.date.fromisoformat(data['accountingDate']);value={'request':data,'periodReference':'synthetic-f16-period','sourceVersion':'v1','startsOn':str(date-datetime.timedelta(days=30)),'endsOn':str(date+datetime.timedelta(days=30)),'observedAt':now(),'validUntil':now(300)}
   elif op=='account-mapping':
    managed=data['managedMapping'];assert managed and managed['tenantId']=='demo' and managed['legalEntityId']==data['legalEntityId'];assert sorted((v['key']['role'],v['key']['selector']) for v in managed['entries'])==sorted((v['role'],v['selector']) for v in data['keys'])
    value={'request':data,'sourceVersion':'synthetic-f16-erp-v1','observedAt':now(),'validUntil':now(300),'entries':managed['entries']}
   elif op=='budget-precheck':value={'request':data,'reference':'synthetic-f16-budget-precheck','checkedAt':now(-1),'validUntil':now(3600)}
   elif op in ('budget-command','budget-query'):
    id=data['command']['id'] if op.endswith('command') else data['operationId'];key='budget:'+id
    with lock:
     if op.endswith('command'):
      command=data['command'];assert self.headers.get('Idempotency-Key')==id
      if key not in peer['receipts']:peer['receipts'][key]={'operationId':id,'commandDigest':data['commandDigest'],'status':'APPLIED','ledgerRevision':command.get('expected',{}).get('revision',0)+1,'reference':'synthetic-f16-budget-'+id,'appliedAt':now()};save_peer()
     value=peer['receipts'].get(key,{'operationId':id,'commandDigest':data['commandDigest'],'status':'NOT_FOUND'})
   elif op in ('voucher-command','voucher-query','payment-command','payment-query'):
    payment=op.startswith('payment');id=data['command']['id'] if op.endswith('command') else data['authorizationId' if payment else 'operationId'];key=('payment:' if payment else 'voucher:')+id
    with lock:
     saved=peer['receipts'].get(key)
     if op.endswith('command'):
      command=data['command'];assert self.headers.get('Idempotency-Key')==id
      if saved:assert saved['command']==command and saved['digest']==data['commandDigest'];saved['receivedCommands']+=1
      else:
       stamp=now()
       if payment:
        assert command['debitAccountReference'] in ['synthetic-f16-bank-x','synthetic-f16-bank-y']
        receipt={'authorizationId':id,'commandDigest':data['commandDigest'],'status':'SUCCEEDED','revision':1,'observedAt':stamp,'paymentReference':'synthetic-f16-payment-'+id,'paidAmount':command['amount'],'accountDigest':command['payee']['accountDigest'],'completedAt':stamp,'receiptReference':'synthetic-f16-receipt-'+id,'failure':None}
       else:
        assert command['mapping']['request']['managedMapping'];receipt={'operationId':id,'commandDigest':data['commandDigest'],'status':'POSTED','revision':1,'observedAt':stamp,'postingReference':'synthetic-f16-posting-'+id,'voucherReference':'synthetic-f16-voucher-'+id,'periodReference':command['period']['periodReference'],'accountingDate':command['accountingDate'],'debitTotal':command['totals']['gross'],'creditTotal':command['totals']['gross'],'postedAt':stamp,'failure':None}
       saved={'command':command,'digest':data['commandDigest'],'receipt':receipt,'acceptedCommands':1,'receivedCommands':1};peer['receipts'][key]=saved
      save_peer()
     if saved:assert saved['digest']==data['commandDigest'];value={**saved['receipt'],'observedAt':now()}
     elif payment:value={'authorizationId':id,'commandDigest':data['commandDigest'],'status':'NOT_FOUND','revision':0,'observedAt':now(),'paymentReference':None,'paidAmount':None,'accountDigest':None,'completedAt':None,'receiptReference':None,'failure':None}
     else:value={'operationId':id,'commandDigest':data['commandDigest'],'status':'NOT_FOUND','revision':0,'observedAt':now(),'postingReference':None,'voucherReference':None,'periodReference':None,'accountingDate':None,'debitTotal':None,'creditTotal':None,'postedAt':None,'failure':None}
    if hold['operation']==op and hold['id']==id:hold['entered']=True;assert release.wait(60),'Synthetic response hold timed out'
   else:raise AssertionError('Unexpected synthetic operation '+op)
   self.reply({'contractVersion':1,'tenantId':'demo','requestId':request['requestId'],'outcome':'SUCCESS','data':value})
  except Exception:
   with (D/'provider-errors.log').open('a') as out:out.write(traceback.format_exc()+'\n')
   self.reply({'error':'Synthetic contract failure'},500)

def setup(c):
 c.call('POST','/organization/initialize',body={},expected=201)
 for label in ['A','B','C']:
  entity=c.call('POST','/organization/units',body={'kind':'LEGAL_ENTITY','name':'F16 法人 '+label,'legalEntityId':None,'parentDepartmentId':None,'active':True},expected=201);info['entities'][label]=entity
  for kind in ['DEPARTMENT','POSITION']:entity[kind]=c.call('POST','/organization/units',body={'kind':kind,'name':'F16 '+label+' '+kind,'legalEntityId':entity['id'],'parentDepartmentId':None,'active':True},expected=201)
 for actor in ['alice','manager','finance','cashier']:
  person=c.call('POST','/organization/people',body={'subject':actor,'displayName':'F16 '+actor,'active':True,'approvalEligible':True},expected=201);info['people'][actor]=person;info['appointments'][actor]={}
  for label,entity in info['entities'].items():info['appointments'][actor][label]=c.call('POST','/organization/appointments',body={'personId':person['id'],'departmentId':entity['DEPARTMENT']['id'],'positionId':entity['POSITION']['id'],'active':True},expected=201)
 for label in info['entities']:
  a=info['appointments']['alice'][label];info['appointments']['alice'][label]=c.call('PUT','/organization/appointments/'+a['id']+'/supervisor',body={'appointmentId':info['appointments']['manager'][label]['id'],'expectedRevision':a['revision']})
 categories=c.call('GET','/admin/expense-categories');categories=c.call('PUT','/admin/expense-categories',body={'expectedVersion':categories['version'],'categories':categories['categories']+PACK['configuration']['categories'],'comment':'F16 合成验收类别'})
 policy=copy.deepcopy(PACK['configuration']['expensePolicy'])
 for rule in policy['rules']:rule['match']['legalEntityIds']=[v['id'] for v in info['entities'].values()]
 active=c.call('GET','/admin/expense-policies/current');draft=c.call('PUT','/admin/expense-policies/f16-runtime/draft',body={'expectedRevision':0,'definition':policy,'comment':'F16 独立制度'})
 c.call('POST','/admin/expense-policies/f16-runtime/publish',body={'expectedDraftRevision':draft['revision'],'expectedCategoryRevision':categories['version'],'expectedActiveRevision':active['activeRevision'],'comment':'F16 发布合成验收制度'})
 for label,entity in info['entities'].items():
  mapping=copy.deepcopy(PACK['configuration']['accountMapping']);mapping['legalEntityId']=entity['id'];mapping['entries']=[v for v in mapping['entries'] if v['key']['role']!='BANK']+[{'key':{'role':'BANK','selector':'synthetic-f16-bank-'+suffix},'accountCode':'SYNTHETIC.BANK.'+suffix.upper()} for suffix in ['x','y']]
  active=c.call('GET','/admin/account-mappings/current?'+urlencode({'legalEntityId':entity['id'],'currency':'CNY'}));path='/admin/account-mappings/f16-'+label.lower();draft=c.call('PUT',path+'/draft',body={'expectedRevision':0,'definition':mapping,'comment':'F16 原版本科目'})
  c.call('POST',path+'/publish',body={'expectedDraftRevision':draft['revision'],'expectedCategoryRevision':categories['version'],'expectedActiveRevision':active['activeRevision'],'comment':'F16 显式发布法人科目'})
 templates=c.call('GET','/process-templates')
 for key in ['advance-request','expense-report']:
  template=next(v for v in templates if v['key']==key)
  draft=c.call('POST','/process-templates/'+key+'/copy',body={'key':'f16-runtime-'+key,'name':'F16 实际办理 '+key,'templateVersion':template['templateVersion']})
  graph=copy.deepcopy(draft['graph'])
  for node in graph['nodes']:
   if node['type']=='USER_TASK' and node['id']!='supervisor':node['properties']['assigneeRule']='role:ORG_PERSON_'+info['people']['finance' if node['id'] in ('receipt','finance','recheck') else 'manager']['id']
  updated=c.call('PUT','/process-definitions/'+draft['id'],body={'name':draft['name'],'graph':graph,'formSchema':draft['formSchema'],'notificationTexts':draft['notificationTexts'],'expectedRevision':draft['revision']})
  info['processes'][key]=c.call('POST','/process-definitions/'+draft['id']+'/publish?expectedRevision='+str(updated['revision']),body={'changeNote':'F16 按实际组织人员发布模板'})
 save();checked('organizationAndPublishedConfiguration',{'legalEntities':3,'cashierAppointments':3,'publishedFinancialTemplates':2})

def create_authorization(c,name,label,expense=False,due_date=None):
 scenario=next(v for v in PACK['scenarios'] if v['id']==('expense-basic' if expense else 'advance-basic'));content=copy.deepcopy(scenario['content']);content['legalEntityId']=info['entities'][label]['id'];content['title']='F16 '+name
 if expense:
  for line in content['lines']:line['incurredOn']=DATE
 else:content['dueOn']=str(datetime.date.fromisoformat(DATE)+datetime.timedelta(days=30))
 kind=scenario['templateKey'];process=info['processes'][kind];resource='expense-reports' if expense else 'advance-requests';version='financialVersion' if expense else 'requestVersion'
 created=c.call('POST','/'+resource,'alice',{'businessNo':'F16-'+name+'-'+uuid.uuid4().hex[:6],'processKey':process['key'],'definitionVersion':process['version'],'content':content},201);path='/'+resource+'/'+created['id'];case={'name':name,'legal':label,'resource':resource,'id':created['id'],'applicationId':created['applicationId']};info['cases'][name]=case;save()
 detail=c.call('GET',path,'alice');options=c.call('GET',path+('/precheck-options' if expense else '/prechecks/options'),'alice');body={k:detail[k] for k in ['applicationVersion',version]};body.update(initiatorAppointmentId=info['appointments']['alice'][label]['id'],targetDigest=options['targetDigest'])
 if expense:body['accountingDate']=DATE
 queued=c.call('POST',path+('/precheck' if expense else '/prechecks'),'alice',body,202)
 def prechecked():
  value=c.call('GET',path+'/prechecks/'+queued['id'],'alice')
  if value['job']['status'] in ['QUEUED','RUNNING']:return None
  assert value['usable'] and value['job']['status']=='READY',value
  return value
 case['precheck']=wait(prechecked,name+' precheck');detail=c.call('GET',path,'alice');case['submit']=c.call('POST',path+'/submit','alice',{**{k:detail[k] for k in ['applicationVersion',version]},'precheckId':queued['id']});save()
 if expense:wait(lambda:c.call('GET',path+'/workflow','alice')['budget']['confirmedCurrent'],name+' confirmed budget reservation')
 for node in (['supervisor','receipt','finance'] if expense else ['supervisor','finance']):
  actor='manager' if node=='supervisor' else 'finance'
  def current_task():
   values=[t for t in c.call('GET','/tasks',actor) if t['applicationId']==created['applicationId']]
   assert len(values)<=1,values
   return values[0] if values else None
  task=wait(current_task,name+' '+node);expected_name=next(v['name'] for v in process['graph']['nodes'] if v['id']==node);assert task['taskName']==expected_name,task
  case['lastDecision']=c.call('POST','/tasks/'+task['taskId']+'/actions',actor,{'action':'APPROVE','comment':'F16 实际办理 '+node,'expectedVersion':task['version']});save()
 assert case['lastDecision']['applicationStatus']=='APPROVED',case
 def payable():
  value=c.call('GET','/applications/'+created['applicationId']+'/payments','finance')
  if value['actions']['authorize']:return value
  voucher=c.call('GET','/applications/'+created['applicationId']+'/vouchers','finance')
  preparation=voucher.get('preparation')
  assert not preparation or preparation['status'] not in ['UNAVAILABLE','BLOCKED'],voucher
  return None
 value=wait(payable,name+' voucher posted');body={k:value[k] for k in ['roundNo','applicationVersion','businessVersion','voucherOperationId','voucherVersion']};body.update(validitySeconds=3600,comment='F16 财务核对原轮次、凭证与金额后授权')
 if due_date is not None:body['dueDate']=due_date
 case.update(authorizationBody=copy.deepcopy(body),authorizationKey=str(uuid.uuid4()),dueDate=due_date)
 endpoint='/applications/'+created['applicationId']+'/payments/authorizations'
 if due_date is not None:
  missing={k:v for k,v in body.items() if k!='dueDate'}
  rejected=c.call('POST',endpoint,'finance',missing,422);assert rejected['code']=='INVALID_PAYMENT_DUE_DATE',rejected
 case['authorization']=c.call('POST',endpoint,'finance',body,202,key=case['authorizationKey']);save();progress('AUTHORIZED_'+name);return case
def detail(c,name):return c.call('GET','/cashier/payments/'+info['cases'][name]['authorization']['authorizationId'],'cashier')
def execute(c,name,suffix='x',finish=True):
 case=info['cases'][name];id=case['authorization']['authorizationId'];path='/cashier/payments/'+id;accounts=c.call('GET',path+'/accounts','cashier');account=next(v for v in accounts['items'] if v['reference']=='synthetic-f16-bank-'+suffix)
 body={'action':'EXECUTE','authorizationVersion':accounts['authorizationVersion'],'debitAccountReference':account['reference'],'debitAccountVersion':account['sourceVersion'],'comment':'F16 出纳逐笔核对并执行'};case['executionBody']=body;case['executionKey']=str(uuid.uuid4());case['executionReceipt']=c.call('POST',path+'/actions','cashier',body,202,key=case['executionKey']);save()
 if finish:
  def paid():
   value=detail(c,name);return value if (value['payment'].get('operation') or {}).get('status')=='SUCCEEDED' else None
  case['paid']=wait(paid,name+' payment');save()
 return case
def set_appointment(c,label,active):
 a=info['appointments']['cashier'][label];info['appointments']['cashier'][label]=c.call('PUT','/organization/appointments/'+a['id'],body={'active':active,'expectedRevision':a['revision']});save()
def snapshot(directory,label,old_columns=False):
 out=D/(label+'.tsv');subprocess.run([JAVA,'-Djava.io.tmpdir=/fyoung/tmp','-cp',str(D/'h2.jar'),str(D/'SnapshotH2.java'),str(directory/'database'),str(out)]+(['old-columns'] if old_columns else []),check=True,timeout=60,stdout=subprocess.DEVNULL)
 return {p[0]:(int(p[1]),p[2]) for line in out.read_text().splitlines() if (p:=line.split('\t'))}
def h2(tool,directory,script,*args):
 with (D/(tool+'-'+str(info['boot'])+'.log')).open('w') as log:subprocess.run([JAVA,'-Djava.io.tmpdir=/fyoung/tmp','-cp',str(D/'h2.jar'),'org.h2.tools.'+tool,'-url',db_url(directory),'-user','sa','-password','','-script',str(script),*args],check=True,timeout=60,stdout=log,stderr=subprocess.STDOUT)
def files(directory):return {str(p.relative_to(directory)):sha(p) for p in directory.rglob('*') if p.is_file()}
def id_of(name):return info['cases'][name]['authorization']['authorizationId']
def list_page(c,parameters=None):return c.call('GET','/cashier/payments'+('?' + urlencode(parameters) if parameters else ''),'cashier')
def check_filters(c,label):
 A=info['entities']['A']['id'];B=info['entities']['B']['id'];C=info['entities']['C']['id']
 page=list_page(c);assert page['totalCount']==6 and len(page['items'])==6 and id_of('C-hidden') not in [v['payment']['id'] for v in page['items']],page
 options=c.call('GET','/cashier/payments/filter-options','cashier');assert {v['id'] for v in options['legalEntities']}=={A,B}
 ax=detail(c,'A-paid')['debitAccount'];ax2=detail(c,'A-expense')['debitAccount'];bx=detail(c,'B-paid')['debitAccount'];by=detail(c,'B-other')['debitAccount']
 assert ax['key']==ax2['key'] and len({ax['key'],bx['key'],by['key']})==3
 for v in options['accounts']:assert 'reference' not in v and 'targetDigest' not in v
 filtered=list_page(c,{'legalEntityId':A,'debitAccount':ax['key'],'limit':1});assert filtered['totalCount']==2 and len(filtered['items'])==1 and filtered['nextBeforeId']
 rest=list_page(c,{'legalEntityId':A,'debitAccount':ax['key'],'limit':1,'beforeId':filtered['nextBeforeId']});assert rest['totalCount']==2 and len(rest['items'])==1 and rest['nextBeforeId'] is None
 assert {filtered['items'][0]['payment']['id'],rest['items'][0]['payment']['id']}=={id_of('A-paid'),id_of('A-expense')}
 c.call('GET','/cashier/payments?'+urlencode({'legalEntityId':B,'beforeId':filtered['nextBeforeId']}),'cashier',expected=400)
 assert list_page(c,{'legalEntityId':C})=={'items':[],'nextBeforeId':None,'totalCount':0}
 assert list_page(c,{'legalEntityId':A,'debitAccount':bx['key']})['totalCount']==0
 c.call('GET','/cashier/payments/'+id_of('C-hidden'),'cashier',expected=404)
 for actor in ['admin','finance','alice','manager','bob']:
  c.call('GET','/cashier/payments',actor,expected=403);c.call('GET','/cashier/payments/filter-options',actor,expected=403)
 first=c.call('GET','/cashier/payments/filter-options?limit=1','cashier');all_keys=[first['accounts'][0]['key']];cursor=first['nextAfterAccountKey']
 while cursor:
  nxt=c.call('GET','/cashier/payments/filter-options?'+urlencode({'limit':1,'afterAccountKey':cursor}),'cashier');all_keys.extend(v['key'] for v in nxt['accounts']);cursor=nxt['nextAfterAccountKey']
 assert len(all_keys)==len(set(all_keys))==len(options['accounts'])
 for item in http:
  if item['boot']==info['boot'] and item['path'].startswith('/api/v1/cashier/payments') and item['status']==200:assert 'no-store' in item['cacheControl']
 checked(label,{'visibleAuthorizations':6,'hiddenAuthorization':id_of('C-hidden'),'stableSameAccountAcrossVersions':True,'filterCountAcrossPages':2,'accountOptionCount':len(all_keys),'bothEmployeeAdvanceAndExpense':True})
 return page

def migration():
 start('baseline-v116',workers=True,baseline=True);c=Client();setup(c)
 for name,label,expense,account in [('A-paid','A',False,'x'),('A-expense','A',True,'x'),('A-pending','A',False,None),('A-restore','A',False,None),('B-paid','B',False,'x'),('B-other','B',False,'y'),('C-hidden','C',False,'x')]:
  if name=='A-expense':peer['bankVersion']='v2';save_peer()
  create_authorization(c,name,label,expense)
  if account:execute(c,name,account)
 set_appointment(c,'C',False);stop();before=snapshot(D,'before-v117',True);original=files(D/'attachments');h2('Script',D,D/'baseline-v116.sql')
 start('upgraded-v117-worker-off');stop();after=snapshot(D,'after-v117',True);changed=[k for k in before if before[k]!=after[k]];assert changed==['flyway_schema_history'],changed;assert before.keys()==after.keys();assert files(D/'attachments')==original
 checked('nonemptyV116ToV117',{'preservedTables':len(before)-1,'oldRows':sum(v[0] for k,v in before.items() if k!='flyway_schema_history'),'originalPaymentAuthorizations':7,'executedPaymentCommands':5,'oldColumnsUnchanged':True})

def crash_and_scope():
 start('fixed-package-worker-on',workers=True);c=Client();check_filters(c,'filtersAfterUpgrade')
 pending=list_page(c,{'debitAccount':'UNASSIGNED'});assert pending['totalCount']==2 and {v['payment']['id'] for v in pending['items']}=={id_of('A-pending'),id_of('A-restore')}
 set_appointment(c,'B',False);assert list_page(c)['totalCount']==4
 options=c.call('GET','/cashier/payments/filter-options','cashier');assert len(options['accounts'])==1 and all(v['legalEntityId']==info['entities']['A']['id'] for v in options['accounts'])
 c.call('GET','/cashier/payments?'+urlencode({'beforeId':id_of('B-paid')}),'cashier',expected=404)
 set_appointment(c,'B',True);assert list_page(c)['totalCount']==6
 checked('revokedAppointmentAffectsListCountOptionsAndCursor')
 hold.update(operation='payment-command',id=id_of('A-pending'),entered=False);release.clear();execute(c,'A-pending','y',False)
 wait(lambda:hold['entered'],'provider committed payment before held response');registered=detail(c,'A-pending');assert registered['debitAccount'] is not None and registered['payment']['executedBy']=='cashier';info['pendingAccountKey']=registered['debitAccount']['key'];save()
 stop(crash=True);hold['operation']=None;release.set();save_peer();start('resume-original-payment',workers=True);c=Client()
 wait(lambda:(detail(c,'A-pending')['payment'].get('operation') or {}).get('status')=='SUCCEEDED','reconcile original payment')
 case=info['cases']['A-pending'];assert c.call('POST','/cashier/payments/'+id_of('A-pending')+'/actions','cashier',case['executionBody'],202,key=case['executionKey'])==case['executionReceipt']
 assert detail(c,'A-pending')['debitAccount']['key']==info['pendingAccountKey'];receipt=peer['receipts']['payment:'+id_of('A-pending')];assert receipt['acceptedCommands']==receipt['receivedCommands']==1
 assert list_page(c,{'debitAccount':'UNASSIGNED'})['totalCount']==1;check_filters(c,'filtersAfterForcedRestart')
 checked('fixedAccountSurvivesCrashAndOriginalCommandReconciles',{'originalAuthorization':id_of('A-pending'),'acceptedCommands':1,'receivedCommands':1,'originalActionReplay':True})
 stop()

def finance_view(c,name):
 return c.call('GET','/applications/'+info['cases'][name]['applicationId']+'/payments','finance')

def check_dates(c,label):
 """期望顺序来自固定授权事实，逐页核对结果和总数，覆盖有日期到空值的边界。"""
 expected={id_of(name):case['dueDate'] for name,case in info['cases'].items() if case['legal']!='C'}
 page=list_page(c,{'sort':'DUE_DATE_ASC'});items=page['items']
 assert page['totalCount']==len(expected) and {v['payment']['id'] for v in items}==set(expected),page
 for v in items:assert v['payment']['dueDate']==expected[v['payment']['id']],v
 ordered=sorted(items,key=lambda v:(v['payment']['authorizedAt'],v['payment']['id']),reverse=True)
 ordered.sort(key=lambda v:(v['payment']['dueDate'] is None,v['payment']['dueDate'] or ''))
 wanted=[v['payment']['id'] for v in ordered];assert [v['payment']['id'] for v in items]==wanted
 for limit in [1,2]:
  found=[];cursor=None
  while True:
   parameters={'sort':'DUE_DATE_ASC','limit':limit}
   if cursor:parameters['beforeId']=cursor
   p=list_page(c,parameters);assert p['totalCount']==len(expected)
   found.extend(v['payment']['id'] for v in p['items']);cursor=p['nextBeforeId']
   if cursor is None:break
   assert len(found)<=len(expected),'Cursor did not advance'
  assert found==wanted and len(found)==len(set(found)),found
 undated=list_page(c,{'undated':'true','sort':'DUE_DATE_ASC'})
 assert {v['payment']['id'] for v in undated['items']}=={key for key,date in expected.items() if date is None}
 assert undated['totalCount']==6
 same=list_page(c,{'dueFrom':'2026-01-01','dueTo':'2026-01-01','sort':'DUE_DATE_ASC'})
 assert same['totalCount']==2 and {v['payment']['id'] for v in same['items']}=={id_of('A-early'),id_of('A-same-expense')}
 key=detail(c,'A-early')['debitAccount']['key'];entity=info['entities']['A']['id']
 combined=list_page(c,{'legalEntityId':entity,'debitAccount':key,'dueFrom':'2026-01-01','dueTo':'2026-01-01','sort':'DUE_DATE_ASC','limit':1})
 assert combined['totalCount']==2 and len(combined['items'])==1 and combined['nextBeforeId']
 c.call('GET','/cashier/payments?'+urlencode({'dueFrom':'2026-02-01','beforeId':combined['nextBeforeId']}),'cashier',expected=400)
 c.call('GET','/cashier/payments?'+urlencode({'sort':'DUE_DATE_ASC','beforeId':id_of('C-hidden')}),'cashier',expected=404)
 assert list_page(c,{'dueTo':'2026-01-01'})['totalCount']==2
 assert list_page(c,{'dueFrom':'9999-12-31'})['totalCount']==1
 assert list_page(c,{'legalEntityId':info['entities']['C']['id'],'dueFrom':'0001-01-01','sort':'DUE_DATE_ASC'})['totalCount']==0
 for query in ['dueFrom=2026-02-30','dueFrom=0000-01-01','dueFrom=2026-1-01','dueFrom=','dueFrom=2026-02-01&dueTo=2026-01-01','undated=false','undated=true&dueFrom=2026-01-01','sort=unknown','dueFrom=2026-01-01&dueFrom=2026-01-01','sort=DUE_DATE_ASC&sort=DUE_DATE_ASC']:
  rejected=c.call('GET','/cashier/payments?'+query,'cashier',expected=400);assert rejected['code']=='INVALID_PAYMENT_QUERY'
 for actor in ['admin','finance','alice','manager','bob']:
  c.call('GET','/cashier/payments?sort=DUE_DATE_ASC',actor,expected=403)
 for item in http:
  if item['boot']==info['boot'] and item['path'].startswith('/api/v1/cashier/payments') and item['status']==200:assert 'no-store' in item['cacheControl']
 checked(label,{'visibleAuthorizations':len(expected),'undated':6,'sameDateAuthorizations':2,'pageSizes':[1,2],'scopedCombinedFilter':True,'order':wanted})

def dated_scenarios():
 """旧请求仍原键回放；新授权和账户复核后的替代授权必须重新明确日期。"""
 start('due-date-actions',workers=True);c=Client()
 for name,case in list(info['cases'].items()):
  path='/applications/'+case['applicationId']+'/payments/authorizations'
  assert c.call('POST',path,'finance',case['authorizationBody'],202,key=case['authorizationKey'])==case['authorization']
  assert http[-1]['replayed']=='true'
  rejected=c.call('POST',path,'finance',case['authorizationBody'],422);assert rejected['code']=='INVALID_PAYMENT_DUE_DATE'
  assert finance_view(c,name)['payment']['dueDate'] is None
 checked('oldAuthorizationKeysReplayMissingDateButNewKeysReject',{'originalKeys':7,'authorizationReceiptsUnchanged':True})
 for name,label,expense,date in [('A-early','A',False,'2026-01-01'),('A-same-expense','A',True,'2026-01-01'),('B-late','B',False,'9999-12-31'),('A-review-original','A',False,'2026-05-01')]:
  case=create_authorization(c,name,label,expense,date)
  path='/applications/'+case['applicationId']+'/payments/authorizations'
  assert c.call('POST',path,'finance',case['authorizationBody'],202,key=case['authorizationKey'])==case['authorization']
  c.call('POST',path,'finance',{**case['authorizationBody'],'dueDate':'2026-12-31'},409,key=case['authorizationKey'])
  assert detail(c,name)['payment']['dueDate']==date
  if name in ['A-early','A-same-expense']:execute(c,name,'x')
 original=info['cases']['A-review-original'];id=id_of('A-review-original');v=finance_view(c,'A-review-original')
 c.call('POST','/payments/'+id+'/finance-actions','finance',{'action':'VOID','authorizationVersion':v['payment']['version'],'comment':'F16 重新核对账户前明确作废'},202)
 v=finance_view(c,'A-review-original');assert v['actions']['reviewAccount'] and v['payment']['dueDate']=='2026-05-01'
 c.call('POST','/payments/'+id+'/payee-reviews','finance',{'authorizationVersion':v['payment']['version'],'voucherVersion':v['voucherVersion'],'comment':'F16 主动复核本人账户'},202)
 def reviewed():
  value=finance_view(c,'A-review-original');return value if value['actions']['authorizeReviewed'] else None
 v=wait(reviewed,'fresh reviewed payee');body={k:v[k] for k in ['roundNo','applicationVersion','businessVersion','voucherOperationId','voucherVersion']}
 body.update(validitySeconds=3600,comment='F16 账户复核后再次明确新日期',payeeReviewId=v['payeeReview']['id'],payeeReviewVersion=v['payeeReview']['version'])
 path='/applications/'+original['applicationId']+'/payments/authorizations'
 rejected=c.call('POST',path,'finance',body,422);assert rejected['code']=='INVALID_PAYMENT_DUE_DATE'
 body['dueDate']='2026-06-01';key=str(uuid.uuid4());receipt=c.call('POST',path,'finance',body,202,key=key)
 info['cases']['A-reviewed']={**copy.deepcopy(original),'name':'A-reviewed','authorizationBody':body,'authorizationKey':key,'authorization':receipt,'dueDate':'2026-06-01'};save()
 assert detail(c,'A-reviewed')['payment']['dueDate']=='2026-06-01' and detail(c,'A-review-original')['payment']['dueDate']=='2026-05-01'
 checked('reviewedAccountAuthorizationHasNewExplicitDateOldDecisionUnchanged')
 check_dates(c,'datesBeforeForcedRestart')
 hold.update(operation='payment-command',id=id_of('A-reviewed'),entered=False);release.clear();execute(c,'A-reviewed','y',False)
 wait(lambda:hold['entered'],'dated payment committed before held response');before=copy.deepcopy(peer['receipts']['payment:'+id_of('A-reviewed')]);assert 'dueDate' not in json.dumps(before['command'])
 stop(crash=True);hold['operation']=None;release.set();save_peer();start('resume-original-dated-payment',workers=True);c=Client()
 wait(lambda:(detail(c,'A-reviewed')['payment'].get('operation') or {}).get('status')=='SUCCEEDED','reconcile dated payment')
 case=info['cases']['A-reviewed'];assert c.call('POST','/cashier/payments/'+id_of('A-reviewed')+'/actions','cashier',case['executionBody'],202,key=case['executionKey'])==case['executionReceipt']
 assert peer['receipts']['payment:'+id_of('A-reviewed')]==before
 assert before['receivedCommands']==before['acceptedCommands']==1
 check_dates(c,'datesAfterForcedRestart')
 checked('datedPaymentCrashKeepsDateOriginalCommandAndDigest',{'authorizationId':id_of('A-reviewed'),'acceptedCommands':1,'receivedCommands':1,'originalCommandUnchanged':True})
 stop()

def restored():
 global peer,peer_dir
 tables=snapshot(D,'restore-source');backup=D/'backup';backup.mkdir();h2('Script',D,backup/'database.sql');h2('Script',D,backup/'schema.sql','-options','NODATA','NOPASSWORDS')
 for name in ['server.jar','provider-state.json']:shutil.copy2(D/name,backup/name)
 for name in ['attachments','web-dist']:
  if not (D/name).exists():(D/name).mkdir()
  shutil.copytree(D/name,backup/name)
 restored=Path(tempfile.mkdtemp(prefix='agentflow-due-restored-',dir='/fyoung/tmp'));info['restoreDirectory']=str(restored);save()
 for name in ['server.jar','provider-state.json']:shutil.copy2(backup/name,restored/name)
 for name in ['attachments','web-dist']:shutil.copytree(backup/name,restored/name)
 h2('RunScript',restored,backup/'database.sql');assert snapshot(restored,'restore-target')==tables;h2('Script',restored,restored/'schema.sql','-options','NODATA','NOPASSWORDS')
 schema=json.loads(subprocess.check_output([JAVA,'-Djava.io.tmpdir=/fyoung/tmp','-cp',str(D/'h2.jar'),str(D/'CompareH2Schema.java'),str(backup/'schema.sql'),str(restored/'schema.sql')],text=True,timeout=60));assert schema['equal'];write(D/'restore-schema-comparison.json',schema)
 assert files(restored/'attachments')==files(D/'attachments') and files(restored/'web-dist')==M['webFiles'] and sha(restored/'server.jar')==M['jarSha256']
 peer_dir=restored;peer=json.loads((restored/'provider-state.json').read_text());start('independently-restored-worker-on',workers=True,directory=restored);c=Client();check_dates(c,'datesAfterIndependentRestore');execute(c,'A-restore','y');execute(c,'B-late','x')
 assert list_page(c,{'debitAccount':'UNASSIGNED'})['totalCount']==1;assert detail(c,'A-restore')['debitAccount']['key']==info['pendingAccountKey'];assert detail(c,'A-restore')['payment']['dueDate'] is None;assert detail(c,'B-late')['payment']['dueDate']=='9999-12-31';check_dates(c,'datesAfterRestoredExecution');stop()
 assert snapshot(D,'source-unchanged-after-restore')==tables
 checked('independentRestoreAndOldAuthorizationContinues',{'tables':len(tables),'rows':sum(v[0] for v in tables.values()),'schemaStatements':schema['statements'],'sourceDatabaseUnchanged':True,'oldAuthorization':id_of('A-restore'),'oldAccountKeyRetained':True,'datedAuthorizationAlsoContinued':id_of('B-late'),'restoreDirectory':str(restored)})

server=ThreadingHTTPServer(('127.0.0.1',info['providerPort']),Finance);server.daemon_threads=True;threading.Thread(target=server.serve_forever,daemon=True).start();save_peer();save()
try:
 migration();crash_and_scope();dated_scenarios();restored();assert not (D/'provider-errors.log').exists()
 result.update(status='PASS',httpResponses=len(http),providerCalls=len(peer['calls']));progress('COMPLETE');print(json.dumps({'result':'PASS','directory':str(D),'httpResponses':len(http),'boots':len(result['boots'])}),flush=True)
except Exception:
 result['status']='FAIL';result['failure']=traceback.format_exc();save();raise
finally:
 release.set()
 if app is not None and app.poll() is None:stop()
 server.shutdown();server.server_close();result['ownedServicesStopped']=True;save()
