"""Client-owned report export → direct mail. Bodies/attachments are never persisted."""
import hashlib
import json
import logging
import os
from types import SimpleNamespace
from uuid import UUID
from typing import Annotated

from fastapi import APIRouter, Request, HTTPException
from fastapi.responses import JSONResponse
from pydantic import Field, StrictBool, StringConstraints, ValidationError, field_validator, model_validator
from starlette.datastructures import UploadFile
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException as StarletteHTTPException
from .speed_models import Model
from . import report_email as mail
from .report_email_store import Store

MIB = 1024 * 1024
MAX_BODY = 8 * MIB
MAX_META = 128 * 1024
MAX_PDF = 5 * MIB
MAX_MAP = 2 * MIB
PATH = '/api/v1/trip-reports/email'
log = logging.getLogger(__name__)
Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]

class Meta(Model):
    trip_id: Text
    planned_trip_id: Text | None = None
    parent_email: str
    subject: Annotated[str, StringConstraints(min_length=1, max_length=200)]
    plain_text: Annotated[str, StringConstraints(min_length=1, max_length=50000)]
    include_detailed_route: StrictBool
    include_reflection_conversation: StrictBool
    started_at_epoch_millis: int = Field(ge=0, strict=True)
    ended_at_epoch_millis: int = Field(ge=0, strict=True)
    starting_point_label: Text | None = None
    destination_label: Text | None = None
    hard_acceleration_count: int = Field(ge=0, le=100000, strict=True)
    hard_braking_count: int = Field(ge=0, le=100000, strict=True)
    client_request_id: UUID

    _email = field_validator('parent_email')(mail.address)

    @field_validator('subject')
    @classmethod
    def header(cls, value):
        if not value.strip() or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ValueError('Invalid subject')
        return value

    @field_validator('plain_text')
    @classmethod
    def body(cls, value):
        if not value.strip() or any((ord(c) < 32 and c not in '\r\n\t') or ord(c) == 127 for c in value):
            raise ValueError('Invalid body')
        return value

    @model_validator(mode='after')
    def duration(self):
        if not 0 <= self.ended_at_epoch_millis-self.started_at_epoch_millis <= 86400000:
            raise ValueError('Invalid trip duration')
        return self


def error(http, code, status='failed', meta=None, duplicate=False):
    body = {'status':status, 'code':code}
    if meta:
        body.update(tripId=meta.trip_id, clientRequestId=str(meta.client_request_id), duplicate=duplicate)
    return JSONResponse(status_code=http, content=body)

class ReportUploadLimit:
    """Bound received bytes even without Content-Length, before multipart spooling."""
    def __init__(self, app): self.app = app
    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or scope.get('path') != PATH or scope.get('method') != 'POST':
            return await self.app(scope, receive, send)
        headers = dict(scope.get('headers', []))
        try:
            length = int(headers.get(b'content-length', b'0'))
        except ValueError:
            return await error(400, 'INVALID_REQUEST')(scope, receive, send)
        if length < 0:
            return await error(400, 'INVALID_REQUEST')(scope, receive, send)
        if length > MAX_BODY:
            return await error(413, 'PAYLOAD_TOO_LARGE')(scope, receive, send)
        # Buffer only this small, capped endpoint before the multipart parser creates temp files.
        chunks=[]; total=0
        while True:
            message=await receive()
            if message['type']=='http.disconnect':return
            total+=len(message.get('body',b''))
            if total>MAX_BODY:
                return await error(413,'PAYLOAD_TOO_LARGE')(scope,receive,send)
            chunks.append(message.get('body',b''))
            if not message.get('more_body',False):break
        body=b''.join(chunks)
        consumed=False
        async def bounded_receive():
            nonlocal consumed
            if not consumed:
                consumed=True
                return {'type':'http.request','body':body,'more_body':False}
            return await receive()
        await self.app(scope,bounded_receive,send)

