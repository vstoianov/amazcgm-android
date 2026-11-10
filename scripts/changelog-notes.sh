#!/usr/bin/env bash
# Prints the CHANGELOG.md section for one version, without its heading.
# Usage: scripts/changelog-notes.sh 1.10
set -euo pipefail

version="${1:?usage: $0 <version>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
changelog="$root/CHANGELOG.md"

[[ -f "$changelog" ]] || { echo "error: $changelog not found" >&2; exit 1; }

notes="$(awk -v v="$version" '
    /^## \[/ {
        if (found) exit
        if (index($0, "## [" v "]") == 1) { found = 1; next }
    }
    found { print }
' "$changelog" | sed -e '/./,$!d')"

# Drop trailing blank lines and link references.
notes="$(printf '%s\n' "$notes" | sed -e '/^\[[^]]*\]: /d' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')"

if [[ -z "${notes//[[:space:]]/}" ]]; then
    echo "error: no CHANGELOG.md section for version $version" >&2
    exit 1
fi
printf '%s\n' "$notes"
