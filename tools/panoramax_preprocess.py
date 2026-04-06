#!/usr/bin/env python3
"""
Builds a lightweight coverage grid from the Panoramax GeoParquet.
Outputs a GeoJSON of H3 cells with aggregated counts (all photos vs 360-only).
Designed to run in the container entrypoint before GraphHopper starts.
"""
from __future__ import annotations

import argparse
import json
import os
import resource
import sys
import time
from concurrent.futures import ProcessPoolExecutor, FIRST_COMPLETED, wait
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

import h3
import pyarrow.parquet as pq
from shapely import wkb
from shapely.geometry import Point, Polygon, mapping

# Rough bounding boxes to clip early.
# Format: (minLon, minLat, maxLon, maxLat),
REGION_BBOX = {
    "centre": (-1.5, 46.0, 3.5, 48.5),
    "centre-val-de-loire": (-1.5, 46.0, 3.5, 48.5),
    "france": (-5.5, 41.0, 9.9, 51.7),
}


def detect_columns(schema) -> Tuple[str | None, str | None, str | None]:
    cols = {f.name.lower(): f for f in schema}
    lon = next((n for n in ("lon", "lng", "longitude") if n in cols), None)
    lat = next((n for n in ("lat", "latitude") if n in cols), None)
    geom = next(
        (n for n in ("geometry", "wkb_geometry", "geom") if n in cols),
        None,
    )
    return lon, lat, geom


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


def is_360_photo(orient_value) -> bool:
    """Return True if the photo is a 360° equirectangular image."""
    if orient_value is None:
        return False
    try:
        fov = orient_value.as_py().get("field_of_view")
        return fov == 360
    except Exception:
        return False


def _needed_columns(lon_col, lat_col, geom_col, has_orient_col) -> List[str]:
    """Return only the parquet columns required for processing."""
    cols = []
    if lon_col and lat_col:
        cols += [lon_col, lat_col]
    elif geom_col:
        cols.append(geom_col)
    if has_orient_col:
        cols.append("pers:interior_orientation")
    return cols or None  # None = load all (fallback)


def _process_row_group(args: Tuple) -> Dict[str, Dict[str, int]]:
    """Worker: process a single parquet row group and return partial counts."""
    parquet_path, rg_idx, lon_col, lat_col, geom_col, has_orient_col, bbox, h3_res = args
    lon_min, lat_min, lon_max, lat_max = bbox

    columns = _needed_columns(lon_col, lat_col, geom_col, has_orient_col)
    parquet = pq.ParquetFile(parquet_path)
    table = parquet.read_row_group(rg_idx, columns=columns)

    counts: Dict[str, Dict[str, int]] = {}
    orient_col = table.column("pers:interior_orientation") if has_orient_col else None

    for idx, (lon, lat) in enumerate(iter_points(table, lon_col, lat_col, geom_col)):
        if not (lon_min <= lon <= lon_max and lat_min <= lat <= lat_max):
            continue
        cell = h3.latlng_to_cell(lat, lon, h3_res)
        entry = counts.setdefault(cell, {"photo_count": 0, "pano360_count": 0})
        entry["photo_count"] += 1
        if orient_col is not None and is_360_photo(orient_col[idx]):
            entry["pano360_count"] += 1

    return counts


def _merge(merged: Dict, partial: Dict) -> None:
    for cell, agg in partial.items():
        entry = merged.setdefault(cell, {"photo_count": 0, "pano360_count": 0})
        entry["photo_count"] += agg["photo_count"]
        entry["pano360_count"] += agg["pano360_count"]


def aggregate(
    parquet_path: Path, bbox: Tuple[float, float, float, float], h3_res: int
) -> Dict[str, Dict[str, int]]:
    bbox = _validate_bbox(bbox)
    parquet = pq.ParquetFile(parquet_path)
    lon_col, lat_col, geom_col = detect_columns(parquet.schema_arrow)
    has_orient_col = "pers:interior_orientation" in [f.name for f in parquet.schema_arrow]

    num_row_groups = parquet.num_row_groups
    n_workers = min(os.cpu_count() or 4, num_row_groups)
    # At most 2× workers pending at once: limits buffered results in RAM
    max_pending = n_workers * 2

    merged: Dict[str, Dict[str, int]] = {}
    completed = 0
    report_every = max(1, num_row_groups // 20)

    def make_args(rg):
        return (str(parquet_path), rg, lon_col, lat_col, geom_col, has_orient_col, bbox, h3_res)

    t_start = time.monotonic()

    with ProcessPoolExecutor(max_workers=n_workers) as executor:
        pending = set()
        rg_iter = iter(range(num_row_groups))

        # Seed the pool
        for rg in rg_iter:
            pending.add(executor.submit(_process_row_group, make_args(rg)))
            if len(pending) >= max_pending:
                break

        while pending:
            done, pending = wait(pending, return_when=FIRST_COMPLETED)
            for future in done:
                _merge(merged, future.result())
                completed += 1
                if completed % report_every == 0 or completed == num_row_groups:
                    pct = completed * 100 // num_row_groups
                    print(
                        f"Processing parquet: {completed}/{num_row_groups} row groups ({pct}%)",
                        file=sys.stderr, flush=True,
                    )
                # Submit next row group as slot freed
                rg = next(rg_iter, None)
                if rg is not None:
                    pending.add(executor.submit(_process_row_group, make_args(rg)))

    elapsed = time.monotonic() - t_start
    ram_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss // 1024
    print(
        f"Parquet processed in {elapsed:.1f}s — peak RAM: {ram_mb} MB",
        file=sys.stderr, flush=True,
    )

    return merged


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
        boundary = [(lng, lat) for lat, lng in h3.cell_to_boundary(cell)]
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
                    "has_360": agg["pano360_count"] > 0,
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
    parser.add_argument("--parquet-path", default="/data/panoramax.parquet")
    parser.add_argument(
        "--h3-res", type=int, default=12, help="H3 resolution (default 12)"
    )
    args = parser.parse_args()

    parquet_path = Path(args.parquet_path)
    if not parquet_path.exists():
        print(
            f"WARNING: Panoramax parquet file not found at {parquet_path}.\n"
            f"Download it manually and place it at that path:\n"
            f"  curl -L https://api.panoramax.xyz/data/geoparquet/panoramax.parquet -o {parquet_path}",
            file=sys.stderr,
        )
        sys.exit(1)

    region_key = args.region.lower()
    bbox = REGION_BBOX.get(region_key, REGION_BBOX["france"])
    output_geojson = Path(args.output_geojson)

    counts = aggregate(parquet_path, bbox, args.h3_res)

    if not counts:
        print("No coverage extracted; leaving output untouched", file=sys.stderr)
        sys.exit(0)
    build_geojson(counts, output_geojson)
    print(f"Wrote coverage to {output_geojson} ({len(counts)} cells)", file=sys.stderr)


if __name__ == "__main__":
    main()
