import json
import smtplib
from concurrent.futures import ThreadPoolExecutor
import httpx
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app import report_email as mail
from app.report_email_store import Store

@pytest.fixture(autouse=True)
def environment(monkeypatch,tmp_path):
    for k in ['REPORT_EMAIL_DATABASE_URL','RUNTIME_DATABASE_URL','DATABASE_URL','RENDER']:monkeypatch.delenv(k,raising=False)
    monkeypatch.setenv('REPORT_EMAIL_DATABASE_PATH',str(tmp_path/'email.db'))
    monkeypatch.setenv('REPORT_EMAIL_ENABLED','true')
    monkeypatch.setenv('REPORT_FROM_EMAIL','sender@example.com')
    monkeypatch.setenv('REPORT_EMAIL_PROVIDER','smtp')
    monkeypatch.setenv('SMTP_HOST','smtp.example.com')
    monkeypatch.setenv('SMTP_USERNAME','sender@example.com')
    monkeypatch.setenv('SMTP_PASSWORD','synthetic-password')
    monkeypatch.setenv('SMTP_SECURITY','starttls')
    monkeypatch.setenv('SMTP_PORT','587')

def payload():
    return {'requestId':'1587c175-b9f9-46b8-9ae0-e46524584b7a','recipientEmail':'parent@example.com',
            'confirmedByDriver':True,'report':{'tripId':'trip-A','startedAt':'2026-10-05T09:00:00+11:00',
            'endedAt':'2026-10-05T09:10:00+11:00','origin':'Clayton','destination':'Chadstone','events':[]}}

def test_preview_never_sends(monkeypatch):
    monkeypatch.setattr(mail,'send_mail',lambda *a:pytest.fail('sent'))
    with TestClient(app) as c:r=c.post('/api/v1/reports/preview',json=payload()['report'])
    assert r.status_code==200 and 'Duration: 10.0 minutes' in r.json()['reportText']
    assert mail.DISCLAIMER in r.json()['reportText'] and 'unavailable' in r.json()['reportText']

def test_sent_duplicate_conflict_and_no_body_persistence(monkeypatch):
    calls=[];monkeypatch.setattr(mail,'send_mail',lambda *a:calls.append(a))
    with TestClient(app) as c:
        p=payload();a=c.post('/api/v1/reports/email',json=p);b=c.post('/api/v1/reports/email',json=p)
        p['report']['destination']='Another place';bad=c.post('/api/v1/reports/email',json=p)
    assert a.json()['emailStatus']=='ACCEPTED' and b.json()['duplicate'] is True
    assert bad.status_code==409 and len(calls)==1
    with Store().db() as db:
        row=dict(db.execute('SELECT * FROM report_email_dispatch').fetchone())
    assert all(x not in json.dumps(row) for x in ['parent@example.com','Clayton','trip-A','synthetic-password'])

@pytest.mark.parametrize('change',[{'confirmedByDriver':False},{'confirmedByDriver':'true'},
                                  {'recipientEmail':'x@example.com\r\nBcc: other@example.com'},
                                  {'phoneNumber':'123'}])
def test_bad_requests_redacted(monkeypatch,change):
    monkeypatch.setattr(mail,'send_mail',lambda *a:pytest.fail('sent'));p=payload();p.update(change)
    with TestClient(app) as c:r=c.post('/api/v1/reports/email',json=p)
    assert r.status_code==422 and 'input' not in r.text and 'Bcc:' not in r.text

@pytest.mark.parametrize('mode,status,reason',[
    ('disabled','UNAVAILABLE','not_enabled'),
    ('no_password','UNAVAILABLE','not_configured'),('failure','FAILED','provider_authentication'),
    ('uncertain','UNKNOWN','delivery_state_unknown')])
def test_failures(monkeypatch,mode,status,reason):
    if mode=='disabled':monkeypatch.setenv('REPORT_EMAIL_ENABLED','false')
    if mode=='no_password':monkeypatch.delenv('SMTP_PASSWORD')
    def send(*a):raise mail.MailFailure(reason,mode=='uncertain')
    monkeypatch.setattr(mail,'send_mail',send)
    with TestClient(app) as c:r=c.post('/api/v1/reports/email',json=payload())
    assert r.json()['emailStatus']==status and r.json()['reason']==reason

def test_any_valid_recipient_and_cloud_storage_required(monkeypatch):
    calls=[];monkeypatch.setattr(mail,'send_mail',lambda *a:calls.append(a))
    with TestClient(app) as c:
        p=payload();p['recipientEmail']='other-parent@example.com'
        assert c.post('/api/v1/reports/email',json=p).json()['emailStatus']=='ACCEPTED'
        assert len(calls)==1
        monkeypatch.setenv('RENDER','true')
        assert c.post('/api/v1/reports/email',json=payload()).json()['reason']=='dispatch_store_unavailable'

def test_durable_pending_and_daily_budget():
    store=Store();assert store.claim('key','digest') is None
    assert store.claim('key','digest')['status']=='PENDING'
    for i in range(19):store.claim(str(i),'d')
    from fastapi import HTTPException
    with pytest.raises(HTTPException) as e:store.claim('21','d')
    assert e.value.status_code==429

def test_atomic_duplicate_claims():
    # Initialise table before concurrent SQLite connections.
    store=Store()
    with store.db():pass
    with ThreadPoolExecutor(4) as pool:results=list(pool.map(lambda _:Store().claim('one','same'),range(4)))
    assert sum(r is None for r in results)==1

