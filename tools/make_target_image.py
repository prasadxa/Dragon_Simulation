#!/usr/bin/env python3
"""Generate app/src/main/assets/images/target.png.

A 1024x1024 high-contrast black-on-white reference image designed for high
ARCore image-tracking quality:
  * rich, well-distributed detail across the WHOLE image (jittered-grid
    coverage -> no large empty areas, no symmetric/repetitive grid),
  * many corners/edges at varied scales (triangles, bars, rings, QR-like
    micro-blocks) -> many detectable features,
  * a single bold asymmetric corner glyph (top-left) + a secondary diamond
    marker (bottom-right) -> orientation is unambiguous.

Pure stdlib (struct/zlib PNG writer + tiny scanline rasterizer), seeded RNG
for reproducibility. Re-run to regenerate the exact same image.

Usage:
    python3 tools/make_target_image.py [output_path]
Default output: app/src/main/assets/images/target.png (repo-root relative).
"""

import math
import os
import random
import struct
import zlib

W = H = 1024
SEED = 0xD3A60  # fixed seed -> deterministic output

BLACK = 0
WHITE = 255


# ---------------------------------------------------------------- rasterizer
class Canvas:
    """8-bit grayscale canvas; expanded to RGB at PNG write time."""

    def __init__(self, w, h, bg=WHITE):
        self.w, self.h = w, h
        self.px = bytearray([bg]) * (w * h)

    def set(self, x, y, v=BLACK):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y * self.w + x] = v

    def fill_rect(self, x0, y0, x1, y1, v=BLACK):
        x0, x1 = sorted((max(0, int(x0)), min(self.w, int(x1))))
        y0, y1 = sorted((max(0, int(y0)), min(self.h, int(y1))))
        row = bytes([v]) * (x1 - x0)
        for y in range(y0, y1):
            o = y * self.w + x0
            self.px[o:o + len(row)] = row

    def fill_circle(self, cx, cy, r, v=BLACK):
        cx, cy, r = int(cx), int(cy), int(r)
        r2 = r * r
        for y in range(max(0, cy - r), min(self.h, cy + r + 1)):
            dy = y - cy
            dx = int(math.isqrt(max(0, r2 - dy * dy)))
            x0, x1 = max(0, cx - dx), min(self.w, cx + dx + 1)
            o = y * self.w + x0
            self.px[o:o + (x1 - x0)] = bytes([v]) * (x1 - x0)

    def fill_ring(self, cx, cy, r_out, r_in, v=BLACK):
        self.fill_circle(cx, cy, r_out, v)
        self.fill_circle(cx, cy, r_in, WHITE)

    def fill_poly(self, pts, v=BLACK):
        """Scanline even-odd fill of polygon given as [(x,y), ...]."""
        ys = [p[1] for p in pts]
        y_min, y_max = max(0, int(min(ys))), min(self.h - 1, int(max(ys)) + 1)
        n = len(pts)
        for y in range(y_min, y_max):
            yc = y + 0.5
            xs = []
            for i in range(n):
                x1, y1 = pts[i]
                x2, y2 = pts[(i + 1) % n]
                if (y1 <= yc < y2) or (y2 <= yc < y1):
                    xs.append(x1 + (yc - y1) / (y2 - y1) * (x2 - x1))
            xs.sort()
            for i in range(0, len(xs) - 1, 2):
                x0 = max(0, int(xs[i]))
                x1 = min(self.w, int(xs[i + 1]) + 1)
                if x1 > x0:
                    o = y * self.w + x0
                    self.px[o:o + (x1 - x0)] = bytes([v]) * (x1 - x0)

    def fill_triangle(self, cx, cy, r, rot, v=BLACK):
        pts = [(cx + r * math.cos(rot + i * 2 * math.pi / 3),
                cy + r * math.sin(rot + i * 2 * math.pi / 3)) for i in range(3)]
        self.fill_poly(pts, v)

    def fill_bar(self, cx, cy, length, thick, angle, v=BLACK):
        """Rotated rectangle centred at (cx, cy)."""
        dx, dy = math.cos(angle), math.sin(angle)
        px, py = -dy * thick / 2, dx * thick / 2
        hx, hy = dx * length / 2, dy * length / 2
        pts = [(cx - hx + px, cy - hy + py), (cx + hx + px, cy + hy + py),
               (cx + hx - px, cy + hy - py), (cx - hx - px, cy - hy - py)]
        self.fill_poly(pts, v)

    def fill_diamond(self, cx, cy, r, v=BLACK):
        self.fill_poly([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)], v)


# ------------------------------------------------------------------- helpers
def qr_block(c, rng, x, y, cell, n):
    """n x n grid of randomly-filled cells (QR-like micro detail)."""
    for gy in range(n):
        for gx in range(n):
            if rng.random() < 0.55:
                c.fill_rect(x + gx * cell, y + gy * cell,
                            x + (gx + 1) * cell - 1, y + (gy + 1) * cell - 1)


