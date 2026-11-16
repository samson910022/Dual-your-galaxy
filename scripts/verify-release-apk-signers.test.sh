#!/usr/bin/env bash
# Self-test for scripts/verify-release-apk-signers.sh:
#   - Fixture arms (SDK-free): pin the staging-set validation that runs BEFORE
#     any apksigner/Android-SDK invocation (unexpected-file allowlist,
#     regular-file / no-symlink apk checks, empty-release refusal).
#   - SDK positive-path arms (runtime-built, gated): build a throwaway-signed
#     APK with the local Android SDK and assert the gate ACCEPTS it with the
#     matching pin and REJECTS it with a different pin/key. These arms never
#     touch the fixture corpus (EXPECTED_FIXTURE_COUNT stays 4) and never use
#     the real release key. If no Android SDK with build-tools is found the
#     arms SKIP (exit 0) unless PR60_SIGNER_TEST_REQUIRE_SDK=1, in which case
#     the suite fails closed.
#
# Fixtures live in scripts/fixtures/verify-release-apk-signers/ and are
# named by expected outcome: fail-* must exit 1 with the pinned ::error::
# substring (expected_reason(); a fail fixture whose output does not contain
# the mapped substring is a MISMATCH, not a pass — no false-confidence
# fixtures). Each fixture is copied to a fresh mktemp dir per case
# (side-effect-free; the corpus is never mutated), removed via trap on exit.
#
# Intentionally `set -u` (not -e): both exit codes are asserted.
set -u

cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

FIXTURE_DIR="scripts/fixtures/verify-release-apk-signers"
SCRIPT="scripts/verify-release-apk-signers.sh"
if [ ! -f "$SCRIPT" ]; then
  echo "::error::script missing: $SCRIPT" >&2
  exit 1
fi

# Fixture-count pin: the suite's summary is only meaningful when the fixture
# corpus is exactly the reviewed set. A silently added or deleted fixture
# changes what "PASS summary" means, so fail closed on drift. Single source
# of truth for the expected count; bump only together with fixture review.
EXPECTED_FIXTURE_COUNT=4
shopt -s nullglob
pinned_fixtures=("$FIXTURE_DIR"/fail-*/)
if [ "${#pinned_fixtures[@]}" -ne "$EXPECTED_FIXTURE_COUNT" ]; then
  echo "::error::fixture count drift: expected $EXPECTED_FIXTURE_COUNT fixture directories (fail-*) in $FIXTURE_DIR, found ${#pinned_fixtures[@]} — update EXPECTED_FIXTURE_COUNT only with fixture review" >&2
  exit 1
fi

# Note: count drift catches add/delete only — NOT substitution; substitution
# is routed to review via CODEOWNERS (/scripts/fixtures/).

# Required output substring per fixture. No substring mapping => mismatch.
expected_reason() {
  case "$1" in
    fail-unexpected-file)
      printf 'unexpected file(s) staged for release' ;;
    fail-symlink)
      printf 'not a regular file (or a symlink)' ;;
    fail-empty)
      printf 'No release APKs found' ;;
    fail-dotfile-apk)
      printf 'unexpected file(s) staged for release' ;;
    *)
      printf '' ;;
  esac
}

pass_count=0
mismatch=0
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

for fixture_path in "${pinned_fixtures[@]}"; do
  fixture="$(basename "$fixture_path")"
  reason="$(expected_reason "$fixture")"
  if [ -z "$reason" ]; then
    echo "MISMATCH fixture $fixture (no reason pin)"
    mismatch=$((mismatch + 1))
    continue
  fi
  stage="$work_dir/$fixture"
  mkdir -p "$stage"
  (cd "$fixture_path" && cp -a . "$stage"/)
  output="$(bash "$SCRIPT" "$stage" 2>&1)"
  rc=$?
  if [ "$rc" -ne 1 ]; then
    echo "MISMATCH fixture $fixture (expected exit 1, got $rc)"
    echo "$output"
    mismatch=$((mismatch + 1))
    continue
  fi
  if ! printf '%s' "$output" | grep -qF "$reason"; then
    echo "MISMATCH fixture $fixture (expected substring not found: $reason) — output was:"
    echo "$output"
    mismatch=$((mismatch + 1))
    continue
  fi
  echo "PASS fixture $fixture (expected exit 1, got 1, reason pinned)"
  pass_count=$((pass_count + 1))
done

# --- SDK-gated positive-path arm (runtime-built APKs; does NOT touch fixtures) ---
#
# Builds a minimal APK with the local SDK (aapt2 compile/link against the
# newest platform android.jar that exists), signs it with a throwaway RSA-2048 key A,
# and asserts:
#   arm A (accept): the gate exits 0 with EXPECTED_CERT_SHA256 = A's fingerprint;
#   arm B (reject): the gate exits 1 with A's pin against an identical APK signed
#                   with a DIFFERENT throwaway key B ("Certificate mismatch").
# Fingerprints are extracted with the shared parser (--parse-only). No release
# key material is involved. All temp state stays under $work_dir (trap-cleaned).
sdk_arms_total=0
sdk_arms_pass=0

