"""Acceptance-validator regression tests; fixtures follow the Java response DTOs."""
import copy
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
import importlib.util
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'verify-demo.py'


def evaluation(policy='P-1001', decision='MANUAL_REVIEW', risk='HIGH', score=70):
    return {
        'id': 'EVAL-test', 'sessionId': 'SESSION-test', 'policyNo': policy,
        'question': '虚构核保问题', 'decision': decision, 'riskLevel': risk,
        'riskScore': score, 'summary': '虚构摘要', 'reasons': [],
        'recommendedActions': [], 'degradations': [], 'ruleHits': [],
        'createdAt': '2026-10-25T00:00:00Z', 'replayedFromEvaluationId': None,
        'stepTraces': [{'step': step, 'status': 'SUCCESS', 'startedAt': '2026-10-25T00:00:00Z',
                        'durationMs': 0, 'errorCode': None} for step in [
            'QUESTION_UNDERSTANDING', 'BUSINESS_DATA_COLLECTION', 'KNOWLEDGE_RETRIEVAL',
            'RISK_ANALYSIS', 'RULE_VALIDATION', 'RECOMMENDATION_GENERATION', 'RESULT_PERSISTENCE']],
        'toolTraces': [{'toolName': name, 'status': 'SUCCESS', 'startedAt': '2026-10-25T00:00:00Z',
                        'durationMs': 0, 'inputSummary': 'test', 'outputSummary': 'test', 'errorCode': None}
                       for name in ['GET_POLICY', 'GET_QUOTATION', 'GET_UNDERWRITING_HISTORY',
                                    'GET_SURVEY_REPORT', 'GET_DISASTER_RISK', 'VALIDATE_RULES']],
        'evidence': [{'documentId': 'RULE-RAIN-001', 'chunkId': 'chunk-1', 'title': '规则',
                      'type': 'UNDERWRITING_RULE', 'excerpt': '虚构内容', 'score': 0.8,
                      'knowledgeVersion': 1, 'vectorScore': 0.7, 'lexicalScore': 1.0,
                      'retrievalMode': 'HYBRID', 'matchedTerms': ['暴雨']}],
        'modelResponse': {'summary': '虚构摘要', 'reasons': [], 'recommendedActions': [],
                          'provider': 'mock', 'model': 'deterministic-underwriter-v1',
                          'attempts': 1, 'fallbackUsed': False,
                          'prompt': {'code': 'underwriting-analysis', 'version': 1,
                                     'templateSha256': 'a' * 64}},
        'ruleSet': {'version': 'property-underwriting-default-v1', 'ruleCount': 5, 'sha256': 'b' * 64},
    }


class ValidatorTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), 'HTTP acceptance validator has not been implemented')
        spec = importlib.util.spec_from_file_location('verify_demo', SCRIPT)
        self.validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.validator)

    def test_all_four_fixed_outcomes_are_accepted(self):
        for args in [('P-1001', 'MANUAL_REVIEW', 'HIGH', 70), ('P-2001', 'APPROVE', 'LOW', 10),
                     ('P-3001', 'MANUAL_REVIEW', 'MEDIUM', 40), ('P-4001', 'REJECT', 'HIGH', 60)]:
            with self.subTest(policy=args[0]):
                self.validator.validate_evaluation(evaluation(*args), args[0])

    def test_wrong_decision_risk_and_score_fail_independently(self):
        for field, value in [('decision', 'APPROVE'), ('riskLevel', 'LOW'), ('riskScore', 10)]:
            result = evaluation()
            result[field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                self.validator.validate_evaluation(result, 'P-1001')

    def test_missing_or_malformed_provenance_fails(self):
        for section, field, value in [('ruleSet', 'sha256', 'unknown'),
                                       ('ruleSet', 'ruleCount', 0),
                                       ('modelResponse', 'prompt', None)]:
            result = evaluation()
            result[section][field] = value
            with self.subTest(field=field), self.assertRaises((AssertionError, KeyError, TypeError)):
                self.validator.validate_evaluation(result, 'P-1001')
        for field, value in [('knowledgeVersion', 0), ('retrievalMode', 'UNKNOWN'),
                             ('vectorScore', None), ('matchedTerms', None)]:
            result = evaluation()
            result['evidence'][0][field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                self.validator.validate_evaluation(result, 'P-1001')

    def test_real_model_fallback_and_degradation_cannot_pass(self):
        for field, value in [('provider', 'openai-compatible'), ('fallbackUsed', True), ('model', '')]:
            result = evaluation()
            result['modelResponse'][field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                self.validator.validate_evaluation(result, 'P-1001')
        result = evaluation()
        result['degradations'] = [{'code': 'NON_CRITICAL_TOOL_UNAVAILABLE'}]
        with self.assertRaises(AssertionError):
            self.validator.validate_evaluation(result, 'P-1001')

    def test_failed_or_duplicate_steps_and_tools_fail(self):
        for section in ['stepTraces', 'toolTraces']:
            for mutation in ['status', 'duplicate']:
                result = evaluation()
                if mutation == 'status':
                    result[section][0]['status'] = 'DEGRADED'
                else:
                    result[section][0] = copy.deepcopy(result[section][1])
                with self.subTest(section=section, mutation=mutation), self.assertRaises(AssertionError):
                    self.validator.validate_evaluation(result, 'P-1001')

    def test_problem_json_conflict_response_is_parsed_and_preserved(self):
        body = b'{"status":409,"title":"Conflict","errorCode":"IDEMPOTENCY_KEY_CONFLICT"}'
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(409)
                self.send_header('Content-Type', 'application/problem+json')
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                client = self.validator.Client(f'http://127.0.0.1:{server.server_port}', Path(directory))
                result, _ = client.request('conflict', '/conflict', expected=409)
                self.assertEqual(result['errorCode'], 'IDEMPOTENCY_KEY_CONFLICT')
                self.assertEqual((Path(directory) / 'conflict.body').read_bytes(), body)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_http_error_and_malformed_json_preserve_failed_summary_and_raw_body(self):
        for status, body in [(503, b'{"error":"unavailable"}'), (200, b'not-json')]:
            class Handler(BaseHTTPRequestHandler):
                def do_GET(self):
                    self.send_response(status)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(body)
                def log_message(self, *args):
                    pass
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with tempfile.TemporaryDirectory() as directory:
                    with redirect_stdout(StringIO()), redirect_stderr(StringIO()):
                        code = self.validator.main(['--base-url', f'http://127.0.0.1:{server.server_port}',
                                                    '--output-dir', directory])
                    summary = json.loads((Path(directory) / 'summary.json').read_text())
                    self.assertEqual(code, 1)
                    self.assertIs(summary['passed'], False)
                    self.assertTrue(summary['error'])
                    self.assertEqual((Path(directory) / 'health.body').read_bytes(), body)
            finally:
                server.shutdown()
                server.server_close()
                thread.join()


if __name__ == '__main__':
    unittest.main()
