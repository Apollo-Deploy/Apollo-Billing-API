# ══════════════════════════════════════════════════════════════════════════════
# Apollo Billing — Makefile
# ══════════════════════════════════════════════════════════════════════════════

SHELL := /bin/bash
.DELETE_ON_ERROR:
.DEFAULT_GOAL := help

# ── Configuration ─────────────────────────────────────────────────────────────
export JAVA_HOME ?= /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

BILLING_PORT       ?= 3040
DEV                ?= 1
TEST               ?= *
SDK_VERSION        ?=
IMAGE_TAG          ?= latest

BILLING_MAIN_CLASS := com.apollodeploy.billing.BillingApplicationKt

# Load .env, then .env.local for per-developer overrides (later wins).
-include .env
-include .env.local
export

# ── Build ─────────────────────────────────────────────────────────────────────

.PHONY: build shadow clean

## build         — Compile and assemble the project
build:
	./gradlew build

## shadow        — Build a fat JAR for deployment
shadow:
	./gradlew shadowJar

## clean         — Delete all build artifacts
clean:
	./gradlew clean

# ── Run ───────────────────────────────────────────────────────────────────────

.PHONY: stop run dev run-prod

## stop          — Kill running Billing JVM and free port $(BILLING_PORT)
stop:
	@pids=$$(pgrep -f '$(BILLING_MAIN_CLASS)' 2>/dev/null || true); \
	if [ -n "$$pids" ]; then \
		echo "Stopping: $$pids"; kill $$pids 2>/dev/null || true; sleep 1; \
		pids=$$(pgrep -f '$(BILLING_MAIN_CLASS)' 2>/dev/null || true); \
		[ -n "$$pids" ] && kill -9 $$pids 2>/dev/null || true; \
	fi
	@pids=$$(lsof -tiTCP:$(BILLING_PORT) -sTCP:LISTEN 2>/dev/null || true); \
	[ -n "$$pids" ] && kill $$pids 2>/dev/null || true

## run           — Start locally (DEV=1 for dev mode, DEV=0 for prod)
run: stop
	@if [ "$(DEV)" = "1" ]; then \
		echo "→ development mode"; \
		APOLLO_BILLING_ENV=development ./gradlew clean run -Dio.ktor.development=true; \
	else \
		echo "→ production mode"; \
		APOLLO_BILLING_ENV=production ./gradlew clean run -Dio.ktor.development=false; \
	fi

## dev           — Start with hot-reload (Gradle continuous)
dev: stop
	APOLLO_BILLING_ENV=development ./gradlew run --continuous -Dio.ktor.development=true

## run-prod      — Start in production mode
run-prod: stop
	APOLLO_BILLING_ENV=production ./gradlew clean run -Dio.ktor.development=false

# ── Test ──────────────────────────────────────────────────────────────────────

.PHONY: test test-unit test-watch

## test          — Run the full test suite
test:
	./gradlew test

## test-unit     — Run a specific test (TEST=com.apollodeploy.billing.*)
test-unit:
	./gradlew test --tests "$(TEST)"

## test-watch    — Re-run tests on source changes
test-watch:
	./gradlew test --continuous

# ── Terraform (local dev) ─────────────────────────────────────────────────────
#
# The full local stack (infra + platform + billing + signal) is managed by
# Terraform under APIs/infra/terraform/local/.

.PHONY: tf-up tf-down tf-logs

## tf-up         — Apply Terraform to start/update the local stack
tf-up:
	@cd ../infra/terraform/local && terraform apply -auto-approve

## tf-down       — Destroy the local Terraform stack
tf-down:
	@cd ../infra/terraform/local && terraform destroy -auto-approve

## tf-logs       — Tail billing container logs
tf-logs:
	docker logs -f apollo-billing

# ── Database ──────────────────────────────────────────────────────────────────

.PHONY: migrate db-connect provision-reader

## migrate       — Apply pending SQL migrations
migrate:
	./scripts/migrate.sh

## db-connect    — Open psql shell to platform database
db-connect:
	PGPASSWORD="$$(grep -E '^PLATFORM_DB_PASSWORD=' .env | cut -d= -f2-)" \
	psql -h 127.0.0.1 -p "$${PLATFORM_DB_PORT:-5432}" \
		-U "$${PLATFORM_DB_USER:-billing_app}" \
		-d "$${PLATFORM_DB_NAME:-apollo_deploy_platform}"

## provision-reader — Create read-only billing reader role
provision-reader:
	scripts/provision/provision-billing-reader.sh

# ── Code Quality ──────────────────────────────────────────────────────────────

.PHONY: lint lint-fix check

## lint          — Run ktlint checks
lint:
	./gradlew ktlintCheck

## lint-fix      — Auto-fix ktlint violations
lint-fix:
	./gradlew ktlintFormat

## check         — lint + test
check: lint test

# ── SDK ───────────────────────────────────────────────────────────────────────

.PHONY: sdk sdk-publish sdk-publish-kotlin sdk-publish-typescript require-sdk-version

## sdk           — Export OpenAPI and generate all SDKs locally
sdk:
	TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-publish   — Export OpenAPI, generate all SDKs, and publish them (SDK_VERSION required)
sdk-publish: require-sdk-version
	TESSERACT_PUBLISH=1 TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-publish-kotlin — Publish only the Kotlin/JVM SDK to Maven Central (SDK_VERSION required)
sdk-publish-kotlin: require-sdk-version
	TESSERACT_PUBLISH=1 TESSERACT_PUBLISH_TARGETS=kotlin TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-publish-typescript — Publish only the TypeScript SDK to npm (SDK_VERSION required)
sdk-publish-typescript: require-sdk-version
	TESSERACT_PUBLISH=1 TESSERACT_PUBLISH_TARGETS=typescript TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

require-sdk-version:
	@[ -n "$(SDK_VERSION)" ] || { echo "Set SDK_VERSION: make sdk-publish SDK_VERSION=1.0.8"; exit 1; }

# ── Misc ──────────────────────────────────────────────────────────────────────

.PHONY: openapi polar-sandbox polar-production

## openapi       — Export OpenAPI spec to docs/openapi.json
openapi:
	@mkdir -p docs
	@curl -fsS http://127.0.0.1:$(BILLING_PORT)/docs/openapi.json > docs/openapi.json
	@echo "→ docs/openapi.json"

## polar-sandbox — Setup Signal products in Polar sandbox
polar-sandbox:
	bash scripts/polar/setup-signal.sh --env sandbox

## polar-production — Setup Signal products in Polar production
polar-production:
	bash scripts/polar/setup-signal.sh --env production

# ── Help ──────────────────────────────────────────────────────────────────────

.PHONY: help

## help          — Show this help
help:
	@printf '\nUsage: make \033[36m<target>\033[0m\n\n'
	@grep -hE '^## ' $(MAKEFILE_LIST) | sed 's/^## //' \
		| awk -F ' — ' '{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'
	@printf '\n'
