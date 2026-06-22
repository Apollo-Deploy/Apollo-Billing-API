#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Print the post-install summary.
# ─────────────────────────────────────────────────────────────────────────────

echo "${_group}Setup complete ..."

BIND=$(get_env_value BILLING_BIND)
BIND="${BIND:-0.0.0.0:443}"
PORT="${BIND##*:}"

POLAR_KEY=$(get_env_value POLAR_API_KEY)
POLAR_WEBHOOK=$(get_env_value POLAR_WEBHOOK_SECRET)
PLATFORM_NETWORK=$(get_env_value PLATFORM_NETWORK)

echo ""
echo "  ┌─────────────────────────────────────────────────────────────┐"
echo "  │         Apollo Billing — Service Ready                      │"
echo "  └─────────────────────────────────────────────────────────────┘"
echo ""
echo "  Service"
echo "  ───────"
echo "  API (direct)             http://localhost:3040/health"
echo "  API (via platform nginx) http://billing.<your-domain>/health"
echo "  Platform network         ${PLATFORM_NETWORK:-platform_default}"
echo ""
echo "  Polar"
echo "  ─────"
echo "  POLAR_API_KEY          ${POLAR_KEY}"
echo "  POLAR_WEBHOOK_SECRET   ${POLAR_WEBHOOK}"
echo ""
echo "  Commands"
echo "  ────────"
echo "  Start              $DC_CMD up -d"
echo "  Local dev (platform stack must be running):"
echo "    make run          # Start API on host"
echo "    make dev          # Start API with hot-reload"
echo "  Tail logs           $DC_CMD logs -f billing"
echo "  Stop                $DC_CMD down"
echo ""
echo "  NOTE: Postgres and Redis are provided by the platform (apollo-deploy)"
echo "        stack. Ensure PLATFORM_NETWORK in .env matches the platform"
echo "        Docker network name."
echo ""
echo "  Install log  ${log_file}"
echo ""
echo "  ─────────────────────────────────────────────────────────────────"
echo ""

echo "${_endgroup}"
