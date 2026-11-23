import json
from uuid import uuid4
from types import SimpleNamespace
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app import report_email as mail
from app import trip_report_email as route
from app.report_email_store import Store

@pytest.fixture(autouse=True)
def env(monkeypatch,tmp_path):
    for k in ['REPORT_EMAIL_DATABASE_URL','RUNTIME_DATABASE_URL','DATABASE_URL','RENDER']:monkeypatch.delenv(k,raising=False)
    for k,v in {'REPORT_EMAIL_DATABASE_PATH':str(tmp_path/'mail.db'),'REPORT_EMAIL_ENABLED':'true',
      'REPORT_FROM_EMAIL':'sender@example.com',
      'REPORT_EMAIL_PROVIDER':'smtp','SMTP_HOST':'smtp.example.com','SMTP_USERNAME':'sender@example.com',
      'SMTP_PASSWORD':'synthetic','SMTP_SECURITY':'starttls','SMTP_PORT':'587'}.items():monkeypatch.setenv(k,v)
    monkeypatch.setattr(mail,'send_mail',lambda *a:None)

def meta():
    return dict(tripId='trip-A',plannedTripId=None,parentEmail='parent@example.com',subject='NextCross report: Home → School',
      plainText='NextCross trip report\nFrom: Home\n',includeDetailedRoute=False,includeReflectionConversation=False,
      startedAtEpochMillis=1728000000000,endedAtEpochMillis=1728003600000,startingPointLabel='Home',
      destinationLabel='School',hardAccelerationCount=1,hardBrakingCount=0,clientRequestId=str(uuid4()))

def post(c,m,files=None,as_file=False):
    parts={'meta':('meta.json',json.dumps(m),'application/json') if as_file else (None,json.dumps(m))}
    parts.update(files or {})
    return c.post(route.PATH,files=parts)

def test_text_accepted_duplicate_conflict(monkeypatch):
    calls=[];monkeypatch.setattr(mail,'send_mail',lambda *a:calls.append(a))
    with TestClient(app) as c:
        m=meta();r=post(c,m);again=post(c,m,as_file=True)
        m['plainText']='changed';conflict=post(c,m)
    assert r.status_code==200 and r.json()['status']=='accepted'
    assert again.status_code==200 and again.json()['duplicate']
    assert conflict.status_code==409 and conflict.json()['code']=='IDEMPOTENCY_CONFLICT'
    assert len(calls)==1 and calls[0][2].report_text=='NextCross trip report\nFrom: Home\n'
    with Store().db() as db:row=dict(db.execute('SELECT * FROM report_email_dispatch').fetchone())
    assert 'parent@example.com' not in json.dumps(row) and 'Home' not in json.dumps(row)

def test_attachments_and_digest(monkeypatch):
    calls=[];monkeypatch.setattr(mail,'send_mail',lambda *a:calls.append(a))
    with TestClient(app) as c:
        m=meta();m['includeDetailedRoute']=True
        f={'reportPdf':('../../evil.pdf',b'%PDF-1.4\nsynthetic','application/pdf'),
           'routeMap':('private-name.png',b'\x89PNG\r\n\x1a\nsynthetic','image/png')}
        assert post(c,m,f).status_code==200
        assert post(c,m,f).json()['duplicate']
        f['reportPdf']=('x.pdf',b'%PDF-1.4\nchanged','application/pdf')
        assert post(c,m,f).status_code==409
    assert [x[0] for x in calls[0][4]]==['trip-report.pdf','trip-route.png']

@pytest.mark.parametrize('changes,http,code',[
    ({'parentEmail':'bad'},400,'INVALID_EMAIL'),({'subject':'x\r\nBcc: secret@example.com'},400,'INVALID_META'),
    ({'plainText':' '},400,'INVALID_META'),({'endedAtEpochMillis':0},400,'INVALID_META'),
    ({'hardBrakingCount':-1},400,'INVALID_META'),({'includeDetailedRoute':'false'},400,'INVALID_META'),
    ({'includeDetailedRoute':True},400,'MISSING_ATTACHMENT'),({'phoneNumber':'123'},400,'INVALID_META')])
def test_validation_redacted(changes,http,code):
    with TestClient(app) as c:
        m=meta();m.update(changes);r=post(c,m)
    assert r.status_code==http and r.json()['code']==code
    assert 'secret@example.com' not in r.text and 'input' not in r.text

def test_any_valid_parent_email_accepted(monkeypatch):
    calls=[];monkeypatch.setattr(mail,'send_mail',lambda *a:calls.append(a))
    with TestClient(app) as c:
        m=meta();m['parentEmail']='other-parent@example.com'
        r=post(c,m)
    assert r.status_code==200 and r.json()['status']=='accepted'
    assert calls[0][1].recipient_email=='other-parent@example.com'

