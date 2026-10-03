#!/usr/bin/env python3
"""On-sale stampede against a live seat-reservation API.

Usage:
  ./burst.sh http://localhost:8080
  python3 burst.py https://your-service.onrender.com --hot 500 --users 100
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def request(method: str, url: str, token: str | None = None, body: dict | None = None, extra_headers: dict | None = None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Accept": "*/*", "X-Request-Id": str(uuid.uuid4())}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if extra_headers:
        headers.update(extra_headers)
    req = Request(url, data=data, headers=headers, method=method)
    try:
        with urlopen(req, timeout=30) as resp:
            raw = resp.read().decode()
            parsed = {}
            if raw:
                try:
                    parsed = json.loads(raw)
                except json.JSONDecodeError:
                    parsed = {}
            return resp.status, parsed, raw
    except HTTPError as exc:
        raw = exc.read().decode()
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"raw": raw}
        return exc.code, parsed, raw
    except URLError as exc:
        return 599, {"error": "transport", "message": str(exc.reason)}, ""


def classify(status: int, body: dict) -> str:
    if status >= 500 or status == 599:
        return "http_5xx"
    if status == 201:
        return "confirmed"
    if status == 409:
        error = body.get("error", "conflict")
        return {
            "seat_taken": "declined_seat_taken",
            "per_user_limit": "declined_per_user_limit",
            "idempotency_mismatch": "declined_idempotency_mismatch",
        }.get(error, f"declined_{error}")
    if status == 429:
        return "declined_busy"
    return f"http_{status}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Reproduce an on-sale seat stampede")
    parser.add_argument("base_url")
    parser.add_argument("--admin-token", default="admin-secret")
    parser.add_argument("--hot", type=int, default=400, help="concurrent attempts on the same hot seat")
    parser.add_argument("--users", type=int, default=80, help="distinct users in the mixed wave")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    live_status, live, _ = request("GET", f"{base}/health/live")
    ready_status, ready, _ = request("GET", f"{base}/health/ready")
    print(f"health live={live_status} {live} ready={ready_status} {ready}")
    if ready_status != 200:
        print("service is not ready; aborting")
        return 1

    seats = [f"A{i}" for i in range(1, 51)] + ["HOT"] + [f"L{i}" for i in range(1, 11)] + ["REPLAY"]
    show_status, show, _ = request(
        "POST",
        f"{base}/shows",
        token=args.admin_token,
        body={"name": f"burst-{int(time.time())}", "seats": seats, "price_paise": 25000, "per_user_limit": 4},
    )
    if show_status != 201:
        print(f"failed to create show: {show_status} {show}")
        return 1
    show_id = show["id"]
    print(f"show_id={show_id} total_seats={show['total_seats']}")

    jobs = []

    def reserve(user: str, wanted: list[str], key: str):
        started = time.perf_counter()
        status, body, _ = request(
            "POST",
            f"{base}/shows/{show_id}/reserve",
            token=user,
            body={"seats": wanted, "idempotency_key": key, "user_id": "spoofed-should-be-ignored"},
        )
        return classify(status, body), status, body, time.perf_counter() - started

    for i in range(args.hot):
        jobs.append(("hot", f"hot-user-{i}", ["HOT"], f"hot-{i}"))

    for i in range(args.users):
        jobs.append(("mixed", f"user-{i}", [f"A{(i % 50) + 1}"], f"mixed-{i}"))

    limit_user = "limit-user"
    for i in range(10):
        jobs.append(("limit", limit_user, [f"L{i + 1}"], f"limit-{i}"))

    counts = Counter()
    reservation_ids = set()
    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=min(256, len(jobs))) as pool:
        futures = [pool.submit(reserve, user, wanted, key) for _, user, wanted, key in jobs]
        for fut in as_completed(futures):
            kind, status, body, _latency = fut.result()
            counts[kind] += 1
            if kind == "confirmed" and "reservation_id" in body:
                reservation_ids.add(body["reservation_id"])

    first_replay = reserve("replay-user", ["REPLAY"], "replay-key")
    second_replay = reserve("replay-user", ["REPLAY"], "replay-key")
    mismatch = reserve("replay-user", ["A49"], "replay-key")
    counts[first_replay[0]] += 1
    if second_replay[0] == "confirmed" and first_replay[2].get("reservation_id") == second_replay[2].get("reservation_id"):
        counts["idempotent_replay"] += 1
    else:
        counts["idempotent_replay_failed"] += 1
    counts[mismatch[0]] += 1

    elapsed = time.perf_counter() - t0
    show_status, final, _ = request("GET", f"{base}/shows/{show_id}")
    available = final.get("available")
    held = final.get("held")
    confirmed = final.get("confirmed")
    total = final.get("total_seats")
    recon_ok = show_status == 200 and available + held + confirmed == total

    print()
    print("=== outcome distribution ===")
    for key in sorted(counts):
        print(f"{key}: {counts[key]}")
    print(f"distinct_reservations: {len(reservation_ids)}")
    print(f"elapsed_s: {elapsed:.3f}")
    print()
    print("=== reconciliation ===")
    print(f"available={available} held={held} confirmed={confirmed} total={total} ok={recon_ok}")

    metrics_status, _, raw_metrics = request("GET", f"{base}/actuator/prometheus")
    if metrics_status == 200:
        interesting = [
            line
            for line in raw_metrics.splitlines()
            if line.startswith("reservations_") or line.startswith("seats_")
        ]
        print()
        print("=== prometheus (subset) ===")
        for line in interesting:
            print(line)

    hot_confirmed = 0
    if show_status == 200:
        for seat in final.get("seats", []):
            if seat.get("label") == "HOT" and seat.get("status") == "confirmed":
                hot_confirmed += 1
    print()
    print(f"hot_seat_confirmed={hot_confirmed} (must be 1)")
    print(f"http_5xx={counts.get('http_5xx', 0)} (must be 0)")

    ok = recon_ok and hot_confirmed == 1 and counts.get("http_5xx", 0) == 0 and counts.get("idempotent_replay", 0) == 1
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
