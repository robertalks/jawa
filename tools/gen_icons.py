#!/usr/bin/env python3
"""Generates the weather icons (res/drawable/wx_*.xml) and the launcher icon.

Each icon is described once as a list of shapes and written both as an Android
<vector> drawable and (optionally) as SVG for previewing, so the preview matches
the app. Only features Android vector drawables support are used: paths, linear
and radial gradients (absolute coordinates), transparency. No blur or filters.

Run from the repo root:  python3 tools/gen_icons.py [svg-preview-dir]
"""
import math
import os
import sys

# ---------------------------------------------------------------- shape model
# fill:   ("fill", paint, path, alpha)       paint = "#RRGGBB" or a gradient dict
# stroke: ("stroke", "#RRGGBB", width, path)
# group:  ("group", tx, ty, scale, [shapes])


def lin(x1, y1, x2, y2, stops):
    return {"type": "linear", "x1": x1, "y1": y1, "x2": x2, "y2": y2, "stops": stops}


def rad(cx, cy, r, stops):
    return {"type": "radial", "cx": cx, "cy": cy, "r": r, "stops": stops}


def circle(cx, cy, r):
    return f"M{cx - r:g},{cy:g} a{r:g},{r:g} 0 1,0 {2 * r:g},0 a{r:g},{r:g} 0 1,0 {-2 * r:g},0 Z"


def fill(paint, d, alpha=1.0):
    return ("fill", paint, d, alpha)


def group(shapes, tx=0, ty=0, s=1):
    return ("group", tx, ty, s, shapes)


# ---------------------------------------------------------------- palette
# "Extra pop" style: saturated colours and strong glows that read well on the
# dark, see-through widget background.

P = dict(
    sun_glow=("#FFC400", "#FF7A00", "#FF3D00"), glow_r=2.6, glow_a=0.8,
    ray=("#FFEA4D", "#FF6F00"),
    core=("#FFFFF0", "#FFD000", "#FF7A00", "#E8430A"),
    moon=("#FFF3B8", "#FFD04A", "#E09A12"), moon_glow="#8FAEFF", moon_glow_a=0.5,
    crater="#B8862A", star_glow="#9FE3FF",
    clouds={
        "light": ("#FFFFFF", "#E3EEFF", "#8FB2EC"),
        "back": ("#D2DFF7", "#93AEE0", "#6883BD"),
        "dark": ("#A7A2EC", "#6B61C4", "#3B2F86"),
    },
    cloud_shadow="#10204A", cloud_shadow_a=0.26,
    drop=("#7CF3FF", "#1F8CFF", "#0E3FD6"),
    flake="#B0E4FF", flake_glow="#5CC8FF",
    bolt=("#FFFFD6", "#FFE000", "#FF5E00"), bolt_glow="#FF9800", bolt_glow_a=0.75,
    fog=("#B9C6E6", "#EEF3FF"),
)

# ---------------------------------------------------------------- elements

def sun(cx=32, cy=32, r=11.5):
    g = P["sun_glow"]
    out = [fill(rad(cx, cy, r * P["glow_r"], [(0, g[0], P["glow_a"]), (0.4, g[1], P["glow_a"] * 0.45), (1, g[2], 0)]),
                circle(cx, cy, r * P["glow_r"]))]
    rays = ""
    for k in range(8):
        a = k * math.pi / 4
        length = r * (2.15 if k % 2 == 0 else 1.9)  # long and short rays
        tip = (cx + math.cos(a) * length, cy + math.sin(a) * length)
        b1 = (cx + math.cos(a + 0.22) * r * 1.28, cy + math.sin(a + 0.22) * r * 1.28)
        b2 = (cx + math.cos(a - 0.22) * r * 1.28, cy + math.sin(a - 0.22) * r * 1.28)
        rays += f"M{b1[0]:.2f},{b1[1]:.2f} L{tip[0]:.2f},{tip[1]:.2f} L{b2[0]:.2f},{b2[1]:.2f} Z "
    out.append(fill(rad(cx, cy, r * 2.15, [(0.5, P["ray"][0], 1), (1, P["ray"][1], 1)]), rays.strip()))
    c = P["core"]
    out.append(fill(rad(cx - r * 0.35, cy - r * 0.4, r * 1.6, [(0, c[0], 1), (0.3, c[1], 1), (0.8, c[2], 1), (1, c[3], 1)]),
                    circle(cx, cy, r)))
    out.append(fill("#FFFFFF", circle(cx - r * 0.38, cy - r * 0.42, r * 0.3), 0.75))
    return out