def draw_scene():
    rng = random.Random(SEED)
    c = Canvas(W, H, WHITE)

    # -- border frame: edge features near the image boundary -----------------
    c.fill_rect(0, 0, W, 11)            # top
    c.fill_rect(0, H - 11, W, H)        # bottom
    c.fill_rect(0, 0, 11, H)            # left
    c.fill_rect(W - 11, 0, W, H)        # right

    # -- asymmetric corner glyph (TOP-LEFT only): bold square + white triangle
    gx, gy, gs = 48, 48, 150            # glyph origin/size
    c.fill_rect(gx, gy, gx + gs, gy + gs)
    c.fill_poly([(gx + 30, gy + 30), (gx + gs - 30, gy + gs / 2),
                 (gx + 30, gy + gs - 30)], WHITE)
    c.fill_circle(gx + gs - 34, gy + 34, 14, WHITE)   # extra notch = unique

    # secondary, subtler orientation cue bottom-right (small diamond pair)
    c.fill_diamond(W - 90, H - 90, 34)
    c.fill_diamond(W - 90, H - 90, 15, WHITE)

    # -- jittered 8x8 grid: one detail cluster per cell -> full coverage -----
    cell = W // 8
    margin = 26
    for gy in range(8):
        for gx in range(8):
            # keep the corner-glyph cell clear so the marker stays isolated
            if gx == 0 and gy == 0:
                continue
            cx = gx * cell + cell // 2 + rng.randint(-22, 22)
            cy = gy * cell + cell // 2 + rng.randint(-22, 22)
            kind = rng.random()
            if kind < 0.30:                     # QR-like micro block
                n = rng.choice((3, 4))
                cc = rng.randint(10, 16)
                qr_block(c, rng, cx - n * cc // 2, cy - n * cc // 2, cc, n)
            elif kind < 0.50:                   # triangle
                c.fill_triangle(cx, cy, rng.randint(26, 52),
                                rng.uniform(0, 2 * math.pi))
            elif kind < 0.68:                   # diagonal bar
                c.fill_bar(cx, cy, rng.randint(70, 120),
                           rng.randint(14, 26), rng.uniform(0, math.pi))
            elif kind < 0.85:                   # filled circle
                c.fill_circle(cx, cy, rng.randint(20, 44))
            else:                               # ring
                r = rng.randint(26, 46)
                c.fill_ring(cx, cy, r, max(10, r - rng.randint(12, 18)))

    # -- medium accent shapes scattered on top (compound overlap features) ---
    for _ in range(10):
        cx = rng.randint(margin + 60, W - margin - 60)
        cy = rng.randint(margin + 60, H - margin - 60)
        if cx < 260 and cy < 260:               # don't cover corner glyph
            continue
        t = rng.random()
        if t < 0.35:
            c.fill_bar(cx, cy, rng.randint(120, 220), rng.randint(16, 30),
                       rng.uniform(0, math.pi))
        elif t < 0.65:
            c.fill_ring(cx, cy, rng.randint(38, 60), rng.randint(16, 26))
        else:
            c.fill_triangle(cx, cy, rng.randint(48, 78),
                            rng.uniform(0, 2 * math.pi))

    # -- bullseye: strong well-known tracking feature ------------------------
    bx, by = 780, 300
    for r in (46, 32, 18):
        c.fill_circle(bx, by, r, BLACK if (r // 14) % 2 else WHITE)
    c.fill_circle(bx, by, 7, BLACK)

    # -- fine grain: tiny dots + micro squares everywhere --------------------
    for _ in range(60):
        x = rng.randint(30, W - 30)
        y = rng.randint(30, H - 30)
        s = rng.randint(5, 12)
        if rng.random() < 0.5:
            c.fill_circle(x, y, s)
        else:
            c.fill_rect(x - s, y - s, x + s, y + s)

    return c


# ------------------------------------------------------------------ PNG write
def write_png(canvas, path):
    w, h = canvas.w, canvas.h
    # grayscale -> RGB
    rgb = bytearray(w * h * 3)
    for i, v in enumerate(canvas.px):
        o = i * 3
        rgb[o] = rgb[o + 1] = rgb[o + 2] = v
    raw = bytearray()
    stride = w * 3
    for y in range(h):
        raw.append(0)  # filter type 0 (None)
        raw += rgb[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data +
                struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
           + chunk(b'IEND', b''))
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(png)


def main():
    import sys
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = (sys.argv[1] if len(sys.argv) > 1 else
           os.path.join(root, 'app/src/main/assets/images/target.png'))
    write_png(draw_scene(), out)
    print('wrote', out, os.path.getsize(out), 'bytes')


if __name__ == '__main__':
    main()
