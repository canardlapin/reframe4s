#!/usr/bin/env python3
"""Derive scalar report values from terminal immutable per-pair receipts."""
import json
from pathlib import Path
import sys

study = Path(sys.argv[1]).resolve()
def read(name):
    return json.loads((study/name).read_text())
p = read('protocol.json'); completion = read('completion.json'); receipt = read('receipt.json')
assert completion['exit_code']==0 and receipt['all_tasks_complete'] and receipt['all_validation_pass']
native = [r for pair in receipt['rows'] for r in pair['native']['report'].values()]
oracle = [r for pair in receipt['rows'] for r in pair['oracle']['rows']]
applications = [r for pair in receipt['rows'] for r in pair['application']['rows']]
assert len(native)==len(oracle)==len(applications)==4
assert sum(r['eligible'] for r in native)==sum(sum(t['half_voxel_counts'].values()) for t in p['tasks'])
anatomy = []
for pair in receipt['rows']:
    for r in pair['application']['rows']:
        scores = r['anatomy']; f = scores['functional']; d = scores['same_checkpoint_dense']; a = scores['saved_affine']
        assert f['label_count']==d['label_count']==a['label_count']==62
        anatomy.append(dict(pair=pair['id'],direction=r['direction'],functional_dice=f['macro_dice'],dense_dice=d['macro_dice'],affine_dice=a['macro_dice'],
                            delta_dice_vs_dense=f['macro_dice']-d['macro_dice'],delta_dice_vs_affine=f['macro_dice']-a['macro_dice'],
                            functional_hd95_mm=f['median_hd95_mm'],dense_hd95_mm=d['median_hd95_mm'],affine_hd95_mm=a['median_hd95_mm'],
                            missing_labels={k:v['missing_labels'] for k,v in scores.items()}))
summary = dict(pairs=len(receipt['rows']),admitted=sum(pair['native']['functional_admitted'] for pair in receipt['rows']),
               half_voxel_queries=sum(r['eligible'] for r in native),half_voxel_successful=sum(r['successful'] for r in native),
               boundary_queries=sum(r['boundaryEligible'] for r in native),boundary_successful=sum(r['boundarySuccessful'] for r in native),
               maximum_native_cycle_mm=max(r['maximumSuccessfulCycleMm'] for r in native),minimum_native_endpoint_jacobian=min(r['minimumSuccessfulJacobian'] for r in native),
               oracle_status_mismatches=sum(r['status_mismatches'] for r in oracle),maximum_oracle_coordinate_difference_mm=max(r['maximum_coordinate_difference_mm'] for r in oracle),
               maximum_oracle_jacobian_difference=max(r['maximum_jacobian_difference'] for r in oracle),
               native_application_nodes=sum(r['eligible'] for r in applications),native_application_successful=sum(r['transform_successful'] for r in applications),
               image_supported=sum(r['image_supported'] for r in applications),maximum_node_component_difference=max(r['maximum_node_component_difference'] for r in applications),
               image_support_mismatches=sum(r['image_support_mismatches'] for r in applications),maximum_intensity_difference=max(r['maximum_intensity_difference'] for r in applications),
               anatomy=anatomy,qualification_claim=False,dense_export_admitted=False)
(study/'summary.json').write_text(json.dumps(summary,indent=2,allow_nan=False)+'\n')
print(json.dumps(summary,indent=2,allow_nan=False))