sdk_skip_or_fail() {
  local reason="$1"
  if [ "${PR60_SIGNER_TEST_REQUIRE_SDK:-0}" = "1" ]; then
    echo "::error::PR60_SIGNER_TEST_REQUIRE_SDK=1 but $reason" >&2
    exit 1
  fi
  echo "SKIP: SDK positive-path arm ($reason)"
}

SDK=""
if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/build-tools" ]; then
  SDK="$ANDROID_HOME"
else
  for probe in /usr/lib/android-sdk "$HOME/Android/Sdk" /usr/local/lib/android/sdk; do
    if [ -d "$probe/build-tools" ]; then
      SDK="$probe"
      break
    fi
  done
fi

if [ -z "$SDK" ]; then
  sdk_skip_or_fail "no Android SDK found"
else
  build_tools="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1 || true)"
  apksigner=""
  aapt2=""
  if [ -n "$build_tools" ]; then
    apksigner="$build_tools/apksigner"
    aapt2="$build_tools/aapt2"
  fi
  # Probe platforms newest->oldest and use the FIRST platform directory
  # that contains a regular non-empty android.jar (a platform dir can ship
  # the profile without a usable jar).
  platform_dir=""
  android_jar=""
  while IFS= read -r probe_platform; do
    if [ -f "${probe_platform}android.jar" ] && [ -s "${probe_platform}android.jar" ]; then
      platform_dir="$probe_platform"
      android_jar="${probe_platform}android.jar"
      break
    fi
  done < <(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -Vr)
  keytool_bin="$(command -v keytool || true)"
  if [ -z "$keytool_bin" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/keytool" ]; then
    keytool_bin="${JAVA_HOME}/bin/keytool"
  fi

  sdk_missing=""
  if [ -z "$build_tools" ] || [ ! -x "$aapt2" ]; then sdk_missing="$sdk_missing aapt2"; fi
  if [ -z "$build_tools" ] || [ ! -x "$apksigner" ]; then sdk_missing="$sdk_missing apksigner"; fi
  if [ -z "$android_jar" ]; then sdk_missing="$sdk_missing android.jar"; fi
  if [ -z "$keytool_bin" ]; then sdk_missing="$sdk_missing keytool"; fi

  if [ -n "$sdk_missing" ]; then
    sdk_skip_or_fail "Android SDK at $SDK is missing:${sdk_missing}"
  else
    sdk_src="$work_dir/sdk"
    mkdir -p "$sdk_src/src"
    cat > "$sdk_src/src/AndroidManifest.xml" <<'MANIFEST'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="dev.pr60.signertest"
    android:versionCode="1"
    android:versionName="1.0">
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36" />
</manifest>
MANIFEST

    # Count arm A up front (BEFORE the build/sign step): the shared
    # build/sign below is the prerequisite for arm A, so a build failure
    # counts as an attempted arm — the summary must never claim
    # "0 sdk arms" after a failed attempt. Arm B is counted the same way,
    # before arm B's keytool/apksigner steps.
    sdk_arms_total=$((sdk_arms_total + 1))
    sdk_build_failed=""
    ( cd "$sdk_src/src" && "$aapt2" compile --dir . -o c.zip ) >"$sdk_src/aapt2-compile.log" 2>&1 || sdk_build_failed="aapt2 compile (see $sdk_src/aapt2-compile.log)"
    if [ -z "$sdk_build_failed" ]; then
      "$aapt2" link -I "$android_jar" --manifest "$sdk_src/src/AndroidManifest.xml" -o "$sdk_src/signer-test.apk" "$sdk_src/src/c.zip" >"$sdk_src/aapt2-link.log" 2>&1 || sdk_build_failed="aapt2 link (see $sdk_src/aapt2-link.log)"
    fi

    # Throwaway RSA-2048 key A (distinct store AND key passwords — JKS,
    # because PKCS12 keystores do not support distinct key passwords).
    if [ -z "$sdk_build_failed" ]; then
      "$keytool_bin" -genkeypair -storetype JKS -alias pr60signertest -keyalg RSA -keysize 2048 -validity 3650 \
        -dname "CN=PR60 Signer Test, O=PR60, C=TW" \
        -keystore "$sdk_src/key-a.jks" -storepass pr60-throwaway-store-a1 -keypass pr60-throwaway-key-a1 \
        >"$sdk_src/keytool-a.log" 2>&1 || sdk_build_failed="keytool key A (see $sdk_src/keytool-a.log)"
    fi
    if [ -z "$sdk_build_failed" ]; then
      "$apksigner" sign --ks "$sdk_src/key-a.jks" --ks-key-alias pr60signertest \
        --ks-pass pass:pr60-throwaway-store-a1 --key-pass pass:pr60-throwaway-key-a1 \
        --out "$sdk_src/signer-test-a.apk" "$sdk_src/signer-test.apk" \
        >"$sdk_src/sign-a.log" 2>&1 || sdk_build_failed="apksigner sign A (see $sdk_src/sign-a.log)"
    fi

    FP_A=""
    if [ -z "$sdk_build_failed" ]; then
      verify_out_a="$("$apksigner" verify --verbose --print-certs "$sdk_src/signer-test-a.apk" 2>&1)"
      FP_A="$(printf '%s\n' "$verify_out_a" | bash scripts/parse-apksigner-output.sh --parse-only 2>/dev/null | sed -n 's/^cert=//p' || true)"
      if ! [[ "$FP_A" =~ ^[0-9a-f]{64}$ ]]; then
        sdk_build_failed="could not extract key A fingerprint via parse-apksigner-output.sh --parse-only"
      fi
    fi

    if [ -n "$sdk_build_failed" ]; then
      echo "MISMATCH sdk-arm (APK build/sign failed: $sdk_build_failed)"
      mismatch=$((mismatch + 1))
    else
      # Arm A: stage ONLY the signed APK; gate must exit 0 with A's pin.
      stage_a="$sdk_src/stage-a"
      mkdir -p "$stage_a"
      cp "$sdk_src/signer-test-a.apk" "$stage_a/signer-test.apk"
      gate_out="$(ANDROID_HOME="$SDK" EXPECTED_CERT_SHA256="$FP_A" bash scripts/verify-release-apk-signers.sh "$stage_a" 2>&1)"
      gate_rc=$?
      if [ "$gate_rc" -eq 0 ] && printf '%s' "$gate_out" | grep -qF "PASS: all release APKs signed by the pinned certificate"; then
        echo "PASS sdk-arm accept (exit 0, pinned-cert gate passed a real apksigner-signed APK)"
        sdk_arms_pass=$((sdk_arms_pass + 1))
      else
        echo "MISMATCH sdk-arm accept (expected exit 0 + PASS line, got exit $gate_rc) — gate output was:"
        printf '%s\n' "$gate_out"
        mismatch=$((mismatch + 1))
      fi

      # Arm B: identical build signed with a DIFFERENT throwaway key B;
      # gate with A's pin must exit 1 with a certificate mismatch.
      # Count arm B up front (BEFORE arm B's keytool/apksigner steps), exactly
      # like arm A's pre-build increment: a build failure counts as an
      # attempted arm.
      sdk_arms_total=$((sdk_arms_total + 1))
      sdk_build_b_failed=""
      "$keytool_bin" -genkeypair -storetype JKS -alias pr60signertest -keyalg RSA -keysize 2048 -validity 3650 \
        -dname "CN=PR60 Signer Test B, O=PR60, C=TW" \
        -keystore "$sdk_src/key-b.jks" -storepass pr60-throwaway-store-b2 -keypass pr60-throwaway-key-b2 \
        >"$sdk_src/keytool-b.log" 2>&1 || sdk_build_b_failed="keytool key B (see $sdk_src/keytool-b.log)"
      if [ -z "$sdk_build_b_failed" ]; then
        "$apksigner" sign --ks "$sdk_src/key-b.jks" --ks-key-alias pr60signertest \
          --ks-pass pass:pr60-throwaway-store-b2 --key-pass pass:pr60-throwaway-key-b2 \
          --out "$sdk_src/signer-test-b.apk" "$sdk_src/signer-test.apk" \
          >"$sdk_src/sign-b.log" 2>&1 || sdk_build_b_failed="apksigner sign B (see $sdk_src/sign-b.log)"
      fi
      if [ -n "$sdk_build_b_failed" ]; then
        echo "MISMATCH sdk-arm reject (APK build/sign failed: $sdk_build_b_failed)"
        mismatch=$((mismatch + 1))
      else
        stage_b="$sdk_src/stage-b"
        mkdir -p "$stage_b"
        cp "$sdk_src/signer-test-b.apk" "$stage_b/signer-test.apk"
        gate_out="$(ANDROID_HOME="$SDK" EXPECTED_CERT_SHA256="$FP_A" bash scripts/verify-release-apk-signers.sh "$stage_b" 2>&1)"
        gate_rc=$?
        if [ "$gate_rc" -eq 1 ] && printf '%s' "$gate_out" | grep -qF "Certificate mismatch"; then
          echo "PASS sdk-arm reject (exit 1, wrong key rejected)"
          sdk_arms_pass=$((sdk_arms_pass + 1))
        else
          echo "MISMATCH sdk-arm reject (expected exit 1 + 'Certificate mismatch', got exit $gate_rc) — gate output was:"
          printf '%s\n' "$gate_out"
          mismatch=$((mismatch + 1))
        fi
      fi
    fi
  fi
fi

total=$((pass_count + mismatch))
if [ "$sdk_arms_total" -eq 0 ]; then
  sdk_note="0 sdk arms (skipped)"
else
  sdk_note="$sdk_arms_total sdk arms, $sdk_arms_pass passed"
fi
if [ "$mismatch" -ne 0 ]; then
  echo "----"
  echo "summary: $total fixtures + $sdk_note, $pass_count fixtures as expected, $mismatch mismatched" >&2
  exit 1
fi
echo "----"
echo "summary: $total fixtures + $sdk_note, $pass_count fixtures as expected, 0 mismatched"
