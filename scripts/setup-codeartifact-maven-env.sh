#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"

if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck source=/dev/null
    source "$ENV_FILE"
    set +a
fi

AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
AWS_PROFILE="${AWS_PROFILE:-}"
CODEARTIFACT_DOMAIN="${CODEARTIFACT_DOMAIN:-${AWS_CODEARTIFACT_DOMAIN:-}}"
CODEARTIFACT_REPOSITORY="${CODEARTIFACT_REPOSITORY:-${AWS_CODEARTIFACT_REPOSITORY:-}}"
CODEARTIFACT_DOMAIN_OWNER="${CODEARTIFACT_DOMAIN_OWNER:-${AWS_CODEARTIFACT_DOMAIN_OWNER:-}}"
CODEARTIFACT_CREATE="${CODEARTIFACT_CREATE:-${AWS_CODEARTIFACT_CREATE:-1}}"
CODEARTIFACT_ROLE_ARN="${CODEARTIFACT_ROLE_ARN:-${AWS_CODEARTIFACT_ROLE_ARN:-}}"
CODEARTIFACT_REPOSITORY_DESCRIPTION="${CODEARTIFACT_REPOSITORY_DESCRIPTION:-Private Maven repository}"
CODEARTIFACT_ENV_PREFIX="${CODEARTIFACT_ENV_PREFIX:-MAVEN_REPOSITORY}"
CODEARTIFACT_REPOSITORY_NAME="${CODEARTIFACT_REPOSITORY_NAME:-codeartifact}"
CODEARTIFACT_BOOTSTRAP_PUBLISHER="${CODEARTIFACT_BOOTSTRAP_PUBLISHER:-auto}"
CODEARTIFACT_PUBLISHER_USER="${CODEARTIFACT_PUBLISHER_USER:-codeartifact-maven-publisher}"
CODEARTIFACT_PUBLISHER_PROFILE="${CODEARTIFACT_PUBLISHER_PROFILE:-$CODEARTIFACT_PUBLISHER_USER}"
CODEARTIFACT_PUBLISHER_POLICY_NAME="${CODEARTIFACT_PUBLISHER_POLICY_NAME:-CodeArtifactMavenPublisherPolicy}"
CODEARTIFACT_UPSTREAM_REPOSITORIES="${CODEARTIFACT_UPSTREAM_REPOSITORIES:-}"
CODEARTIFACT_EXTERNAL_CONNECTIONS="${CODEARTIFACT_EXTERNAL_CONNECTIONS:-}"
CODEARTIFACT_VERIFY="${CODEARTIFACT_VERIFY:-1}"

