#!/usr/bin/env python3
"""Compile editable SVGs to native vectors; no runtime SVG dependency is needed.

Supports paths, circles, ellipses, rectangles, lines and polygons; inherited flat
styles; translate/scale/rotate groups. Unsupported SVG features fail explicitly.
"""
import argparse
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)
A = "{" + ANDROID + "}"
STYLES = {"fill", "stroke", "stroke-width", "fill-opacity", "stroke-opacity",
          "stroke-linecap", "stroke-linejoin", "fill-rule"}
SHAPES = {"path", "circle", "ellipse", "line", "rect", "polygon", "polyline"}


def number(value):
    value = float(value)
    if not math.isfinite(value):
        raise ValueError("Non-finite SVG coordinate")
    return f"{value:.8g}"


def color(value):
    if value == "none":
        return "#00000000"
    if not re.fullmatch(r"#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}", value):
        raise ValueError(f"Only flat hex colours are supported: {value}")
    if len(value) == 4:
        return "#" + "".join(c * 2 for c in value[1:])
    return value


def path_data(element):
    tag = element.tag.rsplit("}", 1)[-1]
    def n(key, default=0):
        return float(element.get(key, default))
    if tag == "path":
        return element.attrib["d"]
    if tag == "line":
        return f'M{number(n("x1"))},{number(n("y1"))}L{number(n("x2"))},{number(n("y2"))}'
    if tag in ("circle", "ellipse"):
        cx, cy = n("cx"), n("cy")
        rx = n("r") if tag == "circle" else n("rx")
        ry = rx if tag == "circle" else n("ry")
        if rx <= 0 or ry <= 0:
            raise ValueError("SVG radius must be positive")
        return f'M{number(cx-rx)},{number(cy)}a{number(rx)},{number(ry)} 0,1 0,{number(rx*2)},0a{number(rx)},{number(ry)} 0,1 0,{number(-rx*2)},0Z'
    if tag == "rect":
        x, y, w, h = n("x"), n("y"), n("width"), n("height")
        rx, ry = n("rx", element.get("ry", 0)), n("ry", element.get("rx", 0))
        rx, ry = min(rx, w/2), min(ry, h/2)
        if not rx or not ry:
            return f'M{number(x)},{number(y)}h{number(w)}v{number(h)}h{number(-w)}Z'
        return f'M{number(x+rx)},{number(y)}H{number(x+w-rx)}a{number(rx)},{number(ry)} 0,0 1,{number(rx)},{number(ry)}V{number(y+h-ry)}a{number(rx)},{number(ry)} 0,0 1,{number(-rx)},{number(ry)}H{number(x+rx)}a{number(rx)},{number(ry)} 0,0 1,{number(-rx)},{number(-ry)}V{number(y+ry)}a{number(rx)},{number(ry)} 0,0 1,{number(rx)},{number(-ry)}Z'
    if tag in ("polygon", "polyline"):
        points = [float(v) for v in re.split(r"[\s,]+", element.attrib["points"].strip())]
        if len(points) < 4 or len(points) % 2:
            raise ValueError("Invalid SVG points")
        return "M" + "L".join(f"{number(x)},{number(y)}" for x, y in zip(points[::2], points[1::2])) + ("Z" if tag == "polygon" else "")
    raise ValueError(f"Unsupported SVG shape: {tag}")


def transformed(parent, transform):
    pattern = r"([A-Za-z]+)\s*\(([^()]*)\)"
    if re.sub(pattern, "", transform).strip(" ,\t\n"):
        raise ValueError(f"Invalid SVG transform: {transform}")
    for name, args in re.findall(pattern, transform):
        values = [float(v) for v in re.split(r"[\s,]+", args.strip())]
        group = ET.SubElement(parent, "group")
        if name == "translate" and len(values) in (1, 2):
            attrs = {"translateX": values[0], "translateY": values[1] if len(values) == 2 else 0}
        elif name == "scale" and len(values) in (1, 2):
            attrs = {"scaleX": values[0], "scaleY": values[-1]}
        elif name == "rotate" and len(values) in (1, 3):
            attrs = {"rotation": values[0]}
            if len(values) == 3:
                attrs.update(pivotX=values[1], pivotY=values[2])
        else:
            raise ValueError(f"Unsupported SVG transform: {name}")
        for key, value in attrs.items():
            group.set(A + key, number(value))
        parent = group
    return parent


