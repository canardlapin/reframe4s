import json
from pathlib import Path
import tempfile
import unittest

from offline_analysis import inspect_plan, resolve_export, sha


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value))


class OfflineAnalysisTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.study, self.exports, self.engine, self.data = [root/name for name in ['study','exports','engine','data']]
        self.event = dict(serial=1, directory='state-0001', export_status='deferred: raw midpoint captured; native export pending')
        panel = dict(cases=[dict(id='pair',data=str(self.data))],lanes=[dict(id='policy'),dict(id='failed')])
        write(self.study/'engine/protocol.json', panel)
        write(self.engine/'freeze.json', dict(source='frozen-engine'))
        inputs = {}
        for policy, exit_code in [('policy', 0), ('failed', 124)]:
            fit = self.study/'pair'/(policy+'-fit-receipt.json')
            row = dict(pair='pair',lane=policy,exit_code=exit_code)
            if policy == 'policy':
                events = self.study/'pair/policy/checkpoints/events.jsonl'
                write(events, self.event)
                row['events_sha256'] = inputs[str(events)] = sha(events)
            write(fit, row)
            inputs[str(fit)] = sha(fit)
        self.checkpoint = self.study/'pair/policy/checkpoints/state-0001'
        write(self.checkpoint/'checkpoint.json', self.event)
        for arm in ['fixed','moving']:
            for suffix in ['residual.f64','residual.json','residual.valid-u8','affine.json']:
                (self.checkpoint/f'midpoint-{arm}-{suffix}').write_text('raw '+arm+' '+suffix)
        for path in self.checkpoint.iterdir():
            inputs[str(path)] = sha(path)
        self.data.mkdir()
        for name in ['moving.nii.gz','fixed.nii.gz','fixed-mask.nii.gz']:
            path = self.data/name
            path.write_text('image identity '+name)
            inputs[str(path)] = sha(path)
        self.task = dict(pair='pair',policy='policy',event=1,checkpoint=str(self.checkpoint),
                         data=str(self.data),fit_exit_code=0,expected_synchronous_exports={})
        self.protocol = dict(schema='hfq-offline-checkpoint-export-v2', captures=str(self.study),
            capture_protocol_sha256=sha(self.study/'engine/protocol.json'), engine=str(self.engine),
            engine_freeze_sha256=sha(self.engine/'freeze.json'),inputs=inputs,tasks=[self.task],pilot=False,
            selected_fits=[dict(pair='pair',policy='policy'),dict(pair='pair',policy='failed')])
        write(self.exports/'protocol.json',self.protocol)
        self.target = self.exports/'pair/policy/event-0001'
        self.receipt_path = self.exports/'pair/policy/event-0001-receipt.json'

    def export(self, accepted=True, construction_failed=False):
        self.target.mkdir(parents=True)
        lane = None if construction_failed else ('halfflow' if accepted else 'halfflow-rejected')
        status = ('export construction failed: inversion failed' if construction_failed else
                  'native export gate accepted' if accepted else 'rejected: round trip exceeded')
        result = dict(checkpoint_sha256=sha(self.checkpoint/'checkpoint.json'),status=status,
                      lane=lane,native_export_admitted=accepted and not construction_failed)
        write(self.target/'result.json',result)
        write(self.target/'inputs.json',{str(p):sha(p) for p in list(self.checkpoint.iterdir())+list(self.data.iterdir())})
        (self.target/'export-status.txt').write_text(status+'\n')
        (self.target/'export-config.txt').write_text('unchanged-config\n')
        if lane:
            (self.target/'export-diagnostics.txt').write_text('recorded endpoint diagnostics\n')
            for direction in ['fixed-to-moving','moving-to-fixed']:
                for suffix in ['.f64','.json','.valid-u8','-warped.f64']:
                    (self.target/(lane+'-'+direction+suffix)).write_text(direction+suffix)
        log = self.target.with_suffix('.log')
        log.write_text('export log\n')
        receipt = dict(task=self.task,protocol_sha256=sha(self.exports/'protocol.json'),exit_code=0,
            log_sha256=sha(log),result=result,outputs={p.name:sha(p) for p in self.target.iterdir()})
        write(self.receipt_path,receipt)
        return receipt

    def test_scope_keeps_failed_fit_without_events(self):
        _, tasks, ledger = inspect_plan(self.study,self.exports)
        self.assertEqual(len(tasks),1)
        self.assertEqual([(f['policy'],f['exit_code'],f['events']) for f in ledger],
                         [('failed',124,0),('policy',0,1)])

    def test_partial_batch_cannot_silently_omit_committed_state(self):
        self.protocol['tasks'] = []
        write(self.exports/'protocol.json',self.protocol)
        with self.assertRaisesRegex(ValueError,'omits a committed state'):
            inspect_plan(self.study,self.exports)

    def test_wrong_fit_outcome_or_state_is_rejected(self):
        self.task['fit_exit_code'] = 0
        self.task['checkpoint'] = str(self.study/'other-state')
        write(self.exports/'protocol.json',self.protocol)
        with self.assertRaisesRegex(ValueError,'Wrong checkpoint path'):
            inspect_plan(self.study,self.exports)
        self.task['checkpoint'] = str(self.checkpoint)
        self.task['fit_exit_code'] = 124
        write(self.exports/'protocol.json',self.protocol)
        with self.assertRaisesRegex(ValueError,'Source fit outcome changed'):
            inspect_plan(self.study,self.exports)

    def test_raw_mutation_is_rejected(self):
        (self.checkpoint/'midpoint-fixed-residual.f64').write_text('changed')
        with self.assertRaisesRegex(ValueError,'Export input changed'):
            inspect_plan(self.study,self.exports)

    def test_pending_export_has_no_usable_map(self):
        self.assertEqual(resolve_export(self.exports,self.protocol,self.task),dict(status='export_pending'))

    def test_admission_comes_from_export_not_deferred_capture(self):
        self.export()
        result = resolve_export(self.exports,self.protocol,self.task)
        self.assertEqual(result['status'],'available')
        self.assertTrue(result['native_export_accepted'])
        self.assertEqual(result['lane'],'halfflow')

    def test_rejected_export_remains_rejected_but_can_be_diagnosed(self):
        self.export(accepted=False)
        result = resolve_export(self.exports,self.protocol,self.task)
        self.assertEqual(result['status'],'available')
        self.assertFalse(result['native_export_accepted'])
        self.assertEqual(result['lane'],'halfflow-rejected')

    def test_construction_and_process_failures_are_distinct(self):
        receipt = self.export(construction_failed=True)
        self.assertEqual(resolve_export(self.exports,self.protocol,self.task)['status'],'export_unavailable')
        receipt['exit_code'] = 124
        write(self.receipt_path,receipt)
        self.assertEqual(resolve_export(self.exports,self.protocol,self.task)['status'],'export_failed')

    def test_changed_export_payload_is_rejected(self):
        self.export()
        (self.target/'halfflow-fixed-to-moving.f64').write_text('different map')
        with self.assertRaisesRegex(ValueError,'Export output changed'):
            resolve_export(self.exports,self.protocol,self.task)

    def test_valid_hashes_cannot_hide_wrong_export_provenance(self):
        receipt = self.export()
        write(self.target/'inputs.json',{'another-checkpoint':'another-hash'})
        receipt['outputs']['inputs.json'] = sha(self.target/'inputs.json')
        write(self.receipt_path,receipt)
        with self.assertRaisesRegex(ValueError,'Export provenance differs'):
            resolve_export(self.exports,self.protocol,self.task)

    def test_wrong_receipt_task_is_rejected(self):
        receipt = self.export()
        receipt['task'] = dict(self.task,event=2)
        write(self.receipt_path,receipt)
        with self.assertRaisesRegex(ValueError,'Wrong export receipt task'):
            resolve_export(self.exports,self.protocol,self.task)


if __name__ == '__main__':
    unittest.main()