def moon():
    m = P["moon"]
    out = [fill(rad(36, 32, 28, [(0, P["moon_glow"], P["moon_glow_a"]), (1, P["moon_glow"], 0)]), circle(36, 32, 28)),
           fill(lin(20, 12, 50, 52, [(0, m[0], 1), (0.55, m[1], 1), (1, m[2], 1)]),
                "M40,10 A22,22 0 1,0 54,44 A20,20 0 0,1 40,10 Z")]
    for x, y, r, o in [(26, 26, 3, 0.2), (22, 38, 2.2, 0.17), (31, 45, 2.6, 0.16)]:
        out.append(fill(P["crater"], circle(x, y, r), o))
    out.append(fill("#FFFFFF", "M30,14 A18,18 0 0,0 17,30 A20,20 0 0,1 30,14 Z", 0.5))  # rim light
    for x, y, r in [(56, 15, 1.9), (49, 24, 1.3), (58, 30, 1.0)]:
        out.append(fill(rad(x, y, r * 2.6, [(0, P["star_glow"], 0.7), (1, P["star_glow"], 0)]), circle(x, y, r * 2.6)))
        out.append(fill("#FFFFFF", circle(x, y, r)))
    return out


CLOUD = [circle(20, 38, 10), circle(32, 28, 13), circle(45, 38, 10), "M20,34 H45 V48 H20 Z"]


def cloud(kind="light"):
    top, mid, bot = P["clouds"][kind]
    shadow = group([fill(P["cloud_shadow"], p, P["cloud_shadow_a"]) for p in CLOUD], 0, 2.4, 1)
    body = [fill(lin(0, 15, 0, 48, [(0, top, 1), (0.5, mid, 1), (1, bot, 1)]), p) for p in CLOUD]
    shine = fill(rad(27, 21, 7, [(0, "#FFFFFF", 0.55 if kind == "dark" else 0.95), (1, "#FFFFFF", 0)]),
                 circle(27, 21, 7))
    return [shadow] + body + [shine]


def drops(points, big=True):
    s = 1.0 if big else 0.72
    d0, d1, d2 = P["drop"]
    g = lin(0, 40, 0, 64, [(0, d0, 1), (0.5, d1, 1), (1, d2, 1)])
    out = []
    for x, y in points:
        d = (f"M{x:g},{y:g} C{x + 3.4 * s:g},{y + 5 * s:g} {x + 3.6 * s:g},{y + 7.7 * s:g} {x:g},{y + 8.8 * s:g} "
             f"C{x - 3.6 * s:g},{y + 7.7 * s:g} {x - 3.4 * s:g},{y + 5 * s:g} {x:g},{y:g} Z")
        out.append(fill(g, d))
        out.append(fill("#FFFFFF", circle(x - 1 * s, y + 5.6 * s, 0.9 * s), 0.85))
    return out


def flakes(points):
    out = [fill(rad(x, y, 5.5, [(0, P["flake_glow"], 0.55), (1, P["flake_glow"], 0)]), circle(x, y, 5.5))
           for x, y in points]
    d = ""
    for x, y in points:
        for dx, dy in [(3.5, 0), (1.75, 3.03), (1.75, -3.03)]:
            d += f"M{x - dx:g},{y - dy:g} L{x + dx:g},{y + dy:g} "
    out.append(("stroke", P["flake"], 2.0, d.strip()))
    out += [fill("#FFFFFF", circle(x, y, 1.3)) for x, y in points]
    return out


def bolt():
    b = P["bolt"]
    return [fill(rad(32, 49, 15, [(0, P["bolt_glow"], P["bolt_glow_a"]), (1, P["bolt_glow"], 0)]), circle(32, 49, 15)),
            fill(lin(28, 33, 36, 63, [(0, b[0], 1), (0.45, b[1], 1), (1, b[2], 1)]),
                 "M34,33 L23,50 H31 L27,63 L41,45 H33 L38,33 Z"),
            fill("#FFFFFF", "M34.5,35 L27,47 H29.5 L35.5,37 Z", 0.6)]  # highlight


def fog():
    f0, f1 = P["fog"]
    g = lin(8, 0, 56, 0, [(0, f0, 0.25), (0.5, f1, 1), (1, f0, 0.25)])
    return [fill(g, f"M{x1},{y - 1.9:g} H{x2} a1.9,1.9 0 0,1 0,3.8 H{x1} a1.9,1.9 0 0,1 0,-3.8 Z")
            for x1, x2, y in [(10, 42, 46), (20, 54, 53), (12, 46, 60)]]


UP = dict(tx=5, ty=-4, s=0.85)  # cloud lifted up to make room for rain/snow

ICONS = {
    "wx_clear_day": sun(),
    "wx_clear_night": moon(),
    "wx_partly_day": [group(sun(), 0, 0, 0.62), group(cloud(), 9, 14, 0.85)],
    "wx_partly_night": [group(moon(), 0, 0, 0.62), group(cloud(), 9, 14, 0.85)],
    "wx_cloudy": [group(cloud(), 0, 1, 1)],
    "wx_overcast": [group(cloud("back"), 18, 0, 0.7), group(cloud(), 1, 12, 0.9)],
    "wx_fog": [group(cloud(), **UP)] + fog(),
    "wx_drizzle": [group(cloud(), **UP)] + drops([(22, 44), (32, 47), (42, 44), (27, 54), (37, 54)], big=False),
    "wx_rain": [group(cloud(), **UP)] + drops([(20, 43), (31, 43), (42, 43), (25.5, 53), (36.5, 53)]),
    "wx_showers_day": [group(sun(), 0, 0, 0.5), group(cloud(), 9, 3, 0.8)] + drops([(25, 46), (36, 46), (47, 46)]),
    "wx_snow": [group(cloud(), **UP)] + flakes([(21, 47), (33, 47), (45, 47), (27, 57), (39, 57)]),
    "wx_thunder": [group(cloud("dark"), **UP)] + bolt() + drops([(17, 43), (47, 43)], big=False),
}


