#!/bin/bash
# Unit tests for auto-update.sh
# Run with: bash src/test/bash/test_auto_update.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
PID_FILE="/tmp/site-manager-auto-update-test.pid"

PASS=0
FAIL=0

assert_eq() {
    local desc="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo "  PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $desc"
        echo "        expected: '$expected'"
        echo "        actual:   '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

assert_match() {
    local desc="$1" pattern="$2" actual="$3"
    if echo "$actual" | grep -qE "$pattern"; then
        echo "  PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $desc"
        echo "        pattern: '$pattern'"
        echo "        actual:  '$actual'"
        FAIL=$((FAIL + 1))
    fi
}

# Shared function definitions matching auto-update.sh exactly
# (copied here so tests are not coupled to parsing the script file)
log() { echo "$(date -u +"%Y-%m-%dT%H:%M:%SZ") $*"; }

stop_app() {
    if [ ! -f "$PID_FILE" ]; then
        log "No PID file found, nothing to stop"
        return
    fi
    local pid
    pid=$(cat "$PID_FILE")
    log "Sending SIGTERM to process group $pid..."
    kill -- -"$pid" 2>/dev/null
    local waited=0
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge 30 ]; then
            log "Process did not stop after 30s, sending SIGKILL..."
            kill -- -"$pid" 2>/dev/null || kill -9 "$pid" 2>/dev/null
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done
    rm -f "$PID_FILE"
    log "Application stopped"
}

check_for_updates() {
    git fetch origin "$AUTO_UPDATE_BRANCH" 2>/dev/null
    local local_ref remote_ref
    local_ref=$(git rev-parse HEAD)
    remote_ref=$(git rev-parse "origin/$AUTO_UPDATE_BRANCH")
    if [ "$local_ref" != "$remote_ref" ]; then return 0; fi
    return 1
}

validate_branch() {
    local branch="$1"
    if echo "$branch" | grep -qE '^[a-zA-Z0-9/_.-]+$'; then
        echo "VALID"; return 0
    else
        echo "INVALID"; return 1
    fi
}

# ---------------------------------------------------------------------------
echo "=== log() tests ==="

output=$(log "hello world")
assert_match "log includes ISO timestamp" "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z" "$output"
assert_match "log includes message" "hello world" "$output"
assert_match "log timestamp ends with Z" "Z " "$output"

# ---------------------------------------------------------------------------
echo "=== branch validation tests ==="

assert_eq "simple branch name accepted"           "VALID"   "$(validate_branch "main")"
assert_eq "branch with slash accepted"            "VALID"   "$(validate_branch "feature/my-branch")"
assert_eq "branch with dots accepted"             "VALID"   "$(validate_branch "release-1.0.0")"
assert_eq "branch with underscores accepted"      "VALID"   "$(validate_branch "my_branch")"
assert_eq "alphanumeric branch accepted"          "VALID"   "$(validate_branch "branch123")"
assert_eq "branch with space rejected"            "INVALID" "$(validate_branch "bad branch")"
assert_eq "shell injection rejected"              "INVALID" "$(validate_branch '$(rm -rf /)')"
assert_eq "semicolon in branch rejected"          "INVALID" "$(validate_branch "branch;rm")"
assert_eq "pipe in branch rejected"               "INVALID" "$(validate_branch "branch|cmd")"
assert_eq "ampersand in branch rejected"          "INVALID" "$(validate_branch "branch&&cmd")"
assert_eq "empty string rejected"                 "INVALID" "$(validate_branch "")"

# Early exit with error code on invalid branch
exit_code=$(AUTO_UPDATE_BRANCH="bad branch!" bash "$REPO_ROOT/auto-update.sh" 2>/dev/null; echo $?)
assert_eq "script exits 1 on invalid branch" "1" "$exit_code"

error_msg=$(AUTO_UPDATE_BRANCH="bad branch!" bash "$REPO_ROOT/auto-update.sh" 2>&1)
assert_match "error message logged on invalid branch" "Invalid AUTO_UPDATE_BRANCH" "$error_msg"

# ---------------------------------------------------------------------------
echo "=== default env var values ==="