@pytest.mark.parametrize('files,change,code',[
    ({'reportPdf':('x.pdf',b'not PDF','application/pdf')},{},'INVALID_ATTACHMENT'),
    ({'routeMap':('x.png',b'\x89PNG\r\n\x1a\n','image/png')},{},'ROUTE_NOT_SELECTED'),
    ({'routeMap':('x.jpg',b'bad','image/jpeg')},{'includeDetailedRoute':True},'INVALID_ATTACHMENT'),
    ({'reportPdf':('x.pdf',b'','application/pdf')},{},'INVALID_ATTACHMENT'),
    ({'unknown':('x',b'a','text/plain')},{},'INVALID_REQUEST')])
def test_bad_attachments(files,change,code):
    with TestClient(app) as c:
        m=meta();m.update(change);r=post(c,m,files)
    assert r.status_code==400 and r.json()['code']==code

@pytest.mark.parametrize('part,mime,magic,limit',[
    ('reportPdf','application/pdf',b'%PDF-',route.MAX_PDF),('routeMap','image/png',b'\x89PNG\r\n\x1a\n',route.MAX_MAP)])
def test_size_limits(part,mime,magic,limit):
    with TestClient(app) as c:r=post(c,meta(),{part:('x',magic+b'a'*limit,mime)})
    assert r.status_code==413 and r.json()['code']=='PAYLOAD_TOO_LARGE'

def test_request_limit_and_wrong_type():
    with TestClient(app) as c:
        assert c.post(route.PATH,content=b'a'*(route.MAX_BODY+1)).status_code==413
        assert c.post(route.PATH,json=meta()).status_code==415
        assert c.post(route.PATH,files={'meta':(None,'{"parentEmail":"secret",bad}')}).json()['code']=='INVALID_META'

@pytest.mark.parametrize('reason,uncertain,http,status,code',[
    ('provider_authentication',False,502,'failed','SMTP_UNAVAILABLE'),
    ('delivery_state_unknown',True,503,'unknown','DELIVERY_STATE_UNKNOWN'),
    ('rate_limited',False,429,'failed','RATE_LIMITED')])
def test_provider_failure_non_success_no_retry(monkeypatch,reason,uncertain,http,status,code):
    calls=[]
    def fail(*a):calls.append(a);raise mail.MailFailure(reason,uncertain)
    monkeypatch.setattr(mail,'send_mail',fail)
    with TestClient(app) as c:
        m=meta();r=post(c,m);again=post(c,m)
    assert r.status_code==http and r.json()['status']==status and r.json()['code']==code
    assert again.status_code==http and again.json()['duplicate'] and len(calls)==1

def test_unavailable_pending_and_budget(monkeypatch):
    with TestClient(app) as c:
        monkeypatch.setenv('REPORT_EMAIL_ENABLED','false')
        assert post(c,meta()).status_code==503
        monkeypatch.setenv('REPORT_EMAIL_ENABLED','true');m=meta()
        import hashlib
        Store().claim(hashlib.sha256(('trip-report-email:'+m['clientRequestId']).encode()).hexdigest(),
                      hashlib.sha256(route.Meta.model_validate(m).model_dump_json(by_alias=True).encode()).hexdigest())
        assert post(c,m).json()['code']=='SEND_PENDING'
        for i in range(19):Store().claim(str(i),'digest')
        assert post(c,meta()).json()['code']=='RATE_LIMITED'

def test_smtp_attachment_bytes(monkeypatch):
    got=[]
    class SMTP:
        def __init__(self,*a,**kw):pass
        def __enter__(self):return self
        def __exit__(self,*a):pass
        def ehlo(self):pass
        def starttls(self,**kw):pass
        def login(self,*a):pass
        def send_message(self,msg):got.append(msg);return {}
    monkeypatch.setattr(mail.smtplib,'SMTP',SMTP)
    # Restore actual adapter (fixture normally stubs it).
    import importlib
    importlib.reload(mail)
    mail.send_mail(mail.settings('parent@example.com'),SimpleNamespace(recipient_email='parent@example.com'),
       SimpleNamespace(subject='subject',report_text='body'),'key',[('trip-report.pdf','application/pdf',b'%PDF-test')])
    att=list(got[0].iter_attachments())[0]
    assert att.get_filename()=='trip-report.pdf' and att.get_payload(decode=True)==b'%PDF-test'

