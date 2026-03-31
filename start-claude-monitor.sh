#!/bin/bash
# start-claude-monitor.sh
#
# Launches Claude Code to run site-manager with auto-update, error extraction,
# and adaptive monitoring (every 5 min during PST daytime, every 30 min at night).
# Displays a rich live dashboard in the terminal AND saves all logs to file.
#
# Usage:
#   ./start-claude-monitor.sh              # foreground with live dashboard
#   ./start-claude-monitor.sh --headless   # background-friendly, no dashboard
#
# Prerequisites:
#   - Claude Code CLI installed and authenticated
#   - Git repo with push access configured
#
# Environment variables (all optional):
#   MONITOR_LOG        - Path to monitor log file (default: claude-monitor.log)
#   AUTO_UPDATE_BRANCH - Branch for auto-update (default: main)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONITOR_LOG="${MONITOR_LOG:-$SCRIPT_DIR/claude-monitor.log}"
DASHBOARD_STATE="$SCRIPT_DIR/.dashboard-state"
HEADLESS=false
[[ "${1:-}" == "--headless" ]] && HEADLESS=true

# ─── Colors & Symbols ──────────────────────────────────────────────────────────
RST="\033[0m"
BOLD="\033[1m"
DIM="\033[2m"
ITAL="\033[3m"

# Foreground
FG_RED="\033[38;5;196m"
FG_GREEN="\033[38;5;82m"
FG_YELLOW="\033[38;5;220m"
FG_BLUE="\033[38;5;75m"
FG_CYAN="\033[38;5;117m"
FG_MAGENTA="\033[38;5;213m"
FG_ORANGE="\033[38;5;208m"
FG_WHITE="\033[38;5;255m"
FG_GRAY="\033[38;5;245m"
FG_DARK="\033[38;5;240m"

# Background
BG_RED="\033[48;5;52m"
BG_GREEN="\033[48;5;22m"
BG_BLUE="\033[48;5;17m"
BG_DARK="\033[48;5;233m"
BG_DARKER="\033[48;5;232m"
BG_HEADER="\033[48;5;236m"

# Symbols
SYM_OK="●"
SYM_WARN="▲"
SYM_ERR="✖"
SYM_ARROW="▸"
SYM_DOT="─"
SYM_BLOCK_FULL="█"
SYM_BLOCK_MED="▓"
SYM_BLOCK_LOW="░"
SYM_GEAR="⚙"
SYM_CLOCK="◷"
SYM_CHART="◈"
SYM_DISK="◉"
SYM_NET="⬡"

# ─── Logging ────────────────────────────────────────────────────────────────────
log_file() {
    echo "$(date -u +"%Y-%m-%dT%H:%M:%SZ") $*" >> "$MONITOR_LOG"
}

log() {
    local msg="$(date -u +"%Y-%m-%dT%H:%M:%SZ") [monitor] $*"
    echo "$msg" >> "$MONITOR_LOG"
    $HEADLESS && echo "$msg"
}

# ─── Bar Drawing ────────────────────────────────────────────────────────────────
draw_bar() {
    local value=$1 max=$2 width=$3 warn_thresh=${4:-70} crit_thresh=${5:-90}
    local filled=$(( value * width / max ))
    [[ $filled -gt $width ]] && filled=$width
    local empty=$(( width - filled ))
    local color="$FG_GREEN"
    (( value >= crit_thresh )) && color="$FG_RED"
    (( value >= warn_thresh && value < crit_thresh )) && color="$FG_YELLOW"
    printf "${color}"
    for ((i=0; i<filled; i++)); do printf "$SYM_BLOCK_FULL"; done
    printf "${FG_DARK}"
    for ((i=0; i<empty; i++)); do printf "$SYM_BLOCK_LOW"; done
    printf "${RST}"
}

# ─── Horizontal Rule ───────────────────────────────────────────────────────────
hr() {
    local width=${1:-80} char="${2:-─}" color="${3:-$FG_DARK}"
    printf "${color}"
    for ((i=0; i<width; i++)); do printf "%s" "$char"; done
    printf "${RST}\n"
}

