#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Parse command-line arguments.
# ─────────────────────────────────────────────────────────────────────────────

echo "${_group}Parsing command line ..."

show_help() {
  cat <<EOF

Usage: ./install.sh [options]

Install the Apollo Billing Service using Docker Compose.

Options:
  -h, --help              Show this message and exit.
  --non-interactive       Skip all prompts. Copies .env.example if no .env exists, then exits.
  --skip-build            Skip building the Docker image (use existing image).
  --skip-migrations       Skip running database migrations.

Environment variables:
  NON_INTERACTIVE=1       Same as --non-interactive.
  DEBUG=1                 Enable bash -x tracing.

EOF
}

# Defaults
NON_INTERACTIVE="${NON_INTERACTIVE:-0}"
SKIP_BUILD=0
SKIP_MIGRATIONS=0

while (($#)); do
  case "$1" in
  -h | --help)
    show_help
    exit 0
    ;;
  --non-interactive) NON_INTERACTIVE=1 ;;
  --skip-build)      SKIP_BUILD=1 ;;
  --skip-migrations) SKIP_MIGRATIONS=1 ;;
  --)                ;;
  *)
    echo "Unexpected argument: $1. Use --help for usage information."
    exit 1
    ;;
  esac
  shift
done

export NON_INTERACTIVE
export SKIP_BUILD
export SKIP_MIGRATIONS

echo "${_endgroup}"
