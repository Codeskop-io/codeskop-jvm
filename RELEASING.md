# Releasing

Published by hand from a machine with the Maven Central credentials and signing key in
`~/.gradle/gradle.properties` (`mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`,
`signingInMemoryKeyId`, `signingInMemoryKeyPassword`), same as the mobile SDK.

1. Bump `version` in `build.gradle.kts` and `Codeskop.VERSION`, update `CHANGELOG.md`, merge to `main`, tag `vX.Y.Z`.
2. `./gradlew test publishToMavenCentral --no-configuration-cache` (the configuration cache breaks the publish task).
3. Open https://central.sonatype.com/publishing/deployments, check the deployment validated, click **Publish**.
