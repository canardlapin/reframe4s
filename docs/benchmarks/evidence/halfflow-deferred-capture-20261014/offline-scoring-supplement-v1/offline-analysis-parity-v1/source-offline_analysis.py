"""Validate the provenance boundary between raw captures, exports and scoring.

Nothing here promotes a checkpoint into a completed or converged fit. Pending
exports are not scored; terminal failures and rejection remain explicit.
"""
import hashlib
import json
from pathlib import Path


def read(path):
    return json.loads(Path(path).read_text())


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1048576), b''):
            digest.update(chunk)
    return digest.hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inspect_plan(study, exports):
    """Verify a sealed export plan and return its complete fit/event scope."""
    protocol = read(exports/'protocol.json')
    require(protocol['schema'] in ['hfq-offline-checkpoint-export-v1',
                                   'hfq-offline-checkpoint-export-v2'], 'Unknown export schema')
    require(Path(protocol['captures']).resolve() == study.resolve(), 'Wrong capture study')
    require(protocol['capture_protocol_sha256'] == sha(study/'engine/protocol.json'),
            'Capture protocol changed')
    require(protocol['engine_freeze_sha256'] == sha(Path(protocol['engine'])/'freeze.json'),
            'Export engine seal changed')
    for path, digest in protocol['inputs'].items():
        require(sha(path) == digest, 'Export input changed: '+path)
    panel = read(study/'engine/protocol.json')
    cases = {case['id']: case for case in panel['cases']}
    lanes = {lane['id'] for lane in panel['lanes']}
    scope = protocol.get('selected_fits', [dict(pair=t['pair'], policy=t['policy'])
                                          for t in protocol['tasks']])
    selected = sorted({(item['pair'], item['policy']) for item in scope})
    require(bool(selected), 'Empty analysis scope')
    ledger = []
    events = {}
    for pair, policy in selected:
        require(pair in cases and policy in lanes, 'Undeclared source fit')
        fit_path = study/pair/(policy+'-fit-receipt.json')
        require(protocol['inputs'].get(str(fit_path)) == sha(fit_path), 'Unsealed fit receipt')
        fit = read(fit_path)
        require((fit['pair'], fit['lane']) == (pair, policy), 'Fit identity mismatch')
        event_path = study/pair/policy/'checkpoints/events.jsonl'
        records = []
        if event_path.exists():
            require(protocol['inputs'].get(str(event_path)) == sha(event_path)
                    == fit['events_sha256'], 'Unsealed or changed event stream')
            records = [json.loads(line) for line in event_path.read_text().splitlines()]
        else:
            require(fit['exit_code'] != 0, 'Completed fit missing events')
        for event in records:
            key = pair, policy, event['serial']
            require(key not in events, 'Duplicate committed event')
            events[key] = event
        ledger.append(dict(pair=pair, policy=policy, exit_code=fit['exit_code'],
                           fit_receipt_sha256=sha(fit_path), events=len(records),
                           captured_states=sum(e['directory'] is not None for e in records)))
    tasks = {}
    for task in protocol['tasks']:
        key = task['pair'], task['policy'], task['event']
        require(key in events and key not in tasks, 'Undeclared or duplicate export task')
        event = events[key]
        require(event['directory'] is not None, 'Export task has no committed state')
        checkpoint = study/key[0]/key[1]/'checkpoints'/event['directory']
        require(Path(task['checkpoint']).resolve() == checkpoint.resolve(), 'Wrong checkpoint path')
        require(task['data'] == cases[key[0]]['data'], 'Wrong prepared images')
        require(task['fit_exit_code'] == next(f['exit_code'] for f in ledger
                if (f['pair'], f['policy']) == key[:2]), 'Source fit outcome changed')
        raw = ['checkpoint.json'] + [f'midpoint-{arm}-{name}' for arm in ['fixed','moving']
            for name in ['residual.f64','residual.json','residual.valid-u8','affine.json']]
        for name in raw:
            path = checkpoint/name
            require(protocol['inputs'].get(str(path)) == sha(path), 'Unsealed raw state: '+name)
        require(read(checkpoint/'checkpoint.json') == event, 'Uncommitted checkpoint metadata')
        tasks[key] = task
    if not protocol['pilot']:
        require(set(tasks) == {key for key, event in events.items() if event['directory'] is not None},
                'Nonpilot export batch omits a committed state')
    return protocol, tasks, ledger


def resolve_export(exports, protocol, task):
    """Return pending, failure, or an identity-checked native export location."""
    parent = exports/task['pair']/task['policy']
    name = f"event-{task['event']:04d}"
    receipt_path = parent/(name+'-receipt.json')
    if not receipt_path.exists():
        return dict(status='export_pending')
    receipt = read(receipt_path)
    require(receipt['protocol_sha256'] == sha(exports/'protocol.json'), 'Wrong export receipt protocol')
    require(receipt['task'] == task, 'Wrong export receipt task')
    require(receipt['log_sha256'] == sha(parent/(name+'.log')), 'Export log changed')
    row = dict(export_receipt_sha256=sha(receipt_path), export_exit_code=receipt['exit_code'])
    if receipt['exit_code'] != 0:
        return dict(row, status='export_failed', failure=receipt.get('failure', 'Export process failed'))
    target = parent/name
    outputs = receipt.get('outputs', {})
    for filename, digest in outputs.items():
        require(Path(filename).name == filename, 'Invalid output name')
        require(sha(target/filename) == digest, 'Export output changed: '+filename)
    require({'result.json', 'inputs.json', 'export-status.txt', 'export-config.txt'} <= set(outputs),
            'Incomplete successful export receipt')
    result = read(target/'result.json')
    require(result == receipt.get('result'), 'Export result differs from receipt')
    require(result['checkpoint_sha256'] == sha(Path(task['checkpoint'])/'checkpoint.json'),
            'Export uses wrong raw state')
    expected_inputs = [Path(task['checkpoint'])/name for name in ['checkpoint.json'] +
        [f'midpoint-{arm}-{name}' for arm in ['fixed','moving']
         for name in ['residual.f64','residual.json','residual.valid-u8','affine.json']]]
    expected_inputs += [Path(task['data'])/name for name in ['moving.nii.gz','fixed.nii.gz','fixed-mask.nii.gz']]
    require(read(target/'inputs.json') == {str(p):protocol['inputs'][str(p)] for p in expected_inputs},
            'Export provenance differs from sealed inputs')
    status = result['status']
    accepted = status == 'native export gate accepted'
    require((target/'export-status.txt').read_text().strip() == status, 'Export status changed')
    require(result['native_export_admitted'] == accepted, 'Contradictory export admission')
    lane = result['lane']
    if lane is None:
        require(not accepted, 'Admitted export has no maps')
        return dict(row, status='export_unavailable', failure=status)
    require(lane == ('halfflow' if accepted else 'halfflow-rejected'), 'Export lane/admission mismatch')
    names = [lane+'-'+direction+suffix for direction in ['fixed-to-moving','moving-to-fixed']
             for suffix in ['.f64','.json','.valid-u8','-warped.f64']]
    require(set(names+['export-diagnostics.txt']) <= set(outputs), 'Export map files missing from receipt')
    return dict(row, status='available', directory=target, lane=lane,
                native_export_accepted=accepted, export_status=status)