def convert_svg(svg):
    root = ET.fromstring(svg)
    x, y, width, height = map(float, root.attrib["viewBox"].split())
    if width <= 0 or height <= 0:
        raise ValueError("Invalid SVG viewBox")
    vector = ET.Element("vector", {
        A + "width": number(root.get("width", 24)) + "dp",
        A + "height": number(root.get("height", 24 * height / width)) + "dp",
        A + "viewportWidth": number(width), A + "viewportHeight": number(height),
    })
    origin = ET.SubElement(vector, "group", {A + "translateX": number(-x), A + "translateY": number(-y)})

    def visit(element, parent, inherited, opacity):
        tag = element.tag.rsplit("}", 1)[-1]
        if tag in ("title", "desc"):
            return
        if tag not in SHAPES | {"svg", "g"}:
            raise ValueError(f"Unsupported SVG element: {tag}")
        common = STYLES | {"transform", "opacity", "id"}
        geometry = {"svg": {"viewBox", "width", "height"}, "g": set(), "path": {"d"},
                    "circle": {"cx", "cy", "r"}, "ellipse": {"cx", "cy", "rx", "ry"},
                    "line": {"x1", "y1", "x2", "y2"}, "rect": {"x", "y", "width", "height", "rx", "ry"},
                    "polygon": {"points"}, "polyline": {"points"}}
        unsupported = element.attrib.keys() - common - geometry[tag]
        if unsupported:
            raise ValueError(f"Unsupported SVG attributes: {sorted(unsupported)}")
        style = inherited | {k: v for k, v in element.attrib.items() if k in STYLES}
        opacity *= float(element.get("opacity", 1))
        parent = transformed(parent, element.get("transform", ""))
        if tag in ("svg", "g"):
            for child in element:
                visit(child, parent, style, opacity)
            return
        attrs = {A + "pathData": path_data(element), A + "fillColor": color(style.get("fill", "#000000"))}
        for kind in ("fill", "stroke"):
            alpha = opacity * float(style.get(kind + "-opacity", 1))
            if not 0 <= alpha <= 1:
                raise ValueError("Invalid SVG opacity")
            if alpha != 1:
                attrs[A + kind + "Alpha"] = number(alpha)
        if style.get("stroke", "none") != "none":
            attrs[A + "strokeColor"] = color(style["stroke"])
            attrs[A + "strokeWidth"] = number(style.get("stroke-width", 1))
        for source, dest in (("stroke-linecap", "strokeLineCap"), ("stroke-linejoin", "strokeLineJoin"), ("fill-rule", "fillType")):
            if source in style:
                value = style[source]
                attrs[A + dest] = {"evenodd": "evenOdd", "nonzero": "nonZero"}.get(value, value)
        ET.SubElement(parent, "path", attrs)

    visit(root, origin, {}, 1)
    ET.indent(vector, space="    ")
    return '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by tools/generate_icons.py; edit artwork/icons/*.svg instead. -->\n' + ET.tostring(vector, encoding="unicode") + "\n"


def generate(root=ROOT, check=False):
    sources = sorted((root / "artwork/icons").rglob("*.svg"))
    if not sources:
        raise ValueError("No SVG icon sources found")
    outputs = {}
    names = set()
    for source in sources:
        name = source.stem
        if name in names or not re.fullmatch(r"[a-z][a-z0-9-]*", name):
            raise ValueError(f"Duplicate or invalid icon name: {name}")
        names.add(name)
        resource = "ic_" + name.replace("-", "_")
        outputs[root / f"app/src/main/res/drawable/{resource}.xml"] = convert_svg(source.read_text())
        if name == "launcher-legacy":
            outputs[root / "app/src/main/res/mipmap-anydpi/ic_launcher.xml"] = outputs[root / f"app/src/main/res/drawable/{resource}.xml"]
    entries = "\n".join(f'            "{name}" -> R.drawable.ic_{name.replace("-", "_")}' for name in sorted(names))
    outputs[root / "app/src/main/java/app/drizziq/IconAssets.kt"] = (
        '// Generated by tools/generate_icons.py; edit artwork/icons/*.svg instead.\n'
        'package app.drizziq\n\nobject IconAssets {\n'
        '    fun resource(name: String): Int =\n        when (name) {\n' + entries + '\n'
        '            else -> error("Unknown icon: $name")\n        }\n}\n'
    )
    changed = []
    for path, content in outputs.items():
        if not path.exists() or path.read_text() != content:
            changed.append(str(path.relative_to(root)))
            if not check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
    for path in (root / "app/src/main/res/drawable").glob("ic_*.xml"):
        if path not in outputs and "Generated by tools/generate_icons.py;" in path.read_text():
            changed.append(str(path.relative_to(root)))
            if not check:
                path.unlink()
    if check and changed:
        raise ValueError("Regenerate SVG icons: " + ", ".join(changed))
    return len(sources)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    print(f"{generate(check=args.check)} SVG icons {'checked' if args.check else 'generated'}")


if __name__ == "__main__":
    main()
