#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Check for em dashes (Unicode U+2014)
echo "[1/3] Scanning for prohibited em dashes..."
if grep -rn --exclude-dir=".git" --exclude-dir=".gradle" --exclude-dir="build" --exclude="*.jar" $'\xe2\x80\x94' . ; then
    echo "ERROR: Em dash (U+2014) detected above! Please remove it."
    exit 1
fi

# Check for forbidden career / buzzword mentions using word boundaries
echo "[2/3] Scanning for forbidden metadata keywords..."
if grep -rni --exclude-dir=".git" --exclude-dir=".gradle" --exclude-dir="build" --exclude="*.jar" --exclude="verify_clean.sh" -E "\b(faang|recruiter|recruiting|quant)\b" . ; then
    echo "ERROR: Prohibited buzzword detected above! Please remove it."
    exit 1
fi

echo "[3/3] Repository clean. Verification passed."