# ─── Dashboard Render ──────────────────────────────────────────────────────────
render_dashboard() {
    local cols=$(tput cols 2>/dev/null || echo 120)
    # No upper cap — use the full terminal width

    local pad=3  # left margin
    local inner=$(( cols - pad * 2 ))  # usable content width

    # Gather data
    local now_utc=$(date -u +"%Y-%m-%d %H:%M:%S UTC")
    local now_pst=$(TZ=US/Pacific date +"%Y-%m-%d %I:%M:%S %p %Z" 2>/dev/null || echo "N/A")
    local pst_hour=$(TZ=US/Pacific date +"%H" 2>/dev/null || echo "0")
    local monitor_mode="DAYTIME (5 min)"
    (( pst_hour >= 22 || pst_hour < 7 )) && monitor_mode="NIGHTTIME (30 min)"

    # Process info
    local app_pid=$(cat /tmp/site-manager-auto-update.pid 2>/dev/null || echo "")
    local app_status="DOWN" app_cpu="0" app_mem="0" app_rss="0" app_vsz="0" app_uptime="N/A"
    local status_color="$FG_RED" status_sym="$SYM_ERR"

    if [[ -n "$app_pid" ]] && ps -p "$app_pid" &>/dev/null; then
        app_status="RUNNING"
        status_color="$FG_GREEN"
        status_sym="$SYM_OK"
        read app_cpu app_mem app_rss app_vsz app_uptime < <(
            ps -p "$app_pid" -o %cpu,%mem,rss,vsz,etime --no-headers 2>/dev/null | awk '{print $1, $2, $3, $4, $5}'
        ) || true
    fi

    # System resources
    local mem_total mem_used mem_avail mem_pct
    read mem_total mem_used mem_avail < <(free -m | awk '/^Mem:/{print $2, $3, $7}') || true
    mem_pct=$(( mem_used * 100 / mem_total )) 2>/dev/null || mem_pct=0

    local disk_used_pct
    disk_used_pct=$(df -h /home/claude 2>/dev/null | awk 'NR==2{gsub(/%/,"",$5); print $5}') || disk_used_pct=0

    local disk_used disk_total
    read disk_total disk_used < <(df -h /home/claude 2>/dev/null | awk 'NR==2{print $2, $3}') || true

    # Error counts
    local err_total=0 err_new=0
    [[ -f "$SCRIPT_DIR/error.log" ]] && err_total=$(wc -l < "$SCRIPT_DIR/error.log" 2>/dev/null || echo 0)
    local prev_err_count=0
    [[ -f "$DASHBOARD_STATE" ]] && prev_err_count=$(cat "$DASHBOARD_STATE" 2>/dev/null || echo 0)
    err_new=$(( err_total - prev_err_count ))
    [[ $err_new -lt 0 ]] && err_new=0
    echo "$err_total" > "$DASHBOARD_STATE"

    # Auto-update process
    local autoupd_status="DOWN" autoupd_color="$FG_RED"
    if pgrep -f "auto-update.sh" &>/dev/null; then
        autoupd_status="RUNNING"
        autoupd_color="$FG_GREEN"
    fi

    # Extract-errors process
    local extractor_status="DOWN" extractor_color="$FG_RED"
    if pgrep -f "extract-errors.sh" &>/dev/null; then
        extractor_status="RUNNING"
        extractor_color="$FG_GREEN"
    fi

    # Git info
    local git_branch git_commit
    git_branch=$(git -C "$SCRIPT_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "N/A")
    git_commit=$(git -C "$SCRIPT_DIR" rev-parse --short HEAD 2>/dev/null || echo "N/A")
    local git_msg
    git_msg=$(git -C "$SCRIPT_DIR" log -1 --pretty=format:'%s' 2>/dev/null || echo "")

    # App RSS in human-readable
    local rss_human="${app_rss} KB"
    if [[ "$app_rss" -gt 1024 ]] 2>/dev/null; then
        rss_human="$(( app_rss / 1024 )) MB"
    fi

    # Thread / open file counts for the Java process
    local thread_count="—" fd_count="—"
    if [[ -n "$app_pid" ]] && [[ -d "/proc/$app_pid" ]]; then
        thread_count=$(ls /proc/$app_pid/task 2>/dev/null | wc -l || echo "—")
        fd_count=$(ls /proc/$app_pid/fd 2>/dev/null | wc -l || echo "—")
    fi

    # Load average
    local load_avg
    load_avg=$(awk '{print $1, $2, $3}' /proc/loadavg 2>/dev/null || echo "— — —")

    # ── Clear screen and draw ──
    clear
    printf "\033[H"

    # ━━ Header banner ━━
    printf "${BG_HEADER}${BOLD}${FG_CYAN}"
    printf "  %-*s" $((cols - 2)) "$SYM_GEAR  SITE-MANAGER MONITOR DASHBOARD"
    printf "${RST}\n"
    hr $cols "━" "$FG_CYAN"

    # ── Timestamp bar ──
    # Spread 3 items evenly across the width
    local ts_col=$(( cols / 3 ))
    printf "${BG_DARK}"
    printf "  ${FG_GRAY}${SYM_CLOCK} ${FG_WHITE}%-*s" $ts_col "$now_utc"
    printf "${FG_GRAY}Pacific: ${FG_CYAN}%-*s" $ts_col "$now_pst"
    printf "${FG_GRAY}Mode: ${FG_MAGENTA}${BOLD}%s" "$monitor_mode"
    # Pad to fill line
    local ts_printed=$(( 2 + 2 + ts_col + 9 + ts_col + 6 + ${#monitor_mode} ))
    local ts_remaining=$(( cols - ts_printed ))
    (( ts_remaining > 0 )) && printf "%*s" $ts_remaining ""
    printf "${RST}\n"
    hr $cols "─" "$FG_DARK"

    # ── Service Status — spread columns across full width ──
    printf "\n"
    printf "   ${BOLD}${FG_BLUE}${SYM_NET} SERVICE STATUS"
    # Right-align load average
    local svc_header="Load avg: $load_avg"
    local svc_gap=$(( cols - 20 - ${#svc_header} - 4 ))
    (( svc_gap > 0 )) && printf "%*s" $svc_gap ""
    printf "${RST}${FG_GRAY}%s${RST}\n" "$svc_header"
    hr $cols "─" "$FG_DARK"

    # Calculate column widths for service table: name | status | details spread across full width
    local svc_name_w=20
    local svc_status_w=14
    local svc_detail_w=$(( cols - pad - svc_name_w - svc_status_w - 4 ))

    printf "   ${FG_GRAY}%-*s" $svc_name_w "Spring Boot App"
    printf "${status_color}${BOLD} ${status_sym} %-*s${RST}" $svc_status_w "$app_status"
    printf "${FG_GRAY}PID: ${FG_WHITE}%-12s" "${app_pid:-N/A}"
    printf "${FG_GRAY}Uptime: ${FG_WHITE}%-16s" "$app_uptime"
    printf "${FG_GRAY}Threads: ${FG_WHITE}%-10s" "$thread_count"
    printf "${FG_GRAY}FDs: ${FG_WHITE}%s${RST}\n" "$fd_count"

    printf "   ${FG_GRAY}%-*s" $svc_name_w "Auto-Update"
    printf "${autoupd_color}${BOLD} ${SYM_OK} %-*s${RST}" $svc_status_w "$autoupd_status"
    printf "${FG_GRAY}Branch: ${FG_WHITE}%-12s" "$git_branch"
    printf "${FG_GRAY}Commit: ${FG_YELLOW}%-16s" "$git_commit"
    local msg_space=$(( cols - pad - svc_name_w - svc_status_w - 12 - 8 - 16 - 8 - 8 ))
    if (( msg_space > 10 )) && [[ -n "$git_msg" ]]; then
        printf "${FG_DARK}${ITAL}%s${RST}" "${git_msg:0:$msg_space}"
    fi
    printf "\n"

    printf "   ${FG_GRAY}%-*s" $svc_name_w "Error Extractor"
    printf "${extractor_color}${BOLD} ${SYM_OK} %-*s${RST}" $svc_status_w "$extractor_status"
    printf "${FG_GRAY}Source: ${FG_WHITE}app.log ${FG_DARK}→${FG_WHITE} error.log${RST}\n"

    # ── Resource Gauges — bars scale to fill available space ──
    printf "\n"
    printf "   ${BOLD}${FG_BLUE}${SYM_CHART} RESOURCE USAGE${RST}\n"
    hr $cols "─" "$FG_DARK"

    # label(14) + value(8) + bar + suffix(~20) + margins(8) = cols
    local label_w=14
    local value_w=8
    local suffix_w=22
    local bar_width=$(( cols - label_w - value_w - suffix_w - pad * 2 - 2 ))
    [[ $bar_width -lt 20 ]] && bar_width=20

    local cpu_int=${app_cpu%.*}
    [[ -z "$cpu_int" ]] && cpu_int=0

    printf "   ${FG_GRAY}%-*s${FG_WHITE}%6s%%  " $label_w "App CPU" "$app_cpu"
    draw_bar "$cpu_int" 100 "$bar_width" 70 90
    printf "${RST}\n"

    local mem_int=${app_mem%.*}
    [[ -z "$mem_int" ]] && mem_int=0

    printf "   ${FG_GRAY}%-*s${FG_WHITE}%6s%%  " $label_w "App Memory" "$app_mem"
    draw_bar "$mem_int" 100 "$bar_width" 60 85
    printf "  ${FG_DARK}(%s)${RST}\n" "$rss_human"

    printf "   ${FG_GRAY}%-*s${FG_WHITE}%5d%%   " $label_w "System RAM" "$mem_pct"
    draw_bar "$mem_pct" 100 "$bar_width" 70 90
    printf "  ${FG_DARK}(%s / %s MB)${RST}\n" "$mem_used" "$mem_total"

    printf "   ${FG_GRAY}%-*s${FG_WHITE}%5d%%   " $label_w "Disk Usage" "$disk_used_pct"
    draw_bar "$disk_used_pct" 100 "$bar_width" 75 90
    printf "  ${FG_DARK}(%s / %s)${RST}\n" "${disk_used:-?}" "${disk_total:-?}"

    # ── Error Summary — use full width for badge + details ──
    printf "\n"
    printf "   ${BOLD}${FG_BLUE}${SYM_WARN} ERROR SUMMARY${RST}\n"
    hr $cols "─" "$FG_DARK"

    local err_color="$FG_GREEN" err_sym="$SYM_OK"
    (( err_total > 0 )) && err_color="$FG_YELLOW" && err_sym="$SYM_WARN"
    (( err_new > 0 ))   && err_color="$FG_RED"    && err_sym="$SYM_ERR"

    printf "   ${err_color}${BOLD}${err_sym}${RST} "
    printf "${FG_GRAY}Total errors: ${FG_WHITE}${BOLD}%d${RST}" "$err_total"
    if (( err_new > 0 )); then
        printf "     ${BG_RED}${FG_WHITE}${BOLD} +%d NEW ${RST}" "$err_new"
    else
        printf "     ${FG_GREEN}No new errors${RST}"
    fi
    # Right-align log file path
    local err_right="error.log: $(du -sh "$SCRIPT_DIR/error.log" 2>/dev/null | awk '{print $1}' || echo '0')"
    local err_left_len=50
    local err_gap=$(( cols - err_left_len - ${#err_right} - 4 ))
    (( err_gap > 0 )) && printf "%*s" $err_gap ""
    printf "${FG_DARK}%s${RST}\n" "$err_right"

    # ── Last 15 App Log Lines — full width ──
    printf "\n"
    printf "   ${BOLD}${FG_BLUE}${SYM_ARROW} RECENT LOGS ${RST}${FG_DARK}(last 15 lines from app.log)${RST}\n"
    hr $cols "─" "$FG_DARK"

    if [[ -f "$SCRIPT_DIR/app.log" ]]; then
        tail -15 "$SCRIPT_DIR/app.log" 2>/dev/null | while IFS= read -r line; do
            local line_color="$FG_GRAY"
            if echo "$line" | grep -qiE 'ERROR|FATAL|Exception'; then
                line_color="${FG_RED}${BOLD}"
            elif echo "$line" | grep -qi 'WARN'; then
                line_color="$FG_YELLOW"
            elif echo "$line" | grep -qi 'INFO'; then
                line_color="$FG_CYAN"
            fi
            # Use full width minus small margin
            printf "   ${line_color}%s${RST}\n" "${line:0:$((cols - pad - 1))}"
        done
    else
        printf "   ${FG_DARK}(no log file found)${RST}\n"
    fi

    # ── Error Log Tail — full width ──
    if (( err_total > 0 )); then
        printf "\n"
        printf "   ${BOLD}${FG_RED}${SYM_ERR} RECENT ERRORS ${RST}${FG_DARK}(last 5 from error.log)${RST}\n"
        hr $cols "─" "$FG_DARK"
        tail -5 "$SCRIPT_DIR/error.log" 2>/dev/null | while IFS= read -r line; do
            printf "   ${FG_RED}%s${RST}\n" "${line:0:$((cols - pad - 1))}"
        done
    fi

    # ── Footer ──
    printf "\n"
    hr $cols "━" "$FG_CYAN"
    local footer_left="Log: $MONITOR_LOG"
    local footer_right="Refresh: 30s │ Ctrl+C to stop"
    local footer_gap=$(( cols - ${#footer_left} - ${#footer_right} - pad * 2 ))
    (( footer_gap < 1 )) && footer_gap=1
    printf "   ${FG_DARK}${ITAL}%s${RST}" "$footer_left"
    printf "%*s" $footer_gap ""
    printf "${FG_DARK}%s${RST}\n" "$footer_right"

    # Also log a machine-readable summary to the log file
    log_file "DASHBOARD status=$app_status cpu=$app_cpu% mem=$app_mem% rss=${rss_human} sys_ram=${mem_pct}% disk=${disk_used_pct}% errors_total=$err_total errors_new=$err_new threads=$thread_count fds=$fd_count load=$load_avg"
}

# ─── Preflight ──────────────────────────────────────────────────────────────────
if ! command -v claude &>/dev/null; then
    echo -e "${FG_RED}${BOLD}ERROR:${RST} Claude Code CLI not found in PATH." >&2
    exit 1
fi

# ─── Build the Claude Prompt ───────────────────────────────────────────────────
PROMPT=$(cat <<'PROMPT_EOF'
You are managing the site-manager application at /home/claude/site-manager. Follow these instructions precisely.

## Phase 1: Start the application

1. Start the app using auto-update.sh, running it in the background with logs going to app.log:
   ```
   touch app.log error.log
   nohup bash ./auto-update.sh > app.log 2>&1 &
   echo "Auto-update PID: $!"
   ```

2. Start the error extraction script in the background:
   ```
   nohup bash ./extract-errors.sh &
   ```

3. Verify both processes are running with `ps aux | grep -E "auto-update|extract-errors" | grep -v grep`.

## Phase 2: Continuous monitoring

Set up a recurring monitoring job using the CronCreate tool. The monitoring interval depends on the current time in US/Pacific (PST/PDT):

- **Daytime (7:00 AM - 10:00 PM Pacific):** Monitor every 5 minutes using cron `*/5 * * * *`
- **Nighttime (10:00 PM - 7:00 AM Pacific):** Monitor every 30 minutes using cron `*/30 * * * *`

To determine which schedule to use, run: `TZ=US/Pacific date +%H` and check the hour.
- If hour is >= 7 and < 22, use the 5-minute interval.
- If hour is < 7 or >= 22, use the 30-minute interval.

Create the cron job with this monitoring prompt:

---
Monitor the site-manager application at /home/claude/site-manager. Execute these steps:

### Step 1: Check if it's time to adjust the monitoring interval
Run `TZ=US/Pacific date +%H` to get the current Pacific hour.
- Daytime (7-21): monitoring should be every 5 minutes (`*/5 * * * *`)
- Nighttime (22-6): monitoring should be every 30 minutes (`*/30 * * * *`)

If the current cron interval doesn't match the time of day, delete the current cron job with CronDelete and create a new one with the correct interval using CronCreate (with this same prompt).

### Step 2: Check error.log
Read /home/claude/site-manager/error.log. Compare against what was there in prior checks.
- If there are NEW errors (lines not seen before), analyze them and proceed to Step 4.
- If no new errors, note "no new errors."

### Step 3: Check resource usage
Run these commands:
```
PID=$(cat /tmp/site-manager-auto-update.pid 2>/dev/null)
ps -p "$PID" -o pid,%cpu,%mem,rss,vsz,etime --no-headers 2>/dev/null
free -m
df -h /home/claude
```
Flag if:
- CPU > 80%
- MEM > 70% of total system RAM
- Disk > 85%
- The Java process is not running (crashed) — if so, restart it: `nohup bash ./auto-update.sh > app.log 2>&1 &`

### Step 4: Fix errors or optimize if needed
If errors were found in error.log:
- Read the relevant source files to diagnose the root cause
- Make targeted fixes in the code
- Do NOT make speculative or unrelated changes

If resource usage is high:
- Look at Spring Boot configuration (application.yml), connection pool, thread pool, JVM memory settings
- Make targeted optimizations to reduce resource consumption
- Do NOT over-engineer — only fix what's actually causing the issue

### Step 5: Commit and push if changes were made
If any code changes were made:
```
git add <changed-files>
git commit -m "<descriptive message>

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
git push origin main
```

### Step 6: Report
Print a brief status line:
- Timestamp, app status (running/down), error count (new/total), CPU%, MEM%, disk%, actions taken
- If everything is healthy: just say "All clear"
---

IMPORTANT: After creating the cron job, confirm the setup is complete and report the initial status of the application (is it running, any immediate errors, resource usage).
PROMPT_EOF
)

# ─── Main ──────────────────────────────────────────────────────────────────────
log "Starting Claude Code monitor session..."
log "Working directory: $SCRIPT_DIR"
log "Monitor log: $MONITOR_LOG"

# Show initial dashboard before Claude starts
if ! $HEADLESS; then
    render_dashboard
    printf "\n  ${FG_YELLOW}${BOLD}${SYM_GEAR} Launching Claude Code...${RST}\n\n"
    sleep 2
fi

# Launch Claude in background, capture output to log
cd "$SCRIPT_DIR"
claude \
    --dangerously-skip-permissions \
    -p "$PROMPT" \
    --model opus \
    --verbose \
    >> "$MONITOR_LOG" 2>&1 &

CLAUDE_PID=$!
log "Claude Code launched with PID $CLAUDE_PID"

# If headless, just wait for Claude to finish
if $HEADLESS; then
    wait $CLAUDE_PID
    EXIT_CODE=$?
    log "Claude Code exited with code $EXIT_CODE"
    exit $EXIT_CODE
fi

# ── Live Dashboard Loop ──
cleanup() {
    printf "\n${FG_YELLOW}${BOLD}Shutting down monitor dashboard...${RST}\n"
    # Don't kill Claude — it keeps running with its cron jobs
    if kill -0 $CLAUDE_PID 2>/dev/null; then
        printf "${FG_GRAY}Claude session (PID $CLAUDE_PID) is still running in background.${RST}\n"
        printf "${FG_GRAY}Monitoring cron jobs continue autonomously.${RST}\n"
    fi
    rm -f "$DASHBOARD_STATE"
    printf "${FG_GREEN}Dashboard stopped. Logs saved to: ${BOLD}$MONITOR_LOG${RST}\n"
    exit 0
}
trap cleanup SIGINT SIGTERM

while true; do
    render_dashboard

    # Show Claude process status
    if kill -0 $CLAUDE_PID 2>/dev/null; then
        printf "\n  ${FG_GREEN}${SYM_OK} ${BOLD}Claude AI${RST}${FG_GREEN} monitoring active${RST}"
        printf "  ${FG_DARK}(PID $CLAUDE_PID)${RST}\n"
    else
        printf "\n  ${FG_YELLOW}${SYM_GEAR} ${BOLD}Claude AI${RST}${FG_YELLOW} setup complete — cron jobs running autonomously${RST}\n"
    fi

    # Show last few lines from monitor log (Claude's actions)
    local_actions=$(grep -E "DASHBOARD|ERROR|fix|commit|push|restart" "$MONITOR_LOG" 2>/dev/null | tail -3)
    if [[ -n "$local_actions" ]]; then
        printf "\n  ${BOLD}${FG_BLUE}${SYM_GEAR} RECENT ACTIONS${RST}\n"
        hr $(tput cols 2>/dev/null || echo 80) "─" "$FG_DARK"
        echo "$local_actions" | while IFS= read -r line; do
            local cols_now=$(tput cols 2>/dev/null || echo 80)
            printf "  ${FG_MAGENTA}%s${RST}\n" "${line:0:$((cols_now - 4))}"
        done
    fi

    sleep 30
done
