"""Explicitly confirmed plain-text report email. No raw GPS or body persistence."""
import base64
import hashlib
import json
import os
import re
import smtplib
import socket
import ssl
from datetime import datetime
from email.message import EmailMessage
from typing import Annotated, Literal
from uuid import UUID
import httpx
from fastapi import APIRouter, HTTPException
from pydantic import Field, StrictBool, StringConstraints, field_validator, model_validator
from .speed_models import Model
from .ai_reflection import SelectedEvent, QuestionResponse, AdviceResponse, DISCLAIMER
from .report_email_store import Store

Text = Annotated[str,StringConstraints(strip_whitespace=True,min_length=1,max_length=200)]
ADDRESS = re.compile(r"^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}$")

def address(value):
    if not isinstance(value,str) or len(value)>254 or not ADDRESS.fullmatch(value):
        raise ValueError('A single email address is required')
    return value

class ReportRequest(Model):
    trip_id: Text
    started_at: datetime
    ended_at: datetime
    origin: Text | None = None
    destination: Text | None = None
    distance_km: float | None = Field(default=None,ge=0,le=20000)
    events: list[SelectedEvent] = Field(default_factory=list,max_length=200)
    ai_question: QuestionResponse | None = None
    driver_context: Annotated[str,StringConstraints(strip_whitespace=True,min_length=1,max_length=400)] | None = None
    ai_advice: AdviceResponse | None = None

    @model_validator(mode='after')
    def consistent(self):
        if self.started_at.utcoffset() is None or self.ended_at.utcoffset() is None:
            raise ValueError('Report timestamps require timezone offsets')
        seconds=(self.ended_at-self.started_at).total_seconds()
        if not 0<=seconds<=86400:raise ValueError('Invalid trip duration')
        ids={e.event_id for e in self.events}
        if len(ids)!=len(self.events):raise ValueError('Duplicate event IDs')
        if any(e.end_offset_seconds>seconds for e in self.events):raise ValueError('Event outside trip')
        for result,field in [(self.ai_question,'question'),(self.ai_advice,'advice')]:
            if result and (result.trip_id!=self.trip_id or result.event_id not in ids or
                           result.ai_status!='COMPLETED' or getattr(result,field) is None):
                raise ValueError('Only completed AI content for an included event may be shared')
        if self.ai_question and self.ai_advice and self.ai_question.event_id!=self.ai_advice.event_id:
            raise ValueError('AI turns refer to different events')
        if self.driver_context and not self.ai_question:raise ValueError('Driver context requires its question')
        if self.ai_advice and (not self.ai_question or not self.driver_context):
            raise ValueError('Advice requires the question and driver context')
        return self

class EmailRequest(Model):
    request_id: UUID
    recipient_email: str
    confirmed_by_driver: StrictBool
    report: ReportRequest

    _address=field_validator('recipient_email')(address)

    @field_validator('confirmed_by_driver')
    @classmethod
    def confirmed(cls,value):
        if not value:raise ValueError('Driver must confirm sending')
        return value

class PreviewResponse(Model):
    trip_id: str
    subject: str
    report_text: str

class EmailResponse(Model):
    request_id: UUID
    trip_id: str
    email_status: Literal['ACCEPTED','PENDING','FAILED','UNKNOWN','UNAVAILABLE']
    duplicate: bool = False
    reason: str | None = None


def clean(value):
    # Prevent control characters from disguising text; content is plain text, not HTML.
    return ' '.join(''.join(' ' if ord(c)<32 or ord(c)==127 else c for c in value).split())

def make_report(p):
    lines=['NextCross Trip Report',f'Trip: {clean(p.trip_id)}',
           f'Started: {p.started_at.isoformat()}',f'Ended: {p.ended_at.isoformat()}',
           f'Duration: {(p.ended_at-p.started_at).total_seconds()/60:.1f} minutes']
    if p.origin:lines.append('Origin: '+clean(p.origin))
    if p.destination:lines.append('Destination: '+clean(p.destination))
    lines.append(f'Distance: {p.distance_km:g} km' if p.distance_km is not None else 'Distance: unavailable')
    labels={'RAPID_ACCELERATION':'Rapid acceleration','RAPID_DECELERATION':'Rapid deceleration',
            'SPEED_LIMIT_EXCEEDANCE':'Recorded speed above reference limit'}
    lines+=['','Included recorded events']
    for kind,label in labels.items():lines.append(f'{label}: {sum(e.type==kind for e in p.events)}')
    for e in sorted(p.events,key=lambda x:x.start_offset_seconds):
        lines+=['',f'{labels[e.type]} — {e.start_offset_seconds:g}–{e.end_offset_seconds:g} seconds after departure',
                f'Recording quality: {e.data_quality}']
        for key,val in e.metrics.model_dump(by_alias=True,exclude_none=True).items():lines.append(f'{key}: {val:g}')
    if p.ai_question:
        q=p.ai_question.question
        lines+=['','AI reflection',clean(q.headline),'Question: '+clean(q.question)]
    if p.driver_context:lines.append('Driver response (self-reported): '+clean(p.driver_context))
    if p.ai_advice:
        a=p.ai_advice.advice
        lines+=[clean(a.acknowledgement)]+['- '+clean(x) for x in a.advice]+[clean(a.encouragement)]
    lines+=['','This report contains the events and reflection selected for sharing.',DISCLAIMER]
    return PreviewResponse(trip_id=p.trip_id,subject='NextCross trip report',report_text='\n'.join(lines))

class MailFailure(Exception):
    def __init__(self,reason,uncertain=False):self.reason=reason;self.uncertain=uncertain

