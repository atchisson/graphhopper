#!/usr/bin/env bash
# check_and_apply.sh
# Vérifie si le NAS contient une version plus récente du graph GraphHopper,
# et si oui, applique la mise à jour et redémarre le container.
#
# Utilisation :
#   bash check_and_apply.sh
#
# Planification (cron — vérifier toutes les heures) :
#   0 * * * * /chemin/vers/scripts/check_and_apply.sh >> /var/log/gh_apply.log 2>&1
#
# Prérequis :
#   - NAS monté sur $NAS_MOUNT (NFS, SMB/CIFS, etc.)
#   - docker compose disponible
#   - rsync installé (sudo apt install rsync)

set -euo pipefail

# ============================================================
# CONFIGURATION
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
DATA_DIR="$REPO_ROOT/data"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
APPLIED_VERSION="$DATA_DIR/.applied_version.json"

NAS_MOUNT="/mnt/nas/graphhopper"   # <-- À ADAPTER (point de montage NAS)

# ============================================================
# LOGGING
# ============================================================
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# ============================================================
# VÉRIFICATION DU NAS
# ============================================================
check_nas_mounted() {
    if [ ! -d "$NAS_MOUNT" ] || [ ! -f "$NAS_MOUNT/.version.json" ]; then
        log "NAS non accessible ou pas encore de version disponible. Abandon."
        exit 0
    fi
}

# ============================================================
# COMPARAISON DES VERSIONS
# ============================================================
parse_built_at() {
    grep -o '"built_at"[^"]*"[^"]*"' "$1" | sed 's/.*"built_at"[^"]*"\([^"]*\)".*/\1/'
}

update_needed() {
    local nas_built_at
    nas_built_at=$(parse_built_at "$NAS_MOUNT/.version.json")

    if [ ! -f "$APPLIED_VERSION" ]; then
        log "Aucune version locale — première application nécessaire."
        return 0
    fi

    local local_built_at
    local_built_at=$(parse_built_at "$APPLIED_VERSION" 2>/dev/null || echo "")

    if [ "$nas_built_at" != "$local_built_at" ]; then
        log "Nouvelle version NAS : $nas_built_at (locale : $local_built_at)"
        return 0
    fi

    log "Déjà à jour ($nas_built_at). Rien à faire."
    return 1
}

# ============================================================
# APPLICATION DE LA MISE À JOUR
# ============================================================
apply_update() {
    log "=== Application de la mise à jour ==="

    log "Arrêt du container..."
    docker compose -f "$COMPOSE_FILE" stop

    log "Synchronisation graph-cache depuis le NAS..."
    mkdir -p "$DATA_DIR/graph-cache"
    rsync -a --delete --info=progress2 \
        "$NAS_MOUNT/graph-cache/" \
        "$DATA_DIR/graph-cache/"

    log "Copie des fichiers panoramax_coverage..."
    cp "$NAS_MOUNT"/panoramax_coverage.* "$DATA_DIR/"

    log "Enregistrement de la version appliquée..."
    cp "$NAS_MOUNT/.version.json" "$APPLIED_VERSION"

    log "Redémarrage de GraphHopper..."
    docker compose -f "$COMPOSE_FILE" up -d

    log "=== Mise à jour appliquée avec succès ==="
}

# ============================================================
# MAIN
# ============================================================
log "=== Vérification de mise à jour GraphHopper ==="
mkdir -p "$DATA_DIR"
check_nas_mounted

if update_needed; then
    apply_update
fi
