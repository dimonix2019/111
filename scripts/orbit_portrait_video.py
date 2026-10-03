#!/usr/bin/env python3
"""Smooth 180° orbit: optical-flow morph through ordered viewpoints.

Frames are kept in RGB and encoded with ffmpeg rawvideo (rgb24). An earlier
cut wrote RGB buffers through OpenCV's BGR writer, which swapped red and blue.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

ASSETS = Path("/opt/cursor/artifacts/assets")
OUT = Path("/opt/cursor/artifacts/garden_orbit_180.mp4")
PREVIEW = Path("/opt/cursor/artifacts/screenshots")
W, H = 720, 1280
FPS = 30
DURATION_SEC = 12.0

KEYFRAMES = [
    ASSETS / "orbit_0.jpg",
    ASSETS / "orbit_30.jpg",
    ASSETS / "orbit_60.jpg",
    ASSETS / "orbit_90.jpg",
    ASSETS / "orbit_120.jpg",
    ASSETS / "orbit_150.jpg",
    ASSETS / "orbit_180.jpg",
]


def load_rgb(path: Path) -> np.ndarray:
    img = Image.open(path).convert("RGB")
    return np.asarray(img, dtype=np.uint8)


def cover(img: np.ndarray) -> np.ndarray:
    """Fill the frame. Reflect-padding was mirroring the grass and the apple."""
    h, w = img.shape[:2]
    scale = max(W / w, H / h)
    resized = cv2.resize(
        img,
        (int(round(w * scale)), int(round(h * scale))),
        interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR,
    )
    nh, nw = resized.shape[:2]
    x = max(0, (nw - W) // 2)
    y = max(0, (nh - H) // 2)
    return resized[y : y + H, x : x + W]


def match_color(img: np.ndarray, ref: np.ndarray) -> np.ndarray:
    """Reinhard transfer in Lab so every viewpoint keeps the photo's colors."""
    src = cv2.cvtColor(img, cv2.COLOR_RGB2LAB).astype(np.float32)
    target = cv2.cvtColor(ref, cv2.COLOR_RGB2LAB).astype(np.float32)
    for c in range(3):
        s = src[:, :, c]
        t = target[:, :, c]
        src[:, :, c] = (s - s.mean()) * (float(t.std()) + 1e-6) / (float(s.std()) + 1e-6) + t.mean()
    return cv2.cvtColor(np.clip(src, 0, 255).astype(np.uint8), cv2.COLOR_LAB2RGB)


def optical_flow(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Dense flow a→b, computed on a smaller frame and scaled back up."""
    h, w = a.shape[:2]
    sw = 540
    sh = int(round(h * (sw / w)))
    a_s = cv2.resize(a, (sw, sh), interpolation=cv2.INTER_AREA)
    b_s = cv2.resize(b, (sw, sh), interpolation=cv2.INTER_AREA)
    dis = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
    flow = dis.calc(
        cv2.cvtColor(a_s, cv2.COLOR_RGB2GRAY),
        cv2.cvtColor(b_s, cv2.COLOR_RGB2GRAY),
        None,
    )
    flow = cv2.resize(flow, (w, h), interpolation=cv2.INTER_LINEAR)
    flow[:, :, 0] *= w / sw
    flow[:, :, 1] *= h / sh
    flow = cv2.GaussianBlur(flow, (0, 0), 7)
    return flow.astype(np.float32)


def warp(img: np.ndarray, flow: np.ndarray, amount: float) -> np.ndarray:
    h, w = img.shape[:2]
    grid_x, grid_y = np.meshgrid(
        np.arange(w, dtype=np.float32),
        np.arange(h, dtype=np.float32),
    )
    map_x = grid_x - flow[:, :, 0] * amount
    map_y = grid_y - flow[:, :, 1] * amount
    return cv2.remap(
        img,
        map_x,
        map_y,
        interpolation=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_REFLECT101,
    )


def morph(a: np.ndarray, b: np.ndarray, flow_ab: np.ndarray, flow_ba: np.ndarray, t: float) -> np.ndarray:
    """Move pixels from a toward b instead of dissolving two stills."""
    t = float(np.clip(t, 0.0, 1.0))
    wa = warp(a, flow_ab, t)
    wb = warp(b, flow_ba, 1.0 - t)
    # Cosine weight spends less time on a 50/50 ghost.
    w = 0.5 - 0.5 * np.cos(np.pi * t)
    out = wa.astype(np.float32) * (1.0 - w) + wb.astype(np.float32) * w
    return np.clip(out, 0, 255).astype(np.uint8)


def ease(t: float) -> float:
    t = float(np.clip(t, 0.0, 1.0))
    return t * t * (3.0 - 2.0 * t)


def frame_at(images: list[np.ndarray], flows_ab: list[np.ndarray], flows_ba: list[np.ndarray], t: float) -> np.ndarray:
    t = ease(t)
    spans = len(images) - 1
    pos = t * spans
    i = min(int(pos), spans - 1)
    local = pos - i
    return morph(images[i], images[i + 1], flows_ab[i], flows_ba[i], local)


def main() -> None:
    PREVIEW.mkdir(parents=True, exist_ok=True)
    aligned = [cover(load_rgb(p)) for p in KEYFRAMES]
    reference = aligned[0]
    images = [reference] + [match_color(img, reference) for img in aligned[1:]]

    flows_ab = [optical_flow(images[i], images[i + 1]) for i in range(len(images) - 1)]
    flows_ba = [optical_flow(images[i + 1], images[i]) for i in range(len(images) - 1)]

    total = int(DURATION_SEC * FPS)
    frames: list[np.ndarray] = []
    prev = None
    motion = []
    for n in range(total):
        t = n / max(total - 1, 1)
        frame = frame_at(images, flows_ab, flows_ba, t)
        frames.append(frame)
        if prev is not None:
            motion.append(float(np.mean(np.abs(frame.astype(np.int16) - prev.astype(np.int16)))))
        prev = frame

    for label, t in (("start", 0.0), ("q1", 0.25), ("mid", 0.5), ("q3", 0.75), ("end", 1.0)):
        preview = frame_at(images, flows_ab, flows_ba, t)
        Image.fromarray(preview).save(PREVIEW / f"orbit_smooth_{label}.jpg", quality=92)

    cmd = [
        "ffmpeg",
        "-y",
        "-f",
        "rawvideo",
        "-vcodec",
        "rawvideo",
        "-pix_fmt",
        "rgb24",
        "-s",
        f"{W}x{H}",
        "-r",
        str(FPS),
        "-i",
        "-",
        "-an",
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        "-crf",
        "17",
        "-colorspace",
        "bt709",
        "-color_primaries",
        "bt709",
        "-color_trc",
        "bt709",
        "-movflags",
        "+faststart",
        str(OUT),
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    assert proc.stdin is not None
    for frame in frames:
        proc.stdin.write(np.ascontiguousarray(frame).tobytes())
    proc.stdin.close()
    err = proc.stderr.read().decode() if proc.stderr else ""
    code = proc.wait()
    if code != 0:
        raise RuntimeError(err[-2000:])

    rgb0 = frames[0].mean(axis=(0, 1))
    print(f"Wrote {OUT}")
    print(f"frame0 RGB mean {rgb0.round(1)} (red should stay above blue)")
    print(f"motion mean {np.mean(motion):.2f} min {np.min(motion):.2f} max {np.max(motion):.2f}")


if __name__ == "__main__":
    main()
