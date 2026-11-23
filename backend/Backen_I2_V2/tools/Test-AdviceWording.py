"""Manual live wording check. Four requests consume provider quota. No automatic retries."""
import argparse,json,urllib.request,urllib.error,uuid
p=argparse.ArgumentParser()
p.add_argument('--base-url',default='https://backend-8sxu.onrender.com')
p.add_argument('--run',action='store_true',help='Explicitly authorise FOUR real AI advice requests')
a=p.parse_args()
if not a.run:p.error('Add --run only when ready to consume quota for four synthetic advice requests.')
accounts=[('ordinary','Traffic ahead slowed.'),('unverifiable hazard','A kangaroo jumped across the lane.'),
          ('flippant','A zombie appeared in front of the car.'),('gibberish','Blorpt zzz flumpo 123.')]
for label,description in accounts:
    body={'tripId':'synthetic-wording-'+str(uuid.uuid4()),'selectedEvent':{
        'eventId':'synthetic-braking','type':'RAPID_DECELERATION','startOffsetSeconds':5,
        'endOffsetSeconds':8,'durationSeconds':3,'dataQuality':'UNKNOWN','detectorVersion':'synthetic',
        'metrics':{'speedBeforeKmh':60,'speedAfterKmh':10,'peakAccelerationMs2':-5}},
        'questionAsked':'What was happening around you when the recording suggests this change?',
        'driverContext':description}
    request=urllib.request.Request(a.base_url.rstrip('/')+'/api/v1/ai/reflection/advice',
       data=json.dumps(body).encode(),headers={'Content-Type':'application/json'},method='POST')
    print('\nCASE:',label,'ACCOUNT:',description,flush=True)
    try:
        with urllib.request.urlopen(request,timeout=90) as response:
            print(response.status,response.read().decode(),flush=True)
    except urllib.error.HTTPError as e:print('HTTP',e.code,e.read().decode(),flush=True)
    except Exception as e:print(type(e).__name__,str(e),'No retry.',flush=True)
print('\nReview all answers manually: second person, attributed accounts, no invented threshold; unclear accounts acknowledged. Mock tests cannot establish this.')
