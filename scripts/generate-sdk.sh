#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck source=/dev/null
    source "$ENV_FILE"
    set +a
fi

OPENAPI_PATH="${OPENAPI_EXPORT_PATH:-build/openapi/billing-openapi.json}"
PUBLISH="${TESSERACT_PUBLISH:-0}"
PUBLISH_TARGETS="${TESSERACT_PUBLISH_TARGETS:-typescript,kotlin}"

SDK_VERSION="${TESSERACT_PACKAGE_VERSION:-${SDK_VERSION:-1.0.0}}"
BASE_URL="${TESSERACT_BASE_URL:-https://billing.apollodeploy.com}"
SDK_STYLE="${TESSERACT_SDK_STYLE:-functional}"
CLIENT_TYPE="${TESSERACT_CLIENT_TYPE:-internal}"
CLIENT_NAME="${TESSERACT_CLIENT_NAME:-ApolloBilling}"

TYPESCRIPT_OUTPUT="${TESSERACT_TYPESCRIPT_OUTPUT:-${TESSERACT_SDK_OUTPUT:-sdk/typescript}}"
TYPESCRIPT_PACKAGE_NAME="${TESSERACT_TYPESCRIPT_PACKAGE_NAME:-${TESSERACT_PACKAGE_NAME:-@apollo-deploy/billing-sdk}}"
NPM_PUBLISH_ACCESS="${NPM_PUBLISH_ACCESS:-public}"

JAVA_OUTPUT="${TESSERACT_JAVA_OUTPUT:-sdk/java}"
JAVA_TESSERACT_LANGUAGE="${TESSERACT_JAVA_LANGUAGE:-kotlin}"
JAVA_PACKAGE_NAME="${TESSERACT_JAVA_PACKAGE_NAME:-com.apollodeploy.billing.sdk}"
JAVA_GROUP_ID="${TESSERACT_JAVA_GROUP_ID:-com.apollodeploy}"
JAVA_ARTIFACT_ID="${TESSERACT_JAVA_ARTIFACT_ID:-billing-sdk}"
JAVA_KOTLIN_VERSION="${TESSERACT_JAVA_KOTLIN_VERSION:-2.3.21}"
JAVA_TOOLCHAIN_VERSION="${TESSERACT_JAVA_TOOLCHAIN_VERSION:-21}"
JAVA_GRADLE_EXECUTABLE="${TESSERACT_JAVA_GRADLE_EXECUTABLE:-$ROOT_DIR/gradlew}"
POM_URL="${POM_URL:-https://github.com/apollo-deploy/apollo-billing-api}"
POM_DEVELOPER_ID="${POM_DEVELOPER_ID:-apollo-deploy}"
POM_DEVELOPER_NAME="${POM_DEVELOPER_NAME:-Apollo Deploy}"
POM_DEVELOPER_EMAIL="${POM_DEVELOPER_EMAIL:-}"
POM_SCM_URL="${POM_SCM_URL:-https://github.com/apollo-deploy/apollo-billing-api}"
POM_SCM_CONNECTION="${POM_SCM_CONNECTION:-scm:git:https://github.com/apollo-deploy/apollo-billing-api.git}"
POM_SCM_DEVELOPER_CONNECTION="${POM_SCM_DEVELOPER_CONNECTION:-scm:git:ssh://git@github.com/apollo-deploy/apollo-billing-api.git}"
ROOT_GRADLE_JAVA_HOME="$(sed -n 's/^org\.gradle\.java\.home=//p' gradle.properties 2>/dev/null | head -n 1)"
JAVA_BUILD_HOME="${TESSERACT_JAVA_HOME:-${ROOT_GRADLE_JAVA_HOME:-${JAVA_HOME:-}}}"

