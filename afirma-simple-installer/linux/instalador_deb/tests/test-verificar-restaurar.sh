#!/bin/bash
# Paquete sin pasos por usuario: Depends, scripts retirados, lanzador y llamadas a restaurar en postinst y prerm.
set -euo pipefail

DEB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=lib.sh
. "$DEB/tests/lib.sh"

NOMBRE=autofirma_1.9.1+fisat0_all.deb
printf x > "$T/a.jar"
"$DEB/construir_deb.sh" 1.9.1+fisat0 "$T/out" "$T/a.jar" >/dev/null 2>&1

# mutar <caso> <orden...>: copia del .deb con la orden ejecutada en su raiz (rutas relativas)
mutar() {
	local caso=$1 r=$T/$1/raiz
	shift
	mkdir -p "$r"
	dpkg-deb -R "$T/out/$NOMBRE" "$r"
	(cd "$r" && "$@")
	dpkg-deb --root-owner-group -b "$r" "$T/$caso/$NOMBRE" >/dev/null 2>&1
}

# sobre <nombre> <caso> <orden...>: verifica solo esa comprobación en el .deb del caso
sobre() { "$DEB/verificar_paquete.sh" --solo "$1" "$T/$2/$NOMBRE"; }

# --- Depends ---
chk_rc depends_real 0 "$DEB/verificar_paquete.sh" --solo control_depends "$T/out/$NOMBRE"
mutar depends_con_python sed -i 's/^Depends:.*/&, python3/' DEBIAN/control
chk_rc depends_con_python 1 sobre control_depends depends_con_python
mutar depends_sin_pkexec sed -i 's/, pkexec | policykit-1//' DEBIAN/control
chk_rc depends_sin_pkexec 1 sobre control_depends depends_sin_pkexec
mutar depends_sin_policykit sed -i 's/pkexec | policykit-1/pkexec/' DEBIAN/control
chk_rc depends_sin_policykit 1 sobre control_depends depends_sin_policykit

# --- Conffile retirado del autostart ---
chk_rc rm_conffile_real 0 "$DEB/verificar_paquete.sh" --solo rm_conffile "$T/out/$NOMBRE"
for s in preinst postinst postrm; do
	mutar "rm_conffile_sin_$s" sed -i '/rm_conffile \/etc/d' "DEBIAN/$s"
	chk_rc "rm_conffile_sin_$s" 1 sobre rm_conffile "rm_conffile_sin_$s"
done
mutar rm_conffile_corte_fisat3 sed -i 's/1\.9\.1+fisat5~/1.9.1+fisat3/' DEBIAN/postinst
chk_rc rm_conffile_corte_fisat3 1 sobre rm_conffile rm_conffile_corte_fisat3
mutar rm_conffile_sin_aviso sed -i 's/ || aviso "no se pudo retirar el autostart antiguo"//' DEBIAN/preinst
chk_rc rm_conffile_sin_aviso 1 sobre rm_conffile rm_conffile_sin_aviso

# --- Ficheros retirados ---
chk_rc sin_scripts_real 0 "$DEB/verificar_paquete.sh" --solo sin_scripts_usuario "$T/out/$NOMBRE"
for f in usr/lib/Autofirma/fisat-usuario.sh usr/lib/Autofirma/fisat-retirar.sh usr/lib/Autofirma/fisat-politicas.py \
	etc/xdg/autostart/autofirma-fisat-usuario.desktop usr/lib/Autofirma/autofirmaConfigurador.jar; do
	caso=con_$(basename "$f" | tr '.-' '__')
	mutar "$caso" bash -c "mkdir -p '$(dirname "$f")' && echo x > '$f'"
	chk_rc "$caso" 1 sobre sin_scripts_usuario "$caso"
done

# --- Lanzador ---
chk_rc lanzador_real 0 "$DEB/verificar_paquete.sh" --solo lanzador "$T/out/$NOMBRE"
mutar lanzador_con_usuario sed -i '/^exec java/i /usr/lib/Autofirma/fisat-usuario.sh --quiet || true' usr/bin/autofirma
chk_rc lanzador_con_usuario 1 sobre lanzador lanzador_con_usuario
mutar lanzador_sin_exec sed -i 's/^exec java/java/' usr/bin/autofirma
chk_rc lanzador_sin_exec 1 sobre lanzador lanzador_sin_exec

# --- postinst y prerm llaman a restaurar ---
chk_rc restaurar_real 0 "$DEB/verificar_paquete.sh" --solo restaurar "$T/out/$NOMBRE"

mutar postinst_sin_restaurar sed -i '/restaurar --sistema/d' DEBIAN/postinst
chk_rc postinst_sin_restaurar 1 sobre restaurar postinst_sin_restaurar

