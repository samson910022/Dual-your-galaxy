# Gradle wrapper (pinned to 8.7).

This directory holds the committed Gradle wrapper:

- `gradlew` / `gradlew.bat` launch scripts.
- `gradle-wrapper.jar` (official wrapper jar) + `gradle-wrapper.properties`
  pointing at the pinned `gradle-8.7-bin.zip` distribution.

Build and CI always invoke `./gradlew`, so the Gradle version is fixed
regardless of what is preinstalled on the runner. `gradle/actions/setup-gradle`
in CI only adds caching around the wrapper invocation; regenerate with
`gradle wrapper --gradle-version 8.7` if the pinned version ever changes.
