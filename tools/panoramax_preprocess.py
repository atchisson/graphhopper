#!/usr/bin/env python3
"""
Builds a lightweight coverage index from the Panoramax GeoParquet.
Outputs a compact binary file (.bin) of H3 cell IDs consumed by GraphHopper
to flag edges that already have street-level photo coverage.
Designed to run in the container entrypoint before GraphHopper starts.
"""
from __future__ import annotations

import argparse
import datetime
import os
import resource
import struct
import sys
import time
from concurrent.futures import ProcessPoolExecutor, FIRST_COMPLETED, wait
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np

import h3
import pyarrow as pa
import pyarrow.parquet as pq

_EPOCH = datetime.date(1000, 1, 1)


def _days_since_epoch(dt_value) -> Optional[int]:
    """Return days since 1000-01-01 from a datetime-like value, or None if invalid or before epoch."""
    if dt_value is None:
        return None
    try:
        d = dt_value.date() if hasattr(dt_value, "date") else dt_value
        days = (d - _EPOCH).days
        return days if days > 0 else None
    except Exception:
        return None

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


def _needed_columns(lon_col, lat_col, geom_col, has_orient_col, has_datetime_col) -> List[str]:
    """Return only the parquet columns required for processing."""
    cols = []
    if lon_col and lat_col:
        cols += [lon_col, lat_col]
    elif geom_col:
        cols.append(geom_col)
    if has_orient_col:
        cols.append("pers:interior_orientation")
    if has_datetime_col:
        cols.append("datetime")
    return cols or None  # None = load all (fallback)


def _process_row_group(args: Tuple) -> Dict[str, Dict]:
    """Worker: process a single parquet row group and return partial counts."""
    parquet_path, rg_idx, lon_col, lat_col, geom_col, has_orient_col, has_datetime_col, bbox, h3_res = (
        args
    )
    lon_min, lat_min, lon_max, lat_max = bbox

    columns = _needed_columns(lon_col, lat_col, geom_col, has_orient_col, has_datetime_col)
    parquet = pq.ParquetFile(parquet_path)
    table = parquet.read_row_group(rg_idx, columns=columns)

    counts: Dict[str, Dict] = {}
    orient_col = table.column("pers:interior_orientation") if has_orient_col else None
    dt_col = table.column("datetime") if has_datetime_col and "datetime" in table.schema.names else None

    def _update_entry(cell, row_idx):
        entry = counts.setdefault(cell, {"photo_count": 0, "pano360_count": 0, "min_date": None, "max_date": None})
        entry["photo_count"] += 1
        if orient_col is not None and is_360_photo(orient_col[row_idx]):
            entry["pano360_count"] += 1
        if dt_col is not None:
            days = _days_since_epoch(dt_col[row_idx].as_py())
            if days is not None:
                if entry["min_date"] is None or days < entry["min_date"]:
                    entry["min_date"] = days
                if entry["max_date"] is None or days > entry["max_date"]:
                    entry["max_date"] = days

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
            _update_entry(cell, row_idx)
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
            _update_entry(cell, row_idx)

    return counts


def _merge(merged: Dict, partial: Dict) -> None:
    for cell, agg in partial.items():
        entry = merged.setdefault(cell, {"photo_count": 0, "pano360_count": 0, "min_date": None, "max_date": None})
        entry["photo_count"] += agg["photo_count"]
        entry["pano360_count"] += agg["pano360_count"]
        agg_min = agg.get("min_date")
        agg_max = agg.get("max_date")
        if agg_min is not None:
            if entry["min_date"] is None or agg_min < entry["min_date"]:
                entry["min_date"] = agg_min
        if agg_max is not None:
            if entry["max_date"] is None or agg_max > entry["max_date"]:
                entry["max_date"] = agg_max


def aggregate(
    parquet_path: Path, bbox: Tuple[float, float, float, float], h3_res: int
) -> Dict[str, Dict[str, int]]:
    bbox = _validate_bbox(bbox)
    parquet = pq.ParquetFile(parquet_path)
    lon_col, lat_col, geom_col = detect_columns(parquet.schema_arrow)
    schema_names = [f.name for f in parquet.schema_arrow]
    has_orient_col = "pers:interior_orientation" in schema_names
    has_datetime_col = "datetime" in schema_names

    num_row_groups = parquet.num_row_groups
    n_workers = min(os.cpu_count() or 4, num_row_groups)
    # At most 2× workers pending at once: limits buffered results in RAM
    max_pending = n_workers * 2

    merged: Dict[str, Dict] = {}
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
            has_datetime_col,
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


