#!/usr/bin/env python3
"""EazyChat launcher icon generator.

Design: a white chat bubble on a diagonal purple gradient, containing three
mesh nodes connected by thin lines (one node in pink).

Outputs (all paths relative to the repo root):
  app/src/main/res/drawable/ic_launcher_background.xml      gradient background
  app/src/main/res/drawable/ic_launcher_foreground.xml      bubble + mesh (colour)
  app/src/main/res/drawable/ic_launcher_monochrome.xml      alpha-only silhouette
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml        adaptive icon
  app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml  adaptive icon (round)
  app/src/main/res/mipmap-*/ic_launcher.png                 legacy fallback
  app/src/main/res/mipmap-*/ic_launcher_round.png           legacy fallback (circle)
  docs/icon-preview.png                                     512x512 preview

Requires Pillow (pip install Pillow). Run from anywhere:
  python tools/icon/generate_icons.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

# ---------------------------------------------------------------------------
# Design tokens (unit = dp on a 108dp adaptive canvas; safe zone is 66dp)
# ---------------------------------------------------------------------------

BACKGROUND_START = "#6C5CE7"
BACKGROUND_END = "#A29BFE"
BUBBLE_FILL = "#FFFFFF"
MESH_LINE = "#6C5CE7"
NODE_FILL = "#6C5CE7"
ACCENT_FILL = "#FD79A8"

BUBBLE_X0, BUBBLE_Y0, BUBBLE_X1, BUBBLE_Y1 = 28.0, 34.0, 80.0, 72.0
BUBBLE_RADIUS = 12.0
TAIL = [(36.0, 72.0), (48.0, 72.0), (37.0, 81.0)]

NODES = [(43.0, 46.0), (63.0, 45.0), (55.0, 64.0)]
ACCENT_NODE_INDEX = 2
NODE_RADIUS = 4.2
LINE_WIDTH = 2.2
MONO_STROKE = 3.2

CANVAS_DP = 108.0
LEGACY_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
MASTER_SIZE = 1024
PREVIEW_SIZE = 512

REPO_ROOT = Path(__file__).resolve().parents[2]
RES_DIR = REPO_ROOT / "app" / "src" / "main" / "res"


# ---------------------------------------------------------------------------
# Vector drawables
# ---------------------------------------------------------------------------


def _bubble_path() -> str:
    x0, y0, x1, y1, r = BUBBLE_X0, BUBBLE_Y0, BUBBLE_X1, BUBBLE_Y1, BUBBLE_RADIUS
    return (
        f"M{x0 + r},{y0} H{x1 - r} A{r},{r} 0 0 1 {x1},{y0 + r} "
        f"V{y1 - r} A{r},{r} 0 0 1 {x1 - r},{y1} H{x0 + r} "
        f"A{r},{r} 0 0 1 {x0},{y1 - r} V{y0 + r} A{r},{r} 0 0 1 {x0 + r},{y0} Z"
    )


def _tail_path() -> str:
    points = " L".join(f"{x},{y}" for x, y in TAIL)
    return f"M{points} Z"


def _mesh_path() -> str:
    points = " L".join(f"{x},{y}" for x, y in NODES)
    return f"M{points} Z"


def _circle_path(cx: float, cy: float, r: float) -> str:
    return f"M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0 Z"


def _vector(width_px: int, paths: str) -> str:
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{width_px}dp"
    android:height="{width_px}dp"
    android:viewportWidth="{CANVAS_DP:g}"
    android:viewportHeight="{CANVAS_DP:g}">
{paths}</vector>
"""


def background_xml() -> str:
    return f"""<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:angle="315"
        android:startColor="{BACKGROUND_START}"
        android:endColor="{BACKGROUND_END}"
        android:type="linear" />
</shape>
"""


def foreground_xml() -> str:
    nodes = "\n".join(
        f"""    <path
        android:fillColor="{ACCENT_FILL if i == ACCENT_NODE_INDEX else NODE_FILL}"
        android:pathData="{_circle_path(cx, cy, NODE_RADIUS)}" />"""
        for i, (cx, cy) in enumerate(NODES)
    )
    paths = f"""    <path
        android:fillColor="{BUBBLE_FILL}"
        android:pathData="{_bubble_path()}" />
    <path
        android:fillColor="{BUBBLE_FILL}"
        android:pathData="{_tail_path()}" />
    <path
        android:strokeColor="{MESH_LINE}"
        android:strokeWidth="{LINE_WIDTH:g}"
        android:strokeLineJoin="round"
        android:pathData="{_mesh_path()}" />
{nodes}
"""
    return _vector(108, paths)


def monochrome_xml() -> str:
    nodes = "\n".join(
        f"""    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="{_circle_path(cx, cy, NODE_RADIUS)}" />"""
        for cx, cy in NODES
    )
    paths = f"""    <path
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{MONO_STROKE:g}"
        android:strokeLineJoin="round"
        android:pathData="{_bubble_path()}" />
    <path
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{MONO_STROKE:g}"
        android:strokeLineJoin="round"
        android:pathData="{_tail_path()}" />
    <path
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{LINE_WIDTH:g}"
        android:strokeLineJoin="round"
        android:pathData="{_mesh_path()}" />
{nodes}
"""
    return _vector(108, paths)