def settings(recipient):  # noqa: ARG001 — address format validated by callers; any inbox allowed
    # No recipient allowlist: send to the address the client supplies (same idea as breach mail).
    if os.getenv('REPORT_EMAIL_ENABLED','false').lower()!='true':raise MailFailure('not_enabled')
    try:sender=address(os.getenv('REPORT_FROM_EMAIL',''))
    except ValueError:raise MailFailure('not_configured')
    provider=os.getenv('REPORT_EMAIL_PROVIDER','smtp').lower()
    if provider=='smtp':
        host=os.getenv('SMTP_HOST','');user=os.getenv('SMTP_USERNAME','');password=os.getenv('SMTP_PASSWORD','')
        if not all((host,user,password)):raise MailFailure('not_configured')
        mode=os.getenv('SMTP_SECURITY','starttls').lower()
        if mode not in ('starttls','ssl'):raise MailFailure('invalid_configuration')
        try:port=int(os.getenv('SMTP_PORT','465' if mode=='ssl' else '587'))
        except ValueError:raise MailFailure('invalid_configuration')
        if not 1<=port<=65535:raise MailFailure('invalid_configuration')
        return dict(provider=provider,sender=sender,host=host,user=user,password=password,mode=mode,port=port)
    if provider=='resend':
        key=os.getenv('RESEND_API_KEY','').strip()
        if not key:raise MailFailure('not_configured')
        return dict(provider=provider,sender=sender,key=key)
    raise MailFailure('invalid_configuration')


def send_mail(config,payload,preview,key,attachments=()):
    if config['provider']=='resend':
        try:
            with httpx.Client(timeout=httpx.Timeout(20,connect=5),follow_redirects=False) as client:
                r=client.post('https://api.resend.com/emails',headers={'Authorization':'Bearer '+config['key'],
                    'Idempotency-Key':key},json={'from':config['sender'],'to':[payload.recipient_email],
                    'subject':preview.subject,'text':preview.report_text,
                    **({'attachments':[{'filename':name,'content':base64.b64encode(data).decode()}
                        for name,mime,data in attachments]} if attachments else {})})
            if r.status_code in (200,201) and isinstance(r.json().get('id'),str):return
            if r.status_code in (401,403):raise MailFailure('provider_authentication')
            if r.status_code==429:raise MailFailure('rate_limited')
            raise MailFailure('provider_unavailable' if r.status_code>=500 else 'provider_rejected',r.status_code>=500)
        except (httpx.RequestError,ValueError):raise MailFailure('delivery_state_unknown',True)
    else:
        message=EmailMessage();message['From']=config['sender'];message['To']=payload.recipient_email
        message['Subject']=preview.subject
        message['Message-ID']=f'<{key}@{config["sender"].split("@",1)[1]}>'
        message.set_content(preview.report_text)
        for name,mime,data in attachments:
            major,minor=mime.split("/",1)
            message.add_attachment(data,maintype=major,subtype=minor,filename=name)
        data_started=False
        accepted=False
        try:
            if config['mode']=='ssl':
                connection=smtplib.SMTP_SSL(config['host'],config['port'],timeout=15,context=ssl.create_default_context())
            else:connection=smtplib.SMTP(config['host'],config['port'],timeout=15)
            with connection as smtp:
                if config['mode']=='starttls':smtp.ehlo();smtp.starttls(context=ssl.create_default_context());smtp.ehlo()
                smtp.login(config['user'],config['password'])
                data_started=True
                rejected=smtp.send_message(message)
                if rejected:raise MailFailure('recipient_rejected')
                # A successful DATA reply means acceptance, even if QUIT fails next.
                data_started=False
                accepted=True
                return
        except smtplib.SMTPAuthenticationError:raise MailFailure('provider_authentication')
        except (smtplib.SMTPRecipientsRefused,smtplib.SMTPSenderRefused,smtplib.SMTPDataError):raise MailFailure('provider_rejected')
        except (smtplib.SMTPException,OSError):
            if accepted:return
            raise MailFailure('delivery_state_unknown' if data_started else 'smtp_connection_failed',data_started)

router=APIRouter(prefix='/reports',tags=['report email'])

@router.post('/preview',response_model=PreviewResponse)
def preview_report(payload: ReportRequest):
    """Preview only; never contacts a mail provider or stores the report."""
    return make_report(payload)

@router.post('/email',response_model=EmailResponse)
def email_report(payload: EmailRequest):
    base=dict(request_id=payload.request_id,trip_id=payload.report.trip_id)
    try:config=settings(payload.recipient_email)
    except MailFailure as e:return EmailResponse(**base,email_status='UNAVAILABLE',reason=e.reason)
    preview=make_report(payload.report)
    digest=hashlib.sha256(payload.model_dump_json(by_alias=True).encode()).hexdigest()
    key=hashlib.sha256(str(payload.request_id).encode()).hexdigest()
    try:
        store=Store();existing=store.claim(key,digest)
    except HTTPException:raise
    except Exception:return EmailResponse(**base,email_status='UNAVAILABLE',reason='dispatch_store_unavailable')
    if existing:
        if existing['digest']!=digest:raise HTTPException(409,'requestId was already used with different content')
        return EmailResponse(**base,email_status=existing['status'],duplicate=True,reason=existing['reason'])
    try:
        send_mail(config,payload,preview,key)
        status,reason='ACCEPTED',None
    except MailFailure as e:status,reason=('UNKNOWN' if e.uncertain else 'FAILED'),e.reason
    except Exception:status,reason='UNKNOWN','delivery_state_unknown'
    try:store.finish(key,status,reason)
    except Exception:
        # Keep the durable PENDING record; never resend merely because bookkeeping failed.
        pass
    return EmailResponse(**base,email_status=status,reason=reason)
