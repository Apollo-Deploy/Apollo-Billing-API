# Billing SDK Generation

Billing OpenAPI 3.1 is the source contract for API documentation and SDK generation. Ktor route documentation defines paths, schemas, responses, authentication, and operation IDs; Tesseract consumes the exported document directly.

Use `x-tesseract` only for SDK-specific metadata that OpenAPI does not express. For example, the inbound Polar webhook remains in the OpenAPI documentation but uses `x-tesseract: { exclude: true }`, so it is not emitted as a callable Billing client method.

## Commands

For installing and using the generated SDKs in internal apps, see [SDK README](sdk-readme.md).

Every SDK command exports a fresh offline OpenAPI contract first, then generates both SDKs from that exact file. Do not run Tesseract directly against a previously exported document.

Generate all SDKs locally:

```bash
make sdk
```

Publish the generated JVM SDK locally:

```bash
cd sdk/java
../../gradlew publishToMavenLocal
```

Because the generated publication is signed, local publishing also requires the in-memory GPG properties listed below.

Publish both SDKs:

```bash
make sdk-publish SDK_VERSION=<next-version>
```

The OpenAPI contract is written to `build/openapi/billing-openapi.json` by default.
The TypeScript SDK is written to `sdk/typescript`.
The Java/JVM SDK is written to `sdk/java`.

Billing uses the `tesseract` executable from `PATH`. The release workflow installs `@apollo-deploy/tesseract@3.1.0` before generation.

Tesseract currently exposes a `kotlin` target rather than a native `java` target. The Java SDK target in this repo generates a JVM SDK with Kotlin source and Maven publishing metadata so Java/Kotlin backends can consume it as a Maven artifact.

## Configuration

The generation script supports these environment variables:

- `OPENAPI_EXPORT_PATH`
- `ENV_FILE` (`.env` by default)
- `TESSERACT_PACKAGE_VERSION`
- `TESSERACT_CLIENT_NAME`
- `TESSERACT_SDK_STYLE`
- `TESSERACT_CLIENT_TYPE`
- `TESSERACT_BASE_URL`
- `TESSERACT_TYPESCRIPT_OUTPUT`
- `TESSERACT_TYPESCRIPT_PACKAGE_NAME`
- `TESSERACT_JAVA_OUTPUT`
- `TESSERACT_JAVA_PACKAGE_NAME`
- `TESSERACT_JAVA_GROUP_ID`
- `TESSERACT_JAVA_ARTIFACT_ID`
- `TESSERACT_JAVA_TOOLCHAIN_VERSION` (`21` by default)
- `TESSERACT_JAVA_HOME` (optional Java home used when publishing the JVM SDK)
- `TESSERACT_JAVA_GRADLE_EXECUTABLE` (the Billing repository's Gradle wrapper by default)
- `TESSERACT_JAVA_KOTLIN_VERSION`
- `TESSERACT_PUBLISH_TARGETS` (`typescript,kotlin` by default; publishing only)

Publishing requires:

- TypeScript: `NPM_TOKEN` or `NODE_AUTH_TOKEN`
- Kotlin/JVM: Maven Central Portal credentials plus an in-memory GPG signing key

## Public Kotlin/JVM Publishing With Maven Central

Before the first release, register and verify the `com.apollodeploy` namespace in the Maven Central Portal. Create a Portal user token and a GPG key, then provide these Gradle environment properties:

```dotenv
ORG_GRADLE_PROJECT_mavenCentralUsername=<portal-token-username>
ORG_GRADLE_PROJECT_mavenCentralPassword=<portal-token-password>
ORG_GRADLE_PROJECT_signingInMemoryKey=<ascii-armored-private-key>
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=<private-key-password>
```

The release workflow reads the same values from the `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_IN_MEMORY_KEY`, and `SIGNING_IN_MEMORY_KEY_PASSWORD` GitHub repository secrets.

Publish only the signed Maven Central release:

```bash
make sdk-publish-kotlin SDK_VERSION=<next-version>
```

Publish the npm package and signed Maven Central release together when both versions are new:

```bash
make sdk-publish SDK_VERSION=<next-version>
```

The generated build uses `publishAndReleaseToMavenCentral`, so a successful Gradle task validates, publishes, and releases the deployment. Maven Central versions are immutable; always choose a new version.

Consumers need only `mavenCentral()` and the public coordinates:

```kotlin
repositories {
    mavenCentral()
}

implementation("com.apollodeploy:billing-sdk:<version>")
```

Example:

```bash
TESSERACT_TYPESCRIPT_OUTPUT=build/sdk/typescript \
TESSERACT_JAVA_OUTPUT=build/sdk/java \
TESSERACT_CLIENT_NAME=ApolloBilling \
make sdk
```

Publish example:

```bash
make sdk-publish-kotlin SDK_VERSION=<next-version>
```

The JVM SDK publish step runs `gradle publishAndReleaseToMavenCentral` from the generated Kotlin SDK directory. Optional POM metadata can be supplied with:

```bash
POM_URL=https://github.com/apollo-deploy/apollo-billing-api \
POM_SCM_URL=https://github.com/apollo-deploy/apollo-billing-api \
POM_SCM_CONNECTION=scm:git:https://github.com/apollo-deploy/apollo-billing-api.git \
POM_SCM_DEVELOPER_CONNECTION=scm:git:ssh://git@github.com/apollo-deploy/apollo-billing-api.git \
POM_DEVELOPER_ID=apollo-deploy \
POM_DEVELOPER_NAME="Apollo Deploy" \
make sdk-publish-kotlin SDK_VERSION=<next-version>
```

The generated TypeScript client accepts the service JWT through `defaultHeaders`:

```ts
const billing = createApolloBillingClient({
  baseUrl: process.env.BILLING_BASE_URL,
  defaultHeaders: {
    Authorization: `Bearer ${process.env.APOLLO_BILLING_SERVICE_JWT}`,
  },
});
```

The generated JVM client accepts the same token through `serviceToken`:

```kotlin
val billing = ApolloBillingClient(
    ClientConfig(
        baseUrl = System.getenv("BILLING_BASE_URL"),
        serviceToken = System.getenv("APOLLO_BILLING_SERVICE_JWT"),
    ),
)
```
