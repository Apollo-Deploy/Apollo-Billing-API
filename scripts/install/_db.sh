#!/usr/bin/env bash
# Database helpers — sourced by scripts that need to talk to Postgres.
#
# Provides:
#   db_resolve_settings  — read connection info from .env into shell vars
#   db_wait_ready        — block until Postgres accepts connections
#   db_apply_migrations  — run .psql files in order, skipping already-applied ones
#
# Requires: _logging.sh and _lib.sh to be sourced first.

# ── Connection resolution ─────────────────────────────────────────────────────

# Reads DB connection details from .env and exports resolved variables.
db_resolve_settings() {
    DB_CONTAINER="$(env_get PLATFORM_DB_HOST)"
    DB_CONTAINER="${DB_CONTAINER:-apollo-platform-postgres}"

    DB_NAME_RESOLVED="$(env_get PLATFORM_DB_NAME)"
    DB_NAME_RESOLVED="${DB_NAME_RESOLVED:-apollo_deploy_platform}"

    # Migration user — always use postgres superuser for DDL privileges.
    # PLATFORM_DB_ROOT_PASSWORD takes priority, then DB_PASSWORD (platform root),
    # then falls back to PLATFORM_DB_PASSWORD (app user pass — last resort).
    local root_pass
    root_pass="$(env_get PLATFORM_DB_ROOT_PASSWORD)"
    if [[ -z "$root_pass" ]]; then
        root_pass="$(env_get DB_PASSWORD)"
    fi
    if [[ -z "$root_pass" ]]; then
        root_pass="$(env_get PLATFORM_DB_PASSWORD)"
    fi
    DB_MIG_USER="postgres"
    DB_MIG_PASS="${root_pass}"

    export DB_CONTAINER DB_NAME_RESOLVED DB_MIG_USER DB_MIG_PASS
}

# ── Connectivity check ────────────────────────────────────────────────────────

# Waits for Postgres to accept connections. Arg $1 = max retries (default 10).
# Returns 0 on success, 1 on timeout.
db_wait_ready() {
    local retries="${1:-10}" attempt=0

    while (( attempt < retries )); do
        if docker exec -e PGPASSWORD="$DB_MIG_PASS" "$DB_CONTAINER" \
            psql -U "$DB_MIG_USER" -d "$DB_NAME_RESOLVED" -c "SELECT 1" &>/dev/null; then
            return 0
        fi
        attempt=$(( attempt + 1 ))
        sleep 2
    done
    return 1
}

# ── Migration runner ──────────────────────────────────────────────────────────

# Ensures the migration tracking table exists.
_db_ensure_history_table() {
    docker exec -i -e PGPASSWORD="$DB_MIG_PASS" "$DB_CONTAINER" \
        psql -U "$DB_MIG_USER" -d "$DB_NAME_RESOLVED" -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE IF NOT EXISTS _billing_migration_history (
    filename    TEXT        PRIMARY KEY,
    checksum    TEXT        NOT NULL,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
SQL
}

# Applies all .psql files from a directory in lexical order, skipping files
# whose filename is already recorded in _billing_migration_history.
#
# Sets: DB_MIGRATIONS_APPLIED, DB_MIGRATIONS_SKIPPED
db_apply_migrations() {
    local dir="${1:?migration directory required}"
    DB_MIGRATIONS_APPLIED=0
    DB_MIGRATIONS_SKIPPED=0

    _db_ensure_history_table

    local file filename checksum existing_checksum
    for file in "$dir"/*.psql; do
        [[ -e "$file" ]] || continue
        filename="$(basename "$file")"
        checksum="$(_db_file_checksum "$file")"

        # Check if already applied.
        existing_checksum="$(docker exec -e PGPASSWORD="$DB_MIG_PASS" "$DB_CONTAINER" \
            psql -U "$DB_MIG_USER" -d "$DB_NAME_RESOLVED" -tAc \
            "SELECT checksum FROM _billing_migration_history WHERE filename = '$filename'" 2>/dev/null | tr -d '[:space:]')"

        if [[ -n "$existing_checksum" ]]; then
            if [[ "$existing_checksum" != "$checksum" ]]; then
                log_warn "Schema drift detected: $filename (re-applying)"
                if docker exec -i -e PGPASSWORD="$DB_MIG_PASS" "$DB_CONTAINER" \
                    psql -U "$DB_MIG_USER" -d "$DB_NAME_RESOLVED" -v ON_ERROR_STOP=1 < "$file"; then
                    docker exec -e PGPASSWORD="$DB_MIG_PASS" "$DB_CONTAINER" \
                        psql -U "$DB_MIG_USER" -d "$DB_NAME_RESOLVED" -c \
                        "UPDATE _billing_migration_history SET checksum = '$checksum', applied_at = now() WHERE filename = '$filename'"
                else
                    log_error "Migration failed: $filename"
                    return 1
                fi
            else
                DB_MIGRATIONS_SKIPPED=$(( DB_MIGRATIONS_SKIPPED + 1 ))
            fi
            continue
        fi

        log_detail "Applying: $filename"
        if docker exec -i -e PGPASSWORD="$DB_MIG_PASS" "$DB_CONTAINER" \
            psql -U "$DB_MIG_USER" -d "$DB_NAME_RESOLVED" -v ON_ERROR_STOP=1 < "$file"; then
            docker exec -e PGPASSWORD="$DB_MIG_PASS" "$DB_CONTAINER" \
                psql -U "$DB_MIG_USER" -d "$DB_NAME_RESOLVED" -c \
                "INSERT INTO _billing_migration_history (filename, checksum) VALUES ('$filename', '$checksum')"
            DB_MIGRATIONS_APPLIED=$(( DB_MIGRATIONS_APPLIED + 1 ))
        else
            log_error "Migration failed: $filename"
            return 1
        fi
    done

    export DB_MIGRATIONS_APPLIED DB_MIGRATIONS_SKIPPED
    return 0
}

# ── Internal helpers ──────────────────────────────────────────────────────────

# Computes a stable checksum for a migration file.
_db_file_checksum() {
    if have_cmd shasum; then
        shasum -a 256 "$1" | cut -d' ' -f1
    elif have_cmd sha256sum; then
        sha256sum "$1" | cut -d' ' -f1
    else
        md5 -q "$1" 2>/dev/null || md5sum "$1" | cut -d' ' -f1
    fi
}
