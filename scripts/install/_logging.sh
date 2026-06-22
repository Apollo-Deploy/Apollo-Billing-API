#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Logging — tee all output to a timestamped log file.
# ─────────────────────────────────────────────────────────────────────────────

log_file="apollo_billing_install_log-$(date +'%Y-%m-%d_%H-%M-%S').txt"
exec &> >(tee -a "$log_file")

# GitHub Actions group markers vs plain prefix
if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
  _group="::group::"
  _endgroup="::endgroup::"
else
  _group="▶ "
  _endgroup=""
fi

# Colour helpers — silently disabled when stdout is not a TTY
if [[ -t 1 ]]; then
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  BLUE='\033[0;34m'
  BOLD='\033[1m'
  RESET='\033[0m'
else
  RED='' GREEN='' YELLOW='' BLUE='' BOLD='' RESET=''
fi

info()    { echo -e "${BLUE}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
