#!/bin/bash
# Construye un .deb con un JAR falso y lo pasa por el verificador.
set -euo pipefail

DEB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=lib.sh
. "$DEB/tests/lib.sh"

printf x > "$T/a.jar"

chk_rc construir_ok 0 "$DEB/construir_deb.sh" 1.9.1+fisat0 "$T/out" "$T/a.jar"
debs=("$T"/out/*.deb)
chk nombre_deb "autofirma_1.9.1+fisat0_all.deb" "$(basename "${debs[0]}")"
chk_rc sha256 0 bash -c "cd '$T/out' && sha256sum -c autofirma_1.9.1+fisat0_all.deb.sha256"
chk_rc version_sin_fisat 1 "$DEB/construir_deb.sh" 1.9.1 "$T/out2" "$T/a.jar"
chk_rc verificar 0 "$DEB/verificar_paquete.sh" "$T/out/autofirma_1.9.1+fisat0_all.deb"

fin
