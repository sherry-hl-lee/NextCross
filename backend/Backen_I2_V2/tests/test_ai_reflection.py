import json
import httpx
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app import ai_reflection as ai


def payload(kind="RAPID_DECELERATION"):
    metrics={"speedBeforeKmh":62,"speedAfterKmh":34,"peakAccelerationMs2":-4.8}
    if kind=="RAPID_ACCELERATION": metrics={"speedBeforeKmh":22,"speedAfterKmh":48,"peakAccelerationMs2":4.1}
    if kind=="SPEED_LIMIT_EXCEEDANCE": metrics={"referenceLimitKmh":50,"peakSpeedKmh":67,"averageExceedanceKmh":11.2}
    return {"tripId":"private-trip","selectedEvent":{"eventId":"private-event","type":kind,
        "startOffsetSeconds":301,"endOffsetSeconds":304,"durationSeconds":3,"dataQuality":"GOOD",
        "detectorVersion":"private-version","metrics":metrics}}


@pytest.mark.parametrize("kind",["RAPID_ACCELERATION","RAPID_DECELERATION","SPEED_LIMIT_EXCEEDANCE"])
def test_success_and_privacy(monkeypatch,kind):
    captured=[]
    def generate(ctx):
        captured.append(ctx)
        return ai.Reflection(summary="Recorded event.",question="What was happening?",suggestion="Discuss the event together.")
    monkeypatch.setattr(ai,"generate",generate)
    with TestClient(app) as c:
        response=c.post("/api/v1/ai/reflection",json=payload(kind))
    assert response.json()["aiStatus"]=="COMPLETED"
    text=json.dumps(captured)
    assert "private" not in text and "latitude" not in text and "detectorVersion" not in text


@pytest.mark.parametrize("mode",["gps","missing","wrong_sign","duration","summary","unknown_type"])
def test_invalid_never_calls_groq(monkeypatch,mode):
    calls=[]
    monkeypatch.setattr(ai,"generate",lambda ctx:calls.append(ctx))
    p=payload(); e=p["selectedEvent"]
    if mode=="gps": e["metrics"]["latitude"]=-37.9
    if mode=="missing": del e["metrics"]["peakAccelerationMs2"]
    if mode=="wrong_sign": e["metrics"]["peakAccelerationMs2"]=4
    if mode=="duration": e["durationSeconds"]=99
    if mode=="summary": p["tripSummary"]={"rapidAccelerationCount":0,"rapidDecelerationCount":0,"speedLimitExceedanceCount":0}
    if mode=="unknown_type": e["type"]="NEW_EVENT"
    with TestClient(app) as c: r=c.post("/api/v1/ai/reflection",json=p)
    assert r.status_code==422 and r.json()["aiStatus"]=="INVALID_EVENT"
    assert not calls and "-37.9" not in r.text


def test_missing_key_and_quality(monkeypatch):
    monkeypatch.delenv("GROQ_API_KEY",raising=False)
    with TestClient(app) as c:
        assert c.post("/api/v1/ai/reflection",json=payload()).json()["aiStatus"]=="UNAVAILABLE"
        p=payload(); p["selectedEvent"]["dataQuality"]="POOR"
        assert c.post("/api/v1/ai/reflection",json=p).json()["aiStatus"]=="INVALID_EVENT"


@pytest.mark.parametrize("mode,expected",[("ok","COMPLETED"),("timeout","UNAVAILABLE"),("429","UNAVAILABLE"),
    ("401","UNAVAILABLE"),("503","UNAVAILABLE"),("badjson","FAILED"),("extra","FAILED"),("truncated","FAILED")])
