#!/usr/bin/env bash
# AmazCGM phone app: builds a signed release APK and publishes it as a GitHub Release.
# Usage: scripts/release.sh <version> [--dry-run]
#   --dry-run  build and verify into dist/, then undo the version bump.
#              Nothing is committed, tagged, pushed or published.
# See RELEASING.md.
set -euo pipefail

usage() { echo "usage: $0 <version> [--dry-run]" >&2; exit 2; }
die() { echo "error: $*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

version=""
dry_run=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) dry_run=1 ;;
        -h|--help) usage ;;
        -*) usage ;;
        *) [[ -z "$version" ]] || usage; version="$arg" ;;
    esac
done
[[ -n "$version" ]] || usage
[[ "$version" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || die "version must look like 1.10 or 1.10.1, got '$version'"

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
tag="v$version"
gradle_file="app/build.gradle.kts"
apk_out="app/build/outputs/apk/release/app-release.apk"
dist="dist"
apk="$dist/amazcgm-$version.apk"

# ---------------------------------------------------------------- preflight
step "Checking repository"
branch="$(git rev-parse --abbrev-ref HEAD)"
[[ "$branch" == "main" ]] || die "releases are made from main, not '$branch'"
[[ -z "$(git status --porcelain)" ]] || die "working tree is not clean; commit or stash first"
git fetch --quiet --tags origin
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "main differs from origin/main; pull or push first"
! git rev-parse -q --verify "refs/tags/$tag" >/dev/null || die "tag $tag already exists"

step "Checking tools"
if (( ! dry_run )); then
    command -v gh >/dev/null || die "gh not found; see RELEASING.md"
    gh auth status >/dev/null 2>&1 || die "gh is not logged in; run 'gh auth login'"
fi
command -v java >/dev/null || die "java not found; install JDK 17"
java_major="$(java -version 2>&1 | awk -F'"' '/version/ { split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit }')"
(( java_major >= 17 )) || die "JDK 17 or newer needed, found $java_major"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f local.properties ]]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | head -n1)"
fi
[[ -n "$sdk" && -d "$sdk" ]] || die "Android SDK not found; set ANDROID_HOME or sdk.dir in local.properties"
build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n1)"
apksigner="$build_tools/apksigner"
[[ -x "$apksigner" ]] || die "apksigner not found under $sdk/build-tools"

if [[ ! -f keystore.properties && -z "${AMAZCGM_KEYSTORE:-}" ]]; then
    die "no release signing key; run scripts/gen-keystore.sh (see RELEASING.md)"
fi

step "Checking CHANGELOG.md"
notes="$(scripts/changelog-notes.sh "$version")" || die "add a '## [$version] - $(date +%F)' section to CHANGELOG.md"
printf '%s\n' "$notes"

# ------------------------------------------------------------ version bump
step "Bumping version"
old_name="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$gradle_file")"
old_code="$(sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' "$gradle_file")"
[[ -n "$old_name" && -n "$old_code" ]] || die "could not read versionName/versionCode from $gradle_file"
new_code=$((old_code + 1))

restore_version() { git checkout --quiet -- "$gradle_file"; }
bumped=1
on_exit() {
    if (( bumped )); then
        echo "release stopped; restoring $gradle_file" >&2
        restore_version
    fi
}
trap on_exit EXIT

sed -i -e "s/^\( *versionName = \)\".*\"/\1\"$version\"/" \
       -e "s/^\( *versionCode = \)[0-9]*/\1$new_code/" "$gradle_file"
echo "versionName $old_name -> $version"
echo "versionCode $old_code -> $new_code"

# ------------------------------------------------------------------- build
step "Testing and building"
./gradlew clean testDebugUnitTest assembleRelease
[[ -f "$apk_out" ]] || die "$apk_out was not built"

step "Verifying signature"
certs="$("$apksigner" verify --print-certs "$apk_out")"
printf '%s\n' "$certs" | grep -E 'certificate (DN|SHA-256)'
if printf '%s\n' "$certs" | grep -q 'CN=Android Debug'; then
    die "APK is signed with the debug key; check keystore.properties"
fi

step "Writing artifacts"
mkdir -p "$dist"
cp "$apk_out" "$apk"
(cd "$dist" && sha256sum "$(basename "$apk")" > "$(basename "$apk").sha256")
cat "$apk.sha256"

if (( dry_run )); then
    bumped=0
    restore_version
    step "Dry run done. $apk is ready to test; version bump undone."
    exit 0
fi

# ----------------------------------------------------------------- publish
step "Committing and tagging"
git commit --quiet -m "Release $version" -- "$gradle_file"
bumped=0
git tag -a "$tag" -m "AmazCGM $version"

step "Pushing"
git push origin main "$tag"

step "Creating GitHub release"
printf '%s\n' "$notes" | gh release create "$tag" "$apk" "$apk.sha256" \
    --title "AmazCGM $version" --notes-file - --verify-tag
gh release view "$tag" --json url --jq .url
