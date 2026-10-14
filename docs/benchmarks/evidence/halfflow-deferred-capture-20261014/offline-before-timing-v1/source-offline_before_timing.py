#!/usr/bin/env python3
"""Prospectively schedule an unchanged untimed checkpoint batch before timing."""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

from run_isolated_reference import ROOT, preflight, read, write, sha, inspect


def freeze(output, study, diagnostics, isolation):
    if output.exists():
        raise FileExistsError('Fresh scheduling amendment directory required')
    if (study/'isolation-plan.json').exists() or (study/'launch-receipt.json').exists():
        raise ValueError('Timing has already been attempted')
    checks = preflight(study, diagnostics)
    exports = diagnostics/'offline-panel-oasis13-mmrr11-v1'
    analysis = diagnostics/'offline-analysis-oasis13-mmrr11-v1'
    if (analysis/'follower-plan.json').exists() or list(analysis.glob('*/*/event-*.json')):
        raise ValueError('Analysis already started')
    e, a = read(exports/'protocol.json'), read(analysis/'protocol.json')
    if len(e['tasks']) != 61 or sum(f['events'] for f in a['offline_exports']['fit_ledger']) != 162:
        raise ValueError('Wrong offline batch')
    if not read(isolation)['external_active_numerical_processes']:
        raise ValueError('Expected evidence of the competing workload')
    helpers = [Path(__file__).resolve(), Path(__file__).with_name('run_isolated_reference.py').resolve()]
    helpers += [ROOT/'benchmarks/halfflow/diagnostics'/name for name in
        ['offline_checkpoints.py','follow_offline_analysis.py','checkpoint_analysis.py','verify_offline_ledger.py']]
    identities = {str(p):sha(p) for p in helpers+[study/'protocol.json',exports/'protocol.json',analysis/'protocol.json']}
    identities.update(a['sources'])
    if sha(helpers[2]) != e['driver_sha256']:
        raise ValueError('Export driver changed since declaration')
    output.mkdir(parents=True)
    write(output/'prior-preflight.json', checks)
    shutil.copyfile(isolation, output/'competing-workload.json')
    for path in helpers:
        shutil.copyfile(path, output/('source-'+path.name))
    write(output/'amendment.json', dict(schema='hfq-offline-before-timing-v1',
        created_at=datetime.now(timezone.utc).isoformat(),timing_study=str(study),diagnostics=str(diagnostics),
        identities=identities,prior_preflight_sha256=sha(output/'prior-preflight.json'),
        competing_workload_sha256=sha(output/'competing-workload.json'),
        change='Run the already declared 61 exports and 162-event analysis before the isolated timing study. '
            'The earlier operational queue order is superseded. The original timing protocol prohibits concurrent '
            'numerical work during timing; it does not prohibit completion of this batch beforehand.',
        unchanged='All frozen images, labels, fits, states, export and scoring code, thresholds, denominators and timing isolation rules.',
        completion_requirement='Both workers must be terminal and the exact 61-export/162-event ledger complete before timing preflight can pass.',
        export_workers=1,analysis_workers=1,concurrent_export_and_analysis=True,
        follower_deadline_seconds=86400,qualification_claim=False,runtime_comparison_allowed=False))
    print('FROZEN scheduling amendment; no scientific settings changed',flush=True)


def run(output):
    amendment = output/'amendment.json';p = read(amendment)
    for path, digest in p['identities'].items():
        if sha(path) != digest:
            raise ValueError('Scheduling identity changed: '+path)
    study = Path(p['timing_study']);diagnostics = Path(p['diagnostics'])
    if (study/'isolation-plan.json').exists() or (study/'launch-receipt.json').exists():
        raise ValueError('Timing must remain unstarted')
    if (output/'launch.json').exists():
        raise FileExistsError('Prior worker launch retained')
    exports = diagnostics/'offline-panel-oasis13-mmrr11-v1'
    analysis = diagnostics/'offline-analysis-oasis13-mmrr11-v1'
    env = os.environ.copy();env.update(read(exports/'protocol.json')['environment'])
    env['MPLCONFIGDIR'] = '/private/tmp/hfq-mpl'
    commands = {
        'exports':[sys.executable,str(ROOT/'benchmarks/halfflow/diagnostics/offline_checkpoints.py'),
            'run','--output',str(exports)],
        'analysis':[sys.executable,str(ROOT/'benchmarks/halfflow/diagnostics/follow_offline_analysis.py'),
            '--analysis',str(analysis),'--deadline-seconds',str(p['follower_deadline_seconds'])]}
    write(output/'launch.json',dict(started_at=datetime.now(timezone.utc).isoformat(),
        amendment_sha256=sha(amendment),commands=commands,qualification_claim=False,runtime_comparison_allowed=False))
    processes = {};streams = {};codes = {};failure = None;exit_code = 1
    try:
        for name, command in commands.items():
            streams[name] = (output/(name+'.log')).open('x')
            processes[name] = subprocess.Popen(command,cwd=ROOT,env=env,stdout=streams[name],
                stderr=subprocess.STDOUT,start_new_session=True)
        print('START 61 exports and 162-event scoring; untimed',flush=True)
        while len(codes) < len(processes):
            for name, process in processes.items():
                code = process.poll()
                if code is not None and name not in codes:
                    codes[name] = code
                    print('TERMINAL',name,code,flush=True)
                    if code != 0:
                        raise RuntimeError(name+' worker failed; partial outcomes retained')
            if len(codes) < len(processes):
                time.sleep(2)
        ledger = inspect(analysis)
        write(output/'ledger-verification.json',ledger)
        if not ledger['ledger_complete']:
            raise ValueError('Workers ended without a complete ledger')
        exit_code = 0
    except BaseException as error:
        failure = type(error).__name__+': '+str(error)
        raise
    finally:
        for name, process in processes.items():
            if process.poll() is None:
                os.killpg(process.pid,signal.SIGTERM)
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid,signal.SIGKILL);process.wait()
            codes[name] = process.returncode
        for stream in streams.values():stream.close()
        write(output/'completion.json',dict(ended_at=datetime.now(timezone.utc).isoformat(),
            amendment_sha256=sha(amendment),exit_code=exit_code,worker_exit_codes=codes,failure=failure,
            log_sha256={name:sha(output/(name+'.log')) for name in streams},
            qualification_claim=False,runtime_comparison_allowed=False))
    print('COMPLETE offline batch; timing still requires a clean isolation preflight',flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['freeze','run'])
    parser.add_argument('--output',type=Path,required=True)
    for name in ['study','diagnostics','isolation']:parser.add_argument('--'+name,type=Path)
    args = parser.parse_args()
    if args.command == 'freeze':
        if any(getattr(args,n) is None for n in ['study','diagnostics','isolation']):
            parser.error('freeze requires --study, --diagnostics and --isolation')
        freeze(args.output.resolve(),args.study.resolve(),args.diagnostics.resolve(),args.isolation.resolve())
    else:run(args.output.resolve())