# ---------------------------------------------------------------- writers

def argb(color, alpha):
    return f"#{int(round(alpha * 255)):02X}{color[1:]}"


def android_gradient(g, indent):
    i = indent
    if g["type"] == "linear":
        head = (f'{i}<gradient android:type="linear" android:startX="{g["x1"]}" android:startY="{g["y1"]}" '
                f'android:endX="{g["x2"]}" android:endY="{g["y2"]}">')
    else:
        head = (f'{i}<gradient android:type="radial" android:centerX="{g["cx"]:g}" android:centerY="{g["cy"]:g}" '
                f'android:gradientRadius="{g["r"]:g}">')
    items = [f'{i}    <item android:offset="{o}" android:color="{argb(c, a)}" />' for o, c, a in g["stops"]]
    return [head] + items + [f"{i}</gradient>"]


def android_shapes(shapes, indent="    "):
    out = []
    for sh in shapes:
        if sh[0] == "group":
            _, tx, ty, s, inner = sh
            out.append(f'{indent}<group android:translateX="{tx}" android:translateY="{ty}" '
                       f'android:scaleX="{s}" android:scaleY="{s}">')
            out += android_shapes(inner, indent + "    ")
            out.append(f"{indent}</group>")
        elif sh[0] == "fill":
            _, paint, d, alpha = sh
            alpha_attr = f' android:fillAlpha="{alpha:g}"' if alpha != 1 else ""
            if isinstance(paint, str):
                out.append(f'{indent}<path android:fillColor="{paint}"{alpha_attr} android:pathData="{d}" />')
            else:
                out.append(f'{indent}<path{alpha_attr} android:pathData="{d}">')
                out.append(f'{indent}    <aapt:attr name="android:fillColor">')
                out += android_gradient(paint, indent + "        ")
                out.append(f"{indent}    </aapt:attr>")
                out.append(f"{indent}</path>")
        else:
            _, color, width, d = sh
            out.append(f'{indent}<path android:strokeColor="{color}" android:strokeWidth="{width}" '
                       f'android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />')
    return out


def vector_xml(shapes, size_dp=64, viewport=64):
    body = "\n".join(android_shapes(shapes))
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by tools/gen_icons.py -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{size_dp}dp" android:height="{size_dp}dp"\n'
            f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n{body}\n</vector>\n')


def svg(shapes):
    defs, n = [], [0]

    def paint_ref(p):
        if isinstance(p, str):
            return p
        n[0] += 1
        gid = f"g{n[0]}"
        stops = "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in p["stops"])
        if p["type"] == "linear":
            defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{p["x1"]}" y1="{p["y1"]}" '
                        f'x2="{p["x2"]}" y2="{p["y2"]}">{stops}</linearGradient>')
        else:
            defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{p["cx"]}" cy="{p["cy"]}" '
                        f'r="{p["r"]}">{stops}</radialGradient>')
        return f"url(#{gid})"

    def walk(shs):
        out = []
        for sh in shs:
            if sh[0] == "group":
                _, tx, ty, s, inner = sh
                out.append(f'<g transform="translate({tx},{ty}) scale({s})">' + "".join(walk(inner)) + "</g>")
            elif sh[0] == "fill":
                out.append(f'<path fill="{paint_ref(sh[1])}" fill-opacity="{sh[3]}" d="{sh[2]}"/>')
            else:
                out.append(f'<path fill="none" stroke="{sh[1]}" stroke-width="{sh[2]}" '
                           f'stroke-linecap="round" stroke-linejoin="round" d="{sh[3]}"/>')
        return out

    body = "".join(walk(shapes))
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><defs>{"".join(defs)}</defs>{body}</svg>'


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    draw = os.path.join(root, "app/src/main/res/drawable")
    for name, shapes in ICONS.items():
        with open(os.path.join(draw, name + ".xml"), "w") as f:
            f.write(vector_xml(shapes))
    # Launcher icon foreground: partly-sunny icon centred in the 108dp adaptive canvas
    with open(os.path.join(draw, "ic_launcher_foreground.xml"), "w") as f:
        f.write(vector_xml([group(ICONS["wx_partly_day"], 22, 22, 1)], 108, 108))
    if len(sys.argv) > 1:  # optional: SVGs for previewing
        os.makedirs(sys.argv[1], exist_ok=True)
        for name, shapes in ICONS.items():
            with open(os.path.join(sys.argv[1], name + ".svg"), "w") as f:
                f.write(svg(shapes))
    print("wrote", len(ICONS), "icons")
