#!/usr/bin/env python3
"""Verify the offline Mock HTTP contract using only the Python standard library."""
import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError
from urllib.parse import urlsplit, quote
from urllib.request import Request, build_opener, ProxyHandler
import uuid

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {'P-1001': ('MANUAL_REVIEW', 'HIGH', 70), 'P-2001': ('APPROVE', 'LOW', 10),
            'P-3001': ('MANUAL_REVIEW', 'MEDIUM', 40), 'P-4001': ('REJECT', 'HIGH', 60)}
STEPS = ['QUESTION_UNDERSTANDING', 'BUSINESS_DATA_COLLECTION', 'KNOWLEDGE_RETRIEVAL',
         'RISK_ANALYSIS', 'RULE_VALIDATION', 'RECOMMENDATION_GENERATION', 'RESULT_PERSISTENCE']
TOOLS = {'GET_POLICY', 'GET_QUOTATION', 'GET_UNDERWRITING_HISTORY', 'GET_SURVEY_REPORT',
         'GET_DISASTER_RISK', 'VALIDATE_RULES'}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def ratio(value):
    return type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 1


def validate_evaluation(result, policy):
    require(result['policyNo'] == policy, 'policy number mismatch')
    require(tuple(result[field] for field in ('decision', 'riskLevel', 'riskScore')) == EXPECTED[policy],
            f'{policy}: decision/risk/score mismatch')
    require(bool(result['id']) and bool(result['sessionId']), 'missing saved evaluation identity')
    require(result['degradations'] == [], 'degraded service cannot pass offline acceptance')
    steps = result['stepTraces']
    require([step['step'] for step in steps] == STEPS, 'expected seven ordered steps')
    require(all(step['status'] == 'SUCCESS' for step in steps), 'unsuccessful step')
    tools = result['toolTraces']
    require(len(tools) == 6 and {tool['toolName'] for tool in tools} == TOOLS, 'expected six distinct tools')
    require(all(tool['status'] == 'SUCCESS' for tool in tools), 'unsuccessful tool')
    model = result['modelResponse']
    require(model['provider'] == 'mock' and model['fallbackUsed'] is False and bool(model['model']),
            'expected explicit Mock model without fallback')
    prompt = model['prompt']
    require(prompt['code'] == 'underwriting-analysis' and type(prompt['version']) is int
            and prompt['version'] >= 1, 'invalid Prompt provenance')
    require(re.fullmatch('[0-9a-f]{64}', prompt['templateSha256']) is not None, 'invalid Prompt fingerprint')
    rules = result['ruleSet']
    require(rules['version'] == 'property-underwriting-default-v1' and rules['ruleCount'] == 5,
            'invalid rule-set provenance')
    require(re.fullmatch('[0-9a-f]{64}', rules['sha256']) is not None, 'invalid rule fingerprint')
    require(bool(result['evidence']), 'knowledge evidence missing')
    for item in result['evidence']:
        require(bool(item['documentId']) and bool(item['chunkId']), 'missing evidence identity')
        require(type(item['knowledgeVersion']) is int and item['knowledgeVersion'] >= 1,
                'missing published knowledge version')
        require(all(ratio(item.get(field)) for field in ('score', 'vectorScore', 'lexicalScore')),
                'invalid retrieval score provenance')
        require(item['retrievalMode'] == 'HYBRID' and isinstance(item['matchedTerms'], list),
                'missing hybrid retrieval provenance')


def run_id():
    return datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '-' + uuid.uuid4().hex[:10]


