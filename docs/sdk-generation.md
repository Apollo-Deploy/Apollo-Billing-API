# Billing SDK Generation

Billing SDKs are generated from the route-level Tesseract annotations in `src/main/kotlin/com/apollodeploy/tesseract`.

Routes should follow the same pattern as the internal app services:

```kotlin
fun Route.exampleRoutes(controller: ExampleController) {
    sdkDomain("/internal/billing/example", "billingExample", stability = "internal")

    route("/internal/billing/example") {
        post({
            summary = "Create example"
            description = "Creates an example resource."
            request {
                body<CreateExampleRequest> { required = true }
            }
            response {
                code(HttpStatusCode.OK) {
                    body<CreateExampleResponse>()
                }
            }
        }) {
            controller.create(call)
        }.sdk {
            operationId = "createExample"
            methodName = "createExample"
            internal = true
            requestBody<CreateExampleRequest>()
            response<CreateExampleResponse>()
        }
    }
}
```

## Commands

For installing and using the generated SDKs in internal apps, see [SDK README](sdk-readme.md).

Export only the Tesseract manifest:

```bash
make sdk-manifest
```

Generate both SDKs:

```bash
make sdk
```

Generate one SDK:

```bash
make sdk-ts
make sdk-java
```

Publish the generated JVM SDK locally:

```bash
cd sdk/java
gradle publishToMavenLocal
```

Publish both SDKs:

```bash
make sdk-publish
```

The manifest is written to `build/tesseract/billing-manifest.json` by default.
The TypeScript SDK is written to `sdk/typescript`.
The Java/JVM SDK is written to `sdk/java`.

Tesseract currently exposes a `kotlin` target rather than a native `java` target. The Java SDK target in this repo generates a JVM SDK with Kotlin source and Maven publishing metadata so Java/Kotlin backends can consume it as a Maven artifact.

## Configuration

The generation script supports these environment variables:

- `TESSERACT_MANIFEST_PATH`
- `ENV_FILE` (`.env` by default)
- `TESSERACT_TARGETS` (`typescript,java` by default)
- `TESSERACT_PACKAGE_VERSION`
- `TESSERACT_CLIENT_NAME`
- `TESSERACT_SDK_STYLE`
- `TESSERACT_CLIENT_TYPE`
- `TESSERACT_NODE_EXECUTABLE`
- `TESSERACT_COMMAND`
- `TESSERACT_BASE_URL`
- `TESSERACT_TYPESCRIPT_OUTPUT`
- `TESSERACT_TYPESCRIPT_PACKAGE_NAME`
- `TESSERACT_JAVA_OUTPUT`
- `TESSERACT_JAVA_PACKAGE_NAME`
- `TESSERACT_JAVA_GROUP_ID`
- `TESSERACT_JAVA_ARTIFACT_ID`
- `TESSERACT_JAVA_TOOLCHAIN_VERSION` (`21` by default)
- `TESSERACT_JAVA_HOME` (optional Java home used when publishing the JVM SDK)
- `TESSERACT_JAVA_GRADLE_EXECUTABLE` (`gradle` by default)
- `TESSERACT_JAVA_KOTLIN_VERSION`

Publishing requires:

- TypeScript: `NPM_TOKEN` or `NODE_AUTH_TOKEN`
- Java/JVM: `MAVEN_REPOSITORY_URL`, `MAVEN_REPOSITORY_USERNAME`, and `MAVEN_REPOSITORY_PASSWORD`

`MAVEN_REPOSITORY_NAME` is optional. It is consumed by the generated `sdk/java/build.gradle.kts` as the Gradle repository name. If omitted, the generated SDK defaults it to `generated`. For this repo, use `codeartifact` for the private AWS CodeArtifact Maven repository.

## Private Maven Publishing With AWS CodeArtifact

Use CodeArtifact when the SDK should stay private. Authenticate AWS CLI first, then configure the Maven repository values:

```bash
aws login
make sdk-codeartifact
```

The CodeArtifact setup uses these values from `.env`, with defaults when omitted:

