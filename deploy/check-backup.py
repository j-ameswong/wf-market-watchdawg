#!/usr/bin/env python3
"""Local regression check: real Compose rendering, stubbed database execution."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


source = Path(__file__).resolve().parent
docker = shutil.which('docker')
assert docker, 'Docker Compose is required; no daemon is contacted'
with tempfile.TemporaryDirectory(prefix='watchdawg-backup-check-') as directory:
    root = Path(directory)
    for name in ('compose.yaml', 'backup.sh'):
        shutil.copy2(source / name, root / name)
    bin_dir = root / 'bin'
    bin_dir.mkdir()
    stub = bin_dir / 'docker'
    stub.write_text('''#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == compose && "$2" == config ]]; then
    exec "$REAL_DOCKER" "$@"
fi
[[ "$1" == compose && "$2" == exec && "$3" == -T && "$4" == postgres && "$5" == pg_dump ]]
printf '%s\\n' "$@" > "$CHECK_ARGS"
printf 'stub dump data'
exit "${CHECK_DUMP_EXIT:-0}"
''')
    stub.chmod(0o755)
    env = {key: value for key, value in os.environ.items()
           if not key.startswith('COMPOSE_') and key != 'WATCHDAWG_DATABASE'}
    env.update(PATH=str(bin_dir) + os.pathsep + env['PATH'], REAL_DOCKER=docker,
               CHECK_ARGS=str(root / 'args'), DATABASE_PASSWORD='test-only',
               NTFY_URL='http://127.0.0.1:8080', NTFY_TOPIC='test-only',
               TAILNET_IP='100.64.0.1')
    for dotenv, override, expected in (
        ('', None, 'watchdawg'),
        ('WATCHDAWG_DATABASE=\n', None, 'watchdawg'),
        ('WATCHDAWG_DATABASE=watchdawg_recovered\n', None, 'watchdawg_recovered'),
        ('WATCHDAWG_DATABASE=watchdawg_recovered\n', 'shell_database', 'shell_database'),
    ):
        (root / '.env').write_text(dotenv)
        case_env = env.copy()
        if override is not None:
            case_env['WATCHDAWG_DATABASE'] = override
        config = json.loads(subprocess.check_output(
            [docker, 'compose', 'config', '--format', 'json'], cwd=root, env=case_env))
        services = config['services']
        assert services['postgres']['environment']['POSTGRES_DB'] == expected
        assert services['market']['environment']['SPRING_DATASOURCE_URL'] == (
            'jdbc:postgresql://postgres:5432/' + expected)
        assert 'POSTGRES_DB' in services['postgres']['healthcheck']['test'][1]
        subprocess.run(['bash', 'backup.sh'], cwd=root, env=case_env,
                       check=True, capture_output=True)
        args = (root / 'args').read_text().splitlines()
        assert args[args.index('-d') + 1] == expected
    archives = set((root / 'backups').iterdir())
    assert len(archives) == 4
    assert all(path.suffix == '.dump' and path.stat().st_mode & 0o777 == 0o600
               for path in archives)
    failure_env = dict(env, CHECK_DUMP_EXIT='1')
    failed = subprocess.run(['bash', 'backup.sh'], cwd=root, env=failure_env,
                            capture_output=True)
    assert failed.returncode != 0
    assert set((root / 'backups').iterdir()) == archives
print('PASS: database defaults, .env, shell override, unique private backups, failure cleanup')
