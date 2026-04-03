#!/usr/bin/env bash
set -euo pipefail

# Select extract to download based on OSM_REGION
REGION="${OSM_REGION:-centre}"
PHOTO_MODE="${PHOTO_MODE:-any}" # any | only360
PHOTO_PROVIDERS="${PHOTO_PROVIDERS:-panoramax}"
PHOTO_WEIGHT="${PHOTO_WEIGHT:-0}"
PHOTO_COVERAGE_FILE="${PHOTO_COVERAGE_FILE:-/data/panoramax_coverage.geojson}"
PHOTO_FORCE_DOWNLOAD="${PHOTO_FORCE_DOWNLOAD:-false}"
PHOTO_SOURCE="${PHOTO_SOURCE:-parquet}" # parquet | mvt
PHOTO_MVT_ZOOM="${PHOTO_MVT_ZOOM:-13}"
PHOTO_MVT_URL_TEMPLATE="${PHOTO_MVT_URL_TEMPLATE:-https://explore.panoramax.fr/api/map/{z}/{x}/{y}.mvt}"
H3_RES="${H3_RES:-12}"
PHOTO_MVT_FLIPY="${PHOTO_MVT_FLIPY:-true}"
FORCE_DOWNLOAD="${OSM_FORCE_DOWNLOAD:-false}"
case "${REGION}" in
  centre|centre-val-de-loire)
    PBF_URL="https://download.geofabrik.de/europe/france/centre-latest.osm.pbf"
    REGION_SLUG="centre"
    ;;
  france)
    PBF_URL="https://download.geofabrik.de/europe/france-latest.osm.pbf"
    REGION_SLUG="france"
    ;;
  *)
    echo "Unsupported OSM_REGION '${REGION}'. Use 'centre' or 'france'." >&2
    exit 1
    ;;
esac

DATA_DIR="${DATA_DIR:-/data}"
GRAPH_DIR="${GRAPH_DIR:-${DATA_DIR}/graph-cache}"
PBF_FILE="${PBF_FILE:-${DATA_DIR}/${REGION_SLUG}.osm.pbf}"
CUSTOM_MODELS_DIR="${CUSTOM_MODELS_DIR:-/graphhopper/custom_models}"
PARQUET_PATH="${PARQUET_PATH:-${DATA_DIR}/panoramax.parquet}"

mkdir -p "${DATA_DIR}" "${GRAPH_DIR}" "${CUSTOM_MODELS_DIR}"

PBF_SIZE=0
if [ -f "${PBF_FILE}" ]; then
  PBF_SIZE=$(stat -c%s "${PBF_FILE}" || echo 0)
fi

if [ "${FORCE_DOWNLOAD}" = "true" ]; then
  echo "OSM_FORCE_DOWNLOAD=true -> redownloading extract for ${REGION_SLUG}"
  rm -f "${PBF_FILE}"
  PBF_SIZE=0
fi

if [ "${PBF_SIZE}" -lt 100000 ]; then
  echo "Downloading OSM extract for ${REGION_SLUG}..."
  curl -fL --retry 3 --retry-delay 2 --retry-connrefused --progress-bar "${PBF_URL}" -o "${PBF_FILE}"
else
  echo "Reusing existing PBF: ${PBF_FILE}"
fi

# quick sanity check on size after download
PBF_SIZE=$(stat -c%s "${PBF_FILE}" || echo 0)
if [ "${PBF_SIZE}" -lt 100000 ]; then
  echo "Downloaded PBF looks too small (${PBF_SIZE} bytes). Delete it and try again." >&2
  exit 1
fi

# Build Panoramax coverage if requested
if [ "${PHOTO_WEIGHT}" != "0" ] || [ "${PHOTO_MODE}" != "any" ] || [ -n "${PHOTO_PROVIDERS}" ]; then
  if [ ! -f "${PHOTO_COVERAGE_FILE}" ] || [ "${PHOTO_FORCE_DOWNLOAD}" = "true" ]; then
    echo "Generating Panoramax coverage grid..."
    PY_ARGS=(
      --region "${REGION_SLUG}"
      --output-geojson "${PHOTO_COVERAGE_FILE}"
    )
    if [ "${PHOTO_SOURCE}" = "mvt" ]; then
      PY_ARGS+=(--source mvt --mvt-zoom "${PHOTO_MVT_ZOOM}" --mvt-url-template "${PHOTO_MVT_URL_TEMPLATE}")
      if [ "${PHOTO_MVT_FLIPY}" = "true" ]; then
        PY_ARGS+=(--mvt-flip-y)
      fi
    else
      PY_ARGS+=(--source parquet --parquet-path "${PARQUET_PATH}")
      if [ "${PHOTO_FORCE_DOWNLOAD}" = "true" ]; then
        PY_ARGS+=(--force-download)
      fi
    fi
    PY_ARGS+=(--h3-res "${H3_RES:-12}")
    python3 /usr/local/bin/panoramax_preprocess.py "${PY_ARGS[@]}"
  else
    echo "Reusing existing Panoramax coverage: ${PHOTO_COVERAGE_FILE}"
  fi
  if [ ! -f "${PHOTO_COVERAGE_FILE}" ]; then
    echo "Coverage file missing after preprocess, continuing without photo avoidance." >&2
  fi
fi

cd /graphhopper
JAVA_OPTS="${JAVA_OPTS:--Xmx4g}"

exec java ${JAVA_OPTS} \
  -Ddw.graphhopper.datareader.file="${PBF_FILE}" \
  -Ddw.graphhopper.graph.location="${GRAPH_DIR}" \
  -Ddw.graphhopper.photo_coverage.file="${PHOTO_COVERAGE_FILE}" \
  -Ddw.graphhopper.photo_weight="${PHOTO_WEIGHT}" \
  -Ddw.graphhopper.photo_mode="${PHOTO_MODE}" \
  -Ddw.graphhopper.photo_providers="${PHOTO_PROVIDERS}" \
  -Ddw.graphhopper.custom_models.directory="${CUSTOM_MODELS_DIR}" \
  -jar /graphhopper/web/target/graphhopper-web-*.jar \
  server /graphhopper/config-example.yml