```bash
AWS_REGION=us-east-1
AWS_PROFILE=
CODEARTIFACT_DOMAIN=apollo-deploy
CODEARTIFACT_DOMAIN_OWNER=
CODEARTIFACT_REPOSITORY=apollo-billing-sdk
CODEARTIFACT_REPOSITORY_DESCRIPTION="Apollo Deploy billing SDK Maven repository"
CODEARTIFACT_ROLE_ARN=
CODEARTIFACT_CREATE=1
CODEARTIFACT_ENV_PREFIX=MAVEN_REPOSITORY
CODEARTIFACT_REPOSITORY_NAME=codeartifact
CODEARTIFACT_BOOTSTRAP_PUBLISHER=auto
CODEARTIFACT_PUBLISHER_USER=codeartifact-maven-publisher
CODEARTIFACT_PUBLISHER_PROFILE=codeartifact-maven-publisher
CODEARTIFACT_PUBLISHER_POLICY_NAME=CodeArtifactMavenPublisherPolicy
CODEARTIFACT_UPSTREAM_REPOSITORIES=
CODEARTIFACT_EXTERNAL_CONNECTIONS=
CODEARTIFACT_VERIFY=1
```

It uses AWS CLI to:

- create the CodeArtifact domain when missing;
- create or update the Maven repository;
- optionally create upstream repositories for public external connections such as `public:maven-central`;
- detect when the current AWS identity cannot fetch CodeArtifact auth tokens;
- optionally create a least-privilege IAM publisher user and local AWS profile;
- attach/update the inline publisher policy;
- fetch the Maven endpoint and authorization token;
- verify repository access.

When the script runs with a publisher-only profile, repository metadata updates are best-effort. It will continue when the profile can read/publish packages but cannot call `codeartifact:UpdateRepository`.

Then it writes:

```bash
MAVEN_REPOSITORY_NAME=codeartifact
MAVEN_REPOSITORY_URL=...
MAVEN_REPOSITORY_USERNAME=aws
MAVEN_REPOSITORY_PASSWORD=...
```

CodeArtifact authorization tokens expire. Rerun `make sdk-codeartifact` before publishing if Gradle receives `401` or `403`.

The setup script is reusable in other JVM projects. Set a different domain/repository and env file:

```bash
ENV_FILE=/path/to/project/.env \
CODEARTIFACT_DOMAIN=my-company \
CODEARTIFACT_REPOSITORY=my-private-maven \
CODEARTIFACT_REPOSITORY_DESCRIPTION="Private Maven packages" \
scripts/setup-codeartifact-maven-env.sh
```

To proxy Maven Central through CodeArtifact for dependencies resolved by the same private repository:

```bash
CODEARTIFACT_DOMAIN=my-company \
CODEARTIFACT_REPOSITORY=my-private-maven \
CODEARTIFACT_EXTERNAL_CONNECTIONS=public:maven-central \
scripts/setup-codeartifact-maven-env.sh
```

Publish only the JVM SDK to CodeArtifact:

```bash
TESSERACT_PUBLISH=1 TESSERACT_TARGETS=java TESSERACT_PACKAGE_VERSION=<next-version> scripts/generate-sdk.sh
```

Internal apps consume it with the same CodeArtifact Maven repository credentials and:

```kotlin
implementation("com.apollodeploy:billing-sdk:1.0.7")
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
NPM_TOKEN=... \
make sdk-codeartifact
make sdk-publish
```

The JVM SDK publish step runs `gradle publish` from the generated Kotlin SDK directory. Optional POM metadata can be supplied with:

```bash
POM_URL=https://github.com/apollo-deploy/apollo-billing-api \
POM_SCM_URL=https://github.com/apollo-deploy/apollo-billing-api \
POM_SCM_CONNECTION=scm:git:https://github.com/apollo-deploy/apollo-billing-api.git \
POM_SCM_DEVELOPER_CONNECTION=scm:git:ssh://git@github.com/apollo-deploy/apollo-billing-api.git \
POM_DEVELOPER_ID=apollo-deploy \
POM_DEVELOPER_NAME="Apollo Deploy" \
make sdk-publish
```

Public Maven/Sonatype publishing is disabled for the JVM SDK. The publish script rejects Sonatype/Central repository URLs so the SDK stays private in CodeArtifact.

For CI, run the same commands from the generated SDK folder after `make sdk-java`:

```bash
cd sdk/java
gradle publish
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
