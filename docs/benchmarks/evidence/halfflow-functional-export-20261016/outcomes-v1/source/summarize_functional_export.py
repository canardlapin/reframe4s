from pathlib import Path
import json,sys
study=Path(sys.argv[1]);r=json.loads((study/'receipt.json').read_text())
assert len(r['rows'])==12
cohorts={}
for cohort in ['corrected','historical-control']:
 states=[x for x in r['rows'] if x['task']['cohort']==cohort]
 assert len(states)==6
 reports=[d for state in states if 'report' in state.get('native',{}) for d in state['native']['report'].values()]
 oracles=[d for state in states for d in state.get('oracle',{}).get('rows',[])]
 cohorts[cohort]=dict(states=len(states),constructed=sum(x.get('native',{}).get('construction_succeeded',False) for x in states),
   admitted=sum(x.get('native',{}).get('functional_admitted',False) for x in states),
   construction_rejected=sum(not x.get('native',{}).get('construction_succeeded',False) for x in states),
   declared_samples=sum(sum(x.get('native',{}).get('declared_samples',[])) for x in states),
   evaluated_samples=sum(d['evaluated'] for d in reports),successful_samples=sum(d['successful'] for d in reports),
   boundary_eligible=sum(d['boundaryEligible'] for d in reports),boundary_successful=sum(d['boundarySuccessful'] for d in reports),
   maximum_successful_cycle_mm=max((d['maximumSuccessfulCycleMm'] for d in reports if d['maximumSuccessfulCycleMm'] is not None),default=None),
   minimum_successful_jacobian=min((d['minimumSuccessfulJacobian'] for d in reports if d['minimumSuccessfulJacobian'] is not None),default=None),
   independent_status_mismatches=sum(d['status_mismatches'] for d in oracles),
   maximum_coordinate_difference_mm=max((d['maximum_coordinate_difference_mm'] for d in oracles),default=None),
   maximum_jacobian_difference=max((d['maximum_jacobian_difference'] for d in oracles),default=None),
   all_validation_pass=all(x['validation_pass'] for x in states),
   outcomes=[dict(case=x['task']['case'],constructed=x['native']['construction_succeeded'],admitted=x['native']['functional_admitted'],
       cell_minima=[d['minimum'] for d in x['native']['forward_cell_reports']],
       cell_nonpositive=[d['nonPositive'] for d in x['native']['forward_cell_reports']],
       maximum_cycle_mm=max((d['maximumSuccessfulCycleMm'] for d in x['native'].get('report',{}).values() if d['maximumSuccessfulCycleMm'] is not None),default=None),
       minimum_jacobian=min((d['minimumSuccessfulJacobian'] for d in x['native'].get('report',{}).values() if d['minimumSuccessfulJacobian'] is not None),default=None)) for x in states])
summary=dict(schema='hfq-functional-query-admission-summary-v1',source_sha256=r['source_sha256'],cohorts=cohorts,
   all_validation_pass=r['all_validation_pass'],all_tasks_complete=r['all_tasks_complete'],
   dense_export_admitted=False,qualification_claim=False)
(study/'summary.json').write_text(json.dumps(summary,indent=2,allow_nan=False)+'\n')
print(json.dumps(summary,indent=2))
