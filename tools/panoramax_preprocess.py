#!/usr/bin/env python3
"""
Builds a lightweight coverage grid from the Panoramax GeoParquet.
Outputs a GeoJSON of H3 cells with aggregated counts (all photos vs 360-only).
Designed to run in the container entrypoint before GraphHopper starts.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import tempfile
import urllib.request
from pathlib import Path
from typing import Dict, Iterable, Tuple

import h3
import mapbox_vector_tile as mvt
import pyarrow.parquet as pq
from shapely import wkb
from shapely.geometry import Point, Polygon, mapping

PANORAMAX_URL = "https://api.panoramax.xyz/data/geoparquet/panoramax.parquet"
PANORAMAX_MVT_TEMPLATE = "https://explore.panoramax.fr/api/map/{z}/{x}/{y}.mvt"

# Rough bounding boxes to clip early.
# Format: (minLon, minLat, maxLon, maxLat),
REGION_BBOX = {
    "centre": (-1.5, 46.0, 3.5, 48.5),
    "centre-val-de-loire": (-1.5, 46.0, 3.5, 48.5),
    "france": (-5.5, 41.0, 9.9, 51.7),
}


def download(url: str, dest: Path, force: bool = False) -> None:
    if dest.exists() and not force:
        print(f"Using cached {dest}", file=sys.stderr)
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp_fd, tmp_path = tempfile.mkstemp(dir=str(dest.parent), suffix=".download")
    os.close(tmp_fd)
    try:
        with urllib.request.urlopen(url) as r, open(tmp_path, "wb") as f:
            total = r.length if hasattr(r, "length") and r.length else None
            downloaded = 0
            chunk = r.read(8192)
            while chunk:
                f.write(chunk)
                downloaded += len(chunk)
                if total:
                    pct = downloaded * 100 / total
                    print(f"\rDownloading {url} [{pct:5.1f}%]", end="", file=sys.stderr)
                chunk = r.read(8192)
        if total:
            print(file=sys.stderr)
        Path(tmp_path).replace(dest)
    finally:
        if Path(tmp_path).exists():
            Path(tmp_path).unlink(missing_ok=True)


def detect_columns(schema) -> Tuple[str | None, str | None, str | None]:
    cols = {f.name.lower(): f for f in schema}
    lon = next((n for n in ("lon", "lng", "longitude") if n in cols), None)
    lat = next((n for n in ("lat", "latitude") if n in cols), None)
    geom = next(
        (n for n in ("geometry", "wkb_geometry", "geom") if n in cols),
        None,
    )
    return lon, lat, geom


def detect_pano_flag(schema) -> str | None:
    candidates = ("is_pano", "pano", "is_360", "is_panorama", "panorama")
    cols = {f.name.lower(): f for f in schema}
    return next((n for n in candidates if n in cols), None)


def iter_points(table, lon_col, lat_col, geom_col) -> Iterable[Tuple[float, float]]:
    if lon_col and lat_col:
        lon_arr = table.column(lon_col)
        lat_arr = table.column(lat_col)
        for lo, la in zip(
            lon_arr.to_numpy(zero_copy_only=False),
            lat_arr.to_numpy(zero_copy_only=False),
        ):
            if lo is None or la is None:
                continue
            yield float(lo), float(la)
    elif geom_col:
        geom_arr = table.column(geom_col)
        for val in geom_arr:
            if val is None:
                continue
            try:
                geom = wkb.loads(bytes(val))
            except Exception:
                continue
            if geom.is_empty:
                continue
            pt = geom.centroid if not isinstance(geom, Point) else geom
            yield pt.x, pt.y
    else:
        return


def aggregate(
    parquet_path: Path, bbox: Tuple[float, float, float, float], h3_res: int
) -> Dict[str, Dict[str, int]]:
    bbox = _validate_bbox(bbox)
    counts: Dict[str, Dict[str, int]] = {}
    parquet = pq.ParquetFile(parquet_path)
    lon_col, lat_col, geom_col = detect_columns(parquet.schema_arrow)
    pano_col = detect_pano_flag(parquet.schema_arrow)

    lon_min, lat_min, lon_max, lat_max = bbox

    for batch in parquet.iter_batches(batch_size=50_000):
        table = batch.to_pydict()
        # convert back to Arrow for consistent access
        atable = batch.to_table()
        pano_values = None
        if pano_col and pano_col in table:
            pano_values = atable.column(pano_col).to_numpy(zero_copy_only=False)

        for idx, (lon, lat) in enumerate(
            iter_points(atable, lon_col, lat_col, geom_col)
        ):
            if not (lon_min <= lon <= lon_max and lat_min <= lat <= lat_max):
                continue
            cell = h3.geo_to_h3(lat, lon, h3_res)
            entry = counts.setdefault(cell, {"photo_count": 0, "pano360_count": 0})
            entry["photo_count"] += 1
            if pano_values is not None:
                try:
                    if bool(pano_values[idx]):
                        entry["pano360_count"] += 1
                except Exception:
                    pass
    return counts


def lonlat_to_tile(lon: float, lat: float, zoom: int) -> Tuple[int, int]:
    lat = max(min(lat, 85.05112878), -85.05112878)
    x = int((lon + 180.0) / 360.0 * (1 << zoom))
    y = int(
        (1.0 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi)
        / 2.0
        * (1 << zoom)
    )
    return x, y


def tiles_for_bbox(bbox: Tuple[float, float, float, float], zoom: int) -> Iterable[Tuple[int, int, int]]:
    lon_min, lat_min, lon_max, lat_max = _validate_bbox(bbox)
    x_min, y_max = lonlat_to_tile(lon_min, lat_min, zoom)
    x_max, y_min = lonlat_to_tile(lon_max, lat_max, zoom)
    for x in range(min(x_min, x_max), max(x_min, x_max) + 1):
        for y in range(min(y_min, y_max), max(y_min, y_max) + 1):
            yield x, y, zoom


def iter_mvt_points(tile_bytes: bytes, x: int, y: int, z: int) -> Iterable[Tuple[float, float]]:
    decoded = mvt.decode(tile_bytes)
    scale = 1 << z
    for layer in decoded.values():
        extent = layer.get("extent", 4096)
        for feat in layer.get("features", []):
            geom = feat.get("geometry")
            if not geom:
                continue
            coords_list = geom if isinstance(geom[0][0], (list, tuple)) else [geom]
            for coords in coords_list:
                if len(coords) < 2:
                    continue
                px, py = coords[0], coords[1]
                lon = (x + px / extent) / scale * 360.0 - 180.0
                n = math.pi - 2.0 * math.pi * (y + py / extent) / scale
                lat = math.degrees(math.atan(math.sinh(n)))
                yield lon, lat


def _fetch_tile(url: str) -> bytes | None:
    try:
        with urllib.request.urlopen(url) as r:
            return r.read()
    except Exception as e:
        print(f"Failed to fetch tile {url}: {e}", file=sys.stderr)
        return None


def aggregate_mvt(
    bbox: Tuple[float, float, float, float],
    zoom: int,
    url_template: str,
    h3_res: int,
    flip_y: bool = True,
) -> Dict[str, Dict[str, int]]:
    bbox = _validate_bbox(bbox)
    counts: Dict[str, Dict[str, int]] = {}
    for x, y, z in tiles_for_bbox(bbox, zoom):
        def build_url(yval: int) -> str:
            return (
                url_template.replace("{z}", str(z))
                .replace("{x}", str(x))
                .replace("{y}", str(yval))
            )

        urls_to_try = [build_url(y)]
        if flip_y:
            tms_y = (1 << z) - 1 - y
            urls_to_try.append(build_url(tms_y))

        data = None
        for url in urls_to_try:
            data = _fetch_tile(url)
            if data:
                break
        if not data:
            continue

        for lon, lat in iter_mvt_points(data, x, y, z):
            if not (bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3]):
                continue
            cell = h3.geo_to_h3(lat, lon, h3_res)
            entry = counts.setdefault(cell, {"photo_count": 0, "pano360_count": 0})
            entry["photo_count"] += 1
    return counts


def _validate_bbox(bbox: Tuple[float, float, float, float]) -> Tuple[float, float, float, float]:
    lon_min, lat_min, lon_max, lat_max = bbox
    if not (-180 <= lon_min <= 180 and -180 <= lon_max <= 180 and -90 <= lat_min <= 90 and -90 <= lat_max <= 90):
        raise ValueError(f"bbox out of bounds: {bbox}")
    if lon_min >= lon_max or lat_min >= lat_max:
        raise ValueError(f"bbox malformed (min >= max): {bbox}")
    return bbox


def build_geojson(counts: Dict[str, Dict[str, int]], out_path: Path) -> None:
    features = []
    for cell, agg in counts.items():
        boundary = h3.h3_to_geo_boundary(cell, geo_json=True)
        poly = Polygon(boundary)
        features.append(
            {
                "type": "Feature",
                "geometry": mapping(poly),
                "properties": {
                    "id": cell,
                    "h3": cell,
                    "photo_count": agg["photo_count"],
                    "pano360_count": agg["pano360_count"],
                    "has_photo": agg["photo_count"] > 0,
                    "has_only_360": agg["photo_count"] > 0
                    and agg["pano360_count"] == agg["photo_count"],
                },
            }
        )
    collection = {"type": "FeatureCollection", "features": features}
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(collection), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Panoramax coverage preprocessor")
    parser.add_argument(
        "--region", default="centre", help="centre | france (bbox clipping)"
    )
    parser.add_argument("--output-geojson", default="/data/panoramax_coverage.geojson")
    parser.add_argument("--source", choices=["parquet", "mvt"], default="parquet")
    # parquet mode
    parser.add_argument("--parquet-url", default=PANORAMAX_URL)
    parser.add_argument("--parquet-path", default="/data/panoramax.parquet")
    # mvt mode
    parser.add_argument("--mvt-url-template", default=PANORAMAX_MVT_TEMPLATE)
    parser.add_argument("--mvt-zoom", type=int, default=13, help="Tile zoom for MVT download (default 13)")
    parser.add_argument("--mvt-flip-y", action="store_true", default=True, help="Try TMS Y (flipped) if XYZ 404 (default true)")
    parser.add_argument(
        "--h3-res", type=int, default=12, help="H3 resolution (default 12)"
    )
    parser.add_argument("--force-download", action="store_true")
    args = parser.parse_args()

    region_key = args.region.lower()
    bbox = REGION_BBOX.get(region_key, REGION_BBOX["france"])

    output_geojson = Path(args.output_geojson)

    if args.source == "parquet":
        parquet_path = Path(args.parquet_path)
        download(args.parquet_url, parquet_path, force=args.force_download)
        counts = aggregate(parquet_path, bbox, args.h3_res)
    else:
        counts = aggregate_mvt(bbox, args.mvt_zoom, args.mvt_url_template, args.h3_res, flip_y=args.mvt_flip_y)

    if not counts:
        print("No coverage extracted; leaving output untouched", file=sys.stderr)
        sys.exit(0)
    build_geojson(counts, output_geojson)
    print(f"Wrote coverage to {output_geojson} ({len(counts)} cells)", file=sys.stderr)


if __name__ == "__main__":
    main()
