from pathlib import Path
import json,sys
study=Path(sys.argv[1])
r=json.loads((study/'receipt.json').read_text())
rows=[]
for case in r['rows']:
    if case['exit_code'] != 0:
        raise SystemExit('Incomplete native case')
    for native, oracle in zip(case['native']['rows'],case['oracle']):
        assert native['direction']==oracle['direction']
        rows.append(dict(case=case['task']['id'],native=native,oracle=oracle))
assert len(rows)==4
summary=dict(schema='hfq-query-bundle-summary-v1',
    checkpoint_count=len(r['rows']),directions=len(rows),
    eligible=sum(x['native']['eligible'] for x in rows),
    successful_cycles=sum(x['native']['cycle_success'] for x in rows),
    boundary_eligible=sum(x['native']['boundary_eligible'] for x in rows),
    boundary_success=sum(x['native']['boundary_success'] for x in rows),
    maximum_native_cycle_mm=max(x['native']['maximum_successful_cycle_mm'] for x in rows),
    reload_parity_failures=sum(x['native']['reload_parity_failures'] for x in rows),
    maximum_reload_difference_mm=max(x['native']['maximum_reload_difference_mm'] for x in rows),
    independent_status_mismatches=sum(x['oracle']['status_mismatches'] for x in rows),
    maximum_independent_coordinate_difference_mm=max(max(x['oracle']['maximum_coordinate_differences_mm']) for x in rows),
    all_native_cycle_pass=all(x['native']['cycle_pass'] for x in rows),
    all_oracle_cycle_pass=all(x['oracle']['full_cycle_pass'] for x in rows),
    all_oracle_parity_pass=all(x['oracle']['oracle_parity_pass'] for x in rows),
    all_bundles_byte_stable=all(x['native']['byte_stable'] for x in r['rows']),
    bundle_sizes={x['task']['id']:x['bundle']['bytes'] for x in r['rows']},
    source_sha256=r['source_sha256'],dense_export_admitted=False,qualification_claim=False)
(study/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
