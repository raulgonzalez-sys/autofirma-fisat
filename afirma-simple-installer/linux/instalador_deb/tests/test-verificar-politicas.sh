#!/bin/bash
# chk_politicas: limpieza de policies.json en prerm (remove|deconfigure) y nunca en postrm.
set -euo pipefail

DEB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=lib.sh
. "$DEB/tests/lib.sh"

NOMBRE=autofirma_1.9.1+fisat0_all.deb
printf x > "$T/a.jar"
printf x > "$T/b.jar"
"$DEB/construir_deb.sh" 1.9.1+fisat0 "$T/out" "$T/a.jar" "$T/b.jar" >/dev/null 2>&1

# mutar <caso> <script> <programa sed>: copia del .deb con un maintainer script modificado
mutar() {
	local r=$T/$1/raiz
	mkdir -p "$r"
	dpkg-deb -R "$T/out/$NOMBRE" "$r"
	sed -i "$3" "$r/DEBIAN/$2"
	dpkg-deb --root-owner-group -b "$r" "$T/$1/$NOMBRE" >/dev/null 2>&1
}

V=("$DEB/verificar_paquete.sh" --solo politicas)
chk_rc paquete_real 0 "${V[@]}" "$T/out/$NOMBRE"

mutar postrm_llama postrm '/^exit 0/i /usr/lib/Autofirma/fisat-politicas.py remove /etc/firefox/policies/policies.json autofirma-csp-fix@local --delete-if-empty'
chk_rc postrm_llama 1 "${V[@]}" "$T/postrm_llama/$NOMBRE"

mutar prerm_fuera_del_case prerm '/^case /i /usr/lib/Autofirma/fisat-politicas.py remove /etc/firefox/policies/policies.json autofirma-csp-fix@local --delete-if-empty'
chk_rc prerm_fuera_del_case 1 "${V[@]}" "$T/prerm_fuera_del_case/$NOMBRE"

mutar prerm_en_upgrade prerm 's/^\tremove|deconfigure)/\tremove|deconfigure|upgrade)/'
chk_rc prerm_en_upgrade 1 "${V[@]}" "$T/prerm_en_upgrade/$NOMBRE"

mutar prerm_sin_delete prerm 's/ --delete-if-empty//'
chk_rc prerm_sin_delete 1 "${V[@]}" "$T/prerm_sin_delete/$NOMBRE"

mutar prerm_sin_llamada prerm '/fisat-politicas\.py remove/d'
chk_rc prerm_sin_llamada 1 "${V[@]}" "$T/prerm_sin_llamada/$NOMBRE"

fin
