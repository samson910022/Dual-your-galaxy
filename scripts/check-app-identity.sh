#!/bin/sh
# Fail-closed identity gate for the v0.1.0-beta.3 package-identity migration.
# Verifies that app/build.gradle.kts (applicationId / namespace / versionCode /
# versionName) and the LSPosed module metadata (module.prop id / version /
# versionCode, scope.list, java_init.list) all agree on the NEW identity.
#
# Usage: check-app-identity.sh [repo-root]   (default: current directory)
# Emits GitHub ::error:: annotations on every failure and exits non-zero.
# POSIX sh: no bashisms, no Gradle, no device, no writes.
set -u

fail() {
  echo "::error::$*" >&2
  exit 1
}

if [ "$#" -gt 1 ]; then
  echo "::error::usage: $0 [repo-root]" >&2
  exit 2
fi

REPO_ROOT="${1:-$PWD}"
GRADLE="$REPO_ROOT/app/build.gradle.kts"
MODULE_PROP="$REPO_ROOT/hook/src/main/resources/META-INF/xposed/module.prop"
SCOPE_LIST="$REPO_ROOT/hook/src/main/resources/META-INF/xposed/scope.list"
JAVA_INIT="$REPO_ROOT/hook/src/main/resources/META-INF/xposed/java_init.list"

TARGET_ID="io.github.samson910022.galaxydualplus"
# applicationId-only migration: namespace stays the Kotlin source package so
# relative manifest component names still resolve to the real classes.
SOURCE_PACKAGE="com.galaxydual.plus"
TARGET_VERSION="0.1.0-beta.3"
TARGET_CODE="4"
OLD_ID="com.galaxydual.plus"
EXPECTED_SCOPE='system
com.samsung.android.da.daagent'
EXPECTED_INIT="com.galaxydual.hook.DualHook"

[ -f "$GRADLE" ] || fail "missing app/build.gradle.kts under '$REPO_ROOT' (wrong repo root?)"
[ -f "$MODULE_PROP" ] || fail "missing module.prop: $MODULE_PROP"
[ -f "$SCOPE_LIST" ] || fail "missing scope.list: $SCOPE_LIST"
[ -f "$JAVA_INIT" ] || fail "missing java_init.list: $JAVA_INIT"

# Parsing is anchored on a whole assignment/property line so that
# applicationIdSuffix and versionCode inside comments cannot satisfy a field.
APP_ID="$(sed -n 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE" | head -n 1 | tr -d '\015')"
APP_NS="$(sed -n 's/^[[:space:]]*namespace[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE" | head -n 1 | tr -d '\015')"
APP_VER="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE" | head -n 1 | tr -d '\015')"
APP_CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$GRADLE" | head -n 1 | tr -d '\015')"

MP_ID="$(sed -n 's/^[[:space:]]*id[[:space:]]*=[[:space:]]*\([^#]*\).*/\1/p' "$MODULE_PROP" | head -n 1 | tr -d ' \015')"
MP_VER="$(sed -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*\([^#]*\).*/\1/p' "$MODULE_PROP" | head -n 1 | tr -d ' \015')"
MP_CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([^#]*\).*/\1/p' "$MODULE_PROP" | head -n 1 | tr -d ' \015')"

# Command substitution strips trailing newlines, so a normal trailing newline
# in either list file is accepted while an extra/missing scope is not.
ACTUAL_SCOPE="$(tr -d '\015' < "$SCOPE_LIST")"
ACTUAL_INIT="$(sed '/^[[:space:]]*$/d' "$JAVA_INIT" | tr -d '\015')"

[ -n "$APP_ID" ] || fail "unable to parse applicationId from $GRADLE"
[ -n "$APP_NS" ] || fail "unable to parse namespace from $GRADLE"
[ -n "$APP_VER" ] || fail "unable to parse versionName from $GRADLE"
[ -n "$APP_CODE" ] || fail "unable to parse versionCode from $GRADLE"
[ -n "$MP_ID" ] || fail "unable to parse module.prop id from $MODULE_PROP"
[ -n "$MP_VER" ] || fail "unable to parse module.prop version from $MODULE_PROP"
[ -n "$MP_CODE" ] || fail "unable to parse module.prop versionCode from $MODULE_PROP"
[ -n "$ACTUAL_INIT" ] || fail "java_init.list is empty: $JAVA_INIT"

[ "$APP_ID" = "$TARGET_ID" ] || fail "applicationId must be '$TARGET_ID' (got '$APP_ID')"
[ "$APP_NS" = "$SOURCE_PACKAGE" ] || fail "namespace must stay the source package '$SOURCE_PACKAGE' (got '$APP_NS')"
[ "$APP_VER" = "$TARGET_VERSION" ] || fail "versionName must be '$TARGET_VERSION' (got '$APP_VER')"
[ "$APP_CODE" = "$TARGET_CODE" ] || fail "versionCode must be '$TARGET_CODE' (got '$APP_CODE')"

[ "$MP_ID" = "$APP_ID" ] || fail "module.prop id must equal applicationId (got '$MP_ID' vs '$APP_ID')"
[ "$MP_VER" = "$APP_VER" ] || fail "module.prop version must equal app versionName (got '$MP_VER' vs '$APP_VER')"
[ "$MP_CODE" = "$APP_CODE" ] || fail "module.prop versionCode must equal app versionCode (got '$MP_CODE' vs '$APP_CODE')"
[ "$MP_ID" = "$TARGET_ID" ] || fail "module.prop id must be '$TARGET_ID' (got '$MP_ID')"
[ "$MP_VER" = "$TARGET_VERSION" ] || fail "module.prop version must be '$TARGET_VERSION' (got '$MP_VER')"
[ "$MP_CODE" = "$TARGET_CODE" ] || fail "module.prop versionCode must be '$TARGET_CODE' (got '$MP_CODE')"

if grep -qxF "id=$OLD_ID" "$MODULE_PROP"; then
  fail "old id line must not appear in module.prop: id=$OLD_ID"
fi
if grep -qE "^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*\"$OLD_ID\"" "$GRADLE"; then
  fail "applicationId must not be the old id: $OLD_ID"
fi

[ "$ACTUAL_SCOPE" = "$EXPECTED_SCOPE" ] || fail "scope.list must be exactly the two canonical scopes"
[ "$ACTUAL_INIT" = "$EXPECTED_INIT" ] || fail "java_init.list must be exactly '$EXPECTED_INIT'"

printf 'PASS: app identity is %s (version %s, code %s); module.prop id/version/code, scope.list and java_init.list all match\n' \
  "$TARGET_ID" "$TARGET_VERSION" "$TARGET_CODE"
exit 0
