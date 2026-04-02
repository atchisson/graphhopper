#!/usr/bin/env bash
set -euo pipefail

# Select extract to download based on OSM_REGION
REGION="${OSM_REGION:-centre}"
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

cd /graphhopper
JAVA_OPTS="${JAVA_OPTS:--Xmx4g}"

exec java ${JAVA_OPTS} \
  -Ddw.graphhopper.datareader.file="${PBF_FILE}" \
  -Ddw.graphhopper.graph.location="${GRAPH_DIR}" \
  -Ddw.graphhopper.custom_models.directory="${CUSTOM_MODELS_DIR}" \
  -jar /graphhopper/web/target/graphhopper-web-*.jar \
  server /graphhopper/config-example.yml
