#!/bin/bash
# chk_politica_chrome y chk_aviso_chrome: política de Chrome y aviso de reinicio en postinst.
set -euo pipefail

DEB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=lib.sh
. "$DEB/tests/lib.sh"

NOMBRE=autofirma_1.9.1+fisat0_all.deb
printf x > "$T/a.jar"
printf x > "$T/b.jar"
"$DEB/construir_deb.sh" 1.9.1+fisat0 "$T/out" "$T/a.jar" "$T/b.jar" >/dev/null 2>&1

# mutar <caso> <orden...>: copia del .deb con la orden ejecutada en su raiz (rutas relativas)
mutar() {
	local caso=$1 r=$T/$1/raiz
	shift
	mkdir -p "$r"
	dpkg-deb -R "$T/out/$NOMBRE" "$r"
	(cd "$r" && "$@")
	dpkg-deb --root-owner-group -b "$r" "$T/$caso/$NOMBRE" >/dev/null 2>&1
}

P=etc/opt/chrome/policies/managed/autofirma-fisat.json
POLITICA=("$DEB/verificar_paquete.sh" --solo politica_chrome)
AVISO=("$DEB/verificar_paquete.sh" --solo aviso_chrome)

chk_rc politica_real 0 "${POLITICA[@]}" "$T/out/$NOMBRE"

mutar origenes_vacios sed -i 's/\["\*"\]/[]/' "$P"
chk_rc origenes_vacios 1 "${POLITICA[@]}" "$T/origenes_vacios/$NOMBRE"

mutar sin_politica rm "$P"
chk_rc sin_politica 1 "${POLITICA[@]}" "$T/sin_politica/$NOMBRE"

mutar extra cp "$P" etc/opt/otro.json
chk_rc extra 1 "${POLITICA[@]}" "$T/extra/$NOMBRE"

chk_rc aviso_real 0 "${AVISO[@]}" "$T/out/$NOMBRE"

mutar sin_aviso sed -i '/reinicie Chrome/d' DEBIAN/postinst
chk_rc sin_aviso 1 "${AVISO[@]}" "$T/sin_aviso/$NOMBRE"

fin
