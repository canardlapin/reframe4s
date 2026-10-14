#!/usr/bin/env python3
"""Measure a direct child using wait4, without privileged system queries."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def measure(command, receipt):
    if receipt.exists():
        raise FileExistsError(receipt)
    started = time.monotonic()
    child = subprocess.Popen(command)
    _, status, usage = os.wait4(child.pid, 0)
    child.returncode = os.waitstatus_to_exitcode(status)
    result = {
        'schema': 'hfq-process-measurement-v1',
        'command': command,
        'exit_code': child.returncode,
        'wall_seconds': time.monotonic() - started,
        'user_seconds': usage.ru_utime,
        'system_seconds': usage.ru_stime,
        'peak_rss_bytes': int(usage.ru_maxrss * (1 if sys.platform == 'darwin' else 1024)),
        'measurement': 'wait4 direct child; Darwin ru_maxrss is bytes; Linux is KiB',
        'scope': 'child registration process, excluding parent conversion and evaluation',
    }
    with receipt.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    return child.returncode if child.returncode >= 0 else 128 - child.returncode


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('a child command is required')
    sys.exit(measure(command, args.receipt))
