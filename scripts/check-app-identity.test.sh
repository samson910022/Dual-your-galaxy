#!/usr/bin/env bash
# Self-test for scripts/check-app-identity.sh (no Gradle, no device).
# Each arm writes a throwaway fake repo and pins BOTH the expected exit code
# and the ::error:: reason, so a drifted checker cannot pass by accident.
# Mirrors scripts/verify-release-artifacts.test.sh: set -u (not -e) because
# both pass and non-zero exits are asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="scripts/check-app-identity.sh"

if [ ! -f "$CHECKER" ]; then
  echo "::error::checker missing: $CHECKER" >&2
  exit 1
fi

SUITE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/check-app-identity-test.XXXXXX")" || {
  echo "::error::mktemp -d failed" >&2
  exit 1
}
trap 'rm -rf "$SUITE_TMP"' EXIT

TARGET_ID="io.github.samson910022.galaxydualplus"
TARGET_VERSION="0.1.0-beta.2"
TARGET_CODE="3"
OLD_ID="com.galaxydual.plus"
JAVA_INIT="com.galaxydual.hook.DualHook"

write_repo() {
  repo="$1"
  mkdir -p "$repo/app" "$repo/hook/src/main/resources/META-INF/xposed"
  cat > "$repo/app/build.gradle.kts" <<EOF
// synthetic repo for the identity self-test
android {
    namespace = "$OLD_ID"
    defaultConfig {
        applicationId = "$TARGET_ID"
        versionCode = $TARGET_CODE
        versionName = "$TARGET_VERSION"
    }
}
EOF
  cat > "$repo/hook/src/main/resources/META-INF/xposed/module.prop" <<EOF
id=$TARGET_ID
version=$TARGET_VERSION
versionCode=$TARGET_CODE
EOF
  printf 'system\ncom.samsung.android.da.daagent\n' > "$repo/hook/src/main/resources/META-INF/xposed/scope.list"
  printf '%s\n' "$JAVA_INIT" > "$repo/hook/src/main/resources/META-INF/xposed/java_init.list"
}

replace_in() {
  file="$1"; from="$2"; to="$3"
  sed "s|$from|$to|g" "$file" > "$file.tmp" || return 1
  mv "$file.tmp" "$file"
}

total=0
matched=0
mismatched=0

run_arm() {
  name="$1"; repo="$2"; expected_rc="$3"; reason="$4"
  output="$(sh "$CHECKER" "$repo" 2>&1)"
  rc=$?
  good=true
  [ "$rc" -eq "$expected_rc" ] || good=false
  printf '%s\n' "$output" | grep -qF -- "$reason" || good=false
  total=$((total + 1))
  if [ "$good" = true ]; then
    matched=$((matched + 1))
    printf 'PASS arm %s (expected exit %d, got %d; reason pinned)\n' "$name" "$expected_rc" "$rc"
  else
    mismatched=$((mismatched + 1))
    printf '::error::FAIL arm %s (expected exit %d + reason %q, got exit %d)\n' \
      "$name" "$expected_rc" "$reason" "$rc"
    printf '%s\n' "$output" | sed 's/^/     /'
  fi
}

# 1. PASS: the full new identity is accepted.
r="$SUITE_TMP/pass-new-identity"
write_repo "$r"
run_arm "pass-new-identity" "$r" 0 "PASS: app identity is"

# 2. FAIL: old id in both build.gradle.kts and module.prop.
r="$SUITE_TMP/fail-old-id"
write_repo "$r"
replace_in "$r/app/build.gradle.kts" "$TARGET_ID" "$OLD_ID"
replace_in "$r/hook/src/main/resources/META-INF/xposed/module.prop" "$TARGET_ID" "$OLD_ID"
run_arm "fail-old-id" "$r" 1 "::error::applicationId must be"

# 3. FAIL: version mismatch (module.prop version drifted, gradle still beta.2).
r="$SUITE_TMP/fail-version-mismatch"
write_repo "$r"
replace_in "$r/hook/src/main/resources/META-INF/xposed/module.prop" "$TARGET_VERSION" "0.1.0-beta.1"
run_arm "fail-version-mismatch" "$r" 1 "::error::module.prop version must equal"

# 4. FAIL: scope.list drift (extra scope line).
r="$SUITE_TMP/fail-scope-drift"
write_repo "$r"
printf 'com.example.extra\n' >> "$r/hook/src/main/resources/META-INF/xposed/scope.list"
run_arm "fail-scope-drift" "$r" 1 "::error::scope.list must be exactly"

# 5. FAIL: java_init.list entry drift.
r="$SUITE_TMP/fail-java-init-drift"
write_repo "$r"
replace_in "$r/hook/src/main/resources/META-INF/xposed/java_init.list" "DualHook" "OtherHook"
run_arm "fail-java-init-drift" "$r" 1 "::error::java_init.list must be exactly"

# 6. FAIL: a legacy id= line appended after the correct one (active id still
#    new) must still be rejected by the explicit no-old-id-line assertion.
r="$SUITE_TMP/fail-legacy-id-line"
write_repo "$r"
printf 'id=%s\n' "$OLD_ID" >> "$r/hook/src/main/resources/META-INF/xposed/module.prop"
run_arm "fail-legacy-id-line" "$r" 1 "::error::old id line must not appear"

echo "----"
printf 'summary: %d arms, %d as expected, %d mismatched\n' "$total" "$matched" "$mismatched"
if [ "$mismatched" -ne 0 ]; then
  exit 1
fi
exit 0
