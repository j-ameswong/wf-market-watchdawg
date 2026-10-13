#!/usr/bin/env python3
"""Manual ntfy transport check only; does not measure application alert latency."""
import json
import pathlib
import secrets
import time
import urllib.request

config = dict(line.split('=', 1) for line in pathlib.Path(__file__).with_name('.env').read_text().splitlines() if '=' in line)
base = config['NTFY_URL'].rstrip('/')
topic = config['NTFY_TOPIC']
nonce = secrets.token_hex(8)
started = time.monotonic()
body = json.dumps({'topic': topic, 'title': 'Watchdawg delivery check', 'message': 'Acceptance probe ' + nonce}).encode()
try:
    request = urllib.request.Request(base + '/', data=body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=5) as response:
        receipt = json.load(response)
    with urllib.request.urlopen(base + '/' + topic + '/json?poll=1&since=30s', timeout=5) as response:
        messages = [json.loads(line) for line in response if line.strip()]
    received = any(m.get('id') == receipt.get('id') and nonce in m.get('message', '') for m in messages)
    elapsed = time.monotonic() - started
    if not received or elapsed >= 10:
        raise RuntimeError('delivery acceptance failed')
    print(json.dumps({'transport_only': True, 'published': True, 'listener_received': True, 'elapsed_seconds': round(elapsed, 3)}))
except Exception as exc:
    # An HTTP exception can contain a topic URL; report its type only.
    print(json.dumps({'accepted': False, 'error_type': type(exc).__name__}))
    raise SystemExit(1)
