.PHONY: build shadow clean \
        stop run dev \
        test test-unit test-watch \
        ps install \
        container-up container-dev container-build container-down container-logs container-status \
        debug debug-health debug-ports debug-db debug-redis debug-net \
        migrate db-connect \
        lint lint-fix check \
        sdk sdk-ts sdk-java \
        sdk-publish sdk-publish-java sdk-publish-ts sdk-upload \
        sdk-codeartifact sdk-manifest require-sdk-version \
        openapi polar-sandbox provision-reader help

# Use Java 21 to avoid Kotlin/Gradle incompatibility with Java 25
export JAVA_HOME := /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# Main class used to identify Billing JVM processes for graceful stop
BILLING_MAIN_CLASS := com.apollodeploy.billing.BillingApplicationKt
BILLING_PORT       ?= 3040
DEV                ?= 1
# For test-unit: make test-unit TEST=com.apollodeploy.billing.feature.enforce.*
TEST               ?= *
# For SDK publishing: make sdk-publish SDK_VERSION=1.0.8
SDK_VERSION        ?=

# Load .env if present (silently skipped when absent; all vars exported to sub-makes)
-include .env
export

PLATFORM_DB_NAME ?= apollo_deploy_platform
PLATFORM_DB_USER ?= billing_app

# ──────────────────────────────────────────────────────────────────────────────
# Build
# ──────────────────────────────────────────────────────────────────────────────

## build         : Compile and assemble the project
build:
	./gradlew build

## shadow         : Build a fat JAR for deployment
shadow:
	./gradlew shadowJar

## clean          : Delete all build artifacts
clean:
	./gradlew clean

# ──────────────────────────────────────────────────────────────────────────────
# Run
# ──────────────────────────────────────────────────────────────────────────────

## stop           : Kill any running Billing JVM and free port $(BILLING_PORT)
stop:
	@pids=$$(pgrep -f '$(BILLING_MAIN_CLASS)' 2>/dev/null || true); \
	if [ -n "$$pids" ]; then \
		echo "Stopping Billing processes: $$pids"; \
		kill $$pids 2>/dev/null || true; \
		sleep 1; \
		pids=$$(pgrep -f '$(BILLING_MAIN_CLASS)' 2>/dev/null || true); \
		if [ -n "$$pids" ]; then \
			echo "Force-stopping: $$pids"; \
			kill -9 $$pids 2>/dev/null || true; \
		fi; \
	else \
		echo "No stale Billing processes found"; \
	fi
	@port_pids=$$(lsof -tiTCP:$(BILLING_PORT) -sTCP:LISTEN 2>/dev/null || true); \
	if [ -n "$$port_pids" ]; then \
		echo "Stopping process on port $(BILLING_PORT): $$port_pids"; \
		kill $$port_pids 2>/dev/null || true; \
	fi

## run            : Start the billing server locally (DEV=1 for development mode, DEV=0 for production)
run: stop
	@if [ "$(DEV)" = "1" ]; then \
		echo "Running in development mode (APOLLO_BILLING_ENV=development, ktor.development=true)"; \
		APOLLO_BILLING_ENV=development ./gradlew clean run -Dio.ktor.development=true; \
	else \
		echo "Running in production mode (APOLLO_BILLING_ENV=production, ktor.development=false)"; \
		APOLLO_BILLING_ENV=production ./gradlew clean run -Dio.ktor.development=false; \
	fi

## dev            : Start with hot-reload (Gradle --continuous)
dev: stop
	APOLLO_BILLING_ENV=development ./gradlew run --continuous -Dio.ktor.development=true

# ──────────────────────────────────────────────────────────────────────────────
# Testing
# ──────────────────────────────────────────────────────────────────────────────

## test           : Run the full test suite
test:
	./gradlew test

## test-unit      : Run a specific test class or pattern  (TEST=com.apollodeploy.billing.*)
test-unit:
	./gradlew test --tests "$(TEST)"

## test-watch     : Re-run tests on every source change
test-watch:
	./gradlew test --continuous

# ──────────────────────────────────────────────────────────────────────────────
# Container (auto-detects Apple container CLI on M-chip or falls back to Docker)
# ──────────────────────────────────────────────────────────────────────────────

## ps             : Show running container status
ps:
	./scripts/container-dev.sh --status

## install        : First-time setup (volumes, image build, migrations)
install:
	./install.sh

## container-up   : Build & start the billing service (auto-detects runtime)
container-up:
	./scripts/container-dev.sh

## container-dev  : Build & start in development mode (verbose errors, stack traces)
container-dev:
	./scripts/container-dev.sh --dev

## container-build : Build the billing container image
container-build:
	./scripts/container-dev.sh --build

## container-down : Stop the billing container
container-down:
	./scripts/container-dev.sh --stop

## container-logs : Tail billing container logs
container-logs:
	./scripts/container-dev.sh --logs

## container-status : Show billing container status
container-status:
	./scripts/container-dev.sh --status

# ──────────────────────────────────────────────────────────────────────────────
# Debugging
# ──────────────────────────────────────────────────────────────────────────────

## debug          : Run full diagnostic report (doctor)
debug:
	./scripts/container-debug.sh doctor

