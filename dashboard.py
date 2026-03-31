#!/usr/bin/env python3
"""Interactive Rich dashboard for the Site Manager application."""

import sqlite3
import os
import sys
import time
import shutil
import signal
import subprocess
import threading
import queue
from datetime import datetime, timezone
from pathlib import Path

from rich.console import Console
from rich.layout import Layout
from rich.live import Live
from rich.panel import Panel
from rich.table import Table
from rich.text import Text
from rich.align import Align
from rich.columns import Columns
from rich.prompt import Prompt, Confirm
from rich import box
from rich.markdown import Markdown
from rich.spinner import Spinner
from rich.status import Status

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "sitemanager.db")

STATUS_COLORS = {
    "MERGED": "green",
    "DEV_COMPLETE": "cyan",
    "FINAL_REVIEW": "yellow",
    "IN_PROGRESS": "blue",
    "PENDING": "white",
    "DENIED": "red",
    "TIMED_OUT": "magenta",
    "DRAFT": "dim white",
}

PRIORITY_COLORS = {
    "HIGH": "red bold",
    "MEDIUM": "yellow",
    "LOW": "dim white",
}

TASK_STATUS_COLORS = {
    "COMPLETED": "green",
    "PENDING": "yellow",
    "IN_PROGRESS": "blue",
    "REVIEWING": "cyan",
    "FAILED": "red",
}

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PID_FILE = "/tmp/site-manager-auto-update.pid"

# ── Service definitions ────────────────────────────────────────────


SERVICES = {
    "app": {
        "name": "Spring Boot App (auto-update)",
        "pid_file": PID_FILE,
        "start_cmd": f"nohup bash {SCRIPT_DIR}/auto-update.sh > {SCRIPT_DIR}/app.log 2>&1 & echo $!",
        "grep_pattern": "auto-update.sh",
        "log_file": os.path.join(SCRIPT_DIR, "app.log"),
    },
    "extractor": {
        "name": "Error Extractor",
        "pid_file": None,
        "start_cmd": f"nohup bash {SCRIPT_DIR}/extract-errors.sh > /dev/null 2>&1 & echo $!",
        "grep_pattern": "extract-errors.sh",
        "log_file": os.path.join(SCRIPT_DIR, "error.log"),
    },
}