usage() {
    cat <<'USAGE'
Usage:
  CODEARTIFACT_DOMAIN=my-domain \
  CODEARTIFACT_REPOSITORY=my-maven-repo \
  scripts/setup-codeartifact-maven-env.sh

Required:
  CODEARTIFACT_DOMAIN       CodeArtifact domain name.
  CODEARTIFACT_REPOSITORY   CodeArtifact repository name.

Optional:
  ENV_FILE                              File to update. Defaults to <repo>/.env.
  AWS_REGION / AWS_DEFAULT_REGION       AWS region. Defaults to us-east-1.
  AWS_PROFILE                           AWS CLI profile to use for setup.
  CODEARTIFACT_DOMAIN_OWNER             AWS account ID that owns the domain.
  CODEARTIFACT_CREATE                   Create missing resources. Defaults to 1.
  CODEARTIFACT_ROLE_ARN                 Role to assume before setup.
  CODEARTIFACT_REPOSITORY_DESCRIPTION   Description for newly created repositories.
  CODEARTIFACT_ENV_PREFIX               Output env prefix. Defaults to MAVEN_REPOSITORY.
  CODEARTIFACT_REPOSITORY_NAME          Value for <prefix>_NAME. Defaults to codeartifact.
  CODEARTIFACT_BOOTSTRAP_PUBLISHER      auto, 1, or 0. Defaults to auto.
  CODEARTIFACT_PUBLISHER_USER           IAM user to create/use when token auth is unavailable.
  CODEARTIFACT_PUBLISHER_PROFILE        Local AWS profile for that IAM user.
  CODEARTIFACT_UPSTREAM_REPOSITORIES    Comma-separated upstream repository names.
  CODEARTIFACT_EXTERNAL_CONNECTIONS     Comma-separated public connections, e.g. public:maven-central.
  CODEARTIFACT_VERIFY                   Verify endpoint/token after setup. Defaults to 1.

Compatibility:
  AWS_CODEARTIFACT_* variables are still accepted as aliases.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

if [[ -z "$CODEARTIFACT_DOMAIN" || -z "$CODEARTIFACT_REPOSITORY" ]]; then
    usage >&2
    exit 1
fi

if ! command -v aws >/dev/null 2>&1; then
    echo "aws CLI is required. Install and authenticate AWS CLI before running this script." >&2
    exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
    touch "$ENV_FILE"
fi

aws_args=(--region "$AWS_REGION")
if [[ -n "$AWS_PROFILE" ]]; then
    aws_args+=(--profile "$AWS_PROFILE")
fi

set_env_value() {
    local key="$1"
    local value="$2"
    local escaped
    escaped="$(printf '%s' "$value" | perl -0pe 's/\\/\\\\/g; s/\$/\\\$/g; s/"/\\"/g; s/\|/\\|/g')"

    if grep -qE "^${key}=" "$ENV_FILE"; then
        perl -0pi -e "s|^${key}=.*$|${key}=\"${escaped}\"|m" "$ENV_FILE"
    else
        printf '%s="%s"\n' "$key" "$escaped" >> "$ENV_FILE"
    fi
}

json_escape() {
    printf '%s' "$1" | perl -0pe 's/\\/\\\\/g; s/"/\\"/g; s/\n/\\n/g'
}

trim() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
}

csv_to_json_array() {
    local csv="$1"
    local first=1
    printf '['
    IFS=',' read -ra values <<< "$csv"
    for raw in "${values[@]}"; do
        local value
        value="$(trim "$raw")"
        [[ -z "$value" ]] && continue
        if [[ "$first" -eq 0 ]]; then
            printf ','
        fi
        first=0
        printf '{"repositoryName":"%s"}' "$(json_escape "$value")"
    done
    printf ']'
}

external_connection_repo_name() {
    local connection="$1"
    local suffix
    suffix="$(printf '%s' "$connection" | tr '[:upper:]' '[:lower:]' | sed -E 's/^public://; s/[^a-z0-9._-]+/-/g')"
    printf '%s-%s' "$CODEARTIFACT_REPOSITORY" "$suffix"
}

current_account_id() {
    aws "${aws_args[@]}" sts get-caller-identity --query Account --output text
}

current_arn() {
    aws "${aws_args[@]}" sts get-caller-identity --query Arn --output text
}

assume_setup_role_if_configured() {
    if [[ -z "$CODEARTIFACT_ROLE_ARN" ]]; then
        return
    fi

    read -r AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN < <(
        aws "${aws_args[@]}" sts assume-role \
            --role-arn "$CODEARTIFACT_ROLE_ARN" \
            --role-session-name codeartifact-maven-setup \
            --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
            --output text
    )
    export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
    unset AWS_PROFILE
    aws_args=(--region "$AWS_REGION")
}

domain_args=()
set_domain_args() {
    domain_args=(--domain "$CODEARTIFACT_DOMAIN" --domain-owner "$CODEARTIFACT_DOMAIN_OWNER")
}

