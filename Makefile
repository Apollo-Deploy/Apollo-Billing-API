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
# Terraform. Run these from infra/terraform/environments/local/.

.PHONY: tf-up tf-down tf-logs

## tf-up         — Apply Terraform to start/update the local stack
tf-up:
	@cd ../../infra/terraform/environments/local && terraform apply -auto-approve

## tf-down       — Destroy the local Terraform stack
tf-down:
	@cd ../../infra/terraform/environments/local && terraform destroy -auto-approve

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

.PHONY: sdk sdk-ts sdk-java sdk-publish sdk-publish-java sdk-publish-ts sdk-codeartifact sdk-manifest require-sdk-version

## sdk           — Generate TypeScript + Java SDKs
sdk:
	TESSERACT_TARGETS=typescript,java TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" TESSERACT_COMMAND=@apollo-deploy/tesseract scripts/generate-sdk.sh

## sdk-ts        — Generate TypeScript SDK only
sdk-ts:
	TESSERACT_TARGETS=typescript TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" TESSERACT_COMMAND=@apollo-deploy/tesseract scripts/generate-sdk.sh

## sdk-java      — Generate Java SDK only
sdk-java:
	TESSERACT_TARGETS=java TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" TESSERACT_COMMAND=@apollo-deploy/tesseract scripts/generate-sdk.sh

## sdk-publish   — Publish TypeScript + Java SDKs (SDK_VERSION required)
sdk-publish: require-sdk-version sdk-codeartifact
	TESSERACT_PUBLISH=1 TESSERACT_TARGETS=typescript,java TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-publish-java — Publish Java SDK only
sdk-publish-java: require-sdk-version sdk-codeartifact
	TESSERACT_PUBLISH=1 TESSERACT_TARGETS=java TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-publish-ts — Publish TypeScript SDK only
sdk-publish-ts: require-sdk-version
	TESSERACT_PUBLISH=1 TESSERACT_TARGETS=typescript TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

sdk-codeartifact:
	scripts/setup-codeartifact-maven-env.sh

require-sdk-version:
	@[ -n "$(SDK_VERSION)" ] || { echo "Set SDK_VERSION: make sdk-publish SDK_VERSION=1.0.8"; exit 1; }

## sdk-manifest  — Export Tesseract manifest only
sdk-manifest:
	TESSERACT_MANIFEST_ONLY=1 scripts/generate-sdk.sh

# ── Misc ──────────────────────────────────────────────────────────────────────

.PHONY: openapi polar-sandbox polar-production

## openapi       — Export OpenAPI spec to docs/openapi.json
openapi:
	@mkdir -p docs
	@curl -fsS http://127.0.0.1:$(BILLING_PORT)/docs/openapi.json > docs/openapi.json
	@echo "→ docs/openapi.json"

## polar-sandbox — Setup Signal products in Polar sandbox
polar-sandbox:
	scripts/polar/polar.sh setup --env sandbox --setup both

## polar-production — Setup Signal products in Polar production
polar-production:
	scripts/polar/polar.sh setup --env production --setup both

# ── Help ──────────────────────────────────────────────────────────────────────

.PHONY: help

## help          — Show this help
help:
	@printf '\nUsage: make \033[36m<target>\033[0m\n\n'
	@grep -hE '^## ' $(MAKEFILE_LIST) | sed 's/^## //' \
		| awk -F ' — ' '{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'
	@printf '\n'
