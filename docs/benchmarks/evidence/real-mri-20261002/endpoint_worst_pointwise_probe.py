"""Compare saved endpoint values with pointwise residual inversion at worst folds.
Uses only helper definitions from the earlier targeted residual probe.
"""
import ast
import json
import sys
from pathlib import Path
import numpy as np

helper = Path(__file__).with_name('targeted_residual_inverse_probe.py')
tree = ast.parse(helper.read_text())
namespace = {}
definitions = [node for node in tree.body if isinstance(node, (ast.Import, ast.ImportFrom, ast.ClassDef))]
exec(compile(ast.Module(body=definitions, type_ignores=[]), str(helper), 'exec'), namespace)
Map = namespace['Map']
base = Path(sys.argv[1])
output = Path(sys.argv[2])
locations = json.loads((base / 'endpoint-fold-localization.json').read_text())
fields = {arm: Map(base / f'midpoint-{arm}-residual') for arm in ['fixed', 'moving']}
affines = {arm: np.array(json.loads((base / f'midpoint-{arm}-affine.json').read_text())).reshape(4, 4)
           for arm in fields}
rows = []
for location in locations:
    target, other = location['direction'].split('-to-')
    own, foreign = fields[target], fields[other]
    query = np.array(location['minimum_affine_query_world'])
    value, jacobian, _ = own.evaluate(query)
    other_value, other_jacobian, _ = foreign.evaluate(query)
    row = dict(direction=location['direction'], query=query.tolist(),
        own_extrapolated_jacobian_det=float(np.linalg.det(jacobian)),
        other_extrapolated_jacobian_det_at_query=float(np.linalg.det(other_jacobian)),
        own_extrapolated_displacement=(value-query).tolist(),
        other_extrapolated_displacement_at_query=(other_value-query).tolist())
    try:
        inverse, residual, own_jacobian = own.solve(query, query)
        mapped, foreign_jacobian, _ = foreign.evaluate(inverse)
        other_affine, own_affine = affines[other], affines[target]
        endpoint = other_affine[:3, :3] @ mapped + other_affine[:3, 3]
        chain = other_affine[:3, :3] @ foreign_jacobian @ np.linalg.inv(own_jacobian) @ np.linalg.inv(own_affine[:3, :3])
        row.update(pointwise_inverse=inverse.tolist(), root_residual_mm=residual,
            own_jacobian_at_root=float(np.linalg.det(own_jacobian)),
            other_jacobian_at_root=float(np.linalg.det(foreign_jacobian)),
            pointwise_endpoint=endpoint.tolist(),
            pointwise_endpoint_chain_jacobian_det=float(np.linalg.det(chain)),
            saved_endpoint_discrepancy_mm=float(np.linalg.norm(endpoint-location['minimum_saved_endpoint'])))
    except RuntimeError as error:
        row['root_failure'] = str(error)
    rows.append(row)
with output.open('x') as stream:
    json.dump(rows, stream, indent=2)
    stream.write('\n')
print(json.dumps(rows, indent=2))