class Client:
    def __init__(self, base_url, output):
        self.base_url, self.output = base_url, output
        # Local acceptance must not accidentally route through an inherited HTTP proxy.
        self.opener = build_opener(ProxyHandler({}))

    def request(self, name, path, payload=None, key=None, expected=200, method=None, markdown=False):
        headers = {'Accept': 'text/markdown' if markdown else 'application/json'}
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        if key:
            headers['Idempotency-Key'] = key
        request = Request(self.base_url + path, data=data, headers=headers,
                          method=method or ('POST' if data is not None else 'GET'))
        try:
            response = self.opener.open(request, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            body = response.read()
            status = response.status
            saved_headers = {field: response.headers.get(field) for field in
                             ('Content-Type', 'Idempotency-Replayed', 'Location')}
        (self.output / (name + '.body')).write_bytes(body)
        (self.output / (name + '.http.json')).write_text(json.dumps(
            {'status': status, 'headers': saved_headers}, ensure_ascii=False, indent=2), encoding='utf-8')
        require(status == expected, f'{name}: expected HTTP {expected}, got {status}')
        if markdown:
            require('text/markdown' in (saved_headers['Content-Type'] or ''), 'report content type mismatch')
            return body.decode('utf-8'), saved_headers
        require((saved_headers['Content-Type'] or '').split(';')[0].strip().lower()
                in ('application/json', 'application/problem+json'), f'{name}: expected JSON content type')
        return json.loads(body), saved_headers


def verify(client, summary):
    health, _ = client.request('health', '/actuator/health')
    require(health['status'] == 'UP', 'service unhealthy')
    scenarios, _ = client.request('scenarios', '/api/v1/demo/scenarios')
    require(len(scenarios) == 4 and {item['policyNo'] for item in scenarios} == set(EXPECTED),
            'expected four demo scenarios')
    catalog = {item['policyNo']: item for item in scenarios}
    summary['checks'].append('health_and_scenario_catalog')
    base = '/api/v1/underwriting/evaluations'
    for policy in EXPECTED:
        question = catalog[policy]['question']
        require(isinstance(question, str) and bool(question.strip()), 'missing scenario question')
        payload = {'policyNo': policy, 'question': question}
        key = summary['runId'] + '-' + policy
        result, headers = client.request(policy, base, payload, key, expected=201)
        require(headers['Idempotency-Replayed'] == 'false', 'first submission must be new')
        validate_evaluation(result, policy)
        summary['models'].append({'policyNo': policy, **{field: result['modelResponse'][field]
                                                       for field in ('provider', 'model', 'fallbackUsed')}})
        saved, _ = client.request(policy + '-saved', base + '/' + quote(result['id'], safe=''))
        require(saved == result, 'saved evaluation differs from submitted result')
        repeated, headers = client.request(policy + '-idempotent', base, payload, key)
        require(headers['Idempotency-Replayed'] == 'true' and repeated == result,
                'idempotent replay did not return same saved result')
        conflict, _ = client.request(policy + '-conflict', base,
                                     {**payload, 'question': question + '（不同问题）'}, key, expected=409)
        require(conflict['errorCode'] == 'IDEMPOTENCY_KEY_CONFLICT', 'wrong conflict error code')
        report, _ = client.request(policy + '-report', base + '/' + quote(result['id'], safe='') + '/report',
                                   markdown=True)
        for value in (result['id'], policy, result['summary'], str(result['riskScore']) + ' / 100',
                      result['modelResponse']['prompt']['templateSha256'], result['ruleSet']['sha256']):
            require(value in report, 'report missing saved result/provenance')
        summary['evaluations'][policy] = {'id': result['id'], 'decision': result['decision'],
                                         'riskLevel': result['riskLevel'], 'riskScore': result['riskScore']}
        summary['checks'].append(policy + ':outcome_traces_provenance_mock_saved_idempotency_report')
    baseline = summary['evaluations']['P-1001']['id']
    replay, _ = client.request('decision-replay', base + '/' + quote(baseline, safe='') + '/replays',
                               key=summary['runId'] + '-replay', expected=201, method='POST')
    require(replay['baseline']['id'] == baseline and replay['replay']['replayedFromEvaluationId'] == baseline,
            'replay baseline linkage mismatch')
    validate_evaluation(replay['replay'], 'P-1001')
    require(replay['drift']['severity'] == 'STABLE', 'decision replay is not STABLE')
    summary['checks'].append('stable_decision_replay')
    cases = [{'name': '产品条款编号', 'query': 'CLAUSE-PROPERTY-001 产品条款',
              'expectedDocumentIds': ['CLAUSE-PROPERTY-001'], 'documentType': 'PRODUCT_CLAUSE', 'productCode': 'PROPERTY'},
             {'name': '暴雨核保规则', 'query': 'RULE-RAIN-001 暴雨洪水核保规则',
              'expectedDocumentIds': ['RULE-RAIN-001'], 'documentType': 'UNDERWRITING_RULE', 'productCode': 'PROPERTY'}]
    rag, _ = client.request('rag-evaluation', '/api/v1/knowledge/evaluations',
                            {'topK': 4, 'minimumRecallAtK': 1.0, 'minimumMeanReciprocalRank': 1.0, 'cases': cases})
    require(rag['passed'] is True and rag['topK'] == 4 and rag['caseCount'] == 2
            and rag['recallAtK'] == 1.0 and rag['meanReciprocalRank'] == 1.0
            and rag['minimumRecallAtK'] == 1.0 and rag['minimumMeanReciprocalRank'] == 1.0,
            'RAG golden-set gate failed')
    summary['rag'] = {field: rag[field] for field in ('topK', 'caseCount', 'recallAtK', 'meanReciprocalRank')}
    summary['checks'].append('rag_recall_at_4_and_mrr_equal_1')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    parser.add_argument('--output-dir', type=Path)
    args = parser.parse_args(argv)
    identifier = run_id()
    output = args.output_dir or ROOT / 'target' / 'demo-evidence' / identifier
    output.mkdir(parents=True, exist_ok=True)
    if (output / 'summary.json').exists() or list(output.glob('*.body')) or list(output.glob('*.http.json')):
        print('Evidence directory must be empty; existing evidence will not be overwritten.', file=sys.stderr)
        return 1
    try:
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        commit = 'unavailable'
    summary = {'passed': False, 'runId': identifier, 'startedAtUtc': datetime.now(timezone.utc).isoformat(),
               'gitCommit': commit, 'models': [], 'evaluations': {}, 'checks': [],
               'scope': 'Fictitious data; deterministic Mock HTTP acceptance, not real LLM quality.'}
    try:
        url = urlsplit(args.base_url)
        require(url.scheme in ('http', 'https') and bool(url.hostname) and not url.username
                and not url.password and not url.query and not url.fragment, 'invalid base URL')
        summary['baseUrl'] = args.base_url.rstrip('/')
        verify(Client(summary['baseUrl'], output), summary)
        summary['passed'] = True
    except Exception as error:
        summary['error'] = f'{type(error).__name__}: {error}'
    finally:
        summary['finishedAtUtc'] = datetime.now(timezone.utc).isoformat()
        (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(('PASS' if summary['passed'] else 'FAIL') + ': ' + str(output / 'summary.json'))
    if not summary['passed']:
        print(summary['error'], file=sys.stderr)
    return 0 if summary['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
