#!/usr/bin/env bash
# Checks navigation graph and layout XML files for broken class references.
# Usage: ./check_xml_references.sh

ROOT="$(cd "$(dirname "$0")" && pwd)"
ERRORS=0

# All source directories to search for classes
SRC_DIRS=(
    "wallet/src"
    "common/src/main/java"
    "features/exploredash/src/main/java"
    "integrations/uphold/src/main/java"
    "integrations/coinbase/src/main/java"
    "integrations/crowdnode/src/main/java"
    "integration-android/src/main/java"
    "sample-integration-android/src/main/java"
)

# Convert fully qualified class name to relative path (dots to slashes)
class_to_path() {
    echo "$1" | tr '.' '/'
}

# Check if a class exists in any source directory
find_class() {
    local rel_path
    rel_path="$(class_to_path "$1")"
    for src_dir in "${SRC_DIRS[@]}"; do
        if [[ -f "$ROOT/$src_dir/$rel_path.kt" || -f "$ROOT/$src_dir/$rel_path.java" ]]; then
            return 0
        fi
    done
    return 1
}

# Resolve relative class name (starting with .) to fully qualified name
resolve_context_class() {
    local class_name="$1"
    local layout_file="$2"
    local rel="${layout_file#$ROOT/}"

    if [[ "$class_name" != .* ]]; then
        echo "$class_name"
        return
    fi

    # Determine package from module path
    local pkg=""
    case "$rel" in
        wallet/res/*) pkg="de.schildbach.wallet" ;;
        common/src/main/res/*) pkg="org.dash.wallet.common" ;;
        features/exploredash/src/main/res/*) pkg="org.dash.wallet.features.exploredash" ;;
        integrations/uphold/src/main/res/*) pkg="org.dash.wallet.integrations.uphold" ;;
        integrations/coinbase/src/main/res/*) pkg="org.dash.wallet.integrations.coinbase" ;;
        integrations/crowdnode/src/main/res/*) pkg="org.dash.wallet.integrations.crowdnode" ;;
        integration-android/src/main/res/*) pkg="de.schildbach.wallet.integration.android" ;;
        sample-integration-android/src/main/res/*) pkg="de.schildbach.wallet.integration.sample" ;;
    esac

    if [[ -n "$pkg" ]]; then
        echo "${pkg}${class_name}"
    else
        echo "$class_name"
    fi
}

echo "=== Checking navigation graph class references ==="
echo ""

nav_count=0
nav_errors=0

while IFS= read -r nav_file; do
    [[ -z "$nav_file" ]] && continue
    [[ "$nav_file" == */build/* ]] && continue
    nav_count=$((nav_count + 1))
    rel_file="${nav_file#$ROOT/}"

    # Only extract android:name from <fragment>, <dialog>, <activity> elements (not <argument>, <action>, etc.)
    while IFS= read -r class_name; do
        [[ -z "$class_name" ]] && continue
        # Skip non-class values (arguments, simple names without dots)
        [[ "$class_name" != *.* ]] && continue
        if ! find_class "$class_name"; then
            echo "  BROKEN: $rel_file"
            echo "    class: $class_name"
            nav_errors=$((nav_errors + 1))
            ERRORS=$((ERRORS + 1))
        fi
    done < <(grep -E '<(fragment|dialog|activity) ' "$nav_file" 2>/dev/null | grep -oE 'android:name="[^"]+"' | sed 's/android:name="//;s/"//' || true)
done < <(find "$ROOT" -path "*/res/navigation/*.xml" -not -path "*/build/*" 2>/dev/null | sort)

if [[ $nav_errors -eq 0 ]]; then
    echo "  All navigation graphs OK ($nav_count files checked)"
else
    echo ""
    echo "  Found $nav_errors broken reference(s) in navigation graphs"
fi

echo ""
echo "=== Checking layout tools:context class references ==="
echo ""

layout_count=0
layout_errors=0

while IFS= read -r layout_file; do
    [[ -z "$layout_file" ]] && continue
    [[ "$layout_file" == */build/* ]] && continue
    layout_count=$((layout_count + 1))

    rel_file="${layout_file#$ROOT/}"

    while IFS= read -r raw_class; do
        [[ -z "$raw_class" ]] && continue
        class_name="$(resolve_context_class "$raw_class" "$layout_file")"
        if ! find_class "$class_name"; then
            echo "  BROKEN: $rel_file"
            echo "    tools:context=\"$raw_class\" -> $class_name"
            layout_errors=$((layout_errors + 1))
            ERRORS=$((ERRORS + 1))
        fi
    done < <(grep -oE 'tools:context="[^"]+"' "$layout_file" 2>/dev/null | sed 's/tools:context="//;s/"//' || true)
done < <(find "$ROOT" -path "*/res/layout*/*.xml" -not -path "*/build/*" 2>/dev/null | sort)

if [[ $layout_errors -eq 0 ]]; then
    echo "  All layout tools:context references OK ($layout_count files checked)"
else
    echo ""
    echo "  Found $layout_errors broken reference(s) in layout files"
fi

echo ""
echo "=== Summary ==="
echo "  Navigation graphs checked: $nav_count"
echo "  Layout files checked:      $layout_count"
if [[ $ERRORS -eq 0 ]]; then
    echo "  Result: ALL OK"
else
    echo "  Result: $ERRORS broken reference(s) found"
    exit 1
fi