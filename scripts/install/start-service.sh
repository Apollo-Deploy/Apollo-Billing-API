#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Start the billing API and wait until it is healthy.
# nginx is owned by the platform stack — no local nginx needed.
# ─────────────────────────────────────────────────────────────────────────────

echo "${_group}Starting billing service ..."

$DC_CMD up -d

wait_for_healthy "apollo-billing-api-billing-1" 120

success "Billing service is up."
echo "${_endgroup}"
