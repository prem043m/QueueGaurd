#!/usr/bin/env python3
"""
QueueGuard multi-user load test (stdlib only, no pip installs).

Simulates N concurrent users, each with its own account and JWT:
register -> login -> enqueue messages -> poll stats.

Usage:
  python3 load_test.py --gateway http://localhost:8080 --users 12 --duration 20
"""
import argparse
import json
import random
import string
import threading
import time
import urllib.error
import urllib.request
from collections import Counter

RESULTS = Counter()
RESULTS_LOCK = threading.Lock()


def api(method, url, token=None, body=None, timeout=5):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            payload = json.loads(res.read().decode() or "{}")
            return res.status, payload
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode() or "{}")
        except Exception:
            payload = {}
        return e.code, payload
    except Exception as e:
        return 0, {"message": str(e)}


def record(status):
    with RESULTS_LOCK:
        RESULTS[status] += 1


def uid():
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=10))


def user_loop(base, idx, duration, end):
    email = f"user{idx}-{uid()}@demo.local"
    password = "DemoPass!123"

    status, _ = api("POST", f"{base}/api/auth/register", body={
        "email": email, "password": password, "displayName": f"User {idx}"})
    record(status)

    status, res = api("POST", f"{base}/api/auth/login", body={"email": email, "password": password})
    record(status)
    token = (res.get("data") or {}).get("token")
    if not token:
        return

    while time.time() < end:
        action = random.random()
        if action < 0.7:
            status, _ = api("POST", f"{base}/api/queues/default/messages", token=token, body={
                "payload": json.dumps({"workMs": random.randint(20, 120), "user": email}),
                "priority": random.randint(0, 9)})
        elif action < 0.9:
            status, _ = api("GET", f"{base}/api/queues/default/stats", token=token)
        else:
            status, _ = api("GET", f"{base}/api/workers/stats", token=token)
        record(status)
        time.sleep(random.uniform(1.0, 2.5))


def main():
    ap = argparse.ArgumentParser(description="QueueGuard multi-user load test")
    ap.add_argument("--gateway", default="http://localhost:8080")
    ap.add_argument("--users", type=int, default=12)
    ap.add_argument("--duration", type=int, default=20, help="seconds after login phase")
    args = ap.parse_args()
    base = args.gateway.rstrip("/")
    end = time.time() + args.duration

    # bootstrap: one admin account creates the shared 'default' queue
    admin_email = f"admin-{uid()}@demo.local"
    api("POST", f"{base}/api/auth/register", body={
        "email": admin_email, "password": "AdminPass!123", "displayName": "Admin"})
    _, res = api("POST", f"{base}/api/auth/login", body={"email": admin_email, "password": "AdminPass!123"})
    admin_token = (res.get("data") or {}).get("token")
    if not admin_token:
        print("FATAL: could not log in admin user - is the gateway up at", base)
        return
    status, _ = api("POST", f"{base}/api/queues", token=admin_token, body={
        "name": "default", "description": "demo queue", "maxConcurrency": 32, "maxAttempts": 5})
    print(f"setup: queue 'default' ready ({status})")

    print(f"launching {args.users} concurrent users for {args.duration}s against {base} ...")
    threads = [threading.Thread(target=user_loop, args=(base, i, args.duration, end), daemon=True)
               for i in range(args.users)]
    t0 = time.time()
    for t in threads:
        t.start()
        time.sleep(0.15)  # staggered arrivals, like real users
    for t in threads:
        t.join()
    dt = time.time() - t0

    total = sum(RESULTS.values())
    print("\n===== MULTI-USER LOAD TEST RESULTS =====")
    print(f"users           : {args.users}")
    print(f"duration        : {dt:.1f}s")
    print(f"total requests  : {total} ({total / dt:.1f} req/s average)")
    for code, count in sorted(RESULTS.items()):
        label = {200: "OK", 201: "Created", 202: "Accepted", 401: "Unauthorized",
                 409: "Conflict", 429: "Rate limited", 0: "Network errors"}.get(code, "")
        print(f"  HTTP {code:<4} {label:<12} : {count}")
    print("verdict: concurrent multi-user traffic served" if RESULTS[429] == 0
          else "verdict: some users were throttled - lower --users or raise RL_BASE_LIMIT_PER_SEC")


if __name__ == "__main__":
    main()
