#!/usr/bin/env python3
"""Regenerate DWD station metadata and the pinned offline timezone database."""
import json
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
    value = float(degrees_minutes)
    whole = int(abs(value))
    return (whole + (abs(value) - whole) * 100 / 60) * (1 if value >= 0 else -1)


def main():
    catalogue = get("https://www.dwd.de/DE/leistungen/met_verfahren_mosmix/"
                    "mosmix_stationskatalog.cfg?view=nasPublication&nn=16102", "latin-1")
    rows = []
    for line in catalogue.splitlines()[2:]:
        if not line.strip():
            continue
        lat, lon = decimal(line[32:38]), decimal(line[39:46])
        assert -90 <= lat <= 90 and -180 <= lon <= 180
        rows.append("\t".join((line[:5].strip(), line[11:31].strip(),
                               f"{lat:.6f}", f"{lon:.6f}", line[47:].strip())))
    assert len(rows) > 2000 and any(row.startswith("10865\t") for row in rows)
    timezone_js = get(f"https://raw.githubusercontent.com/photostructure/tz-lookup/{TZ_REVISION}/tz.js")
    match = re.search(r'var X=(".*?"),T=(\[.*?\]);', timezone_js)
    assert match, "Pinned tz-lookup format changed"
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "stations.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    (ASSETS / "timezones.tree").write_text(json.loads(match[1]), encoding="utf-8")
    (ASSETS / "timezones.txt").write_text("\n".join(json.loads(match[2])) + "\n", encoding="utf-8")
    print(f"Wrote {len(rows)} DWD stations and the pinned timezone database")


if __name__ == "__main__":
    main()