ensure_domain_and_repositories() {
    if [[ "$CODEARTIFACT_CREATE" != "1" && "$CODEARTIFACT_CREATE" != "true" ]]; then
        return
    fi

    if ! aws "${aws_args[@]}" codeartifact describe-domain "${domain_args[@]}" >/dev/null 2>&1; then
        aws "${aws_args[@]}" codeartifact create-domain --domain "$CODEARTIFACT_DOMAIN" >/dev/null
        echo "Created CodeArtifact domain: $CODEARTIFACT_DOMAIN"
    fi

    local upstream_json="[]"
    local combined_upstreams="$CODEARTIFACT_UPSTREAM_REPOSITORIES"

    if [[ -n "$CODEARTIFACT_EXTERNAL_CONNECTIONS" ]]; then
        IFS=',' read -ra connections <<< "$CODEARTIFACT_EXTERNAL_CONNECTIONS"
        for raw_connection in "${connections[@]}"; do
            local connection upstream_repo
            connection="$(trim "$raw_connection")"
            [[ -z "$connection" ]] && continue
            upstream_repo="$(external_connection_repo_name "$connection")"

            if ! aws "${aws_args[@]}" codeartifact describe-repository "${domain_args[@]}" --repository "$upstream_repo" >/dev/null 2>&1; then
                aws "${aws_args[@]}" codeartifact create-repository \
                    "${domain_args[@]}" \
                    --repository "$upstream_repo" \
                    --description "External upstream for $connection" >/dev/null
                echo "Created CodeArtifact upstream repository: $upstream_repo"
            fi

            if aws "${aws_args[@]}" codeartifact associate-external-connection \
                "${domain_args[@]}" \
                --repository "$upstream_repo" \
                --external-connection "$connection" >/dev/null 2>&1; then
                echo "Associated external connection $connection with $upstream_repo"
            fi

            if [[ -z "$combined_upstreams" ]]; then
                combined_upstreams="$upstream_repo"
            else
                combined_upstreams="$combined_upstreams,$upstream_repo"
            fi
        done
    fi

    if [[ -n "$combined_upstreams" ]]; then
        upstream_json="$(csv_to_json_array "$combined_upstreams")"
    fi

    if ! aws "${aws_args[@]}" codeartifact describe-repository "${domain_args[@]}" --repository "$CODEARTIFACT_REPOSITORY" >/dev/null 2>&1; then
        aws "${aws_args[@]}" codeartifact create-repository \
            "${domain_args[@]}" \
            --repository "$CODEARTIFACT_REPOSITORY" \
            --description "$CODEARTIFACT_REPOSITORY_DESCRIPTION" \
            --upstreams "$upstream_json" >/dev/null
        echo "Created CodeArtifact repository: $CODEARTIFACT_REPOSITORY"
    else
        if ! aws "${aws_args[@]}" codeartifact update-repository \
            "${domain_args[@]}" \
            --repository "$CODEARTIFACT_REPOSITORY" \
            --description "$CODEARTIFACT_REPOSITORY_DESCRIPTION" \
            --upstreams "$upstream_json" >/dev/null 2>&1; then
            echo "Skipped repository metadata update for $CODEARTIFACT_REPOSITORY; current AWS identity can use the repository but cannot update it."
        fi
    fi
}

write_publisher_policy_file() {
    local file="$1"
    cat > "$file" <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "codeartifact:GetAuthorizationToken",
        "codeartifact:GetRepositoryEndpoint",
        "codeartifact:ReadFromRepository",
        "codeartifact:PublishPackageVersion",
        "codeartifact:PutPackageMetadata",
        "codeartifact:DescribeDomain",
        "codeartifact:DescribeRepository",
        "codeartifact:CreateRepository",
        "codeartifact:CreateDomain",
        "codeartifact:UpdateRepository",
        "codeartifact:ListRepositories",
        "codeartifact:ListDomains",
        "codeartifact:ListPackageVersions",
        "codeartifact:DescribePackageVersion",
        "codeartifact:ListPackageVersionAssets",
        "codeartifact:GetPackageVersionAsset",
        "codeartifact:AssociateExternalConnection"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": "sts:GetServiceBearerToken",
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "sts:AWSServiceName": "codeartifact.amazonaws.com"
        }
      }
    }
  ]
}
JSON
}

can_get_codeartifact_token() {
    aws "${aws_args[@]}" codeartifact get-authorization-token \
        "${domain_args[@]}" \
        --query authorizationToken \
        --output text >/dev/null 2>&1
}

