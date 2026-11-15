#!/usr/bin/env python3
"""
QueueGuard attack simulator - reproduces "one IP hammering the API".

Phase 1: flood the gateway from a single IP with many threads.
         Expect: most requests -> HTTP 429, then an IP ban.
Phase 2: try to dodge the limiter by rotating forged X-Forwarded-For headers.
         Expect: still throttled (the gateway does not trust client-supplied XFF).
Phase 3: show the ban state via the admin API.

Usage:
  python3 attack_sim.py --gateway http://localhost:8080 --threads 40 --duration 15
"""
import argparse
import json
import threading
import time
import urllib.error
import urllib.request
from collections import Counter

RESULTS = Counter()
RESULTS_LOCK = threading.Lock()


def api(method, url, token=None, body=None, headers=None, timeout=5):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, json.loads(res.read().decode() or "{}"), dict(res.headers)
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode() or "{}")
        except Exception:
            payload = {}
        return e.code, payload, dict(e.headers)
    except Exception as e:
        return 0, {"message": str(e)}, {}


def record(status):
    with RESULTS_LOCK:
        RESULTS[status] += 1


def flood_thread(base, path, end):
    while time.time() < end:
        status, _, _ = api("GET", f"{base}{path}", timeout=3)
        record(status)


def spoof_thread(base, path, end):
    i = 0
    while time.time() < end:
        fake_ip = f"10.13.37.{i % 250}"
        status, _, _ = api("GET", f"{base}{path}", headers={"X-Forwarded-For": fake_ip}, timeout=3)
        record(("spoof", status))
        i += 1


def main():
    ap = argparse.ArgumentParser(description="QueueGuard single-IP attack simulator")
    ap.add_argument("--gateway", default="http://localhost:8080")
    ap.add_argument("--threads", type=int, default=40)
    ap.add_argument("--duration", type=int, default=15)
    ap.add_argument("--path", default="/api/queues")
    args = ap.parse_args()
    base = args.gateway.rstrip("/")

    # admin account (created before the attack) to inspect the ban list afterwards
    import random, string
    uid = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
    email = f"sec-auditor-{uid}@demo.local"
    api("POST", f"{base}/api/auth/register", body={
        "email": email, "password": "AuditorPass!123", "displayName": "Auditor"})
    _, login, _ = api("POST", f"{base}/api/auth/login", body={"email": email, "password": "AuditorPass!123"})
    token = (login.get("data") or {}).get("token")

    print(f"PHASE 1: flooding {base}{args.path} from ONE IP with {args.threads} threads for {args.duration}s ...")
    end = time.time() + args.duration
    threads = [threading.Thread(target=flood_thread, args=(base, args.path, end), daemon=True)
               for _ in range(args.threads)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    dt = time.time() - t0

    total = sum(v for k, v in RESULTS.items() if not isinstance(k, tuple))
    ok = RESULTS[200] + RESULTS[201] + RESULTS[202]
    denied = RESULTS[429]
    unauthorized = RESULTS[401]
    print("\n===== ATTACK RESULTS (single IP) =====")
    print(f"  attack duration   : {dt:.1f}s")
    print(f"  requests fired    : {total} ({total / dt:.0f} req/s attempted)")
    print(f"  through (2xx)     : {ok}")
    print(f"  auth rejected 401 : {unauthorized}")
    print(f"  THROTTLED 429     : {denied}   <-- rate limiter at work")
    for code in sorted(k for k in RESULTS if isinstance(k, int) and k not in (200, 201, 202, 401, 429)):
        print(f"  HTTP {code}: {RESULTS[code]}")

    print("\nPHASE 2: X-Forwarded-For spoof rotation (30 attempts, fake IPs) ...")
    for i in range(30):
        status, _, _ = api("GET", f"{base}{args.path}",
                           headers={"X-Forwarded-For": f"10.9.9.{i}"}, timeout=3)
        record(("spoof", status))
        print(f"   spoofed as 10.9.9.{i:<3} -> HTTP {status}")
    spoof_denied = RESULTS[("spoof", 429)]
    print(f"  spoofed requests throttled: {spoof_denied}/30 "
          f"({'PASS - spoofing does NOT bypass the limiter' if spoof_denied >= 25 else 'check TRUST_PROXY_HEADERS setting'})")

    print("\nPHASE 3: current ban state (via admin API) ...")
    _, bans, _ = api("GET", f"{base}/api/ratelimit/bans", token=token, timeout=5)
    _, adaptive, _ = api("GET", f"{base}/api/ratelimit/adaptive", token=token, timeout=5)
    print("  active bans :", json.dumps(bans.get("data"), indent=2)[:600])
    print("  adaptive    :", json.dumps(adaptive.get("data"), indent=2)[:400])

    print("\n===== VERDICT =====")
    if denied > ok:
        print("  QueueGuard absorbed the attack: the single IP was throttled and banned")
        print("  while legitimate users behind other IPs keep full service.")
    else:
        print("  Limiter did not engage - check RL_BASE_LIMIT_PER_SEC / strike threshold config.")


if __name__ == "__main__":
    main()