mutar postinst_sin_aviso sed -i 's/restaurar --sistema || aviso.*/restaurar --sistema/' DEBIAN/postinst
chk_rc postinst_sin_aviso 1 sobre restaurar postinst_sin_aviso

mutar postinst_con_configurador sed -i '/^exit 0/i java -jar /usr/lib/Autofirma/autofirmaConfigurador.jar || aviso x' DEBIAN/postinst
chk_rc postinst_con_configurador 1 sobre restaurar postinst_con_configurador

mutar postrm_llama sed -i '/^exit 0/i /usr/bin/autofirma restaurar --quitar-politicas || aviso x' DEBIAN/postrm
chk_rc postrm_llama 1 sobre restaurar postrm_llama

mutar prerm_fuera_del_case sed -i '/^case /i /usr/bin/autofirma restaurar --quitar-politicas || aviso x' DEBIAN/prerm
chk_rc prerm_fuera_del_case 1 sobre restaurar prerm_fuera_del_case

mutar prerm_en_upgrade sed -i 's/^\tremove|deconfigure)/\tremove|deconfigure|upgrade)/' DEBIAN/prerm
chk_rc prerm_en_upgrade 1 sobre restaurar prerm_en_upgrade

mutar prerm_sin_llamada sed -i '/quitar-politicas/d' DEBIAN/prerm
chk_rc prerm_sin_llamada 1 sobre restaurar prerm_sin_llamada

mutar prerm_con_uninstall sed -i '/^exit 0/i /usr/lib/Autofirma/uninstall.sh || aviso x' DEBIAN/prerm
chk_rc prerm_con_uninstall 1 sobre restaurar prerm_con_uninstall

mutar prerm_con_script_usuario sed -i '/^exit 0/i /usr/lib/Autofirma/fisat-retirar.sh || aviso x' DEBIAN/prerm
chk_rc prerm_con_script_usuario 1 sobre restaurar prerm_con_script_usuario

# --- prerm conserva el almacén SSL y la CA al actualizar ---
chk_rc prerm_ca_real 0 "$DEB/verificar_paquete.sh" --solo prerm_ca "$T/out/$NOMBRE"
mutar prerm_ca_pfx_fuera sed -i '/^case /i rm -f /usr/lib/Autofirma/autofirma.pfx || aviso x' DEBIAN/prerm
chk_rc prerm_ca_pfx_fuera 1 sobre prerm_ca prerm_ca_pfx_fuera
mutar prerm_ca_cer_fuera sed -i '/^exit 0/i rm -f /usr/lib/Autofirma/Autofirma_ROOT.cer || aviso x' DEBIAN/prerm
chk_rc prerm_ca_cer_fuera 1 sobre prerm_ca prerm_ca_cer_fuera
mutar prerm_ca_update_fuera sed -i '/^exit 0/i update-ca-certificates || aviso x' DEBIAN/prerm
chk_rc prerm_ca_update_fuera 1 sobre prerm_ca prerm_ca_update_fuera
mutar prerm_ca_en_upgrade sed -i 's/^\tremove|deconfigure)/\tremove|deconfigure|upgrade)/' DEBIAN/prerm
chk_rc prerm_ca_en_upgrade 1 sobre prerm_ca prerm_ca_en_upgrade
mutar prerm_ca_sin_borrar_pfx sed -i '/autofirma\.pfx ||/d' DEBIAN/prerm
chk_rc prerm_ca_sin_borrar_pfx 1 sobre prerm_ca prerm_ca_sin_borrar_pfx

# --- El lanzador lleva restaurar a su clase ---
mutar lanzador_restaurar_con_jar sed -i 's/^\t\tes\.gob\.afirma\.standalone\.ui\.restoreconfig\.linux\.RestoreCommandLinux "\$@"/\t\t"$@"/' usr/bin/autofirma
chk_rc lanzador_restaurar_con_jar 1 sobre lanzador lanzador_restaurar_con_jar

# --- Enlaces de preferencias de Firefox ---
chk_rc prefs_real 0 "$DEB/verificar_paquete.sh" --solo prefs_firefox "$T/out/$NOMBRE"
mutar prefs_sin_fichero rm etc/firefox/pref/AutoFirma.js
chk_rc prefs_sin_fichero 1 sobre prefs_firefox prefs_sin_fichero
mutar prefs_postinst_con_icecat sed -i '/^exit 0/i ln -sfn /etc/firefox/pref/AutoFirma.js /etc/icecat/pref/AutoFirma.js || aviso x' DEBIAN/postinst
chk_rc prefs_postinst_con_icecat 1 sobre prefs_firefox prefs_postinst_con_icecat
mutar prefs_prerm_sin_esr sed -i '/firefox-esr\/pref\/AutoFirma.js/d' DEBIAN/prerm
chk_rc prefs_prerm_sin_esr 1 sobre prefs_firefox prefs_prerm_sin_esr

fin
