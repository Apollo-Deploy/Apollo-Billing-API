#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Create Docker volumes required by the billing stack.
#
# NOTE: Postgres, Redis, and nginx are all owned by the platform stack.
#       No billing-specific volumes are needed.
# ─────────────────────────────────────────────────────────────────────────────

echo "${_group}Creating Docker volumes ..."

# No billing-specific volumes required — platform stack owns postgres, redis, nginx.
info "No billing-specific volumes to create."

success "Volumes ready"
echo "${_endgroup}"
