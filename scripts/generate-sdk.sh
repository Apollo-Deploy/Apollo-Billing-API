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

MANIFEST_PATH="${TESSERACT_MANIFEST_PATH:-build/tesseract/billing-manifest.json}"
TARGETS="${TESSERACT_TARGETS:-typescript,java}"
PUBLISH="${TESSERACT_PUBLISH:-0}"

SDK_VERSION="${TESSERACT_PACKAGE_VERSION:-${SDK_VERSION:-1.0.0}}"
BASE_URL="${TESSERACT_BASE_URL:-https://billing.apollodeploy.com}"
SDK_STYLE="${TESSERACT_SDK_STYLE:-functional}"
CLIENT_TYPE="${TESSERACT_CLIENT_TYPE:-internal}"
CLIENT_NAME="${TESSERACT_CLIENT_NAME:-ApolloBilling}"
NODE_EXECUTABLE="${TESSERACT_NODE_EXECUTABLE:-npx}"
TESSERACT_COMMAND="${TESSERACT_COMMAND:-tesseract}"
GENERATE_ONLY_MANIFEST="${TESSERACT_MANIFEST_ONLY:-0}"

TYPESCRIPT_OUTPUT="${TESSERACT_TYPESCRIPT_OUTPUT:-${TESSERACT_SDK_OUTPUT:-sdk/typescript}}"
TYPESCRIPT_PACKAGE_NAME="${TESSERACT_TYPESCRIPT_PACKAGE_NAME:-${TESSERACT_PACKAGE_NAME:-@apollo-deploy/billing-sdk}}"
NPM_PUBLISH_ACCESS="${NPM_PUBLISH_ACCESS:-restricted}"

JAVA_OUTPUT="${TESSERACT_JAVA_OUTPUT:-sdk/java}"
JAVA_TESSERACT_LANGUAGE="${TESSERACT_JAVA_LANGUAGE:-kotlin}"
JAVA_PACKAGE_NAME="${TESSERACT_JAVA_PACKAGE_NAME:-com.apollodeploy.billing.sdk}"
JAVA_GROUP_ID="${TESSERACT_JAVA_GROUP_ID:-com.apollodeploy}"
JAVA_ARTIFACT_ID="${TESSERACT_JAVA_ARTIFACT_ID:-billing-sdk}"
JAVA_KOTLIN_VERSION="${TESSERACT_JAVA_KOTLIN_VERSION:-2.1.10}"
JAVA_TOOLCHAIN_VERSION="${TESSERACT_JAVA_TOOLCHAIN_VERSION:-21}"
JAVA_GRADLE_EXECUTABLE="${TESSERACT_JAVA_GRADLE_EXECUTABLE:-gradle}"
POM_NAME="${POM_NAME:-Apollo Billing SDK}"
POM_DESCRIPTION="${POM_DESCRIPTION:-Server-to-server SDK for Apollo Deploy billing, entitlement, checkout, usage, and customer billing operations.}"
POM_URL="${POM_URL:-https://github.com/apollo-deploy/apollo-billing-api}"
POM_LICENSE_NAME="${POM_LICENSE_NAME:-MIT License}"
POM_LICENSE_URL="${POM_LICENSE_URL:-https://opensource.org/licenses/MIT}"
POM_DEVELOPER_ID="${POM_DEVELOPER_ID:-apollo-deploy}"
POM_DEVELOPER_NAME="${POM_DEVELOPER_NAME:-Apollo Deploy}"
POM_DEVELOPER_EMAIL="${POM_DEVELOPER_EMAIL:-}"
POM_SCM_URL="${POM_SCM_URL:-https://github.com/apollo-deploy/apollo-billing-api}"
POM_SCM_CONNECTION="${POM_SCM_CONNECTION:-scm:git:https://github.com/apollo-deploy/apollo-billing-api.git}"
POM_SCM_DEVELOPER_CONNECTION="${POM_SCM_DEVELOPER_CONNECTION:-scm:git:ssh://git@github.com/apollo-deploy/apollo-billing-api.git}"
ROOT_GRADLE_JAVA_HOME="$(sed -n 's/^org\.gradle\.java\.home=//p' gradle.properties 2>/dev/null | head -n 1)"
JAVA_BUILD_HOME="${TESSERACT_JAVA_HOME:-${ROOT_GRADLE_JAVA_HOME:-${JAVA_HOME:-}}}"

mkdir -p "$(dirname "$MANIFEST_PATH")"