bootstrap_publisher_user() {
    local setup_mode="$CODEARTIFACT_BOOTSTRAP_PUBLISHER"
    if [[ "$setup_mode" == "0" || "$setup_mode" == "false" ]]; then
        return 1
    fi

    if aws configure get aws_access_key_id --profile "$CODEARTIFACT_PUBLISHER_PROFILE" >/dev/null 2>&1; then
        AWS_PROFILE="$CODEARTIFACT_PUBLISHER_PROFILE"
        aws_args=(--region "$AWS_REGION" --profile "$AWS_PROFILE")
        echo "Using existing AWS profile: $CODEARTIFACT_PUBLISHER_PROFILE"
        return 0
    fi

    if [[ "$setup_mode" != "1" && "$setup_mode" != "true" && "$setup_mode" != "auto" ]]; then
        return 1
    fi

    local policy_file access_key_count
    policy_file="$(mktemp)"
    write_publisher_policy_file "$policy_file"

    if ! aws iam get-user --user-name "$CODEARTIFACT_PUBLISHER_USER" >/dev/null 2>&1; then
        aws iam create-user --user-name "$CODEARTIFACT_PUBLISHER_USER" >/dev/null
        echo "Created IAM user: $CODEARTIFACT_PUBLISHER_USER"
    fi

    aws iam put-user-policy \
        --user-name "$CODEARTIFACT_PUBLISHER_USER" \
        --policy-name "$CODEARTIFACT_PUBLISHER_POLICY_NAME" \
        --policy-document "file://$policy_file" >/dev/null
    rm -f "$policy_file"

    access_key_count="$(
        aws iam list-access-keys \
            --user-name "$CODEARTIFACT_PUBLISHER_USER" \
            --query 'length(AccessKeyMetadata)' \
            --output text
    )"

    if [[ "$access_key_count" -ge 2 ]]; then
        echo "IAM user $CODEARTIFACT_PUBLISHER_USER already has two access keys and no local profile $CODEARTIFACT_PUBLISHER_PROFILE." >&2
        echo "Delete an unused access key or configure AWS_PROFILE manually." >&2
        return 1
    fi

    read -r AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY < <(
        aws iam create-access-key \
            --user-name "$CODEARTIFACT_PUBLISHER_USER" \
            --query 'AccessKey.[AccessKeyId,SecretAccessKey]' \
            --output text
    )

    aws configure set aws_access_key_id "$AWS_ACCESS_KEY_ID" --profile "$CODEARTIFACT_PUBLISHER_PROFILE"
    aws configure set aws_secret_access_key "$AWS_SECRET_ACCESS_KEY" --profile "$CODEARTIFACT_PUBLISHER_PROFILE"
    aws configure set region "$AWS_REGION" --profile "$CODEARTIFACT_PUBLISHER_PROFILE"

    AWS_PROFILE="$CODEARTIFACT_PUBLISHER_PROFILE"
    aws_args=(--region "$AWS_REGION" --profile "$AWS_PROFILE")
    echo "Configured AWS profile: $CODEARTIFACT_PUBLISHER_PROFILE"
}

assume_setup_role_if_configured

if [[ -z "$CODEARTIFACT_DOMAIN_OWNER" ]]; then
    CODEARTIFACT_DOMAIN_OWNER="$(current_account_id)"
fi
set_domain_args

ensure_domain_and_repositories

if ! can_get_codeartifact_token; then
    caller="$(current_arn)"
    echo "Current AWS identity cannot fetch a CodeArtifact authorization token: $caller"
    echo "Attempting automatic publisher profile setup..."
    bootstrap_publisher_user
    set_domain_args

    if ! can_get_codeartifact_token; then
        echo "Unable to fetch a CodeArtifact token after publisher setup." >&2
        echo "Ensure the identity can call codeartifact:GetAuthorizationToken and sts:GetServiceBearerToken." >&2
        exit 1
    fi
fi

