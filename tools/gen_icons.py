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


# ---------------------------------------------------------------- elements

def sun(cx=32, cy=32, r=11.5):
    out = [fill(rad(cx, cy, r * 2.3, [(0, "#FFD54A", 0.45), (0.45, "#FFB300", 0.18), (1, "#FF9800", 0)]),
                circle(cx, cy, r * 2.3))]
    rays = ""
    for k in range(8):
        a = k * math.pi / 4
        tip = (cx + math.cos(a) * r * 2.05, cy + math.sin(a) * r * 2.05)
        b1 = (cx + math.cos(a + 0.2) * r * 1.3, cy + math.sin(a + 0.2) * r * 1.3)
        b2 = (cx + math.cos(a - 0.2) * r * 1.3, cy + math.sin(a - 0.2) * r * 1.3)
        rays += f"M{b1[0]:.2f},{b1[1]:.2f} L{tip[0]:.2f},{tip[1]:.2f} L{b2[0]:.2f},{b2[1]:.2f} Z "
    out.append(fill(rad(cx, cy, r * 2.1, [(0.55, "#FFE082", 1), (1, "#FFB300", 1)]), rays.strip()))
    out.append(fill(rad(cx - r * 0.35, cy - r * 0.4, r * 1.5,
                        [(0, "#FFFBE0", 1), (0.35, "#FFD54A", 1), (1, "#FF9A1F", 1)]), circle(cx, cy, r)))
    out.append(fill("#FFFFFF", circle(cx - r * 0.35, cy - r * 0.4, r * 0.32), 0.55))
    return out


def moon():
    out = [fill(rad(36, 32, 26, [(0, "#FFF6D0", 0.28), (1, "#BFD4FF", 0)]), circle(36, 32, 26)),
           fill(lin(20, 12, 50, 52, [(0, "#FFFCEB", 1), (0.6, "#F3E3A2", 1), (1, "#D9C36E", 1)]),
                "M40,10 A22,22 0 1,0 54,44 A20,20 0 0,1 40,10 Z")]
    for x, y, r, o in [(26, 26, 3, 0.18), (22, 38, 2.2, 0.15), (31, 45, 2.6, 0.14)]:
        out.append(fill("#B9A24E", circle(x, y, r), o))
    for x, y, r in [(56, 15, 1.9), (49, 24, 1.3), (58, 30, 1.0)]:
        out.append(fill(rad(x, y, r * 2.2, [(0, "#FFFFFF", 0.5), (1, "#FFFFFF", 0)]), circle(x, y, r * 2.2)))
        out.append(fill("#FFFFFF", circle(x, y, r), 0.95))
    return out


CLOUD = [circle(20, 38, 10), circle(32, 28, 13), circle(45, 38, 10), "M20,34 H45 V48 H20 Z"]


def cloud(kind="light"):
    top, mid, bot = {
        "light": ("#FFFFFF", "#EEF2F8", "#C9D3E1"),
        "back": ("#E4E9F1", "#C4CEDB", "#A3AFBF"),
        "dark": ("#C9D2DE", "#9AA6B6", "#6E7A8C"),
    }[kind]
    shadow = group([fill("#0A1020", p, 0.10) for p in CLOUD], 0, 2.2, 1)
    body = [fill(lin(0, 15, 0, 48, [(0, top, 1), (0.55, mid, 1), (1, bot, 1)]), p) for p in CLOUD]
    shine = fill(rad(28, 22, 6, [(0, "#FFFFFF", 0.45 if kind == "dark" else 0.9), (1, "#FFFFFF", 0)]),
                 circle(28, 22, 6))
    return [shadow] + body + [shine]


def drops(points, big=True):
    s = 1.0 if big else 0.7
    g = lin(0, 40, 0, 64, [(0, "#9ADBFF", 1), (1, "#2E86E6", 1)])
    out = []
    for x, y in points:
        d = (f"M{x:g},{y:g} C{x + 3.2 * s:g},{y + 5 * s:g} {x + 3.4 * s:g},{y + 7.5 * s:g} {x:g},{y + 8.5 * s:g} "
             f"C{x - 3.4 * s:g},{y + 7.5 * s:g} {x - 3.2 * s:g},{y + 5 * s:g} {x:g},{y:g} Z")
        out.append(fill(g, d))
        out.append(fill("#FFFFFF", circle(x - 0.9 * s, y + 5.6 * s, 0.8 * s), 0.7))
    return out


def flakes(points):
    d = ""
    for x, y in points:
        for dx, dy in [(3.4, 0), (1.7, 2.95), (1.7, -2.95)]:
            d += f"M{x - dx:g},{y - dy:g} L{x + dx:g},{y + dy:g} "
    out = [("stroke", "#E8F4FF", 1.9, d.strip())]
    out += [fill("#FFFFFF", circle(x, y, 1.2)) for x, y in points]
    return out


def bolt():
    return [fill(rad(32, 49, 13, [(0, "#FFE066", 0.35), (1, "#FFB300", 0)]), circle(32, 49, 13)),
            fill(lin(28, 33, 36, 63, [(0, "#FFF3A6", 1), (0.5, "#FFD23F", 1), (1, "#FF9F1C", 1)]),
                 "M34,33 L23,50 H31 L27,63 L41,45 H33 L38,33 Z")]


def fog():
    g = lin(8, 0, 56, 0, [(0, "#C9D3E1", 0.2), (0.5, "#E6ECF4", 1), (1, "#C9D3E1", 0.2)])
    return [fill(g, f"M{x1},{y - 1.8:g} H{x2} a1.8,1.8 0 0,1 0,3.6 H{x1} a1.8,1.8 0 0,1 0,-3.6 Z")
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