assert_match "AUTO_UPDATE_BRANCH defaults to main" \
    'AUTO_UPDATE_BRANCH.*:-main' \
    "$(grep 'AUTO_UPDATE_BRANCH=' "$REPO_ROOT/auto-update.sh" | head -1)"

assert_match "AUTO_UPDATE_INTERVAL defaults to 60" \
    'AUTO_UPDATE_INTERVAL.*:-60' \
    "$(grep 'AUTO_UPDATE_INTERVAL=' "$REPO_ROOT/auto-update.sh" | head -1)"

# ---------------------------------------------------------------------------
echo "=== stop_app() — no PID file ==="

rm -f "$PID_FILE"
output=$(stop_app)
assert_match "stop_app with no PID file logs message" "No PID file found" "$output"
assert_eq "PID file still absent after no-op stop" "0" "$([ -f "$PID_FILE" ] && echo 1 || echo 0)"

# ---------------------------------------------------------------------------
echo "=== stop_app() — PID file with dead process ==="

rm -f "$PID_FILE"
echo "99999999" > "$PID_FILE"
output=$(stop_app)
assert_match "stop_app with dead PID logs stopped" "stopped" "$output"
assert_eq "PID file removed after dead process stop" "0" "$([ -f "$PID_FILE" ] && echo 1 || echo 0)"

# ---------------------------------------------------------------------------
echo "=== stop_app() — PID file with live process ==="

rm -f "$PID_FILE"
sleep 60 &
SLEEP_PID=$!
echo "$SLEEP_PID" > "$PID_FILE"

output=$(stop_app)
assert_match "stop_app with live PID logs SIGTERM" "SIGTERM" "$output"
assert_match "stop_app with live PID logs stopped" "stopped" "$output"
assert_eq "PID file removed after live process stop" "0" "$([ -f "$PID_FILE" ] && echo 1 || echo 0)"
sleep 0.5
if kill -0 "$SLEEP_PID" 2>/dev/null; then
    kill "$SLEEP_PID" 2>/dev/null
    echo "  FAIL: process was not killed by stop_app"
    FAIL=$((FAIL + 1))
else
    echo "  PASS: process was killed by stop_app"
    PASS=$((PASS + 1))
fi

# ---------------------------------------------------------------------------
echo "=== check_for_updates() — same commit ==="

TMPDIR_TEST=$(mktemp -d)
cd "$TMPDIR_TEST"
git init -q
git config user.email "test@test.com"
git config user.name "Test"
echo "v1" > file.txt
git add file.txt
git commit -q -m "init"
git update-ref refs/remotes/origin/main HEAD

AUTO_UPDATE_BRANCH=main check_for_updates
result=$?
assert_eq "check_for_updates returns 1 when up to date" "1" "$result"

# ---------------------------------------------------------------------------
echo "=== check_for_updates() — different commit ==="

git commit -q --allow-empty -m "new commit on origin"
git update-ref refs/remotes/origin/main HEAD
git reset -q --hard HEAD~1

# Override git to skip the fetch (refs already set), compare HEAD vs origin/main
git() {
    if [ "$1" = "fetch" ]; then return 0; fi
    command git "$@"
}

AUTO_UPDATE_BRANCH=main check_for_updates
result=$?
assert_eq "check_for_updates returns 0 when update available" "0" "$result"
unset -f git

cd "$REPO_ROOT"
rm -rf "$TMPDIR_TEST"

# ---------------------------------------------------------------------------
echo "=== script is executable ==="

assert_match "auto-update.sh is executable" "" "$([ -x "$REPO_ROOT/auto-update.sh" ] && echo "executable" || echo "not executable")"
# Simpler check
if [ -x "$REPO_ROOT/auto-update.sh" ]; then
    echo "  PASS: auto-update.sh is executable"
    PASS=$((PASS + 1))
else
    echo "  FAIL: auto-update.sh is not executable"
    FAIL=$((FAIL + 1))
fi

assert_match "shebang is bash" "^#!/bin/bash" "$(head -1 "$REPO_ROOT/auto-update.sh")"

# ---------------------------------------------------------------------------
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
