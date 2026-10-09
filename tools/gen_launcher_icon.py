#!/usr/bin/env python3
"""Generates the launcher icon: a thermometer in a trench coat, holding an umbrella in the rain.

  res/drawable/ic_launcher_background.xml   rainy-sky gradient (adaptive icon background)
  res/drawable/ic_launcher_foreground.xml   the figure, umbrella and rain (108 x 108 canvas)

The icon is drawn once as SVG (handy for previews: pass a folder to also write
ic_launcher.svg) and converted to Android vector drawables. Run from the repo root.
"""
import os
import sys
import xml.etree.ElementTree as ET

INK = "#141A26"
COAT, COAT_SHADE = "#C9A66B", "#A07D45"


def lin(gid, x1, y1, x2, y2, stops):
    s = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
    return (f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1:g}" y1="{y1:g}" x2="{x2:g}" '
            f'y2="{y2:g}">{s}</linearGradient>')


def rad(gid, cx, cy, r, stops):
    s = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
    return f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx:g}" cy="{cy:g}" r="{r:g}">{s}</radialGradient>'


# ---------------------------------------------------------------- drawing (SVG, 108 x 108)

RAIN = [(24, 26), (30, 40), (22, 52), (28, 66), (34, 78), (20, 80), (84, 46), (80, 60), (88, 70), (76, 76),
        (86, 30), (40, 22), (72, 22)]


def rain():
    return "".join(f'<path d="M{x},{y} l-2.2,6.5" fill="none" stroke="#D8E6F5" stroke-width="0.9" '
                   f'stroke-linecap="round" opacity="0.55"/>' for x, y in RAIN)


