#!/usr/bin/env bash
# Explicitly requested Star action; uses the user's existing GitHub CLI login.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "${1:-}" in
  '') dry_run=false ;;
  --dry-run) dry_run=true ;;
  -h|--help) echo 'Usage: bash scripts/star-ai-repos.sh [--dry-run]'; exit 0 ;;
  *) echo 'Unknown argument. Use --dry-run to list repositories.' >&2; exit 2 ;;
esac

selected_repos="$(python3 - "$repo_root/docs/AI_OPEN_SOURCE_TOP20.json" <<'PY'
import json, re, sys
repos = [item['repository'] for item in json.load(open(sys.argv[1]))['repositories']]
if len(repos) != 20 or len(set(repos)) != 20 or any(not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo) for repo in repos):
    raise SystemExit('Expected 20 unique owner/repository names')
print('\n'.join(repos))
PY
)"

if [[ "$dry_run" == true ]]; then
  printf '%s\n' "$selected_repos"
  exit 0
fi

command -v gh >/dev/null || { echo 'Install GitHub CLI and sign in on your computer first.' >&2; exit 1; }
active_login="$(gh api user --jq .login)"
if [[ "$active_login" != hrniux ]]; then
  echo "Expected GitHub account hrniux; active account is $active_login. No Stars changed." >&2
  exit 1
fi

confirmed=0
while IFS= read -r repository; do
  if ! gh api --silent --method PUT "user/starred/$repository"; then
    echo "Star failed for $repository. Confirm Starring write permission. Confirmed so far: $confirmed/20." >&2
    exit 1
  fi
  if ! gh api --silent "user/starred/$repository"; then
    echo "Could not verify Star for $repository. Confirm Starring read permission. Confirmed so far: $confirmed/20." >&2
    exit 1
  fi
  confirmed=$((confirmed + 1))
  printf '[%s/20] Star confirmed: %s\n' "$confirmed" "$repository"
done <<< "$selected_repos"
