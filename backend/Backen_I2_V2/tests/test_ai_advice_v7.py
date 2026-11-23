"""Offline tests for the v7 advice turn: classify first, then enforce approved high-risk replies.
They never call Groq and cost no quota. They prove the code path, not real model wording;
tools/prompt_lab.py is the live check for wording."""
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app import ai_reflection as ai
from test_ai_reflection import payload

PAIRS = {category: options[0] for category, options in ai.APPROVED_ADVICE.items()}
COACHING = ["Apply the throttle progressively from the lights.", "Build speed smoothly over four seconds."]


def draft(category, advice=None, ack=None, encouragement="Keep enjoying your drives!"):
    return ai.CategorizedAdvice(
        context_category=category,
        acknowledgement=ack or f"You mentioned it; {ai.OUTSIDE_SCOPE}",
        advice=list(advice or COACHING),
        encouragement=encouragement)


@pytest.mark.parametrize("category", ["fleeing", "racing", "impairment", "aggression"])
def test_high_risk_coaching_is_replaced_by_the_approved_pair(category):
    reply = ai.enforce_safe_reply(draft(category))
    assert reply.advice == list(ai.FALLBACK_ADVICE[category])
    assert reply.encouragement == ai.HONEST_THANKS
    assert not any("throttle" in item or "speed" in item for item in reply.advice)


@pytest.mark.parametrize("category", ["fleeing", "racing", "impairment", "aggression"])
def test_an_exact_approved_pair_from_the_model_is_kept(category):
    reply = ai.enforce_safe_reply(draft(category, advice=PAIRS[category],
                                        ack="You mentioned cops on your tail; " + ai.OUTSIDE_SCOPE))
    assert reply.advice == list(PAIRS[category])
    assert reply.acknowledgement.startswith("You mentioned cops")


def test_fleeing_after_a_collision_keeps_the_collision_pair():
    pair = [ai.COLLISION_STOP, ai.COLLISION_REPORT]
    assert ai.enforce_safe_reply(draft("fleeing", advice=pair)).advice == pair


def test_fleeing_fallback_covers_both_police_and_collisions():
    assert ai.enforce_safe_reply(draft("fleeing")).advice == [ai.POLICE_STOP, ai.COLLISION_STOP]


def test_typographic_variants_still_match_an_approved_pair():
    curly = [item.replace("'", "’") for item in PAIRS["impairment"]]
    reply = ai.enforce_safe_reply(draft("impairment", advice=curly,
                                        ack="You mentioned drinking; that is outside what post‑trip reflection can assess."))
    assert reply.advice == list(PAIRS["impairment"])
    assert "post‑trip" in reply.acknowledgement  # the model's own acknowledgement is kept


def test_a_high_risk_acknowledgement_without_the_scope_clause_is_replaced():
    reply = ai.enforce_safe_reply(draft("racing", ack="Great launch, you won the race!"))
    assert reply.acknowledgement == ai.GENERIC_HIGH_RISK_ACK


def test_self_harm_always_returns_the_fixed_support_reply():
    reply = ai.enforce_safe_reply(draft("self_harm", advice=COACHING, ack="Anything at all."))
    assert reply == ai.SELF_HARM_REPLY
    assert any("13 11 14" in item for item in reply.advice)


@pytest.mark.parametrize("category", ["ordinary", "personal_emergency", "distraction", "fatigue_or_medical",
                                      "data_dispute", "unclear_or_absurd"])
def test_ordinary_categories_pass_through_unchanged(category):
    model = draft(category, ack="From what you describe, traffic slowed.",
                  advice=["When traffic ahead slows, ease off early.", "Look about 12 seconds ahead."],
                  encouragement="Thinking it through helps.")
    reply = ai.enforce_safe_reply(model)
    assert (reply.acknowledgement, reply.advice, reply.encouragement) == (
        model.acknowledgement, model.advice, model.encouragement)


def test_every_approved_sentence_appears_in_the_prompt():
    prompt = " ".join(ai.ADVICE_SYSTEM_V7.split())
    sentences = [s for options in ai.APPROVED_ADVICE.values() for pair in options for s in pair]
    sentences += ai.SELF_HARM_REPLY.advice + [ai.SELF_HARM_REPLY.acknowledgement,
                                              ai.SELF_HARM_REPLY.encouragement, ai.HONEST_THANKS, ai.OUTSIDE_SCOPE]
    assert [s for s in sentences if s not in prompt] == []


def test_generation_schema_puts_a_closed_category_enum_first():
    schema = ai.CategorizedAdvice.model_json_schema(by_alias=True)
    assert list(schema["properties"])[0] == "contextCategory"
    assert set(schema["properties"]["contextCategory"]["enum"]) == {
        "ordinary", "personal_emergency", "distraction", "fatigue_or_medical", "data_dispute",
        "unclear_or_absurd", "fleeing", "racing", "impairment", "aggression", "self_harm"}
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == set(schema["properties"])  # needed for Groq strict mode


def test_advice_route_enforces_and_never_returns_the_category(monkeypatch):
    calls = []
    def generate(ctx, **kwargs):
        calls.append(kwargs)
        return kwargs["output_model"].model_validate({
            "contextCategory": "racing", "acknowledgement": "You mentioned racing; " + ai.OUTSIDE_SCOPE,
            "advice": COACHING, "encouragement": "Nice launch!"})
    monkeypatch.setattr(ai, "generate", generate)
    p = payload("RAPID_ACCELERATION"); p.update(questionAsked="What happened?", driverContext="跟朋友在红绿灯飙车")
    with TestClient(app) as c:
        body = c.post("/api/v1/ai/reflection/advice", json=p).json()
    assert body["aiStatus"] == "COMPLETED"
    assert body["advice"]["advice"] == list(PAIRS["racing"])
    assert body["advice"]["encouragement"] == ai.HONEST_THANKS
    assert "contextCategory" not in str(body) and "racing\"" not in str(body)
    assert calls[0]["output_model"] is ai.CategorizedAdvice
    assert ai.TYPE_GUIDANCE["RAPID_ACCELERATION"] in calls[0]["system"]
    assert "{{TYPE_GUIDANCE}}" not in calls[0]["system"]


def test_question_turn_is_unchanged(monkeypatch):
    calls = []
    def generate(ctx, **kwargs):
        calls.append(kwargs)
        return kwargs["output_model"].model_validate(
            {"headline": "H.", "question": "Q?", "quickReplies": ["a", "b", "c"]})
    monkeypatch.setattr(ai, "generate", generate)
    with TestClient(app) as c:
        c.post("/api/v1/ai/reflection/question", json=payload("RAPID_ACCELERATION"))
    assert calls[0]["output_model"] is ai.ReflectionQuestion
    assert calls[0]["system"].startswith(ai.QUESTION_SYSTEM) and ai.SHARED in calls[0]["system"]


def test_an_invalid_category_from_the_model_fails_closed(monkeypatch):
    monkeypatch.setattr(ai, "generate", lambda ctx, **kw: {"contextCategory": "street_racing",
        "acknowledgement": "a", "advice": ["b", "c"], "encouragement": "d"})
    p = payload(); p.update(questionAsked="What happened?", driverContext="drag racing")
    with TestClient(app) as c:
        body = c.post("/api/v1/ai/reflection/advice", json=p).json()
    assert body["aiStatus"] == "FAILED" and body["advice"] is None