def adaptive_xml() -> str:
    return """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""


# ---------------------------------------------------------------------------
# Bitmap rendering
# ---------------------------------------------------------------------------


def _rgb(hex_colour: str) -> tuple[int, int, int]:
    value = hex_colour.lstrip("#")
    return tuple(int(value[i : i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


def _diagonal_gradient(size: int) -> Image.Image:
    """Top-left -> bottom-right gradient, rendered small then upscaled."""
    source = 256
    start, end = _rgb(BACKGROUND_START), _rgb(BACKGROUND_END)
    image = Image.new("RGB", (source, source))
    pixels = image.load()
    for y in range(source):
        for x in range(source):
            t = (x + y) / (2 * (source - 1))
            pixels[x, y] = tuple(
                round(start[c] + (end[c] - start[c]) * t) for c in range(3)
            )
    return image.resize((size, size), Image.LANCZOS)


def _draw_foreground(draw: ImageDraw.ImageDraw, scale: float) -> None:
    def s(value: float) -> float:
        return value * scale

    box = [s(BUBBLE_X0), s(BUBBLE_Y0), s(BUBBLE_X1), s(BUBBLE_Y1)]
    draw.rounded_rectangle(box, radius=s(BUBBLE_RADIUS), fill=BUBBLE_FILL)
    draw.polygon([(s(x), s(y)) for x, y in TAIL], fill=BUBBLE_FILL)

    points = [(s(x), s(y)) for x, y in NODES]
    draw.line(points + [points[0]], fill=MESH_LINE, width=round(s(LINE_WIDTH)), joint="curve")
    for index, (x, y) in enumerate(points):
        radius = s(NODE_RADIUS)
        colour = ACCENT_FILL if index == ACCENT_NODE_INDEX else NODE_FILL
        draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=colour)


def _render_master(round_icon: bool) -> Image.Image:
    image = _diagonal_gradient(MASTER_SIZE).convert("RGBA")

    mask = Image.new("L", (MASTER_SIZE, MASTER_SIZE), 0)
    mask_draw = ImageDraw.Draw(mask)
    if round_icon:
        mask_draw.ellipse([0, 0, MASTER_SIZE - 1, MASTER_SIZE - 1], fill=255)
    else:
        radius = round(MASTER_SIZE * 0.2)
        mask_draw.rounded_rectangle(
            [0, 0, MASTER_SIZE - 1, MASTER_SIZE - 1], radius=radius, fill=255
        )
    image.putalpha(mask)

    layer = Image.new("RGBA", (MASTER_SIZE, MASTER_SIZE), (0, 0, 0, 0))
    _draw_foreground(ImageDraw.Draw(layer), MASTER_SIZE / CANVAS_DP)
    image.alpha_composite(layer)
    return image


def _write_pngs() -> list[Path]:
    written: list[Path] = []
    square = _render_master(round_icon=False)
    circle = _render_master(round_icon=True)

    for density, size in LEGACY_SIZES.items():
        directory = RES_DIR / f"mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        for name, master in (("ic_launcher.png", square), ("ic_launcher_round.png", circle)):
            target = directory / name
            master.resize((size, size), Image.LANCZOS).save(target, optimize=True)
            written.append(target)
    return written


def _write_preview() -> Path:
    target = REPO_ROOT / "docs" / "icon-preview.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    _render_master(round_icon=False).resize(
        (PREVIEW_SIZE, PREVIEW_SIZE), Image.LANCZOS
    ).save(target, optimize=True)
    return target


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    drawable_dir = RES_DIR / "drawable"
    adaptive_dir = RES_DIR / "mipmap-anydpi-v26"
    drawable_dir.mkdir(parents=True, exist_ok=True)
    adaptive_dir.mkdir(parents=True, exist_ok=True)

    (drawable_dir / "ic_launcher_background.xml").write_text(
        background_xml(), encoding="utf-8"
    )
    (drawable_dir / "ic_launcher_foreground.xml").write_text(
        foreground_xml(), encoding="utf-8"
    )
    (drawable_dir / "ic_launcher_monochrome.xml").write_text(
        monochrome_xml(), encoding="utf-8"
    )
    (adaptive_dir / "ic_launcher.xml").write_text(adaptive_xml(), encoding="utf-8")
    (adaptive_dir / "ic_launcher_round.xml").write_text(adaptive_xml(), encoding="utf-8")

    pngs = _write_pngs()
    preview = _write_preview()

    for path in pngs + [preview]:
        print(f"{path.relative_to(REPO_ROOT)}  {path.stat().st_size / 1024:.1f} KB")


if __name__ == "__main__":
    main()