def test_resend_attachment_encoding(monkeypatch):
    import importlib,base64
    importlib.reload(mail);got=[]
    class Client:
        def __init__(self,*a,**kw):pass
        def __enter__(self):return self
        def __exit__(self,*a):pass
        def post(self,*a,**kw):got.append(kw);return SimpleNamespace(status_code=200,json=lambda:{'id':'test'})
    monkeypatch.setattr(mail.httpx,'Client',Client)
    mail.send_mail({'provider':'resend','sender':'sender@example.com','key':'synthetic'},
       SimpleNamespace(recipient_email='parent@example.com'),SimpleNamespace(subject='subject',report_text='body'),
       'key',[('trip-route.png','image/png',b'synthetic')])
    assert base64.b64decode(got[0]['json']['attachments'][0]['content'])==b'synthetic'

def test_duplicates_and_meta_limit():
    with TestClient(app) as c:
        m=json.dumps(meta())
        duplicate=c.post(route.PATH,files=[('meta',(None,m)),('meta',(None,m))])
        keys=c.post(route.PATH,files={'meta':(None,'{"tripId":"A","tripId":"B"}')})
        large=c.post(route.PATH,files={'meta':('meta.json',b' '* (route.MAX_META+1),'application/json')})
    assert duplicate.json()['code']=='INVALID_REQUEST'
    assert keys.json()['code']=='INVALID_META'
    assert large.status_code==413

def test_chunked_limit_without_content_length():
    import asyncio
    calls=[];responses=[]
    async def downstream(*a):calls.append(a)
    chunks=iter([{'type':'http.request','body':b'x'*route.MAX_BODY,'more_body':True},
                 {'type':'http.request','body':b'y','more_body':False}])
    async def receive():return next(chunks)
    async def send(message):responses.append(message)
    asyncio.run(route.ReportUploadLimit(downstream)({'type':'http','path':route.PATH,'method':'POST','headers':[]},receive,send))
    assert not calls and responses[0]['status']==413

def test_log_redaction(caplog):
    import logging
    with caplog.at_level(logging.INFO,logger=route.__name__):
        with TestClient(app) as c:assert post(c,meta()).status_code==200
    entries=[r.getMessage() for r in caplog.records if r.name==route.__name__]
    assert len(entries)==1 and 'status=ACCEPTED' in entries[0]
    assert all(x not in entries[0] for x in ['parent@example.com','Home','trip-A','synthetic'])

def test_jpeg_and_reflection_text_preserved(monkeypatch):
    calls=[];monkeypatch.setattr(mail,'send_mail',lambda *a:calls.append(a))
    with TestClient(app) as c:
        m=meta();m['includeDetailedRoute']=True;m['includeReflectionConversation']=True
        m['plainText']='Question\nDriver answer\nAdvice'
        assert post(c,m,{'routeMap':('route.jpg',b'\xff\xd8\xffsynthetic','image/jpeg')}).status_code==200
    assert calls[0][2].report_text==m['plainText'] and calls[0][4][0][0]=='trip-route.jpg'

def test_test_mode_bypasses_database_but_still_calls_adapter(monkeypatch):
    monkeypatch.setenv('REPORT_EMAIL_TEST_BYPASS_STORE','true')
    monkeypatch.setenv('RENDER','true')
    def unavailable():raise RuntimeError('No database/table')
    monkeypatch.setattr(route,'Store',unavailable)
    calls=[];monkeypatch.setattr(mail,'send_mail',lambda *a:calls.append(a))
    with TestClient(app) as c:
        m=meta();r=post(c,m)
        assert r.status_code==200 and r.json()['testMode'] is True
        assert r.json()['dispatchStore']=='bypassed' and len(calls)==1
        m['parentEmail']='another-parent@example.com'
        assert post(c,m).status_code==200 and len(calls)==2

def test_test_mode_off_restores_required_database(monkeypatch):
    monkeypatch.setenv('REPORT_EMAIL_TEST_BYPASS_STORE','false')
    monkeypatch.setenv('RENDER','true')
    monkeypatch.setattr(mail,'send_mail',lambda *a:pytest.fail('Must not send without database'))
    with TestClient(app) as c:r=post(c,meta())
    assert r.status_code==503 and r.json()['code']=='DISPATCH_STORE_UNAVAILABLE'

def test_test_mode_does_not_fake_provider_success(monkeypatch):
    monkeypatch.setenv('REPORT_EMAIL_TEST_BYPASS_STORE','true')
    def failure(*a):raise mail.MailFailure('provider_rejected')
    monkeypatch.setattr(mail,'send_mail',failure)
    with TestClient(app) as c:r=post(c,meta())
    assert r.status_code==502 and r.json()['status']=='failed'