def test_transport(monkeypatch,mode,expected):
    monkeypatch.setenv("GROQ_API_KEY","fake-test-key")
    captured=[]
    class FakeClient:
        def __init__(self,**kwargs): assert kwargs["follow_redirects"] is False
        def __enter__(self): return self
        def __exit__(self,*args): pass
        def post(self,url,headers,json):
            captured.append(json)
            assert url=="https://api.groq.com/openai/v1/chat/completions"
            if mode=="timeout": raise httpx.ReadTimeout("private info")
            content='{"summary":"An event was recorded.","question":"What happened?","suggestion":"Discuss it."}'
            if mode=="badjson": content="not json"
            if mode=="extra": content='{"summary":"s","question":"q","suggestion":"s","extra":1}'
            return httpx.Response(int(mode) if mode.isdigit() else 200,json={"choices":[{
                "finish_reason":"length" if mode=="truncated" else "stop","message":{"content":content}}]})
    # Patch only the provider's constructor after TestClient creation.
    with TestClient(app) as c:
        monkeypatch.setattr(ai.httpx,"Client",FakeClient)
        response=c.post("/api/v1/ai/reflection",json=payload())
    assert response.json()["aiStatus"]==expected
    assert "fake-test-key" not in response.text
    assert captured[0]["response_format"]["json_schema"]["strict"] is True

# Prompt-addendum checks are offline wiring/fixture checks, not real-LLM compliance evidence.
def test_turn_two_prompt_addendum_reaches_provider(monkeypatch):
    captured=[]
    def generate(ctx,**kwargs):
        captured.append(kwargs['system'])
        return ai.CategorizedAdvice(context_category='ordinary',
          acknowledgement='From what you describe, traffic ahead changed.',
          advice=['Review the recorded drop from 62 to 34 km/h.',
                  'Discuss a point to begin easing off when traffic ahead changes.'],
          encouragement='Thank you for reflecting on your trip.')
    monkeypatch.setattr(ai,'generate',generate)
    p=payload();p.update(questionAsked='What was happening?',driverContext='A truck was blocking the lane.')
    with TestClient(app) as c:r=c.post('/api/v1/ai/reflection/advice',json=p)
    assert r.json()['aiStatus']=='COMPLETED'
    prompt=" ".join(captured[0].split())
    # v7 advice prompt (classify first); see ADVICE_SYSTEM_V7.
    assert 'Write to the driver as "you"' in prompt
    assert 'you mentioned' in prompt and 'never state it as fact' in prompt
    assert 'must never become a target or limit' in prompt and 'established guidelines' in prompt
    assert 'Return contextCategory FIRST' in prompt and 'untrusted data' in prompt
    assert 'quote a supplied number' not in prompt

@pytest.mark.parametrize('account,ack',[
    ('A truck blocked the lane.','You mentioned a truck in the lane; the recording does not confirm that account.'),
    ('An animal crossed the road.','From what you describe, an animal was involved; the recording cannot confirm it.'),
    ('A person stepped into the road.','You mentioned a person stepping into the road; that remains your account.')])
def test_mocked_advice_second_person_attribution_and_no_invented_limit(monkeypatch,account,ack):
    import re
    def generate(ctx,**kwargs):
        assert ctx['driverDescription']==account
        assert 'never state it as fact' in kwargs['system']
        return ai.CategorizedAdvice(context_category='ordinary',acknowledgement=ack,
           advice=['Review the recorded drop from 62 to 34 km/h.',
                   'Discuss where you could begin easing off when traffic ahead changes.'],
           encouragement='Thank you for reflecting on your trip.')
    monkeypatch.setattr(ai,'generate',generate)
    p=payload();p.update(questionAsked='What was happening?',driverContext=account)
    with TestClient(app) as c:r=c.post('/api/v1/ai/reflection/advice',json=p)
    text=json.dumps(r.json()['advice']).lower()
    assert all(phrase not in text for phrase in ['the driver','the user','this driver'])
    assert 'you mentioned' in text or 'from what you describe' in text
    assert not re.search(r'keep[^.!?]*below\s+[-\d]|aim[^.!?]*under\s+[-\d]',text)
    # This assertion checks the mock fixture, not generated Groq behaviour.