## debug-health   : Check all service health endpoints
debug-health:
	./scripts/container-debug.sh health

## debug-ports    : Show what's listening on expected ports
debug-ports:
	./scripts/container-debug.sh ports

## debug-db       : Test database connectivity
debug-db:
	./scripts/container-debug.sh db

## debug-redis    : Test Redis connectivity
debug-redis:
	./scripts/container-debug.sh redis

## debug-net      : Network diagnostics
debug-net:
	./scripts/container-debug.sh net

# ──────────────────────────────────────────────────────────────────────────────
# Database
# ──────────────────────────────────────────────────────────────────────────────

## migrate        : Apply pending SQL migrations from scripts/migrations/
migrate:
	@echo "Applying migrations in apollo-platform-postgres as $(PLATFORM_DB_USER)@$(PLATFORM_DB_NAME)..."
	@count=0; \
	for f in $$(ls scripts/migrations/*.psql 2>/dev/null | sort); do \
		echo "  Applying $$f..."; \
		docker exec -i \
			-e PGPASSWORD="$$(grep -E '^PLATFORM_DB_PASSWORD=' .env | cut -d= -f2-)" \
			apollo-platform-postgres \
			psql -U "$(PLATFORM_DB_USER)" -d "$(PLATFORM_DB_NAME)" -v ON_ERROR_STOP=1 \
			< "$$f" \
			|| { echo "Migration failed: $$f"; exit 1; }; \
		count=$$((count+1)); \
	done; \
	if [ $$count -eq 0 ]; then echo "No migrations found."; else echo "$$count migration(s) applied."; fi

## db-connect     : Open a psql shell to the platform Postgres (port-mapped to localhost)
db-connect:
	PGPASSWORD="$$(grep -E '^PLATFORM_DB_PASSWORD=' .env | cut -d= -f2-)" \
	psql -h 127.0.0.1 -p "$${PLATFORM_DB_PORT:-5432}" -U "$(PLATFORM_DB_USER)" -d "$(PLATFORM_DB_NAME)"

## provision-reader : Create the read-only billing reader role across both databases
provision-reader:
	scripts/provision/provision-billing-reader.sh

# ──────────────────────────────────────────────────────────────────────────────
# Code quality
# ──────────────────────────────────────────────────────────────────────────────

## lint           : Run ktlint checks
lint:
	./gradlew ktlintCheck

## lint-fix       : Auto-fix ktlint violations
lint-fix:
	./gradlew ktlintFormat

## check          : Run lint + tests
check: lint test

# ──────────────────────────────────────────────────────────────────────────────
# Misc
# ──────────────────────────────────────────────────────────────────────────────

## sdk            : Generate TypeScript and Java/JVM billing SDKs with Tesseract
sdk:
	TESSERACT_TARGETS=typescript,java TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-ts         : Generate only the TypeScript billing SDK
sdk-ts:
	TESSERACT_TARGETS=typescript TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-java       : Generate only the Java/JVM billing SDK
sdk-java:
	TESSERACT_TARGETS=java TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-publish    : Generate and publish TypeScript + Java/JVM SDKs (SDK_VERSION=1.0.x required)
sdk-publish: require-sdk-version sdk-codeartifact
	TESSERACT_PUBLISH=1 TESSERACT_TARGETS=typescript,java TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-publish-java : Generate and publish only the Java/JVM SDK (SDK_VERSION=1.0.x required)
sdk-publish-java: require-sdk-version sdk-codeartifact
	TESSERACT_PUBLISH=1 TESSERACT_TARGETS=java TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-publish-ts : Generate and publish only the TypeScript SDK (SDK_VERSION=1.0.x required)
sdk-publish-ts: require-sdk-version
	TESSERACT_PUBLISH=1 TESSERACT_TARGETS=typescript TESSERACT_PACKAGE_VERSION="$(SDK_VERSION)" scripts/generate-sdk.sh

## sdk-codeartifact : Configure .env for AWS CodeArtifact Maven publishing
sdk-codeartifact:
	scripts/setup-codeartifact-maven-env.sh

require-sdk-version:
	@if [ -z "$(SDK_VERSION)" ]; then \
		echo "Set SDK_VERSION, for example: make sdk-publish SDK_VERSION=1.0.8"; \
		exit 1; \
	fi

## sdk-manifest   : Export the Tesseract sdk-manifold manifest only
sdk-manifest:
	TESSERACT_MANIFEST_ONLY=1 scripts/generate-sdk.sh

## openapi        : Export the OpenAPI spec to docs/openapi.json
openapi:
	@mkdir -p docs
	@curl -fsS http://127.0.0.1:$(BILLING_PORT)/docs/openapi.json > docs/openapi.json
	@echo "Exported docs/openapi.json from http://127.0.0.1:$(BILLING_PORT)/docs/openapi.json"

## polar-sandbox  : Create/update Signal products, meters, and benefits in Polar sandbox
polar-sandbox:
	scripts/polar/setup-signal-sandbox.sh

## help           : List all targets with descriptions
help:
	@grep -h -E '^## ' $(MAKEFILE_LIST) \
		| sed 's/^## //' \
		| awk -F ' : ' '{printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
