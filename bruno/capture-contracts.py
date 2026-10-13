"""Manual, paced fixture capture. Never invoked by tests; discards trader information."""
import json
import time
import urllib.request
from pathlib import Path

DEST = Path(__file__).resolve().parents[1] / 'market/src/test/resources/fixtures/contracts'
HEADERS = {'User-Agent': 'wf-market-watchdawg/0.1 (+https://github.com/j-ameswong/wf-market-watchdawg)',
           'Platform': 'pc', 'Crossplay': 'true', 'Language': 'en'}

def get(path):
    request = urllib.request.Request('https://api.warframe.market/' + path, headers=HEADERS)
    with urllib.request.urlopen(request, timeout=20) as response:
        result = json.load(response)
    return result

for kind, weapon in [('riven', 'kulstar'), ('lich', 'kuva_bramma'), ('sister', 'tenet_tetra')]:
    manifest = get('v2/' + kind + '/weapons')
    (DEST / (kind + '-weapons.json')).write_text(json.dumps(manifest, indent=2) + '\n')
    time.sleep(0.6)
    result = get('v1/auctions/search?type=' + kind + '&weapon_url_name=' + weapon)
    fields = {'id', 'starting_price', 'buyout_price', 'top_bid', 'is_direct_sell', 'visible', 'closed', 'created', 'updated', 'platform', 'item'}
    auctions = [{k: v for k, v in a.items() if k in fields} for a in result['payload']['auctions']]
    (DEST / (kind + '.json')).write_text(json.dumps({'payload': {'auctions': auctions}}, indent=2) + '\n')
    print(kind, 'captured', len(auctions), 'auctions; fields', sorted(auctions[0]) if auctions else [])
    time.sleep(5)  # contract-search 12/min; no other WFM client is running during capture

attributes = get('v2/riven/attributes')
(DEST / 'riven-attributes.json').write_text(json.dumps(attributes, indent=2) + '\n')
