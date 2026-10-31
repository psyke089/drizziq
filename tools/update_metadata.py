#!/usr/bin/env python3
"""Regenerate DWD station metadata and the pinned offline timezone database."""
import json
import math
import tempfile
import re
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
TZ_REVISION = "6051e7e2fe8b754e23d40aff0120cf9b38bde608"


def get(url, encoding="utf-8"):
    request = Request(url, headers={"User-Agent": "Drizziq-metadata/1.0"})
    with urlopen(request, timeout=30) as response:
        return response.read().decode(encoding)


def decimal(degrees_minutes):
    match = re.fullmatch(r"([+-]?)([0-9]+)\.([0-9]{2})", degrees_minutes.strip())
    if not match or int(match[3]) >= 60:
        raise ValueError("Invalid degrees/minutes")
    value = int(match[2]) + int(match[3]) / 60
    return -value if match[1] == "-" else value


def parse_catalogue(text, minimum_rows=2000):
    rows, ids = [], set()
    for number, line in enumerate(text.splitlines()[2:], 3):
        if not line.strip():
            continue
        try:
            if len(line) < 47:
                raise ValueError("Truncated row")
            identifier, name = line[:5].strip(), line[11:31].strip()
            lat, lon = decimal(line[32:38]), decimal(line[39:46])
            elevation = line[47:].strip()
            if not re.fullmatch(r"[A-Za-z0-9]+", identifier) or not name or identifier in ids:
                raise ValueError("Invalid/duplicate ID or blank name")
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                raise ValueError("Coordinates out of range")
            if elevation and not math.isfinite(float(elevation)):
                raise ValueError("Invalid elevation")
            ids.add(identifier)
            rows.append("\t".join((identifier, name, f"{lat:.6f}", f"{lon:.6f}", elevation)))
        except (ValueError, IndexError) as error:
            raise ValueError(f"MOSMIX line {number}: {error}") from error
    if len(rows) <= minimum_rows:
        raise ValueError(f"Catalogue needs more than {minimum_rows} rows, got {len(rows)}")
    return rows


def validate_reference_points(rows):
    points = [row.split("\t") for row in rows]
    def distance(lat, lon, point):
        a, b = math.radians(float(point[2])), math.radians(lat)
        h = math.sin((a-b)/2)**2 + math.cos(a)*math.cos(b)*math.sin(math.radians(float(point[3])-lon)/2)**2
        return 6371.0088*2*math.asin(math.sqrt(min(1,max(0,h))))
    for lat, lon, expected in ((48.137,11.575,"10865"),(52.520,13.405,"10389"),(53.551,9.993,"P0489")):
        if min(points,key=lambda p:(distance(lat,lon,p),p[0]))[0] != expected:
            raise ValueError("Reference nearest point changed; review catalogue revision")


def main():
    catalogue = get("https://www.dwd.de/DE/leistungen/met_verfahren_mosmix/"
                    "mosmix_stationskatalog.cfg?view=nasPublication&nn=16102", "latin-1")
    rows = parse_catalogue(catalogue)
    validate_reference_points(rows)
    timezone_js = get(f"https://raw.githubusercontent.com/photostructure/tz-lookup/{TZ_REVISION}/tz.js")
    match = re.search(r'var X=(".*?"),T=(\[.*?\]);', timezone_js)
    if not match:
        raise ValueError("Pinned tz-lookup format changed")
    tree, zones = json.loads(match[1]), json.loads(match[2])
    if not isinstance(tree,str) or not tree or not isinstance(zones,list) or not zones or not all(isinstance(z,str) and z for z in zones):
        raise ValueError("Invalid timezone metadata")
    outputs={"stations.tsv":"\n".join(rows)+"\n","timezones.tree":tree,"timezones.txt":"\n".join(zones)+"\n"}
    # Complete fetching, parsing and validation before touching any generated asset.
    ASSETS.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=ASSETS.parent) as temporary:
        staged=Path(temporary)
        for name,content in outputs.items():
            (staged/name).write_text(content,encoding="utf-8")
        ASSETS.mkdir(parents=True,exist_ok=True)
        for name in outputs:
            (staged/name).replace(ASSETS/name)
    print(f"Wrote {len(rows)} DWD stations and the pinned timezone database")


if __name__ == "__main__":
    main()
