#!/usr/bin/env bash
# Logging + presentation helpers — sourced by install.sh and standalone scripts.
# shellcheck disable=SC2034

# Colours are emitted only when stdout is a TTY, so logs piped to a file or CI
# stay clean. tput failures (dumb terminals) degrade gracefully to no colour.
if [[ -t 1 ]]; then
    _BOLD=$(tput bold    2>/dev/null || true)
    _DIM=$(tput dim      2>/dev/null || true)
    _RESET=$(tput sgr0   2>/dev/null || true)
    _GREEN=$(tput setaf 2 2>/dev/null || true)
    _YELLOW=$(tput setaf 3 2>/dev/null || true)
    _RED=$(tput setaf 1   2>/dev/null || true)
    _CYAN=$(tput setaf 6  2>/dev/null || true)
    _BLUE=$(tput setaf 4  2>/dev/null || true)
else
    _BOLD="" _DIM="" _RESET="" _GREEN="" _YELLOW="" _RED="" _CYAN="" _BLUE=""
fi

# Step counter — set INSTALL_TOTAL_STEPS before the run for "▶ [n/N]" prefixes.
_STEP_NO=0
INSTALL_TOTAL_STEPS="${INSTALL_TOTAL_STEPS:-0}"

log_info()    { echo "${_CYAN}${_BOLD}[INFO]${_RESET}  $*"; }
log_success() { echo "${_GREEN}${_BOLD}[ OK ]${_RESET}  $*"; }
log_warn()    { echo "${_YELLOW}${_BOLD}[WARN]${_RESET}  $*" >&2; }
log_error()   { echo "${_RED}${_BOLD}[FAIL]${_RESET}  $*" >&2; }
log_detail()  { echo "        ${_DIM}$*${_RESET}"; }

# A numbered section header. Increments the global step counter.
log_step() {
    _STEP_NO=$(( _STEP_NO + 1 ))
    echo ""
    if [[ "${INSTALL_TOTAL_STEPS}" -gt 0 ]]; then
        echo "${_BOLD}${_BLUE}▶  [${_STEP_NO}/${INSTALL_TOTAL_STEPS}]${_RESET} ${_BOLD}$*${_RESET}"
    else
        echo "${_BOLD}${_BLUE}▶${_RESET}  ${_BOLD}$*${_RESET}"
    fi
}

# A full-width rule, used to frame banners.
log_rule() {
    printf '%s' "${_DIM}"
    printf '─%.0s' $(seq 1 "${COLUMNS:-72}") 2>/dev/null || printf '────────────────────────────────────────────────────────────────────────'
    printf '%s\n' "${_RESET}"
}

# A boxed title banner used at the top of the installer.
log_banner() {
    echo ""
    log_rule
    echo "  ${_BOLD}$1${_RESET}"
    [[ -n "${2:-}" ]] && echo "  ${_DIM}$2${_RESET}"
    log_rule
}

# Tee all subsequent stdout/stderr to a timestamped log file so a failed run
# leaves something to inspect. The terminal copy keeps its colours; the file
# copy has ANSI codes stripped so it stays readable in editors and CI artifacts.
#
# Call once, early, from a top-level entrypoint (install.sh) — NOT from shared
# libraries, so day-to-day tools like `make migrate` don't litter the tree with
# log files. Exposes $log_file for the error trap and summary to reference.
#
# The .log extension matches the existing *.log rule in .gitignore.
log_to_file() {
    [[ -n "${_LOG_TO_FILE_STARTED:-}" ]] && return 0
    _LOG_TO_FILE_STARTED=1
    local prefix="${1:-apollo_install}"
    log_file="${prefix}-$(date +'%Y-%m-%d_%H-%M-%S').log"
    local _esc
    _esc=$(printf '\033')
    # Strip both CSI sequences (colours: ESC[...m) and charset-designation
    # sequences (ESC(B, emitted by some terminals' `tput sgr0`) so the file copy
    # stays plain text. Works with BSD (macOS) and GNU sed.
    exec > >(tee >(sed -E -e "s/${_esc}\[[0-9;]*[A-Za-z]//g" -e "s/${_esc}\([0-9A-Za-z]//g" >> "$log_file")) 2>&1
}
