#!/bin/bash
# chk_retirar: fisat-retirar.sh se llama solo en prerm dentro de remove|deconfigure; chk_usuario lo exige en 755.
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

V=("$DEB/verificar_paquete.sh" --solo retirar)
chk_rc paquete_real 0 "${V[@]}" "$T/out/$NOMBRE"

mutar llamada_fuera_del_case prerm '/^case /i /usr/lib/Autofirma/fisat-retirar.sh || aviso "x"'
chk_rc llamada_fuera_del_case 1 "${V[@]}" "$T/llamada_fuera_del_case/$NOMBRE"

mutar llamada_en_upgrade prerm 's/^\tremove|deconfigure)/\tremove|deconfigure|upgrade)/'
chk_rc llamada_en_upgrade 1 "${V[@]}" "$T/llamada_en_upgrade/$NOMBRE"

mutar sin_llamada prerm '/fisat-retirar\.sh/d'
chk_rc sin_llamada 1 "${V[@]}" "$T/sin_llamada/$NOMBRE"

mutar postrm_llama postrm '/^exit 0/i /usr/lib/Autofirma/fisat-retirar.sh'
chk_rc postrm_llama 1 "${V[@]}" "$T/postrm_llama/$NOMBRE"

# Paquete sin fisat-retirar.sh: chk_usuario falla
r=$T/sin_script/raiz
mkdir -p "$r"
dpkg-deb -R "$T/out/$NOMBRE" "$r"
rm "$r/usr/lib/Autofirma/fisat-retirar.sh"
dpkg-deb --root-owner-group -b "$r" "$T/sin_script/$NOMBRE" >/dev/null 2>&1
chk_rc usuario_sin_script 1 "$DEB/verificar_paquete.sh" --solo usuario "$T/sin_script/$NOMBRE"
chk_rc usuario_paquete_real 0 "$DEB/verificar_paquete.sh" --solo usuario "$T/out/$NOMBRE"

fin