generate_tesseract_sdk() {
    local language="$1"
    local output="$2"
    local package_name="$3"
    local staging_output="${output}.tmp"

    rm -rf "$staging_output"
    mkdir -p "$(dirname "$output")"

    echo "Generating $language SDK: $output"
    tesseract generate \
        --input "$OPENAPI_PATH" \
        --output "$staging_output" \
        --language "$language" \
        --name "$package_name" \
        --package-version "$SDK_VERSION" \
        --client-name "$CLIENT_NAME" \
        --base-url "$BASE_URL" \
        --sdk-style "$SDK_STYLE" \
        --client-type "$CLIENT_TYPE"

    rm -rf "$output"
    mv "$staging_output" "$output"
}

postprocess_java_sdk() {
    local output="$1"
    local build_file="$output/build.gradle.kts"
    local build_template="$ROOT_DIR/scripts/kotlin-sdk-build.gradle.kts"
    local package_dir="$output/src/main/kotlin/${JAVA_PACKAGE_NAME//.//}"
    local client_file="$package_dir/Client.kt"
    local transport_file="$package_dir/internal/Transport.kt"
    local types_file="$package_dir/models/Types.kt"
    local readme_file="$output/README.md"

    if [[ ! -f "$build_file" ]]; then
        echo "Expected generated JVM build file at $build_file" >&2
        exit 1
    fi

    cp "$build_template" "$build_file"
    perl -0pi -e "s/__GROUP_ID__/$JAVA_GROUP_ID/g; s/__ARTIFACT_ID__/$JAVA_ARTIFACT_ID/g; s/__SDK_VERSION__/$SDK_VERSION/g; s/__KOTLIN_VERSION__/$JAVA_KOTLIN_VERSION/g; s/__JVM_TOOLCHAIN__/$JAVA_TOOLCHAIN_VERSION/g" "$build_file"

    if ! grep -q 'publishToMavenCentral()' "$build_file" ||
        ! grep -q 'signAllPublications()' "$build_file"; then
        echo "Generated JVM SDK is missing Maven Central publishing or artifact signing configuration." >&2
        exit 1
    fi

    if [[ ! -d "$package_dir" ]]; then
        echo "Expected generated JVM source directory at $package_dir" >&2
        exit 1
    fi

    find "$package_dir" -name '*.kt' -print0 | xargs -0 perl -0pi -e '
        s/HttpMethod\.GET/HttpMethod.Get/g;
        s/HttpMethod\.POST/HttpMethod.Post/g;
        s/HttpMethod\.PATCH/HttpMethod.Patch/g;
        s/HttpMethod\.DELETE/HttpMethod.Delete/g;
        s/HttpMethod\.PUT/HttpMethod.Put/g;
    '

    find "$package_dir" -name '*API.kt' -print0 | xargs -0 perl -0pi -e '
        s/query: \{[^\n]+\}\? = null,/query: Map<String, String?>? = null,/g;
        s/        val queryMap = mutableMapOf<String, String\?>\(\)\n(?:        queryMap\["[^"]+"\] = null \/\/[^\n]*\n)+/        val queryMap = query\n/g;
        s/return transport\.execute<Unit>\(/transport.executeRaw(/g;
    '

    if [[ -f "$types_file" ]]; then
        perl -pi -e '
            s/val quantity: Double\? = null,/val quantity: Int? = null,/ if /val quantity: /;
        ' "$types_file"
    fi

    # Tesseract now emits defaultHeaders natively. Only inject billing-specific
    # serviceToken auth, and only when the generator has not already added it.
    if [[ -f "$transport_file" ]] && ! grep -q 'val serviceToken' "$transport_file"; then
        perl -0pi -e '
            s/(val maxRetries: Int = 3,\n)/$1    val serviceToken: String? = null,\n/s;
            s/(config\.defaultHeaders\.forEach \{ \(key, value\) -> headers\.append\(key, value\) \}\n)/$1            config.serviceToken?.takeIf { it.isNotBlank() }?.let { token ->\n                headers.append(HttpHeaders.Authorization, "Bearer \$token")\n            }\n/s;
            s/private val json = Json/\@PublishedApi\n    internal val json = Json/;
        ' "$transport_file"
    fi

    if [[ -f "$client_file" ]] && ! grep -q 'val serviceToken' "$client_file"; then
        perl -0pi -e '
            s/(val maxRetries: Int = 3,\n)/$1    val serviceToken: String? = null,\n/s;
            s/(maxRetries = config\.maxRetries,\n)/$1            serviceToken = config.serviceToken,\n/s;
        ' "$client_file"
    fi

    if [[ -f "$readme_file" ]]; then
        perl -0pi -e "s/implementation\\(\"com\\.example:com\\.apollodeploy\\.billing\\.sdk:[^\"]+\"\\)/implementation(\"$JAVA_GROUP_ID:$JAVA_ARTIFACT_ID:$SDK_VERSION\")/g" "$readme_file"
        perl -0pi -e "s/<groupId>com\\.example<\\/groupId>/<groupId>$JAVA_GROUP_ID<\\/groupId>/g" "$readme_file"
        perl -0pi -e "s/<artifactId>com\\.apollodeploy\\.billing\\.sdk<\\/artifactId>/<artifactId>$JAVA_ARTIFACT_ID<\\/artifactId>/g" "$readme_file"
        perl -0pi -e "s/import com\\.apollodeploy\\.billing\\.sdk\\.Client\\n//g" "$readme_file"
        perl -0pi -e "s/import com\\.apollodeploy\\.billing\\.sdk\\.ClientConfig/import com.apollodeploy.billing.sdk.ApolloBillingClient\\nimport com.apollodeploy.billing.sdk.ClientConfig/g" "$readme_file"
        perl -0pi -e 's/val config = ClientConfig\(\n    \)/val config = ClientConfig(\n        baseUrl = System.getenv("BILLING_BASE_URL") ?: "https:\/\/billing.apollodeploy.com",\n        serviceToken = System.getenv("APOLLO_BILLING_SERVICE_JWT"),\n    )/g' "$readme_file"
        perl -0pi -e "s/val client = Client\\(config\\)/val client = ApolloBillingClient(config)/g" "$readme_file"
        perl -0pi -e 's/\/\/ val result = client\.health\.someOperation\(\)/val result = client.health.getHealth()\n    println("Billing service: " + result.status)/g' "$readme_file"
        perl -0pi -e "s/client\\.users\\.getUser\\(\"user-123\"\\)/client.health.getHealth()/g" "$readme_file"
    fi
}

publish_typescript_sdk() {
    local output="$1"

    if [[ -z "${NPM_TOKEN:-${NODE_AUTH_TOKEN:-}}" ]]; then
        echo "Set NPM_TOKEN or NODE_AUTH_TOKEN before publishing the TypeScript SDK." >&2
        exit 1
    fi

    (
        cd "$output"
        if [[ -n "${NPM_TOKEN:-}" ]]; then
            printf '//registry.npmjs.org/:_authToken=%s\n' "$NPM_TOKEN" > .npmrc
            trap 'rm -f .npmrc' EXIT
        fi
        npm install --ignore-scripts
        npm run build
        npm publish --access "$NPM_PUBLISH_ACCESS"
    )
}

require_java_publish_credentials() {
    if [[ -z "${ORG_GRADLE_PROJECT_mavenCentralUsername:-}" ||
        -z "${ORG_GRADLE_PROJECT_mavenCentralPassword:-}" ]]; then
        echo "Set ORG_GRADLE_PROJECT_mavenCentralUsername and ORG_GRADLE_PROJECT_mavenCentralPassword before publishing the JVM SDK." >&2
        exit 1
    fi
    if [[ -z "${ORG_GRADLE_PROJECT_signingInMemoryKey:-}" ||
        -z "${ORG_GRADLE_PROJECT_signingInMemoryKeyPassword:-}" ]]; then
        echo "Set ORG_GRADLE_PROJECT_signingInMemoryKey and ORG_GRADLE_PROJECT_signingInMemoryKeyPassword before publishing the JVM SDK." >&2
        exit 1
    fi
}

publish_java_sdk() {
    local output="$1"

    require_java_publish_credentials

    (
        cd "$output"
        if [[ -n "$JAVA_BUILD_HOME" && -d "$JAVA_BUILD_HOME" ]]; then
            POM_URL="$POM_URL" \
            POM_DEVELOPER_ID="$POM_DEVELOPER_ID" \
            POM_DEVELOPER_NAME="$POM_DEVELOPER_NAME" \
            POM_DEVELOPER_EMAIL="$POM_DEVELOPER_EMAIL" \
            POM_SCM_URL="$POM_SCM_URL" \
            POM_SCM_CONNECTION="$POM_SCM_CONNECTION" \
            POM_SCM_DEVELOPER_CONNECTION="$POM_SCM_DEVELOPER_CONNECTION" \
            JAVA_HOME="$JAVA_BUILD_HOME" \
                "$JAVA_GRADLE_EXECUTABLE" publishAndReleaseToMavenCentral --no-daemon
        else
            POM_URL="$POM_URL" \
            POM_DEVELOPER_ID="$POM_DEVELOPER_ID" \
            POM_DEVELOPER_NAME="$POM_DEVELOPER_NAME" \
            POM_DEVELOPER_EMAIL="$POM_DEVELOPER_EMAIL" \
            POM_SCM_URL="$POM_SCM_URL" \
            POM_SCM_CONNECTION="$POM_SCM_CONNECTION" \
            POM_SCM_DEVELOPER_CONNECTION="$POM_SCM_DEVELOPER_CONNECTION" \
                "$JAVA_GRADLE_EXECUTABLE" publishAndReleaseToMavenCentral --no-daemon
        fi
    )
}

publish_target_enabled() {
    [[ ",$PUBLISH_TARGETS," == *",$1,"* ]]
}

if [[ "$PUBLISH" == "1" || "$PUBLISH" == "true" ]]; then
    if [[ ! "$PUBLISH_TARGETS" =~ ^(typescript|kotlin)(,(typescript|kotlin))*$ ]]; then
        echo "TESSERACT_PUBLISH_TARGETS must be typescript, kotlin, or a comma-separated combination of both." >&2
        exit 1
    fi
    if publish_target_enabled "typescript" && [[ -z "${NPM_TOKEN:-${NODE_AUTH_TOKEN:-}}" ]]; then
        echo "Set NPM_TOKEN or NODE_AUTH_TOKEN before publishing the TypeScript SDK." >&2
        exit 1
    fi
    if publish_target_enabled "kotlin"; then
        require_java_publish_credentials
    fi
fi

mkdir -p "$(dirname "$OPENAPI_PATH")"

echo "Exporting OpenAPI document: $OPENAPI_PATH"
OPENAPI_EXPORT_PATH="$OPENAPI_PATH" \
APOLLO_BILLING_ENV="${APOLLO_BILLING_ENV:-development}" \
./gradlew run

generate_tesseract_sdk "typescript" "$TYPESCRIPT_OUTPUT" "$TYPESCRIPT_PACKAGE_NAME"

if [[ "$PUBLISH" == "1" || "$PUBLISH" == "true" ]] && publish_target_enabled "typescript"; then
    publish_typescript_sdk "$TYPESCRIPT_OUTPUT"
fi

if [[ "$JAVA_TESSERACT_LANGUAGE" != "kotlin" ]]; then
    echo "Tesseract does not currently expose a native Java target; using language=$JAVA_TESSERACT_LANGUAGE as configured." >&2
else
    echo "Tesseract does not expose a native Java target; generating a JVM SDK with the Kotlin target."
fi
generate_tesseract_sdk "$JAVA_TESSERACT_LANGUAGE" "$JAVA_OUTPUT" "$JAVA_PACKAGE_NAME"
postprocess_java_sdk "$JAVA_OUTPUT"

if [[ "$PUBLISH" == "1" || "$PUBLISH" == "true" ]] && publish_target_enabled "kotlin"; then
    publish_java_sdk "$JAVA_OUTPUT"
fi