def umbrella(hx, base, rx, ry, sw):
    top = base - ry
    n = 4
    w = 2 * rx / n
    pts = [hx - rx + i * w for i in range(n + 1)]
    d = f"M{hx - rx},{base} A{rx},{ry} 0 0 1 {hx + rx},{base} "
    for i in range(n, 0, -1):
        d += f"Q{pts[i] - w / 2:g},{base - 3.4:g} {pts[i - 1]:g},{base} "
    d += "Z"
    o = [f'<defs>{lin("um", hx - rx, top, hx + rx, base, [(0, "#3A4256"), (1, "#151A24")])}'
         f'<clipPath id="uc"><path d="{d}"/></clipPath></defs>',
         f'<path d="{d}" fill="url(#um)"/>',
         # soft sheen on the left panels
         f'<path clip-path="url(#uc)" d="M{hx - rx},{base} A{rx},{ry} 0 0 1 {hx - 2},{top} L{hx - rx * 0.62:g},{base} Z" '
         f'fill="#FFFFFF" opacity="0.10"/>']
    for p in pts[1:-1]:
        o.append(f'<path d="M{hx},{top + 0.5:g} Q{(hx + p) / 2:g},{top + ry * 0.45:g} {p:g},{base - 0.4:g}" fill="none" '
                 f'stroke="#5B6680" stroke-width="0.8" opacity="0.8"/>')
    o.append(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="{sw}" stroke-linejoin="round"/>')
    o.append(f'<path d="M{hx},{top} V{top - 2.4:g}" fill="none" stroke="{INK}" stroke-width="{sw * 1.1:g}" '
             f'stroke-linecap="round"/>')
    return "".join(o)


def shaft(hx, base, end, sw):
    return (f'<path d="M{hx},{base - 1} V{end}" fill="none" stroke="#2A3040" stroke-width="{sw * 1.15:g}" '
            f'stroke-linecap="round"/>'
            f'<path d="M{hx},{end - 1} V{end + 3.2:g}" fill="none" stroke="#4A3220" stroke-width="{sw * 1.9:g}" '
            f'stroke-linecap="round"/>')


def tube(cx, top, bottom, sw):
    """The thermometer's head: glass tube with mercury and a scale, above the collar."""
    w = 6.2
    o = [f'<defs>{lin("gl", cx - w, 0, cx + w, 0, [(0, "#FFFFFF"), (1, "#C9DAEE")])}</defs>',
         f'<rect x="{cx - w:g}" y="{top}" width="{2 * w:g}" height="{bottom - top}" rx="{w}" fill="url(#gl)" '
         f'stroke="{INK}" stroke-width="{sw}"/>',
         f'<rect x="{cx - 1.7:g}" y="{top + 7}" width="3.4" height="{bottom - top - 6}" rx="1.7" fill="#D7261E"/>']
    for i in range(5):
        y = top + 5 + i * 2.6
        length = 3.2 if i % 2 == 0 else 1.9
        o.append(f'<path d="M{cx + w - 0.6 - length:g},{y:g} H{cx + w - 0.6:g}" fill="none" stroke="{INK}" '
                 f'stroke-width="0.8" stroke-linecap="round" opacity="0.75"/>')
    o.append(f'<rect x="{cx - w + 1.6:g}" y="{top + 3}" width="1.6" height="{bottom - top - 6}" rx="0.8" '
             f'fill="#FFFFFF" opacity="0.9"/>')
    return "".join(o)


def coat(cx, sw):
    sh, hem = 56.5, 82
    body = (f"M{cx - 11},{sh + 3} Q{cx - 11},{sh} {cx - 7},{sh} L{cx + 7},{sh} Q{cx + 11},{sh} {cx + 11},{sh + 3} "
            f"L{cx + 13},{hem} L{cx - 13},{hem} Z")
    o = [f'<defs>{lin("ct", cx - 13, 0, cx + 13, 0, [(0, COAT), (1, COAT_SHADE)])}</defs>',
         f'<path d="{body}" fill="url(#ct)" stroke="{INK}" stroke-width="{sw}" stroke-linejoin="round"/>',
         # front overlap edge
         f'<path d="M{cx + 2.5},{sh + 5} L{cx + 3.5},{hem}" fill="none" stroke="{INK}" stroke-width="{sw * 0.6:g}" opacity="0.7"/>',
         # belt + buckle
         f'<path d="M{cx - 11.9:g},70 L{cx + 11.9:g},70 L{cx + 12.1:g},73 L{cx - 12.1:g},73 Z" fill="{COAT_SHADE}" '
         f'stroke="{INK}" stroke-width="{sw * 0.6:g}"/>',
         f'<rect x="{cx - 0.6:g}" y="69.4" width="4.2" height="4.2" rx="0.6" fill="none" stroke="#2A2014" stroke-width="0.9"/>']
    for bx in (cx - 2.5, cx + 6.5):  # double-breasted buttons
        for by in (62, 66.3, 77):
            o.append(f'<circle cx="{bx:g}" cy="{by}" r="0.95" fill="#3B2A16"/>')
    # popped collar
    o.append(f'<path d="M{cx - 6.4:g},{sh - 4} L{cx - 10.5:g},{sh + 1.5} L{cx - 4},{sh + 9} L{cx - 1.5:g},{sh + 1.2:g} Z" '
             f'fill="{COAT}" stroke="{INK}" stroke-width="{sw * 0.75:g}" stroke-linejoin="round"/>')
    o.append(f'<path d="M{cx + 6.4:g},{sh - 4} L{cx + 10.5:g},{sh + 1.5} L{cx + 5},{sh + 9} L{cx + 1.5:g},{sh + 1.2:g} Z" '
             f'fill="{COAT_SHADE}" stroke="{INK}" stroke-width="{sw * 0.75:g}" stroke-linejoin="round"/>')
    for a, b in ((cx - 10.5, cx - 6.5), (cx + 10.5, cx + 6.5)):  # epaulettes
        o.append(f'<path d="M{a:g},{sh + 1.6} L{b:g},{sh + 0.4}" fill="none" stroke="{INK}" stroke-width="{sw * 0.6:g}" opacity="0.6"/>')
    return "".join(o)


def sleeves(cx, hx, hand_y, sw):
    o = []
    for c, w in ((INK, 6.4), (COAT_SHADE, 4.6)):  # right arm bent, hand on the umbrella
        o.append(f'<path d="M{cx + 9.5},58.6 Q{cx + 14},63.5 {hx + 0.5},{hand_y + 0.3:g}" fill="none" stroke="{c}" '
                 f'stroke-width="{w}" stroke-linecap="round"/>')
    for c, w in ((INK, 6.4), (COAT, 4.6)):  # left arm down
        o.append(f'<path d="M{cx - 10},59 Q{cx - 13},66 {cx - 12.5},73.5" fill="none" stroke="{c}" '
                 f'stroke-width="{w}" stroke-linecap="round"/>')
    o.append(f'<circle cx="{hx}" cy="{hand_y}" r="2.1" fill="#2B2B33" stroke="{INK}" stroke-width="0.8"/>')
    o.append(f'<circle cx="{cx - 12.4:g}" cy="75.3" r="2" fill="#2B2B33" stroke="{INK}" stroke-width="0.8"/>')
    return "".join(o)


def bulb(cx, cy, sw):
    r = 5.4
    return (f'<defs>{rad("mer", cx - r + 0.38 * 2 * r, cy - r + 0.35 * 2 * r, 0.7 * 2 * r, [(0, "#FF7A6A"), (0.6, "#D7261E"), (1, "#8E1212")])}</defs>'
            f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="url(#mer)" stroke="{INK}" stroke-width="{sw}"/>'
            f'<circle cx="{cx - 1.8:g}" cy="{cy - 1.6:g}" r="1.3" fill="#FFFFFF" opacity="0.55"/>')


BACKGROUND = (f'<defs>{lin("bg", 0, 0, 0, 108, [(0, "#6E86A4"), (1, "#2E3D55")])}</defs>'
              f'<rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>')


def foreground():
    cx, hx, sw = 50, 60, 1.4
    parts = [rain(),
             f'<ellipse cx="{cx}" cy="88.5" rx="16" ry="1.8" fill="#9FB6D2" opacity="0.35"/>',  # wet ground
             bulb(cx, 84, sw), tube(cx, 40, 60, sw), coat(cx, sw),
             shaft(hx, 35, 62, sw), umbrella(hx, 35, 24, 15, sw), sleeves(cx, hx, 62.5, sw)]
    # A little smaller and higher than drawn, so it sits well inside the adaptive icon's safe zone.
    return f'<g transform="translate(54 54) scale(0.92) translate(-54 -55)">{"".join(parts)}</g>'


def svg(body):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">{body}</svg>'


# ---------------------------------------------------------------- SVG -> Android vector drawable

def _num(v):
    return float(v) if v is not None else 0.0


def _shape_path(el):
    tag = el.tag.split("}")[-1]
    a = el.attrib
    if tag == "path":
        return a["d"]
    if tag == "rect":
        x, y, w, h, r = (_num(a.get(k)) for k in ("x", "y", "width", "height", "rx"))
        r = min(r, w / 2, h / 2)
        if r <= 0:
            return f"M{x:g},{y:g} h{w:g} v{h:g} h{-w:g} Z"
        return (f"M{x + r:g},{y:g} H{x + w - r:g} A{r:g},{r:g} 0 0 1 {x + w:g},{y + r:g} V{y + h - r:g} "
                f"A{r:g},{r:g} 0 0 1 {x + w - r:g},{y + h:g} H{x + r:g} A{r:g},{r:g} 0 0 1 {x:g},{y + h - r:g} "
                f"V{y + r:g} A{r:g},{r:g} 0 0 1 {x + r:g},{y:g} Z")
    if tag in ("circle", "ellipse"):
        cx, cy = _num(a.get("cx")), _num(a.get("cy"))
        rx = _num(a.get("r") or a.get("rx"))
        ry = _num(a.get("r") or a.get("ry"))
        return (f"M{cx - rx:g},{cy:g} A{rx:g},{ry:g} 0 1 0 {cx + rx:g},{cy:g} "
                f"A{rx:g},{ry:g} 0 1 0 {cx - rx:g},{cy:g} Z")
    return None


def _color(c):
    c = c.strip()
    if len(c) == 4:  # #abc
        c = "#" + "".join(ch * 2 for ch in c[1:])
    return c.upper()


def _ref(v):
    return v[5:-1] if v and v.startswith("url(#") else None


def to_vector(svg_text, comment):
    root = ET.fromstring(svg_text)
    grads, clips = {}, {}
    for el in root.iter():
        tag = el.tag.split("}")[-1]
        if tag in ("linearGradient", "radialGradient"):
            grads[el.get("id")] = el
        elif tag == "clipPath":
            clips[el.get("id")] = _shape_path(list(el)[0])

    def gradient_xml(g, ind):
        tag = g.tag.split("}")[-1]
        if tag == "linearGradient":
            head = (f'<gradient android:type="linear" android:startX="{g.get("x1")}" android:startY="{g.get("y1")}" '
                    f'android:endX="{g.get("x2")}" android:endY="{g.get("y2")}">')
        else:
            head = (f'<gradient android:type="radial" android:centerX="{g.get("cx")}" android:centerY="{g.get("cy")}" '
                    f'android:gradientRadius="{g.get("r")}">')
        stops = [f'{ind}    <item android:offset="{s.get("offset")}" android:color="{_color(s.get("stop-color"))}" />'
                 for s in g]
        return [ind + head] + stops + [ind + "</gradient>"]

    def path_xml(el, ind):
        d = _shape_path(el)
        a = el.attrib
        fill, stroke = a.get("fill", "#000000"), a.get("stroke")
        op = a.get("opacity")
        attrs, kids = [f'android:pathData="{d}"'], []
        if fill != "none":
            if _ref(fill):
                kids += [f'{ind}    <aapt:attr name="android:fillColor">'] + gradient_xml(grads[_ref(fill)], ind + "        ") + [f"{ind}    </aapt:attr>"]
            else:
                attrs.append(f'android:fillColor="{_color(fill)}"')
            if op:
                attrs.append(f'android:fillAlpha="{op}"')
        if stroke and stroke != "none":
            attrs.append(f'android:strokeColor="{_color(stroke)}"')
            attrs.append(f'android:strokeWidth="{a.get("stroke-width", "1")}"')
            if a.get("stroke-linecap"):
                attrs.append(f'android:strokeLineCap="{a["stroke-linecap"]}"')
            if a.get("stroke-linejoin"):
                attrs.append(f'android:strokeLineJoin="{a["stroke-linejoin"]}"')
            if op:
                attrs.append(f'android:strokeAlpha="{op}"')
        open_tag = f"{ind}<path " + f"\n{ind}    ".join(attrs)
        if not kids:
            return [open_tag + " />"]
        return [open_tag + ">"] + kids + [f"{ind}</path>"]

    def walk(el, ind):
        out = []
        for ch in el:
            tag = ch.tag.split("}")[-1]
            if tag in ("defs", "linearGradient", "radialGradient", "clipPath", "stop"):
                continue
            if tag == "g":
                tr = ch.get("transform", "")
                # only the form used above: translate(px py) scale(s) translate(-px -qy)
                nums = [float(x) for x in tr.replace("translate(", " ").replace("scale(", " ").replace(")", " ").split()]
                px, py, s, _, qy = nums
                out.append(f'{ind}<group android:pivotX="{px:g}" android:pivotY="{py:g}" android:scaleX="{s:g}" '
                           f'android:scaleY="{s:g}" android:translateY="{s * (py + qy):g}">')
                out += walk(ch, ind + "    ")
                out.append(f"{ind}</group>")
            elif _ref(ch.get("clip-path")):
                out.append(f"{ind}<group>")
                out.append(f'{ind}    <clip-path android:pathData="{clips[_ref(ch.get("clip-path"))]}" />')
                out += path_xml(ch, ind + "    ")
                out.append(f"{ind}</group>")
            else:
                out += path_xml(ch, ind)
        return out

    body = "\n".join(walk(root, "    "))
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Generated by tools/gen_launcher_icon.py: {comment} -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp" android:height="108dp"\n'
            f'    android:viewportWidth="108" android:viewportHeight="108">\n{body}\n</vector>\n')


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    draw = os.path.join(root, "app/src/main/res/drawable")
    with open(os.path.join(draw, "ic_launcher_background.xml"), "w") as f:
        f.write(to_vector(svg(BACKGROUND), "rainy sky"))
    with open(os.path.join(draw, "ic_launcher_foreground.xml"), "w") as f:
        f.write(to_vector(svg(foreground()), "thermometer in a trench coat with an umbrella"))
    if len(sys.argv) > 1:  # optional: SVG preview
        os.makedirs(sys.argv[1], exist_ok=True)
        with open(os.path.join(sys.argv[1], "ic_launcher.svg"), "w") as f:
            f.write(svg(BACKGROUND + foreground()))
    print("wrote launcher icon")