def read_max_datetime(parquet_path: Path) -> str | None:
    """Return the max datetime found in parquet row-group statistics (ISO date, no full scan)."""
    try:
        import pyarrow as pa
        meta = pq.read_metadata(parquet_path)
        schema = meta.schema.to_arrow_schema()
        col_names = [schema.field(i).name for i in range(len(schema))]
        if "datetime" not in col_names:
            return None
        col_idx = col_names.index("datetime")
        dt_type = schema.field(col_idx).type
        if not (pa.types.is_timestamp(dt_type) or pa.types.is_date(dt_type)):
            # Column exists but is not a proper temporal type (e.g. string/bytes);
            # statistics would be uninterpretable — fall through to full scan below.
            return _read_max_datetime_scan(parquet_path)
        max_dt = None
        for rg in range(meta.num_row_groups):
            stats = meta.row_group(rg).column(col_idx).statistics
            if stats is not None and stats.has_min_max and stats.max is not None:
                if max_dt is None or stats.max > max_dt:
                    max_dt = stats.max
        if max_dt is None:
            return None
        # stats.max is a datetime-like or timestamp int depending on pyarrow version
        try:
            if hasattr(max_dt, "strftime"):
                return max_dt.strftime("%Y-%m-%d")
            # fallback: microseconds since Unix epoch
            dt = datetime.datetime.fromtimestamp(int(max_dt) / 1_000_000, tz=datetime.timezone.utc)
            return dt.strftime("%Y-%m-%d")
        except (ValueError, TypeError, OSError):
            # Stats corrupted (UUID string instead of timestamp) — full scan fallback
            return _read_max_datetime_scan(parquet_path)
    except Exception as e:
        print(f"WARNING: could not read max datetime from parquet: {e}", file=sys.stderr)
        return None


def _read_max_datetime_scan(parquet_path: Path) -> str | None:
    """Full-scan fallback: find the max value in the 'datetime' string column."""
    try:
        parquet = pq.ParquetFile(parquet_path)
        max_dt = None
        for rg in range(parquet.num_row_groups):
            tbl = parquet.read_row_group(rg, columns=["datetime"])
            col = tbl.column("datetime")
            for val in col:
                v = val.as_py()
                days = _days_since_epoch(v)
                if days is not None:
                    if max_dt is None or days > max_dt:
                        max_dt = days
        if max_dt is None:
            return None
        return (_EPOCH + datetime.timedelta(days=max_dt)).isoformat()
    except Exception as e:
        print(f"WARNING: could not read max datetime from parquet: {e}", file=sys.stderr)
        return None


def build_coverage_binary(
    counts: Dict[str, Dict], out_path: Path, h3_res: int
) -> None:
    """Write a PCB2 binary index consumed by GraphHopper's PhotoCoverageLoader.

    Format (big-endian):
      4 bytes  magic "PCB2"
      4 bytes  h3_resolution (int32)
      8 bytes  n_cells (int64)
      n_cells * 24 bytes per cell:
        8 bytes  cell_id      (int64)
        4 bytes  min_date     (int32, days since 1970-01-01; 0 = unknown)
        4 bytes  max_date     (int32, days since 1970-01-01; 0 = unknown)
        4 bytes  photo_count  (int32)
        4 bytes  pano360_count(int32)
    """
    t_start = time.monotonic()

    records = [
        (
            int(c, 16),
            v.get("min_date") or 0,
            v.get("max_date") or 0,
            v["photo_count"],
            v["pano360_count"],
        )
        for c, v in counts.items()
        if v["photo_count"] > 0
    ]

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("wb") as f:
        f.write(b"PCB2")
        f.write(struct.pack(">i", h3_res))
        f.write(struct.pack(">q", len(records)))
        for cell_id, min_d, max_d, photo_cnt, pano360_cnt in records:
            f.write(struct.pack(">qiiii", cell_id, min_d, max_d, photo_cnt, pano360_cnt))

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
    parser.add_argument(
        "--date", default=None, help="Override coverage date (YYYY-MM-DD); skips parquet content scan"
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

    if args.date:
        max_dt = args.date
        print(f"Coverage date (from --date): {max_dt}", file=sys.stderr)
    else:
        max_dt = read_max_datetime(parquet_path)
        if max_dt:
            print(f"Parquet max datetime: {max_dt}", file=sys.stderr)
        else:
            print("WARNING: could not determine parquet max datetime", file=sys.stderr)

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

    if max_dt:
        date_path = output_path.with_suffix(".date")
        date_path.write_text(max_dt)
        print(f"Wrote coverage date to {date_path}", file=sys.stderr)

    # Write min date sidecar from the processed cell data
    all_min_dates = [v["min_date"] for v in counts.values() if v.get("min_date")]
    if all_min_dates:
        min_days = min(all_min_dates)
        min_dt_str = (_EPOCH + datetime.timedelta(days=min_days)).isoformat()
        date_min_path = output_path.with_suffix(".date_min")
        date_min_path.write_text(min_dt_str)
        print(f"Wrote coverage min date to {date_min_path} ({min_dt_str})", file=sys.stderr)


if __name__ == "__main__":
    main()