async def parse(request):
    if request.headers.get('content-type','').split(';',1)[0].strip().lower() != 'multipart/form-data':
        return None, None, error(415, 'UNSUPPORTED_MEDIA_TYPE')
    # Keep parser spill files bounded and close them also on validation failure.
    try:
        async with request.form(max_files=3, max_fields=3, max_part_size=MAX_META) as form:
            items = form.multi_items()
            if any(k not in ('meta','reportPdf','routeMap') for k,v in items) or len({k for k,v in items}) != len(items):
                return None,None,error(400,'INVALID_REQUEST')
            raw = form.get('meta')
            if raw is None: return None,None,error(400,'INVALID_META')
            if isinstance(raw,UploadFile):
                if raw.content_type != 'application/json': return None,None,error(400,'INVALID_META')
                raw = await raw.read(MAX_META+1)
            else: raw = raw.encode('utf-8')
            if len(raw)>MAX_META: return None,None,error(413,'PAYLOAD_TOO_LARGE')
            try:
                # Reject duplicate JSON keys rather than silently taking the last value.
                def unique(pairs):
                    d={}
                    for k,v in pairs:
                        if k in d: raise ValueError('Duplicate key')
                        d[k]=v
                    return d
                obj=json.loads(raw,object_pairs_hook=unique)
                meta=Meta.model_validate(obj)
            except ValidationError as exc:
                code='INVALID_EMAIL' if any(e['loc']==('parentEmail',) or e['loc']==('parent_email',) for e in exc.errors()) else 'INVALID_META'
                return None,None,error(400,code)
            except (ValueError,TypeError,UnicodeError): return None,None,error(400,'INVALID_META')
            attachments=[]
            for part,limit in [('reportPdf',MAX_PDF),('routeMap',MAX_MAP)]:
                f=form.get(part)
                if f is None: continue
                if not isinstance(f,UploadFile): return None,None,error(400,'INVALID_ATTACHMENT',meta=meta)
                data=await f.read(limit+1)
                if len(data)>limit: return None,None,error(413,'PAYLOAD_TOO_LARGE',meta=meta)
                if not data: return None,None,error(400,'INVALID_ATTACHMENT',meta=meta)
                if part=='reportPdf':
                    valid=f.content_type=='application/pdf' and data.startswith(b'%PDF-')
                    name,mime='trip-report.pdf','application/pdf'
                elif f.content_type=='image/png':
                    valid=data.startswith(b'\x89PNG\r\n\x1a\n');name,mime='trip-route.png','image/png'
                else:
                    valid=f.content_type=='image/jpeg' and data.startswith(b'\xff\xd8\xff')
                    name,mime='trip-route.jpg','image/jpeg'
                if not valid: return None,None,error(400,'INVALID_ATTACHMENT',meta=meta)
                if part=='routeMap' and not meta.include_detailed_route:
                    return None,None,error(400,'ROUTE_NOT_SELECTED',meta=meta)
                attachments.append((name,mime,data))
            if meta.include_detailed_route and not any(name.startswith('trip-route.') for name,mime,data in attachments):
                return None,None,error(400,'MISSING_ATTACHMENT',meta=meta)
            return meta,attachments,None
    except StarletteHTTPException as exc:
        if "Part exceeded maximum size" in str(exc.detail):
            return None,None,error(413,'PAYLOAD_TOO_LARGE')
        return None,None,error(400,'INVALID_MULTIPART')


def dispatch(meta,attachments):
    try: config=mail.settings(meta.parent_email)
    except HTTPException: return error(403,'RECIPIENT_NOT_ALLOWED',meta=meta)
    except mail.MailFailure: return error(503,'SMTP_UNAVAILABLE',meta=meta)
    # Namespace separate from legacy requestId endpoint. Digest excludes multipart boundaries and filenames.
    key=hashlib.sha256(('trip-report-email:'+str(meta.client_request_id)).encode()).hexdigest()
    digest=hashlib.sha256(meta.model_dump_json(by_alias=True).encode())
    for name,mime,data in attachments:
        digest.update(name.encode());digest.update(mime.encode());digest.update(hashlib.sha256(data).digest())
    # Temporary demo mode. Default keeps durable claims and the shared budget.
    bypass=os.getenv('REPORT_EMAIL_TEST_BYPASS_STORE','false').strip().lower()=='true'
    store=None;existing=None
    if not bypass:
        try:
            store=Store();existing=store.claim(key,digest.hexdigest())
        except HTTPException: return error(429,'RATE_LIMITED',meta=meta)
        except Exception: return error(503,'DISPATCH_STORE_UNAVAILABLE',meta=meta)
    duplicate=existing is not None
    if existing and existing['digest']!=digest.hexdigest():
        return error(409,'IDEMPOTENCY_CONFLICT',meta=meta,duplicate=True)
    if existing: status,reason=existing['status'],existing['reason']
    else:
        try:
            mail.send_mail(config,SimpleNamespace(recipient_email=meta.parent_email),
                SimpleNamespace(subject=meta.subject,report_text=meta.plain_text),key,attachments)
            status,reason='ACCEPTED',None
        except mail.MailFailure as exc:
            status,reason=('UNKNOWN' if exc.uncertain else 'FAILED'),exc.reason
        except Exception: status,reason='UNKNOWN','delivery_state_unknown'
        if store is not None:
            try:store.finish(key,status,reason)
            except Exception: pass  # Durable PENDING prevents repeat submission after bookkeeping failure.
    # Do not log address/body/location/driver answers/file bytes/provider secrets.
    log.info('trip_report_email request_hash=%s status=%s duplicate=%s test_mode=%s',key,status,duplicate,bypass)
    if status=='ACCEPTED':
        return JSONResponse(status_code=200,content={'status':'accepted','tripId':meta.trip_id,
            'clientRequestId':str(meta.client_request_id),'duplicate':duplicate,
            **({'testMode':True,'dispatchStore':'bypassed'} if bypass else {})})
    if status=='PENDING':return error(409,'SEND_PENDING','pending',meta,duplicate)
    if status=='UNKNOWN':return error(503,'DELIVERY_STATE_UNKNOWN','unknown',meta,duplicate)
    return error(429 if reason=='rate_limited' else 502,
        'RATE_LIMITED' if reason=='rate_limited' else 'SMTP_UNAVAILABLE',meta=meta,duplicate=duplicate)

router=APIRouter(prefix='/trip-reports',tags=['trip report email'])

@router.post('/email',openapi_extra={'requestBody':{'required':True,'content':{'multipart/form-data':{
    'schema':{'type':'object','required':['meta'],'properties':{
        'meta':{'type':'string','description':'JSON object (field or application/json file)'},
        'reportPdf':{'type':'string','format':'binary'},'routeMap':{'type':'string','format':'binary'}}}}}}})
async def email(request:Request):
    meta,attachments,invalid=await parse(request)
    if invalid is not None:return invalid
    return await run_in_threadpool(dispatch,meta,attachments)
