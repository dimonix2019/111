"""Async tip1m sim jobs — long windows off the uvicorn threadpool hot path."""

from __future__ import annotations

import threading
import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable

SIM_ASYNC_THRESHOLD_DAYS = 90
_JOB_TTL_SEC = 3600.0
_MAX_JOBS = 48


@dataclass
class SimJob:
    job_id: str
    status: str  # pending | running | done | error
    created_at: float
    span_days: int = 0
    started_at: float = 0.0
    finished_at: float = 0.0
    result: dict[str, Any] | None = None
    error: str | None = None


_jobs: dict[str, SimJob] = {}
_job_fns: dict[str, Callable[[], dict[str, Any]]] = {}
_queue: list[str] = []
_lock = threading.Lock()
_worker_started = False


def sim_span_days(csv: str, start: str | None, end: str | None) -> int:
    from replay.tip_touch import named_csv_lookback_days, window_span_days

    span = window_span_days(start, end)
    if span is None:
        return named_csv_lookback_days(csv)
    return span


def should_run_async(csv: str, start: str | None, end: str | None) -> bool:
    return sim_span_days(csv, start, end) > SIM_ASYNC_THRESHOLD_DAYS


def _purge_old_jobs() -> None:
    now = time.time()
    drop: list[str] = []
    for jid, job in _jobs.items():
        finished = job.finished_at or job.created_at
        if job.status in ("done", "error") and now - finished > _JOB_TTL_SEC:
            drop.append(jid)
    if len(_jobs) > _MAX_JOBS:
        done_sorted = sorted(
            ((jid, j) for jid, j in _jobs.items() if j.status in ("done", "error")),
            key=lambda x: x[1].finished_at or x[1].created_at,
        )
        for jid, _ in done_sorted[: max(0, len(_jobs) - _MAX_JOBS)]:
            if jid not in drop:
                drop.append(jid)
    for jid in drop:
        _jobs.pop(jid, None)
        _job_fns.pop(jid, None)


def _ensure_worker() -> None:
    global _worker_started
    if _worker_started:
        return
    _worker_started = True
    threading.Thread(target=_worker_loop, name="tip1m-sim-worker", daemon=True).start()


def _worker_loop() -> None:
    while True:
        job_id: str | None = None
        run_fn: Callable[[], dict[str, Any]] | None = None
        with _lock:
            while _queue:
                candidate = _queue.pop(0)
                job = _jobs.get(candidate)
                fn = _job_fns.get(candidate)
                if job and fn and job.status == "pending":
                    job_id = candidate
                    run_fn = fn
                    job.status = "running"
                    job.started_at = time.time()
                    break
        if not job_id or not run_fn:
            time.sleep(0.05)
            continue
        try:
            result = run_fn()
            with _lock:
                job = _jobs.get(job_id)
                if job:
                    job.status = "done"
                    job.result = result
                    job.finished_at = time.time()
                _job_fns.pop(job_id, None)
        except Exception as e:
            with _lock:
                job = _jobs.get(job_id)
                if job:
                    job.status = "error"
                    job.error = str(e)
                    job.finished_at = time.time()
                _job_fns.pop(job_id, None)


def submit_sim_job(
    run_fn: Callable[[], dict[str, Any]],
    *,
    span_days: int = 0,
) -> str:
    job_id = uuid.uuid4().hex[:16]
    job = SimJob(
        job_id=job_id,
        status="pending",
        created_at=time.time(),
        span_days=int(span_days),
    )
    with _lock:
        _purge_old_jobs()
        _jobs[job_id] = job
        _job_fns[job_id] = run_fn
        _queue.append(job_id)
    _ensure_worker()
    return job_id


def job_status_payload(job_id: str) -> dict[str, Any] | None:
    with _lock:
        job = _jobs.get(job_id)
        if not job:
            return None
        elapsed = 0.0
        if job.started_at:
            end = job.finished_at or time.time()
            elapsed = round(end - job.started_at, 1)
        elif job.created_at:
            elapsed = round(time.time() - job.created_at, 1)
        payload: dict[str, Any] = {
            "job_id": job.job_id,
            "status": job.status,
            "spanDays": job.span_days,
            "elapsedSec": elapsed,
        }
        if job.status == "done" and job.result is not None:
            payload["result"] = job.result
        if job.status == "error" and job.error:
            payload["error"] = job.error
        return payload