echo "Exporting Tesseract manifest: $MANIFEST_PATH"
TESSERACT_GENERATE=1 \
TESSERACT_MANIFEST_PATH="$MANIFEST_PATH" \
TESSERACT_BASE_URL="$BASE_URL" \
TESSERACT_CLIENT_NAME="$CLIENT_NAME" \
TESSERACT_PACKAGE_VERSION="$SDK_VERSION" \
APOLLO_BILLING_ENV="${APOLLO_BILLING_ENV:-development}" \
./gradlew run

if [[ "$GENERATE_ONLY_MANIFEST" == "1" || "$GENERATE_ONLY_MANIFEST" == "true" ]]; then
    echo "Manifest exported. Skipping SDK generation because TESSERACT_MANIFEST_ONLY=$GENERATE_ONLY_MANIFEST."
    exit 0
fi

normalize_target() {
    case "$1" in
        ts) echo "typescript" ;;
        jvm|kotlin) echo "java" ;;
        *) echo "$1" ;;
    esac
}

generate_tesseract_sdk() {
    local language="$1"
    local output="$2"
    local package_name="$3"

    rm -rf "$output"
    mkdir -p "$(dirname "$output")"

    echo "Generating $language SDK: $output"
    "$NODE_EXECUTABLE" "$TESSERACT_COMMAND" generate \
        --input "$MANIFEST_PATH" \
        --output "$output" \
        --language "$language" \
        --name "$package_name" \
        --package-version "$SDK_VERSION" \
        --client-name "$CLIENT_NAME" \
        --base-url "$BASE_URL" \
        --sdk-style "$SDK_STYLE" \
        --client-type "$CLIENT_TYPE"
}

postprocess_java_sdk() {
    local output="$1"
    local build_file="$output/build.gradle.kts"
    local package_dir="$output/src/main/kotlin/${JAVA_PACKAGE_NAME//.//}"
    local client_file="$package_dir/Client.kt"
    local transport_file="$package_dir/Transport.kt"
    local types_file="$package_dir/Types.kt"
    local readme_file="$output/README.md"

    if [[ ! -f "$build_file" ]]; then
        echo "Expected generated JVM build file at $build_file" >&2
        exit 1
    fi

    perl -0pi -e "s/group = \"[^\"]+\"/group = \"$JAVA_GROUP_ID\"/" "$build_file"
    perl -0pi -e "s/version = \"[^\"]+\"/version = \"$SDK_VERSION\"/" "$build_file"
    perl -0pi -e "s/kotlin\\(\"jvm\"\\) version \"[^\"]+\"/kotlin(\"jvm\") version \"$JAVA_KOTLIN_VERSION\"/" "$build_file"
    perl -0pi -e "s/kotlin\\(\"plugin\\.serialization\"\\) version \"[^\"]+\"/kotlin(\"plugin.serialization\") version \"$JAVA_KOTLIN_VERSION\"/" "$build_file"
    perl -0pi -e "s/jvmToolchain\\(\\d+\\)/jvmToolchain($JAVA_TOOLCHAIN_VERSION)/" "$build_file"
    perl -0pi -e "s/artifactId = \"[^\"]+\"/artifactId = \"$JAVA_ARTIFACT_ID\"/" "$build_file"
    perl -0pi -e "s/create<MavenPublication>\\(\"maven\"\\) \\{\\n\\s*from\\(components\\[\"java\"\\]\\)/create<MavenPublication>(\"maven\") {\\n            artifactId = \"$JAVA_ARTIFACT_ID\"\\n            from(components[\"java\"])/" "$build_file"
    perl -0pi -e 's/\n\s*signing\n//g; s/\nval signingKey = providers\.gradleProperty\("signingInMemoryKey"\).*?\nsigning \{\n.*?\n\}\n//s' "$build_file"

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
            s/val check: .*,/val check: JsonElement,/ if /val check: /;
            s/val customer: .*,/val customer: JsonObject,/ if /val customer: /;
            s/val paymentMethods: .*,/val paymentMethods: JsonObject,/ if /val paymentMethods: /;
            s/val quantity: Double\? = null,/val quantity: Int? = null,/ if /val quantity: /;
            s/val metadata: .*,/val metadata: JsonObject? = null,/ if /val metadata: / && !/Map<String, String>/;
            s/val data: .*,/val data: JsonElement,/ if /val data: /;
        ' "$types_file"
    fi

    if [[ -f "$transport_file" ]]; then
        perl -0pi -e '
            s/(val maxRetries: Int = 3,\n)/$1    val defaultHeaders: Map<String, String> = emptyMap(),\n    val serviceToken: String? = null,\n/s;
            s/private val json = Json/\@PublishedApi\n    internal val json = Json/;
            s/(                \/\/ Set custom headers\n)/                config.defaultHeaders.forEach { (key, value) ->\n                    this.headers.append(key, value)\n                }\n\n$1/s;
            s/private fun HttpRequestBuilder\.applyAuth\(\) \{\n    \}/private fun HttpRequestBuilder.applyAuth() {\n        config.serviceToken?.takeIf { it.isNotBlank() }?.let { token ->\n            headers.append(HttpHeaders.Authorization, "Bearer \$token")\n        }\n    }/s;
        ' "$transport_file"
    fi

    if [[ -f "$client_file" ]]; then
        perl -0pi -e '
            s/(val maxRetries: Int = 3,\n)/$1    val defaultHeaders: Map<String, String> = emptyMap(),\n    val serviceToken: String? = null,\n/s;
            s/(maxRetries = config\.maxRetries,\n)/$1            defaultHeaders = config.defaultHeaders,\n            serviceToken = config.serviceToken,\n/s;
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
    if [[ -z "${MAVEN_REPOSITORY_URL:-}" ]]; then
        echo "Set MAVEN_REPOSITORY_URL before publishing the Java/JVM SDK." >&2
        exit 1
    fi
    if [[ -z "${MAVEN_REPOSITORY_USERNAME:-}" || -z "${MAVEN_REPOSITORY_PASSWORD:-}" ]]; then
        echo "Set MAVEN_REPOSITORY_USERNAME and MAVEN_REPOSITORY_PASSWORD before publishing the Java/JVM SDK." >&2
        exit 1
    fi
    if [[ "$MAVEN_REPOSITORY_URL" == *"sonatype.com"* || "$MAVEN_REPOSITORY_URL" == *"central.sonatype.com"* ]]; then
        echo "Public Maven/Sonatype publishing is disabled. Use AWS CodeArtifact for the Java/JVM SDK." >&2
        exit 1
    fi
}