def get_service_pid(svc_key):
    """Get the PID of a managed service, or None if not running."""
    svc = SERVICES[svc_key]
    # Try PID file first
    if svc["pid_file"] and os.path.exists(svc["pid_file"]):
        try:
            pid = int(open(svc["pid_file"]).read().strip())
            # Check if process is alive
            os.kill(pid, 0)
            return pid
        except (ValueError, OSError, ProcessLookupError):
            pass
    # Fall back to grep
    try:
        result = subprocess.run(
            ["pgrep", "-f", svc["grep_pattern"]],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            pids = result.stdout.strip().split("\n")
            return int(pids[0]) if pids[0] else None
    except (subprocess.TimeoutExpired, ValueError):
        pass
    return None


def get_service_status(svc_key):
    """Return (is_running: bool, pid: int|None, info: dict)."""
    pid = get_service_pid(svc_key)
    if pid is None:
        return False, None, {}
    try:
        result = subprocess.run(
            ["ps", "-p", str(pid), "-o", "%cpu,%mem,rss,etime", "--no-headers"],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            parts = result.stdout.strip().split()
            if len(parts) >= 4:
                return True, pid, {
                    "cpu": parts[0], "mem": parts[1],
                    "rss": parts[2], "uptime": parts[3],
                }
        return True, pid, {}
    except subprocess.TimeoutExpired:
        return True, pid, {}


def start_service(svc_key):
    """Start a service. Returns (success, message)."""
    svc = SERVICES[svc_key]
    running, pid, _ = get_service_status(svc_key)
    if running:
        return False, f"{svc['name']} is already running (PID {pid})"
    try:
        # Ensure log files exist
        for f in [os.path.join(SCRIPT_DIR, "app.log"), os.path.join(SCRIPT_DIR, "error.log")]:
            Path(f).touch(exist_ok=True)
        result = subprocess.run(
            svc["start_cmd"], shell=True, capture_output=True, text=True,
            cwd=SCRIPT_DIR,
        )
        new_pid = result.stdout.strip().split("\n")[-1].strip()
        return True, f"{svc['name']} started (PID {new_pid})"
    except Exception as e:
        return False, f"Failed to start {svc['name']}: {e}"


def stop_service(svc_key):
    """Stop a service. Returns (success, message)."""
    svc = SERVICES[svc_key]
    running, pid, _ = get_service_status(svc_key)
    if not running:
        return False, f"{svc['name']} is not running"
    try:
        # Try SIGTERM first
        os.kill(pid, signal.SIGTERM)
        # Wait up to 10 seconds
        for _ in range(20):
            time.sleep(0.5)
            try:
                os.kill(pid, 0)
            except OSError:
                # Process is gone
                if svc["pid_file"] and os.path.exists(svc["pid_file"]):
                    os.remove(svc["pid_file"])
                return True, f"{svc['name']} stopped (was PID {pid})"
        # Force kill
        os.kill(pid, signal.SIGKILL)
        if svc["pid_file"] and os.path.exists(svc["pid_file"]):
            os.remove(svc["pid_file"])
        return True, f"{svc['name']} force-killed (was PID {pid})"
    except Exception as e:
        return False, f"Failed to stop {svc['name']}: {e}"


def restart_service(svc_key):
    """Restart a service. Returns (success, message)."""
    running, _, _ = get_service_status(svc_key)
    if running:
        ok, msg = stop_service(svc_key)
        if not ok:
            return False, msg
        time.sleep(1)
    ok, msg = start_service(svc_key)
    return ok, msg


# ── Claude Interactive Session ─────────────────────────────────────


class ClaudeSession:
    """Manages an interactive Claude CLI session for the user."""

    def __init__(self, console):
        self.console = console
        self.history = []  # list of (role, text) tuples
        self.working = False
        self._lock = threading.Lock()

    def send_message(self, user_message):
        """Send a message to Claude and get a response. Runs synchronously."""
        self.working = True
        self.history.append(("user", user_message))

        system_context = (
            "You are managing the site-manager application at /home/claude/site-manager. "
            "The user is interacting with you through a dashboard terminal. "
            "You can make code changes, commit and push them, restart services, etc. "
            "Be concise in responses (under 500 chars unless showing code). "
            "When making changes, actually edit the files and run the commands. "
            "After making code changes, offer to commit and push. "
            "Available services: Spring Boot app (auto-update.sh), Error Extractor (extract-errors.sh). "
            "To restart the app: stop auto-update.sh processes, then relaunch. "
            "To commit: git add, git commit, git push origin main. "
            "Current directory: /home/claude/site-manager "
            "IMPORTANT: Do exactly what the user requests and nothing more. Do not get creative or make changes beyond what was explicitly asked. If the user asks to move something in a specific way, do only that exact move."
        )

        try:
            result = subprocess.run(
                [
                    "claude", "--dangerously-skip-permissions",
                    "-p", f"{system_context}\n\nUser request: {user_message}",
                    "--model", "sonnet",
                    "--output-format", "text",
                ],
                capture_output=True, text=True,
                cwd=SCRIPT_DIR,
            )
            response = result.stdout.strip() if result.stdout else "(no response)"
            if result.returncode != 0 and result.stderr:
                response += f"\n[stderr]: {result.stderr.strip()[:200]}"
        except Exception as e:
            response = f"[error: {e}]"

        # Truncate very long responses for display
        if len(response) > 3000:
            response = response[:2900] + "\n... (truncated)"

        self.history.append(("claude", response))
        self.working = False
        return response

    def send_async(self, user_message, callback=None):
        """Send a message in a background thread."""
        def _run():
            response = self.send_message(user_message)
            if callback:
                callback(response)
        t = threading.Thread(target=_run, daemon=True)
        t.start()
        return t

    def get_recent_history(self, n=10):
        """Return the last n history entries."""
        return self.history[-n:]


def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def ts_to_str(ts):
    """Convert epoch millis to readable string."""
    if not ts:
        return "N/A"
    try:
        dt = datetime.fromtimestamp(ts / 1000, tz=timezone.utc)
        return dt.strftime("%Y-%m-%d %H:%M")
    except (OSError, ValueError):
        return "N/A"


def time_ago(ts):
    """Convert epoch millis to relative time."""
    if not ts:
        return "N/A"
    try:
        now = time.time() * 1000
        diff_sec = (now - ts) / 1000
        if diff_sec < 60:
            return f"{int(diff_sec)}s ago"
        elif diff_sec < 3600:
            return f"{int(diff_sec / 60)}m ago"
        elif diff_sec < 86400:
            return f"{int(diff_sec / 3600)}h ago"
        else:
            return f"{int(diff_sec / 86400)}d ago"
    except (OSError, ValueError):
        return "N/A"


# ── Panel builders ──────────────────────────────────────────────────


def build_header(settings):
    site_name = settings["site_name"] or "Site Manager"
    model = settings["claude_model"] or "unknown"
    repo = (settings["target_repo_url"] or "").split("/")[-1].replace(".git", "")
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    header_text = Text()
    header_text.append(f"  {site_name}", style="bold white")
    header_text.append(f"   Model: ", style="dim")
    header_text.append(model, style="cyan")
    header_text.append(f"   Repo: ", style="dim")
    header_text.append(repo, style="green")
    header_text.append(f"   {now}", style="dim")
    return Panel(header_text, style="bold blue", height=3)


def build_status_summary(conn):
    c = conn.cursor()
    c.execute("SELECT status, COUNT(*) FROM suggestions GROUP BY status")
    status_counts = dict(c.fetchall())

    total = sum(status_counts.values())
    merged = status_counts.get("MERGED", 0)
    in_progress = status_counts.get("IN_PROGRESS", 0)
    denied = status_counts.get("DENIED", 0)
    dev_complete = status_counts.get("DEV_COMPLETE", 0)
    final_review = status_counts.get("FINAL_REVIEW", 0)
    timed_out = status_counts.get("TIMED_OUT", 0)
    success_rate = (merged / total * 100) if total > 0 else 0

    table = Table(box=box.SIMPLE, show_header=False, padding=(0, 1))
    table.add_column("label", style="dim", width=14)
    table.add_column("value", width=6)

    table.add_row("Total", f"[bold white]{total}[/]")
    table.add_row("Merged", f"[green]{merged}[/]")
    table.add_row("In Progress", f"[blue]{in_progress}[/]")
    table.add_row("Dev Complete", f"[cyan]{dev_complete}[/]")
    table.add_row("Final Review", f"[yellow]{final_review}[/]")
    table.add_row("Denied", f"[red]{denied}[/]")
    table.add_row("Timed Out", f"[magenta]{timed_out}[/]")
    table.add_row("Success Rate", f"[bold green]{success_rate:.0f}%[/]")

    return Panel(table, title="[bold]Suggestions", border_style="green")


def build_task_summary(conn):
    c = conn.cursor()
    c.execute("SELECT status, COUNT(*) FROM plan_tasks GROUP BY status")
    task_counts = dict(c.fetchall())

    total = sum(task_counts.values())
    completed = task_counts.get("COMPLETED", 0)
    pending = task_counts.get("PENDING", 0)
    reviewing = task_counts.get("REVIEWING", 0)
    in_progress = task_counts.get("IN_PROGRESS", 0)

    # Progress bar
    pct = (completed / total * 100) if total > 0 else 0
    bar_width = 20
    filled = int(bar_width * pct / 100)
    bar = f"[green]{'█' * filled}[/][dim]{'░' * (bar_width - filled)}[/] {pct:.0f}%"

    table = Table(box=box.SIMPLE, show_header=False, padding=(0, 1))
    table.add_column("label", style="dim", width=12)
    table.add_column("value", width=22)

    table.add_row("Total", f"[bold white]{total}[/]")
    table.add_row("Completed", f"[green]{completed}[/]")
    table.add_row("Pending", f"[yellow]{pending}[/]")
    table.add_row("Reviewing", f"[cyan]{reviewing}[/]")
    table.add_row("In Progress", f"[blue]{in_progress}[/]")
    table.add_row("Completion", bar)

    return Panel(table, title="[bold]Plan Tasks", border_style="cyan")


def build_activity_summary(conn):
    c = conn.cursor()

    c.execute("SELECT COUNT(*) FROM suggestion_messages")
    total_msgs = c.fetchone()[0]

    c.execute("SELECT COUNT(DISTINCT suggestion_id) FROM suggestion_messages")
    active_threads = c.fetchone()[0]

    c.execute("SELECT COUNT(*) FROM app_users")
    user_count = c.fetchone()[0]

    c.execute("SELECT COUNT(*) FROM suggestions WHERE pr_url IS NOT NULL")
    pr_count = c.fetchone()[0]

    db_size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    db_size_mb = db_size / (1024 * 1024)

    table = Table(box=box.SIMPLE, show_header=False, padding=(0, 1))
    table.add_column("label", style="dim", width=14)
    table.add_column("value", width=12)

    table.add_row("Messages", f"[bold white]{total_msgs}[/]")
    table.add_row("Active Threads", f"[cyan]{active_threads}[/]")
    table.add_row("Users", f"[yellow]{user_count}[/]")
    table.add_row("PRs Created", f"[green]{pr_count}[/]")
    table.add_row("DB Size", f"[dim]{db_size_mb:.1f} MB[/]")

    return Panel(table, title="[bold]Activity", border_style="yellow")


def build_suggestions_table(conn, page=0, page_size=10, filter_status=None, sort_by="id"):
    c = conn.cursor()

    query = "SELECT id, title, status, up_votes, down_votes, priority, pr_url, created_at, last_activity_at FROM suggestions"
    params = []
    if filter_status:
        query += " WHERE status = ?"
        params.append(filter_status)

    sort_map = {
        "id": "id DESC",
        "votes": "(up_votes - down_votes) DESC",
        "activity": "last_activity_at DESC",
        "created": "created_at DESC",
        "status": "status ASC",
    }
    query += f" ORDER BY {sort_map.get(sort_by, 'id DESC')}"

    # Get total count for pagination
    count_query = "SELECT COUNT(*) FROM suggestions"
    if filter_status:
        count_query += " WHERE status = ?"
    c.execute(count_query, params)
    total = c.fetchone()[0]
    total_pages = max(1, (total + page_size - 1) // page_size)
    page = max(0, min(page, total_pages - 1))

    query += f" LIMIT {page_size} OFFSET {page * page_size}"
    c.execute(query, params)
    rows = c.fetchall()

    table = Table(box=box.ROUNDED, show_lines=False, padding=(0, 1))
    table.add_column("#", style="dim", width=4, justify="right")
    table.add_column("Title", min_width=30, max_width=50, no_wrap=True)
    table.add_column("Status", width=14)
    table.add_column("Priority", width=8)
    table.add_column("Votes", width=6, justify="center")
    table.add_column("PR", width=5, justify="center")
    table.add_column("Activity", width=10)

    for row in rows:
        sid = str(row["id"])
        title = row["title"] or "(untitled)"
        if len(title) > 48:
            title = title[:45] + "..."
        status = row["status"] or "UNKNOWN"
        status_style = STATUS_COLORS.get(status, "white")
        priority = row["priority"] or "-"
        priority_style = PRIORITY_COLORS.get(priority, "dim")
        votes = row["up_votes"] - row["down_votes"]
        vote_str = f"+{votes}" if votes > 0 else str(votes)
        vote_style = "green" if votes > 0 else ("red" if votes < 0 else "dim")
        has_pr = "[green]Y[/]" if row["pr_url"] else "[dim]-[/]"
        activity = time_ago(row["last_activity_at"])

        table.add_row(
            sid,
            title,
            f"[{status_style}]{status}[/]",
            f"[{priority_style}]{priority}[/]",
            f"[{vote_style}]{vote_str}[/]",
            has_pr,
            activity,
        )

    filter_label = f" | Filter: {filter_status}" if filter_status else ""
    sort_label = f" | Sort: {sort_by}"
    title = f"[bold]Suggestions[/] [dim](Page {page + 1}/{total_pages}{filter_label}{sort_label})[/]"
    return Panel(table, title=title, border_style="blue")


def build_suggestion_detail(conn, suggestion_id):
    c = conn.cursor()
    c.execute("SELECT * FROM suggestions WHERE id = ?", (suggestion_id,))
    row = c.fetchone()
    if not row:
        return Panel(f"[red]Suggestion #{suggestion_id} not found[/]", title="Detail", border_style="red")

    detail = Text()
    detail.append(f"#{row['id']} ", style="bold dim")
    detail.append(f"{row['title']}\n", style="bold white")
    detail.append(f"\nStatus: ", style="dim")
    detail.append(f"{row['status']}\n", style=STATUS_COLORS.get(row["status"], "white"))
    detail.append(f"Priority: ", style="dim")
    detail.append(f"{row['priority'] or 'N/A'}\n", style=PRIORITY_COLORS.get(row["priority"] or "", "dim"))
    detail.append(f"Author: ", style="dim")
    detail.append(f"{row['author_name'] or 'N/A'}\n", style="white")
    detail.append(f"Created: ", style="dim")
    detail.append(f"{ts_to_str(row['created_at'])}\n", style="white")
    detail.append(f"Last Activity: ", style="dim")
    detail.append(f"{time_ago(row['last_activity_at'])}\n", style="white")

    if row["pr_url"]:
        detail.append(f"PR: ", style="dim")
        detail.append(f"{row['pr_url']}\n", style="cyan underline")

    if row["description"]:
        desc = row["description"]
        if len(desc) > 300:
            desc = desc[:297] + "..."
        detail.append(f"\n{desc}\n", style="white")

    if row["plan_display_summary"]:
        summary = row["plan_display_summary"]
        if len(summary) > 500:
            summary = summary[:497] + "..."
        detail.append(f"\n[bold]Plan Summary:[/]\n{summary}\n")

    # Tasks for this suggestion
    c.execute("SELECT title, status, status_detail FROM plan_tasks WHERE suggestion_id = ? ORDER BY task_order", (suggestion_id,))
    tasks = c.fetchall()
    if tasks:
        detail.append(f"\n[bold]Tasks ({len(tasks)}):[/]\n")
        for t in tasks:
            icon = {"COMPLETED": "[green]✓[/]", "PENDING": "[yellow]○[/]", "IN_PROGRESS": "[blue]►[/]", "REVIEWING": "[cyan]◎[/]", "FAILED": "[red]✗[/]"}.get(t["status"], "?")
            task_title = t["title"] or "(untitled)"
            if len(task_title) > 60:
                task_title = task_title[:57] + "..."
            detail.append(f"  {icon} {task_title}\n")

    # Recent messages
    c.execute("SELECT sender_name, sender_type, content, created_at FROM suggestion_messages WHERE suggestion_id = ? ORDER BY created_at DESC LIMIT 5", (suggestion_id,))
    msgs = c.fetchall()
    if msgs:
        detail.append(f"\n[bold]Recent Messages ({len(msgs)}):[/]\n")
        for m in reversed(msgs):
            sender = m["sender_name"] or m["sender_type"]
            content = m["content"] or ""
            if len(content) > 120:
                content = content[:117] + "..."
            sender_style = "cyan" if m["sender_type"] == "SYSTEM" else "yellow"
            detail.append(f"  [{sender_style}]{sender}[/]: {content}\n")

    return Panel(detail, title=f"[bold]Suggestion #{suggestion_id}", border_style="magenta")


def build_recent_activity(conn, limit=8):
    c = conn.cursor()
    c.execute("""
        SELECT sm.sender_name, sm.sender_type, sm.content, sm.created_at, sm.suggestion_id, s.title
        FROM suggestion_messages sm
        JOIN suggestions s ON s.id = sm.suggestion_id
        ORDER BY sm.created_at DESC
        LIMIT ?
    """, (limit,))
    msgs = c.fetchall()

    table = Table(box=box.SIMPLE, show_header=True, padding=(0, 1))
    table.add_column("Time", width=8, style="dim")
    table.add_column("Sug#", width=5, style="dim")
    table.add_column("From", width=12)
    table.add_column("Message", min_width=30, no_wrap=True)

    for m in msgs:
        when = time_ago(m["created_at"])
        sid = f"#{m['suggestion_id']}"
        sender = m["sender_name"] or m["sender_type"] or "?"
        content = m["content"] or ""
        if len(content) > 80:
            content = content[:77] + "..."
        sender_style = "cyan" if m["sender_type"] == "SYSTEM" else "yellow"
        table.add_row(when, sid, f"[{sender_style}]{sender}[/]", content)

    return Panel(table, title="[bold]Recent Activity", border_style="magenta")


def build_services_panel():
    """Build a panel showing status of all managed services."""
    table = Table(box=box.ROUNDED, show_lines=False, padding=(0, 1))
    table.add_column("Service", min_width=28, no_wrap=True)
    table.add_column("Status", width=10)
    table.add_column("PID", width=8, justify="right")
    table.add_column("CPU%", width=6, justify="right")
    table.add_column("MEM%", width=6, justify="right")
    table.add_column("RSS", width=10, justify="right")
    table.add_column("Uptime", width=14)

    for key, svc in SERVICES.items():
        running, pid, info = get_service_status(key)
        status_str = "[green]RUNNING[/]" if running else "[red]DOWN[/]"
        pid_str = str(pid) if pid else "-"
        cpu = info.get("cpu", "-")
        mem = info.get("mem", "-")
        rss_kb = info.get("rss", "0")
        try:
            rss_val = int(rss_kb)
            rss_str = f"{rss_val // 1024} MB" if rss_val > 1024 else f"{rss_val} KB"
        except (ValueError, TypeError):
            rss_str = "-"
        uptime = info.get("uptime", "-")
        table.add_row(svc["name"], status_str, pid_str, cpu, mem, rss_str, uptime)

    # System resources
    try:
        result = subprocess.run(["free", "-m"], capture_output=True, text=True)
        for line in result.stdout.splitlines():
            if line.startswith("Mem:"):
                parts = line.split()
                total, used = int(parts[1]), int(parts[2])
                pct = used * 100 // total if total else 0
                table.add_row(
                    "[dim]System Memory[/]", f"[dim]{pct}%[/]", "-",
                    "-", f"[dim]{pct}[/]", f"[dim]{used}MB[/]", f"[dim]{total}MB total[/]",
                )
    except Exception:
        pass

    try:
        result = subprocess.run(["df", "-h", "/home/claude"], capture_output=True, text=True)
        lines = result.stdout.strip().splitlines()
        if len(lines) >= 2:
            parts = lines[1].split()
            table.add_row(
                "[dim]Disk (/home/claude)[/]", f"[dim]{parts[4]}[/]", "-",
                "-", "-", f"[dim]{parts[2]}[/]", f"[dim]{parts[1]} total[/]",
            )
    except Exception:
        pass

    return Panel(table, title="[bold]Services & Resources", border_style="yellow")


def build_claude_panel(claude_session):
    """Build a panel showing recent Claude conversation history."""
    content = Text()

    if claude_session is None:
        content.append("  No Claude session active. Press [c] to chat with Claude.\n", style="dim")
        return Panel(content, title="[bold]Claude Session", border_style="cyan", height=12)

    history = claude_session.get_recent_history(6)
    if not history:
        content.append("  Session ready. Press [c] to send a message to Claude.\n", style="dim")
    else:
        for role, text in history:
            if role == "user":
                content.append("  You: ", style="bold yellow")
                # Truncate long messages for display
                display_text = text if len(text) <= 100 else text[:97] + "..."
                content.append(f"{display_text}\n", style="white")
            else:
                content.append("  Claude: ", style="bold cyan")
                # Show first few lines
                lines = text.split("\n")
                preview = "\n         ".join(lines[:6])
                if len(lines) > 6:
                    preview += "\n         ..."
                content.append(f"{preview}\n", style="white")
            content.append("\n")

    if claude_session.working:
        content.append("  [bold yellow]Working...[/]\n")

    return Panel(content, title="[bold]Claude Session", border_style="cyan")


def build_commands_panel():
    t = Text()
    t.append("  Navigation\n", style="bold underline")
    t.append("  [d] ", style="bold cyan"); t.append("Dashboard  ")
    t.append("[l] ", style="bold cyan"); t.append("List  ")
    t.append("[a] ", style="bold cyan"); t.append("Activity\n")
    t.append("  [sv] ", style="bold cyan"); t.append("Services  ")
    t.append("[h] ", style="bold cyan"); t.append("Help  ")
    t.append("[r] ", style="bold cyan"); t.append("Refresh  ")
    t.append("[q] ", style="bold cyan"); t.append("Quit\n")

    t.append("\n  Suggestions\n", style="bold underline")
    t.append("  [v] <id>  ", style="bold cyan"); t.append("View detail\n")
    t.append("  [n] / [p] ", style="bold cyan"); t.append("Next/prev page\n")
    t.append("  [f] <status> ", style="bold cyan"); t.append("Filter list\n")
    t.append("  [s] <field>  ", style="bold cyan"); t.append("Sort list\n")

    t.append("\n  Services\n", style="bold underline")
    t.append("  [start/stop/restart] ", style="bold cyan"); t.append("<svc>\n")
    t.append("  [restart all]        ", style="bold cyan"); t.append("All svcs\n")

    t.append("\n  Claude\n", style="bold underline")
    t.append("  [c] <msg>  ", style="bold cyan"); t.append("Send message\n")
    t.append("  [c]        ", style="bold cyan"); t.append("Multi-line mode\n")
    return Panel(t, title="[bold]Commands", border_style="green")


def build_help():
    help_text = Text()
    help_text.append("  Navigation & Views\n", style="bold underline")
    help_text.append("  [h]  ", style="bold cyan")
    help_text.append("Help          ")
    help_text.append("[d]  ", style="bold cyan")
    help_text.append("Dashboard     ")
    help_text.append("[l]  ", style="bold cyan")
    help_text.append("List view     ")
    help_text.append("[a]  ", style="bold cyan")
    help_text.append("Activity feed\n")
    help_text.append("  [sv] ", style="bold cyan")
    help_text.append("Services view\n")

    help_text.append("\n  Suggestion Detail\n", style="bold underline")
    help_text.append("  [v] <id>  ", style="bold cyan")
    help_text.append("View suggestion detail\n")

    help_text.append("\n  Service Management\n", style="bold underline")
    help_text.append("  [start] <svc>   ", style="bold cyan")
    help_text.append("Start a service (app, extractor)\n")
    help_text.append("  [stop] <svc>    ", style="bold cyan")
    help_text.append("Stop a service\n")
    help_text.append("  [restart] <svc> ", style="bold cyan")
    help_text.append("Restart a service\n")
    help_text.append("  [restart] all   ", style="bold cyan")
    help_text.append("Restart all services\n")

    help_text.append("\n  Claude Interaction\n", style="bold underline")
    help_text.append("  [c] <message>   ", style="bold cyan")
    help_text.append("Send a message to Claude\n")
    help_text.append("  [c]             ", style="bold cyan")
    help_text.append("Enter multi-line Claude chat mode (empty line to send)\n")

    help_text.append("\n  List Controls\n", style="bold underline")
    help_text.append("  [n/p]     ", style="bold cyan")
    help_text.append("Next/previous page\n")
    help_text.append("  [f] <status>  ", style="bold cyan")
    help_text.append("Filter by status (MERGED, IN_PROGRESS, DENIED, etc.)\n")
    help_text.append("  [f] clear     ", style="bold cyan")
    help_text.append("Clear filter\n")
    help_text.append("  [s] <field>   ", style="bold cyan")
    help_text.append("Sort by: id, votes, activity, created, status\n")

    help_text.append("\n  Other\n", style="bold underline")
    help_text.append("  [r]  ", style="bold cyan")
    help_text.append("Refresh data  ")
    help_text.append("[q]  ", style="bold cyan")
    help_text.append("Quit\n")

    return Panel(help_text, title="[bold]Keyboard Commands", border_style="green")


# ── Main views ──────────────────────────────────────────────────────


def _available_height(reserved_lines=2):
    """Return the height available for the layout, reserving lines for cmd bar and prompt."""
    term_h = shutil.get_terminal_size((80, 24)).lines
    return max(12, term_h - reserved_lines)


def render_dashboard(conn, settings, claude_session=None):
    avail = _available_height()
    header_h = 3
    top_h = min(14, max(8, (avail - header_h) * 4 // 10))
    commands_h = min(14, max(10, avail // 4))
    mid_h = avail - header_h - top_h - commands_h

    layout = Layout(size=avail)
    layout.split_column(
        Layout(name="header", size=header_h),
        Layout(name="top", size=top_h),
        Layout(name="mid", size=max(4, mid_h)),
        Layout(name="commands", size=commands_h),
    )
    layout["header"].update(build_header(settings))
    layout["top"].split_row(
        Layout(build_status_summary(conn), name="status"),
        Layout(build_task_summary(conn), name="tasks"),
        Layout(build_activity_summary(conn), name="activity"),
    )
    activity_limit = max(3, mid_h - 3)
    layout["mid"].split_row(
        Layout(build_recent_activity(conn, limit=activity_limit), name="recent_activity", ratio=3),
        Layout(build_services_panel(), name="services", ratio=2),
        Layout(build_claude_panel(claude_session), name="claude", ratio=2),
    )
    layout["commands"].update(build_commands_panel())
    return layout


def render_list(conn, settings, page, filter_status, sort_by):
    avail = _available_height()
    layout = Layout(size=avail)
    layout.split_column(
        Layout(name="header", size=3),
        Layout(name="table"),
    )
    layout["header"].update(build_header(settings))
    page_size = max(5, avail - 3 - 5)  # header + table chrome
    layout["table"].update(build_suggestions_table(conn, page, page_size, filter_status, sort_by))
    return layout


def render_detail(conn, settings, suggestion_id):
    avail = _available_height()
    layout = Layout(size=avail)
    layout.split_column(
        Layout(name="header", size=3),
        Layout(name="detail"),
    )
    layout["header"].update(build_header(settings))
    layout["detail"].update(build_suggestion_detail(conn, suggestion_id))
    return layout


def render_activity(conn, settings):
    avail = _available_height()
    layout = Layout(size=avail)
    layout.split_column(
        Layout(name="header", size=3),
        Layout(name="activity"),
    )
    layout["header"].update(build_header(settings))
    activity_limit = max(5, avail - 3 - 4)
    layout["activity"].update(build_recent_activity(conn, limit=activity_limit))
    return layout


def render_services(conn, settings, claude_session=None):
    avail = _available_height()
    svc_h = min(12, max(6, avail // 3))
    layout = Layout(size=avail)
    layout.split_column(
        Layout(name="header", size=3),
        Layout(name="services", size=svc_h),
        Layout(name="claude"),
    )
    layout["header"].update(build_header(settings))
    layout["services"].update(build_services_panel())
    layout["claude"].update(build_claude_panel(claude_session))
    return layout


def render_claude_view(conn, settings, claude_session):
    avail = _available_height()
    layout = Layout(size=avail)
    layout.split_column(
        Layout(name="header", size=3),
        Layout(name="claude"),
    )
    layout["header"].update(build_header(settings))
    layout["claude"].update(build_claude_panel(claude_session))
    return layout


def get_settings(conn):
    c = conn.cursor()
    c.execute("SELECT * FROM site_settings LIMIT 1")
    row = c.fetchone()
    if row:
        return dict(row)
    return {"site_name": "Site Manager", "claude_model": "unknown", "target_repo_url": ""}


# ── Interactive loop ────────────────────────────────────────────────


def main():
    console = Console()

    # Initialize Claude session
    claude_session = ClaudeSession(console)

    # State
    view = "dashboard"  # dashboard, list, detail, activity, help, services, claude
    page = 0
    filter_status = None
    sort_by = "id"
    detail_id = None
    status_msg = None  # one-line status message shown after command bar

    def refresh():
        conn = get_db()
        settings = get_settings(conn)
        if view == "dashboard":
            result = render_dashboard(conn, settings, claude_session)
        elif view == "list":
            result = render_list(conn, settings, page, filter_status, sort_by)
        elif view == "detail" and detail_id:
            result = render_detail(conn, settings, detail_id)
        elif view == "activity":
            result = render_activity(conn, settings)
        elif view == "services":
            result = render_services(conn, settings, claude_session)
        elif view == "claude":
            result = render_claude_view(conn, settings, claude_session)
        elif view == "help":
            avail = _available_height()
            layout = Layout(size=avail)
            layout.split_column(
                Layout(name="header", size=3),
                Layout(name="help"),
            )
            layout["header"].update(build_header(settings))
            layout["help"].update(build_help())
            result = layout
        else:
            result = render_dashboard(conn, settings, claude_session)
        conn.close()
        return result

    # ── Startup: launch Claude for initial health check ──
    console.clear()
    console.print(Panel(
        "[bold cyan]Site Manager Dashboard[/]\n\n"
        "Starting Claude session and checking service health...",
        border_style="cyan",
    ))

    # Run initial health check via Claude
    with console.status("[bold cyan]Claude is checking service status...", spinner="dots"):
        startup_response = claude_session.send_message(
            "Check the current status of the site-manager application. "
            "Run: ps aux | grep -E 'auto-update|extract-errors|gradlew|bootRun' | grep -v grep "
            "to see what's running. Also check if app.log and error.log exist and show "
            "the last 5 lines of each if they do. Report a brief status summary."
        )

    console.clear()
    console.print(refresh())
    if status_msg:
        console.print(f"\n{status_msg}")
        status_msg = None

    while True:
        try:
            cmd = Prompt.ask("[bold blue]>[/]").strip()
        except (KeyboardInterrupt, EOFError):
            break

        cmd_lower = cmd.lower()

        if not cmd:
            continue
        elif cmd_lower == "q":
            console.print("[dim]Goodbye![/]")
            break
        elif cmd_lower == "d":
            view = "dashboard"
        elif cmd_lower == "l":
            view = "list"
            page = 0
        elif cmd_lower == "a":
            view = "activity"
        elif cmd_lower == "sv":
            view = "services"
        elif cmd_lower == "h":
            view = "help"
        elif cmd_lower == "r":
            pass  # just refresh
        elif cmd_lower == "n":
            page += 1
            view = "list"
        elif cmd_lower == "p":
            page = max(0, page - 1)
            view = "list"

        # ── Service management commands ──
        elif cmd_lower.startswith("start "):
            svc_key = cmd_lower.split(None, 1)[1].strip()
            if svc_key in SERVICES:
                with console.status(f"[bold yellow]Starting {SERVICES[svc_key]['name']}..."):
                    ok, msg = start_service(svc_key)
                status_msg = f"[green]{msg}[/]" if ok else f"[red]{msg}[/]"
                view = "services"
            else:
                status_msg = f"[red]Unknown service: {svc_key}. Available: {', '.join(SERVICES.keys())}[/]"

        elif cmd_lower.startswith("stop "):
            svc_key = cmd_lower.split(None, 1)[1].strip()
            if svc_key in SERVICES:
                with console.status(f"[bold yellow]Stopping {SERVICES[svc_key]['name']}..."):
                    ok, msg = stop_service(svc_key)
                status_msg = f"[green]{msg}[/]" if ok else f"[red]{msg}[/]"
                view = "services"
            else:
                status_msg = f"[red]Unknown service: {svc_key}. Available: {', '.join(SERVICES.keys())}[/]"

        elif cmd_lower.startswith("restart "):
            svc_key = cmd_lower.split(None, 1)[1].strip()
            if svc_key == "all":
                msgs = []
                for key in SERVICES:
                    with console.status(f"[bold yellow]Restarting {SERVICES[key]['name']}..."):
                        ok, msg = restart_service(key)
                    msgs.append(f"[green]{msg}[/]" if ok else f"[red]{msg}[/]")
                status_msg = " | ".join(msgs)
                view = "services"
            elif svc_key in SERVICES:
                with console.status(f"[bold yellow]Restarting {SERVICES[svc_key]['name']}..."):
                    ok, msg = restart_service(svc_key)
                status_msg = f"[green]{msg}[/]" if ok else f"[red]{msg}[/]"
                view = "services"
            else:
                status_msg = f"[red]Unknown service: {svc_key}. Available: {', '.join(SERVICES.keys())}, all[/]"

        # ── Claude interaction ──
        elif cmd_lower == "c":
            # Multi-line chat mode
            console.print("[bold cyan]Claude chat mode[/] [dim](type your message, empty line to send, 'exit' to cancel)[/]")
            lines = []
            while True:
                try:
                    line = Prompt.ask("[dim]...[/]")
                    if line.strip().lower() == "exit":
                        lines = []
                        break
                    if line == "" and lines:
                        break
                    lines.append(line)
                except (KeyboardInterrupt, EOFError):
                    lines = []
                    break
            if lines:
                message = "\n".join(lines)
                with console.status("[bold cyan]Claude is working..."):
                    claude_session.send_message(message)
                view = "claude"
            else:
                status_msg = "[dim]Chat cancelled[/]"

        elif cmd_lower.startswith("c "):
            message = cmd[2:].strip()
            if message:
                with console.status("[bold cyan]Claude is working..."):
                    claude_session.send_message(message)
                view = "claude"

        elif cmd_lower.startswith("v "):
            try:
                detail_id = int(cmd.split()[1])
                view = "detail"
            except (ValueError, IndexError):
                console.print("[red]Usage: v <suggestion_id>[/]")
                continue
        elif cmd_lower.startswith("f "):
            arg = cmd_lower[2:].strip().upper()
            if arg == "CLEAR":
                filter_status = None
            else:
                filter_status = arg
            page = 0
            view = "list"
        elif cmd_lower.startswith("s ") and not cmd_lower.startswith("start") and not cmd_lower.startswith("stop"):
            arg = cmd_lower[2:].strip()
            if arg in ("id", "votes", "activity", "created", "status"):
                sort_by = arg
            else:
                console.print(f"[red]Unknown sort field: {arg}. Use: id, votes, activity, created, status[/]")
                continue
            view = "list"
        else:
            console.print(f"[red]Unknown command: {cmd}. Press 'h' for help.[/]")
            continue

        console.clear()
        console.print(refresh())
        if status_msg:
            console.print(f"\n{status_msg}")
            status_msg = None


if __name__ == "__main__":
    main()
