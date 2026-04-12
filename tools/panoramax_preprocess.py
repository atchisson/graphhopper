#!/usr/bin/env python3
"""
Builds a lightweight coverage index from the Panoramax GeoParquet.
Outputs a compact binary file (.bin) of H3 cell IDs consumed by GraphHopper
to flag edges that already have street-level photo coverage.
Designed to run in the container entrypoint before GraphHopper starts.
"""
from __future__ import annotations

import argparse
import os
import resource
import struct
import sys
import time
from concurrent.futures import ProcessPoolExecutor, FIRST_COMPLETED, wait
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np

import h3
import pyarrow.parquet as pq

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
    parquet_path, rg_idx, lon_col, lat_col, geom_col, has_orient_col, bbox, h3_res = (
        args
    )
    lon_min, lat_min, lon_max, lat_max = bbox

    columns = _needed_columns(lon_col, lat_col, geom_col, has_orient_col)
    parquet = pq.ParquetFile(parquet_path)
    table = parquet.read_row_group(rg_idx, columns=columns)

    counts: Dict[str, Dict[str, int]] = {}
    orient_col = table.column("pers:interior_orientation") if has_orient_col else None

    if lon_col and lat_col:
        lon_arr = table.column(lon_col).to_numpy(zero_copy_only=False)
        lat_arr = table.column(lat_col).to_numpy(zero_copy_only=False)
        for row_idx, (lo, la) in enumerate(zip(lon_arr, lat_arr)):
            # to_numpy() encodes nulls as nan — skip them explicitly
            if lo != lo or la != la:
                continue
            lo, la = float(lo), float(la)
            if not (lon_min <= lo <= lon_max and lat_min <= la <= lat_max):
                continue
            cell = h3.latlng_to_cell(la, lo, h3_res)
            entry = counts.setdefault(cell, {"photo_count": 0, "pano360_count": 0})
            entry["photo_count"] += 1
            if orient_col is not None and is_360_photo(orient_col[row_idx]):
                entry["pano360_count"] += 1
    elif geom_col:
        from shapely import wkb
        from shapely.geometry import Point

        geom_arr = table.column(geom_col)
        for row_idx, val in enumerate(geom_arr):
            if val is None:
                continue
            try:
                geom = wkb.loads(bytes(val))
            except Exception:
                continue
            if geom.is_empty:
                continue
            pt = geom.centroid if not isinstance(geom, Point) else geom
            lo, la = pt.x, pt.y
            if not (lon_min <= lo <= lon_max and lat_min <= la <= lat_max):
                continue
            cell = h3.latlng_to_cell(la, lo, h3_res)
            entry = counts.setdefault(cell, {"photo_count": 0, "pano360_count": 0})
            entry["photo_count"] += 1
            if orient_col is not None and is_360_photo(orient_col[row_idx]):
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
    has_orient_col = "pers:interior_orientation" in [
        f.name for f in parquet.schema_arrow
    ]

    num_row_groups = parquet.num_row_groups
    n_workers = min(os.cpu_count() or 4, num_row_groups)
    # At most 2× workers pending at once: limits buffered results in RAM
    max_pending = n_workers * 2

    merged: Dict[str, Dict[str, int]] = {}
    completed = 0
    report_every = max(1, num_row_groups // 20)

    def make_args(rg):
        return (
            str(parquet_path),
            rg,
            lon_col,
            lat_col,
            geom_col,
            has_orient_col,
            bbox,
            h3_res,
        )

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
                        file=sys.stderr,
                        flush=True,
                    )
                # Submit next row group as slot freed
                rg = next(rg_iter, None)
                if rg is not None:
                    pending.add(executor.submit(_process_row_group, make_args(rg)))

    elapsed = time.monotonic() - t_start
    ram_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss // 1024
    print(
        f"Parquet processed in {elapsed:.1f}s — peak RAM: {ram_mb} MB",
        file=sys.stderr,
        flush=True,
    )

    return merged


def _validate_bbox(
    bbox: Tuple[float, float, float, float],
) -> Tuple[float, float, float, float]:
    lon_min, lat_min, lon_max, lat_max = bbox
    if not (
        -180 <= lon_min <= 180
        and -180 <= lon_max <= 180
        and -90 <= lat_min <= 90
        and -90 <= lat_max <= 90
    ):
        raise ValueError(f"bbox out of bounds: {bbox}")
    if lon_min >= lon_max or lat_min >= lat_max:
        raise ValueError(f"bbox malformed (min >= max): {bbox}")
    return bbox


def build_coverage_binary(
    counts: Dict[str, Dict[str, int]], out_path: Path, h3_res: int
) -> None:
    """Write a compact binary index consumed by GraphHopper's PhotoCoverageLoader.

    Format (big-endian):
      4 bytes  magic "PCB1"
      4 bytes  h3_resolution (int32)
      8 bytes  n_photo (int64)
      8 bytes  n_360   (int64)
      n_photo * 8 bytes  photo cell IDs (int64)
      n_360   * 8 bytes  360° cell IDs  (int64)
    """
    t_start = time.monotonic()

    # H3 cell strings are hex representations of 64-bit integers (MSB always 0)
    photo_cells = np.array(
        [int(c, 16) for c, v in counts.items() if v["photo_count"] > 0],
        dtype=">i8",
    )
    cells_360 = np.array(
        [int(c, 16) for c, v in counts.items() if v["pano360_count"] > 0],
        dtype=">i8",
    )

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("wb") as f:
        f.write(b"PCB1")
        f.write(struct.pack(">i", h3_res))
        f.write(struct.pack(">q", len(photo_cells)))
        f.write(struct.pack(">q", len(cells_360)))
        f.write(photo_cells.tobytes())
        f.write(cells_360.tobytes())

    elapsed = time.monotonic() - t_start
    ram_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss // 1024
    size_mb = out_path.stat().st_size // (1024 * 1024)
    print(
        f"Coverage binary written in {elapsed:.1f}s — {size_mb} MB — peak RAM: {ram_mb} MB",
        file=sys.stderr,
        flush=True,
    )


def main():
    parser = argparse.ArgumentParser(description="Panoramax coverage preprocessor")
    parser.add_argument(
        "--region", default="centre", help="centre | france (bbox clipping)"
    )
    parser.add_argument("--output", default="/data/panoramax_coverage.bin")
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
    output_path = Path(args.output)

    counts = aggregate(parquet_path, bbox, args.h3_res)

    if not counts:
        print("No coverage extracted; leaving output untouched", file=sys.stderr)
        sys.exit(0)
    print(
        f"Aggregated coverage for {len(counts)} cells, building binary index...",
        file=sys.stderr,
    )
    build_coverage_binary(counts, output_path, args.h3_res)
    print(f"Wrote coverage to {output_path} ({len(counts)} cells)", file=sys.stderr)


if __name__ == "__main__":
    main()
