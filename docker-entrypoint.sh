#!/usr/bin/env bash
set -euo pipefail

# Select extract to download based on OSM_REGION
REGION="${OSM_REGION:-centre}"
PHOTO_MODE="${PHOTO_MODE:-any}" # any | only360
PHOTO_PROVIDERS="${PHOTO_PROVIDERS:-panoramax}"
PHOTO_COVERAGE_FILE="${PHOTO_COVERAGE_FILE:-/data/panoramax_coverage.bin}"
H3_RES="${H3_RES:-12}"
FORCE_DOWNLOAD="${OSM_FORCE_DOWNLOAD:-false}"
PARQUET_URL="${PARQUET_URL:-}"
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

# Download Panoramax parquet if a URL is provided and the file is absent or refresh is forced
if [ -n "${PARQUET_URL}" ]; then
  if [ "${FORCE_DOWNLOAD}" = "true" ] || [ ! -f "${PARQUET_PATH}" ]; then
    echo "Downloading Panoramax parquet from ${PARQUET_URL}..."
    curl -fL --retry 3 --retry-delay 5 --retry-connrefused --progress-bar \
      "${PARQUET_URL}" -o "${PARQUET_PATH}" 2>&1 | tr '\r' '\n'
  else
    echo "Reusing existing parquet: ${PARQUET_PATH}"
  fi
fi

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
  curl -fL --retry 5 --retry-delay 10 --retry-connrefused -C - --progress-bar "${PBF_URL}" -o "${PBF_FILE}" 2>&1 | tr '\r' '\n'
else
  echo "Reusing existing PBF: ${PBF_FILE}"
fi

# quick sanity check on size after download
PBF_SIZE=$(stat -c%s "${PBF_FILE}" || echo 0)
if [ "${PBF_SIZE}" -lt 100000 ]; then
  echo "Downloaded PBF looks too small (${PBF_SIZE} bytes). Delete it and try again." >&2
  exit 1
fi

# Fetch parquet Last-Modified date via HEAD if not already known
if [ -n "${PARQUET_URL}" ] && [ ! -f "${PARQUET_PATH}.lastmod" ]; then
  lastmod=$(curl -sI "${PARQUET_URL}" | grep -i "^last-modified:" | cut -d' ' -f2- | tr -d '\r\n')
  if [ -n "$lastmod" ]; then
    date -d "$lastmod" "+%Y-%m-%d" > "${PARQUET_PATH}.lastmod" 2>/dev/null || true
  fi
fi

# Build Panoramax coverage if requested
if [ "${PHOTO_MODE}" != "any" ] || [ -n "${PHOTO_PROVIDERS}" ]; then
  if [ ! -f "${PHOTO_COVERAGE_FILE}" ]; then
    echo "Generating Panoramax coverage grid..."
    PREPROCESS_EXTRA_ARGS=""
    if [ -f "${PARQUET_PATH}.lastmod" ]; then
      PREPROCESS_EXTRA_ARGS="--date $(cat "${PARQUET_PATH}.lastmod")"
    fi
    python3 /usr/local/bin/panoramax_preprocess.py \
      --region "${REGION_SLUG}" \
      --output "${PHOTO_COVERAGE_FILE}" \
      --parquet-path "${PARQUET_PATH}" \
      --h3-res "${H3_RES}" \
      ${PREPROCESS_EXTRA_ARGS}
  else
    echo "Reusing existing Panoramax coverage: ${PHOTO_COVERAGE_FILE}"
  fi
fi

cd /graphhopper
JAVA_OPTS="${JAVA_OPTS:--Xmx4g}"

# Server-side Matomo analytics. Passed as -Ddw. overrides rather than written into config-example.yml,
# because that file is tracked in git and the auth token is a secret. See docs/web/analytics.md
MATOMO_OPTS=()
if [ "${MATOMO_ENABLED:-false}" = "true" ]; then
  MATOMO_OPTS+=(-Ddw.matomo.enabled=true)
  for pair in \
    "url:${MATOMO_URL:-}" \
    "site_id:${MATOMO_SITE_ID:-}" \
    "token_auth:${MATOMO_TOKEN_AUTH:-}" \
    "site_url:${MATOMO_SITE_URL:-}" \
    "visitor_id_salt:${MATOMO_VISITOR_ID_SALT:-}" \
    "trust_forwarded_for:${MATOMO_TRUST_FORWARDED_FOR:-}" \
    "anonymize_ip:${MATOMO_ANONYMIZE_IP:-}" \
    "track_api_requests:${MATOMO_TRACK_API_REQUESTS:-}" \
    "api_sample_rate:${MATOMO_API_SAMPLE_RATE:-}"; do
    key="${pair%%:*}"
    value="${pair#*:}"
    if [ -n "${value}" ]; then
      MATOMO_OPTS+=(-Ddw.matomo."${key}"="${value}")
    fi
  done
  echo "Matomo tracking enabled -> ${MATOMO_URL:-<no url set>}"
fi

exec java ${JAVA_OPTS} \
  "${MATOMO_OPTS[@]}" \
  -Ddw.graphhopper.datareader.file="${PBF_FILE}" \
  -Ddw.graphhopper.graph.location="${GRAPH_DIR}" \
  -Ddw.graphhopper.photo_coverage.file="${PHOTO_COVERAGE_FILE}" \
  -Ddw.graphhopper.photo_mode="${PHOTO_MODE}" \
  -Ddw.graphhopper.photo_providers="${PHOTO_PROVIDERS}" \
  -Ddw.graphhopper.custom_models.directory="${CUSTOM_MODELS_DIR}" \
  -jar /graphhopper/web/target/graphhopper-web-*.jar \
  server /graphhopper/config-example.yml