MAVEN_REPOSITORY_URL="$(
    aws "${aws_args[@]}" codeartifact get-repository-endpoint \
        "${domain_args[@]}" \
        --repository "$CODEARTIFACT_REPOSITORY" \
        --format maven \
        --query repositoryEndpoint \
        --output text
)"

MAVEN_REPOSITORY_PASSWORD="$(
    aws "${aws_args[@]}" codeartifact get-authorization-token \
        "${domain_args[@]}" \
        --query authorizationToken \
        --output text
)"

if [[ "$CODEARTIFACT_VERIFY" == "1" || "$CODEARTIFACT_VERIFY" == "true" ]]; then
    aws "${aws_args[@]}" codeartifact describe-repository \
        "${domain_args[@]}" \
        --repository "$CODEARTIFACT_REPOSITORY" >/dev/null
fi

set_env_value "AWS_REGION" "$AWS_REGION"
set_env_value "CODEARTIFACT_DOMAIN" "$CODEARTIFACT_DOMAIN"
set_env_value "CODEARTIFACT_DOMAIN_OWNER" "$CODEARTIFACT_DOMAIN_OWNER"
set_env_value "CODEARTIFACT_REPOSITORY" "$CODEARTIFACT_REPOSITORY"
set_env_value "CODEARTIFACT_CREATE" "$CODEARTIFACT_CREATE"
set_env_value "CODEARTIFACT_REPOSITORY_DESCRIPTION" "$CODEARTIFACT_REPOSITORY_DESCRIPTION"
set_env_value "CODEARTIFACT_ENV_PREFIX" "$CODEARTIFACT_ENV_PREFIX"
set_env_value "CODEARTIFACT_REPOSITORY_NAME" "$CODEARTIFACT_REPOSITORY_NAME"
set_env_value "CODEARTIFACT_BOOTSTRAP_PUBLISHER" "$CODEARTIFACT_BOOTSTRAP_PUBLISHER"
set_env_value "CODEARTIFACT_PUBLISHER_USER" "$CODEARTIFACT_PUBLISHER_USER"
set_env_value "CODEARTIFACT_PUBLISHER_PROFILE" "$CODEARTIFACT_PUBLISHER_PROFILE"
set_env_value "CODEARTIFACT_PUBLISHER_POLICY_NAME" "$CODEARTIFACT_PUBLISHER_POLICY_NAME"
if [[ -n "$CODEARTIFACT_UPSTREAM_REPOSITORIES" ]]; then
    set_env_value "CODEARTIFACT_UPSTREAM_REPOSITORIES" "$CODEARTIFACT_UPSTREAM_REPOSITORIES"
fi
if [[ -n "$CODEARTIFACT_EXTERNAL_CONNECTIONS" ]]; then
    set_env_value "CODEARTIFACT_EXTERNAL_CONNECTIONS" "$CODEARTIFACT_EXTERNAL_CONNECTIONS"
fi
if [[ -n "$CODEARTIFACT_ROLE_ARN" ]]; then
    set_env_value "CODEARTIFACT_ROLE_ARN" "$CODEARTIFACT_ROLE_ARN"
fi
if [[ -n "$AWS_PROFILE" ]]; then
    set_env_value "AWS_PROFILE" "$AWS_PROFILE"
fi
set_env_value "${CODEARTIFACT_ENV_PREFIX}_NAME" "$CODEARTIFACT_REPOSITORY_NAME"
set_env_value "${CODEARTIFACT_ENV_PREFIX}_URL" "$MAVEN_REPOSITORY_URL"
set_env_value "${CODEARTIFACT_ENV_PREFIX}_USERNAME" "aws"
set_env_value "${CODEARTIFACT_ENV_PREFIX}_PASSWORD" "$MAVEN_REPOSITORY_PASSWORD"

echo "Configured $ENV_FILE for AWS CodeArtifact Maven publishing."
echo "Repository endpoint: $MAVEN_REPOSITORY_URL"
echo "Auth token stored in ${CODEARTIFACT_ENV_PREFIX}_PASSWORD. Refresh it when it expires."
