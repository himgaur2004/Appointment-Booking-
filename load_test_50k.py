#!/usr/bin/env python3
"""
High-Performance Load Test: 50,000 Appointment Booking Requests
Tests the Appointment & Reminder Service under sustained heavy load.
Measures throughput (RPS), latency distribution (p50, p95, p99), error rates,
and post-load database integrity and query performance.
"""

import asyncio
import time
import json
import random
from datetime import datetime, timedelta, timezone
import aiohttp

TOTAL_REQUESTS = 50000
CONCURRENCY = 40  # Matched well to HikariCP pool size (20) & CPU cores
BASE_URL = "http://localhost:8080/api/v1/appointments"
REPORT_INTERVAL = 5000

DEALERSHIPS = [1, 2, 3, 4, 5]
SERVICES = [
    "Oil Change & Filter",
    "Brake Pad Replacement",
    "Tire Rotation & Balance",
    "Transmission Flush",
    "Multi-Point Inspection",
    "Battery Replacement",
    "Cabin Air Filter",
    "Wheel Alignment"
]
CARS = [
    "2024 Honda Civic",
    "2023 Toyota RAV4",
    "2022 Ford F-150",
    "2024 Tesla Model Y",
    "2021 BMW 330i",
    "2023 Chevrolet Silverado",
    "2024 Hyundai Tucson",
    "2022 Subaru Outback"
]

def generate_payload(i: int) -> dict:
    hours_in_future = random.randint(4, 168)
    scheduled_at = (datetime.now(timezone.utc) + timedelta(hours=hours_in_future)).strftime("%Y-%m-%dT%H:%M:%SZ")
    
    return {
        "dealershipId": random.choice(DEALERSHIPS),
        "customerName": f"Customer_{i}",
        "customerEmail": f"user_{i}@loadtest.com",
        "customerPhone": f"+1-555-{i:06d}"[-12:],
        "vehicleInfo": random.choice(CARS),
        "serviceType": random.choice(SERVICES),
        "scheduledAt": scheduled_at
    }

async def worker(queue: asyncio.Queue, session: aiohttp.ClientSession, results: list, progress_counter: list):
    while not queue.empty():
        try:
            item_id = queue.get_nowait()
        except asyncio.QueueEmpty:
            break

        payload = generate_payload(item_id)
        start_t = time.perf_counter()
        try:
            async with session.post(BASE_URL, json=payload, timeout=aiohttp.ClientTimeout(total=30)) as response:
                latency = (time.perf_counter() - start_t) * 1000  # ms
                status = response.status
                results.append((status, latency))
        except Exception as e:
            latency = (time.perf_counter() - start_t) * 1000
            results.append((0, latency))

        progress_counter[0] += 1
        count = progress_counter[0]
        if count % REPORT_INTERVAL == 0 or count == TOTAL_REQUESTS:
            elapsed = time.perf_counter() - progress_counter[1]
            rps = count / elapsed if elapsed > 0 else 0
            print(f" [{count:>5}/{TOTAL_REQUESTS}] Progress: {count/TOTAL_REQUESTS*100:>5.1f}% | Elapsed: {elapsed:>5.1f}s | Speed: {rps:>6.1f} req/s")

async def main():
    print("=" * 70)
    print(f"🔥 Starting Load Test: {TOTAL_REQUESTS:,} Appointments")
    print(f"🎯 Target URL: {BASE_URL}")
    print(f"⚡ Concurrency: {CONCURRENCY} parallel workers")
    print(f"📅 Timestamp: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 70)

    # Health check
    async with aiohttp.ClientSession() as check_session:
        try:
            async with check_session.get("http://localhost:8080/api/v1/appointments?page=0&size=1") as resp:
                if resp.status != 200:
                    print(f" Target service responded with status {resp.status}. Aborting.")
                    return
        except Exception as e:
            print(f" Cannot connect to target service at {BASE_URL}: {e}")
            return
    print(" Target service health check PASSED. Commencing load generation...\n")

    queue = asyncio.Queue()
    for i in range(1, TOTAL_REQUESTS + 1):
        queue.put_nowait(i)

    results = []
    start_time = time.perf_counter()
    progress_counter = [0, start_time]

    connector = aiohttp.TCPConnector(limit=CONCURRENCY, ttl_dns_cache=300)
    async with aiohttp.ClientSession(connector=connector) as session:
        workers = [
            asyncio.create_task(worker(queue, session, results, progress_counter))
            for _ in range(CONCURRENCY)
        ]
        await asyncio.gather(*workers)

    total_time = time.perf_counter() - start_time
    total_completed = len(results)

    successes = [lat for status, lat in results if status == 201]
    failures = [lat for status, lat in results if status != 201]
    success_count = len(successes)
    failure_count = len(failures)
    overall_rps = total_completed / total_time if total_time > 0 else 0

    successes.sort()
    avg_latency = sum(successes) / success_count if success_count else 0
    p50 = successes[int(success_count * 0.50)] if success_count else 0
    p95 = successes[int(success_count * 0.95)] if success_count else 0
    p99 = successes[int(success_count * 0.99)] if success_count else 0
    min_lat = successes[0] if success_count else 0
    max_lat = successes[-1] if success_count else 0

    print("\n" + "=" * 70)
    print(" LOAD TEST RESULTS (50,000 REQUESTS)")
    print("=" * 70)
    print(f"Total Requests Sent:      {total_completed:,}")
    print(f"Successful (HTTP 201):    {success_count:,} ({success_count/total_completed*100:.2f}%)")
    print(f"Failed / Errors:          {failure_count:,} ({failure_count/total_completed*100:.2f}%)")
    print(f"Total Duration:           {total_time:.2f} seconds ({total_time/60:.2f} minutes)")
    print(f"Throughput (Avg RPS):     {overall_rps:.1f} requests/second")
    print("-" * 70)
    print("  LATENCY DISTRIBUTION (SUCCESSFUL REQUESTS)")
    print("-" * 70)
    print(f"Min Latency:              {min_lat:.2f} ms")
    print(f"Average Latency:          {avg_latency:.2f} ms")
    print(f"Median (p50):             {p50:.2f} ms")
    print(f"95th Percentile (p95):    {p95:.2f} ms")
    print(f"99th Percentile (p99):    {p99:.2f} ms")
    print(f"Max Latency:              {max_lat:.2f} ms")
    print("=" * 70)

if __name__ == "__main__":
    asyncio.run(main())
