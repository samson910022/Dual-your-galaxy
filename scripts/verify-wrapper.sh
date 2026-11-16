#!/bin/sh
# Supply-chain gate: gradle/wrapper/gradle-wrapper.properties pins the
# distribution zip (Gradle 8.7, verified via distributionSha256Sum), but
# nothing covered the committed wrapper jar shim that downloads the
# distribution. Verify it byte-for-byte against the official Gradle
# 8.13-compatible wrapper jar (the committed shim; see gradle/wrapper/README.md).
# Run immediately after every actions/checkout that precedes a ./gradlew call.
set -e
echo "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f  gradle/wrapper/gradle-wrapper.jar" | sha256sum -c -
# Also pin the wrapper entry scripts themselves: they are what CI executes first,
# and the distributionSha256Sum covers only the zip the jar downloads.
echo "734b3879d3501dce471cf0522d3bcbafe76873d9fc5129345b67fb43bd15e933  gradlew" | sha256sum -c -
echo "57931b17dd228e5c24dac90e815d0bf82477e831a4618dfab4136f5446b42a9f  gradlew.bat" | sha256sum -c -