@pytest.mark.parametrize('mode',['starttls','ssl'])
def test_smtp_tls_and_message(monkeypatch,mode):
    captured=[]
    class FakeSMTP:
        def __init__(self,*a,**kw):captured.append(('connect',a,kw))
        def __enter__(self):return self
        def __exit__(self,*a):pass
        def ehlo(self):pass
        def starttls(self,**kw):assert kw['context'].verify_mode==2;captured.append(('tls',))
        def login(self,user,password):assert password=='synthetic-password';captured.append(('login',))
        def send_message(self,msg):captured.append(('message',msg));return {}
    monkeypatch.setattr(mail.smtplib,'SMTP',FakeSMTP);monkeypatch.setattr(mail.smtplib,'SMTP_SSL',FakeSMTP)
    monkeypatch.setenv('SMTP_SECURITY',mode)
    p=mail.EmailRequest.model_validate(payload());cfg=mail.settings(p.recipient_email)
    mail.send_mail(cfg,p,mail.make_report(p.report),'digest')
    msg=next(x[1] for x in captured if x[0]=='message')
    assert msg['To']=='parent@example.com' and msg.get_content_type()=='text/plain'
    assert 'trip-A' in msg.get_content() and mail.DISCLAIMER in msg.get_content()
    assert any(x[0]=='tls' for x in captured)==(mode=='starttls')

@pytest.mark.parametrize('error,reason,uncertain',[
    (smtplib.SMTPAuthenticationError(535,b'secret'),'provider_authentication',False),
    (smtplib.SMTPRecipientsRefused({'private':(550,b'no')}),'provider_rejected',False),
    (TimeoutError('secret'),'delivery_state_unknown',True)])
def test_smtp_error_mapping(monkeypatch,error,reason,uncertain):
    class Fake:
        def __init__(self,*a,**k):pass
        def __enter__(self):return self
        def __exit__(self,*a):pass
        def ehlo(self):pass
        def starttls(self,**k):pass
        def login(self,*a):
            if isinstance(error,smtplib.SMTPAuthenticationError):raise error
        def send_message(self,*a):raise error
    monkeypatch.setattr(mail.smtplib,'SMTP',Fake)
    p=mail.EmailRequest.model_validate(payload())
    with pytest.raises(mail.MailFailure) as e:mail.send_mail(mail.settings(p.recipient_email),p,mail.make_report(p.report),'key')
    assert (e.value.reason,e.value.uncertain)==(reason,uncertain)

@pytest.mark.parametrize('code,expected',[(200,'ACCEPTED'),(401,'FAILED'),(429,'FAILED'),(500,'UNKNOWN')])
def test_https_adapter(monkeypatch,code,expected):
    monkeypatch.setenv('REPORT_EMAIL_PROVIDER','resend');monkeypatch.setenv('RESEND_API_KEY','synthetic-key')
    class Fake:
        def __init__(self,**kw):assert kw['follow_redirects'] is False
        def __enter__(self):return self
        def __exit__(self,*a):pass
        def post(self,url,headers,json):
            assert url=='https://api.resend.com/emails' and headers['Idempotency-Key']
            assert json['to']==['parent@example.com'] and 'text' in json
            return httpx.Response(code,json={'id':'test-id'})
    with TestClient(app) as c:
        monkeypatch.setattr(mail.httpx,'Client',Fake)
        r=c.post('/api/v1/reports/email',json=payload())
    assert r.json()['emailStatus']==expected and 'synthetic-key' not in r.text

def test_report_rejects_other_trip_ai_and_invalid_time():
    p=payload()['report'];p['aiQuestion']={'tripId':'trip-B','eventId':'other','aiStatus':'COMPLETED',
        'question':{'headline':'h','question':'q','quickReplies':['a','b','c']}}
    with pytest.raises(ValueError):mail.ReportRequest.model_validate(p)
    p=payload()['report'];p['startedAt']='2026-10-05T09:00:00'
    with pytest.raises(ValueError):mail.ReportRequest.model_validate(p)

def test_smtp_quit_failure_after_acceptance_is_still_accepted(monkeypatch):
    class Fake:
        def __init__(self,*a,**k):pass
        def __enter__(self):return self
        def __exit__(self,*a):raise smtplib.SMTPServerDisconnected('quit disconnected')
        def ehlo(self):pass
        def starttls(self,**k):pass
        def login(self,*a):pass
        def send_message(self,*a):return {}
    monkeypatch.setattr(mail.smtplib,'SMTP',Fake)
    p=mail.EmailRequest.model_validate(payload())
    mail.send_mail(mail.settings(p.recipient_email),p,mail.make_report(p.report),'key')

@pytest.mark.parametrize('uncertain',[False,True])
def test_failed_or_unknown_request_is_not_automatically_resent(monkeypatch,uncertain):
    calls=[]
    def send(*a):calls.append(a);raise mail.MailFailure('test_failure',uncertain)
    monkeypatch.setattr(mail,'send_mail',send)
    with TestClient(app) as c:
        first=c.post('/api/v1/reports/email',json=payload()).json()
        second=c.post('/api/v1/reports/email',json=payload()).json()
    assert len(calls)==1 and second['duplicate']
    assert first['emailStatus']==second['emailStatus']==('UNKNOWN' if uncertain else 'FAILED')