publish_java_sdk() {
    local output="$1"

    require_java_publish_credentials

    (
        cd "$output"
        if [[ -n "$JAVA_BUILD_HOME" && -d "$JAVA_BUILD_HOME" ]]; then
            JAVA_HOME="$JAVA_BUILD_HOME" "$JAVA_GRADLE_EXECUTABLE" publish
        else
            "$JAVA_GRADLE_EXECUTABLE" publish
        fi
    )
}

IFS=',' read -ra target_list <<< "$TARGETS"
normalized_targets=()
for raw_target in "${target_list[@]}"; do
    normalized_targets+=("$(normalize_target "$(echo "$raw_target" | xargs)")")
done

if [[ "$PUBLISH" == "1" || "$PUBLISH" == "true" ]]; then
    for target in "${normalized_targets[@]}"; do
        case "$target" in
            typescript)
                if [[ -z "${NPM_TOKEN:-${NODE_AUTH_TOKEN:-}}" ]]; then
                    echo "Set NPM_TOKEN or NODE_AUTH_TOKEN before publishing the TypeScript SDK." >&2
                    exit 1
                fi
                ;;
            java)
                require_java_publish_credentials
                ;;
        esac
    done
fi

for target in "${normalized_targets[@]}"; do
    case "$target" in
        typescript)
            generate_tesseract_sdk "typescript" "$TYPESCRIPT_OUTPUT" "$TYPESCRIPT_PACKAGE_NAME"
            if [[ "$PUBLISH" == "1" || "$PUBLISH" == "true" ]]; then
                publish_typescript_sdk "$TYPESCRIPT_OUTPUT"
            fi
            ;;
        java)
            if [[ "$JAVA_TESSERACT_LANGUAGE" != "kotlin" ]]; then
                echo "Tesseract does not currently expose a native Java target; using language=$JAVA_TESSERACT_LANGUAGE as configured." >&2
            else
                echo "Tesseract does not expose a native Java target; generating a JVM SDK with the Kotlin target."
            fi
            generate_tesseract_sdk "$JAVA_TESSERACT_LANGUAGE" "$JAVA_OUTPUT" "$JAVA_PACKAGE_NAME"
            postprocess_java_sdk "$JAVA_OUTPUT"
            if [[ "$PUBLISH" == "1" || "$PUBLISH" == "true" ]]; then
                publish_java_sdk "$JAVA_OUTPUT"
            fi
            ;;
        *)
            echo "Unknown TESSERACT_TARGETS entry: $target" >&2
            exit 1
            ;;
    esac
done
