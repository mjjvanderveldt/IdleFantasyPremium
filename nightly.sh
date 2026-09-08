#!/usr/bin/env bash
# nightly.sh — publish a nightly build as a GitHub pre-release
#
# Usage: ./nightly.sh [nightly-number]
#   With no argument, uses one more than the highest nightly already
#   released on GitHub for the current stable base (1 if none).
#
# What it does:
#   1. Reads the stable versionName + versionCode from app/build.gradle.kts
#      (stable codes are multiples of 1000; nightly N publishes as
#      <versionName>.N with versionCode <versionCode>+N)
#   2. Runs unit tests and builds a release-signed APK from the current
#      working tree via -PnightlyBuild=N (no commit, no version bump)
#   3. Publishes the APK as GitHub pre-release nightly-<versionName>.N,
#      with the pending next-release changelog as the notes
#   4. Prunes nightly pre-releases beyond the newest KEEP_NIGHTLIES
#
# Testers subscribe with Obtainium: add this GitHub repo as a source and
# enable "Include prereleases" for the app. Stable users are untouched:
# pre-releases never show as Latest, and the official F-Droid checkupdates
# (UpdateCheckMode: Tags) reads the committed gradle file at each tag,
# which always carries the stable version, so nightly tags are inert.
#
# Nothing on main is committed; the stable pipeline (release.sh) is untouched.

set -euo pipefail

export PATH="$HOME/.local/bin:$PATH"

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_FILE="$REPO_DIR/app/build.gradle.kts"
CHANGELOG_DIR="$REPO_DIR/fastlane/metadata/android/en-US/changelogs"
KEEP_NIGHTLIES=7

# ---------------------------------------------------------------------------
# Read stable version + pre-flight checks
# ---------------------------------------------------------------------------

VERSION_NAME=$(grep '^\s*versionName\s*=' "$GRADLE_FILE" | head -1 | sed 's/.*"\(.*\)".*/\1/')
VERSION_CODE=$(grep '^\s*versionCode\s*=' "$GRADLE_FILE" | head -1 | grep -oP '\d+')

if (( VERSION_CODE % 1000 != 0 )); then
    echo "ERROR: stable versionCode ($VERSION_CODE) is not a multiple of 1000."
    exit 1
fi

if [[ -z "${DEFIDE_STORE_PASSWORD:-}" || -z "${DEFIDE_KEY_PASSWORD:-}" ]]; then
    echo "ERROR: DEFIDE_STORE_PASSWORD and DEFIDE_KEY_PASSWORD must be set in the environment"
    exit 1
fi

if ! command -v gh &>/dev/null; then
    echo "ERROR: gh CLI not found on PATH"
    exit 1
fi

# ---------------------------------------------------------------------------
# Determine the nightly number (from existing GitHub nightly releases)
# ---------------------------------------------------------------------------

if [[ -n "${1:-}" ]]; then
    NIGHTLY_NUM="$1"
else
    LAST=$(gh release list --limit 100 --json tagName --jq '.[].tagName' \
        | grep -oP "^nightly-\Q$VERSION_NAME\E\.\K\d+" | sort -n | tail -1 || true)
    NIGHTLY_NUM=$(( ${LAST:-0} + 1 ))
fi

if (( NIGHTLY_NUM < 1 || NIGHTLY_NUM > 999 )); then
    echo "ERROR: nightly number must be 1..999, got $NIGHTLY_NUM"
    exit 1
fi

NIGHTLY_CODE=$((VERSION_CODE + NIGHTLY_NUM))
NIGHTLY_NAME="$VERSION_NAME.$NIGHTLY_NUM"
TAG="nightly-$NIGHTLY_NAME"

if gh release view "$TAG" &>/dev/null; then
    echo "ERROR: release $TAG already exists on GitHub. Pass an explicit number to override."
    exit 1
fi

echo "==> Nightly: $NIGHTLY_NAME (versionCode $NIGHTLY_CODE, tag $TAG)"

# ---------------------------------------------------------------------------
# Test + build from the working tree
# ---------------------------------------------------------------------------

cd "$REPO_DIR"
./gradlew testDebugUnitTest assembleRelease -PnightlyBuild="$NIGHTLY_NUM"

APK=$(find "$REPO_DIR/app/build/outputs/apk/release/" -name "*.apk" | head -1)
echo "==> Built $APK"

# ---------------------------------------------------------------------------
# Compose release notes: build provenance + the pending next-release changelog
# ---------------------------------------------------------------------------

COMMIT=$(git -C "$REPO_DIR" rev-parse --short HEAD)
DIRTY=""
if [[ -n "$(git -C "$REPO_DIR" status --porcelain)" ]]; then
    DIRTY=" plus uncommitted changes"
fi

# The pending changelog is the highest-numbered file above the stable code.
PENDING_CHANGELOG=$(ls "$CHANGELOG_DIR"/*.txt 2>/dev/null \
    | grep -oP '\d+(?=\.txt$)' | awk -v c="$VERSION_CODE" '$1 > c' | sort -n | tail -1 || true)

NOTES_FILE=$(mktemp)
{
    echo "Untested nightly build for playtesters, built from the working tree on top of stable v$VERSION_NAME."
    echo
    echo "Built: $(date '+%Y-%m-%d %H:%M %Z') at commit $COMMIT$DIRTY"
    echo
    if [[ -n "$PENDING_CHANGELOG" && -f "$CHANGELOG_DIR/$PENDING_CHANGELOG.txt" ]]; then
        echo "Changes staged for the next release:"
        echo
        cat "$CHANGELOG_DIR/$PENDING_CHANGELOG.txt"
        echo
    fi
    echo "Install with Obtainium: add this repository as a source and enable Include prereleases."
} > "$NOTES_FILE"

# ---------------------------------------------------------------------------
# Publish the pre-release and prune old nightlies
# ---------------------------------------------------------------------------

gh release create "$TAG" "$APK" \
    --prerelease \
    --title "Nightly v$NIGHTLY_NAME" \
    --notes-file "$NOTES_FILE"
rm -f "$NOTES_FILE"

RELEASE_URL=$(gh release view "$TAG" --json url --jq '.url')

PRUNE=$(gh release list --limit 100 --json tagName,createdAt \
    --jq '[.[] | select(.tagName | startswith("nightly-"))] | sort_by(.createdAt) | reverse | .['"$KEEP_NIGHTLIES"':] | .[].tagName' || true)
for tag in $PRUNE; do
    gh release delete "$tag" --cleanup-tag --yes
    echo "==> Pruned old nightly $tag"
done

echo
echo "======================================================"
echo "  Nightly $NIGHTLY_NAME published (versionCode $NIGHTLY_CODE)"
echo "  APK:     $APK"
echo "  Release: $RELEASE_URL"
echo "======================================================"
