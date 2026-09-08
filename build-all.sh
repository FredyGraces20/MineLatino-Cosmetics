#!/usr/bin/env bash
# Build MineLatino Cosmetics for every Minecraft version in versions.properties.
# Usage: ./build-all.sh [mcVersion ...]
#   With no arguments, builds every version listed in versions.properties.

set -euo pipefail
cd "$(dirname "$0")"

PROPS="versions.properties"
DIST="dist"

# Collect MC versions: either from arguments or by scanning versions.properties
if [ $# -gt 0 ]; then
    VERSIONS=("$@")
else
    VERSIONS=()
    while IFS= read -r line; do
        # Match lines like "1.21.4.fabric.loader=..." and extract the MC version
        if [[ "$line" =~ ^([0-9]+\.[0-9]+\.[0-9]+)\.fabric\.loader= ]]; then
            VERSIONS+=("${BASH_REMATCH[1]}")
        fi
    done < "$PROPS"
fi

if [ ${#VERSIONS[@]} -eq 0 ]; then
    echo "No Minecraft versions found in $PROPS"
    exit 1
fi

rm -rf "$DIST"
mkdir -p "$DIST"

echo "=== Building MineLatino Cosmetics for ${#VERSIONS[@]} version(s): ${VERSIONS[*]} ==="

for v in "${VERSIONS[@]}"; do
    echo ""
    echo "--- Fabric $v ---"
    ./gradlew -PmcVersion="$v" :fabric:build

    # Only build Forge if versions.properties has an entry for this MC version
    if grep -q "^${v}\.forge\.version=" "$PROPS"; then
        echo "--- Forge $v ---"
        ./gradlew -PmcVersion="$v" :forge:build
    else
        echo "--- Forge $v: no entry in $PROPS, skipping ---"
    fi

    # Copy JARs to dist/
    for jar in fabric/build/libs/*"${v}"*.jar forge/build/libs/*"${v}"*.jar; do
        [ -f "$jar" ] && cp "$jar" "$DIST/"
    done
done

echo ""
echo "=== Build complete. JARs in $DIST/: ==="
ls -lh "$DIST/"
