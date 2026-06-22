#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Apollo Billing — Service Installer
#
#   ./install.sh
#   ./install.sh --non-interactive
#   ./install.sh --skip-build
#   ./install.sh --skip-migrations
#   APOLLO_NO_CACHE=1 ./install.sh
#
# Use --help for full usage information.
# ─────────────────────────────────────────────────────────────────────────────
set -eEuo pipefail
test "${DEBUG:-}" && set -x

umask 022

# Guard: MSYS2 / Git Bash on Windows is not supported
if [[ -n "${MSYSTEM:-}" ]]; then
  echo "ERROR: MSYS2-based shells (e.g. Git Bash) are not supported. Use WSL." >&2
  exit 1
fi

# Guard: must be run from the project root (where docker-compose.yml lives)
if [[ ! -f "docker-compose.yml" ]]; then
  echo "ERROR: Run this script from the apollo-billing-api project root." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/scripts/install" && pwd)"

# ---------------------------------------------------------------------------
# Bootstrap logging first — it sets up tee to the log file
# ---------------------------------------------------------------------------
source "${SCRIPT_DIR}/_logging.sh"

# Shared utilities (env loading, helpers, trap_with_arg)
source "${SCRIPT_DIR}/_lib.sh"

# Parse CLI flags — sets NON_INTERACTIVE, SKIP_BUILD, SKIP_MIGRATIONS
source "${SCRIPT_DIR}/parse-cli.sh" "$@"

# Register the error/signal trap now that helpers are loaded
trap_with_arg cleanup ERR INT TERM EXIT

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
echo ""
echo "================================================================"
echo "       Apollo Billing — Service Installer"
echo "================================================================"
echo ""

# ---------------------------------------------------------------------------
# Pre-flight (no side effects)
# ---------------------------------------------------------------------------
source "${SCRIPT_DIR}/check-requirements.sh"

# ---------------------------------------------------------------------------
# Environment setup
# ---------------------------------------------------------------------------
source "${SCRIPT_DIR}/ensure-env.sh"
source "${SCRIPT_DIR}/generate-secrets.sh"

# ---------------------------------------------------------------------------
# Volumes
# ---------------------------------------------------------------------------
source "${SCRIPT_DIR}/create-volumes.sh"

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
if [[ "${SKIP_BUILD}" == "1" ]]; then
  echo "  Skipping Docker image build (--skip-build)."
else
  source "${SCRIPT_DIR}/build-images.sh"
fi

# ---------------------------------------------------------------------------
# Migrations
# ---------------------------------------------------------------------------
if [[ "${SKIP_MIGRATIONS}" == "1" ]]; then
  echo "  Skipping database migrations (--skip-migrations)."
else
  source "${SCRIPT_DIR}/run-migrations.sh"
fi

# ---------------------------------------------------------------------------
# Start service
# ---------------------------------------------------------------------------
source "${SCRIPT_DIR}/start-service.sh"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
source "${SCRIPT_DIR}/wrap-up.sh"
