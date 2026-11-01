#!/bin/bash
# Verifica un .deb: verificar_paquete.sh [--solo <nombre>] <deb>
# Cada comprobación es una función chk_<nombre> que devuelve 0 o imprime el detalle y devuelve 1.
set -euo pipefail

AQUI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SOLO=""
if [ "${1:-}" = "--solo" ]; then
	SOLO=${2:-}
	[ -n "$SOLO" ] || { echo "Falta el nombre tras --solo" >&2; exit 2; }
	shift 2
fi
[ "$#" -eq 1 ] || { echo "Uso: $0 [--solo <nombre>] <deb>" >&2; exit 2; }
DEB=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
[ -f "$DEB" ] || { echo "No existe $DEB" >&2; exit 2; }

# X: contenido extraído del paquete (datos y DEBIAN)
X=$(mktemp -d)
trap 'rm -rf "$X"' EXIT
dpkg-deb -x "$DEB" "$X"
dpkg-deb -e "$DEB" "$X/DEBIAN"

# Lista de datos con permisos: "perm propietario nombre"
listado() { dpkg-deb --fsys-tarfile "$DEB" | tar -tvf - | awk '{print $1, $2, $6}'; }

chk_control_depends() {
	local d esperado='libnss3-tools, openjdk-21-jre | default-jre, openssl, ca-certificates, pkexec | policykit-1'
	d=$(dpkg-deb -f "$DEB" Depends)
	[ "$d" = "$esperado" ] || { echo "Depends='$d'"; return 1; }
	case $d in *headless*) echo "Depends contiene headless"; return 1 ;; esac
}

chk_control_recommends() {
	local r esperado='kdialog | zenity'
	r=$(dpkg-deb -f "$DEB" Recommends)
	[ "$r" = "$esperado" ] || { echo "Recommends='$r'"; return 1; }
}

chk_control_version() {
	local v nombre esperado
	v=$(dpkg-deb -f "$DEB" Version)
	case $v in *+fisat*) ;; *) echo "Version='$v' sin +fisat"; return 1 ;; esac
	nombre=$(basename "$DEB")
	if [[ $nombre =~ ^autofirma_(.+)_all\.deb$ ]]; then
		esperado=${BASH_REMATCH[1]}
		case $v in "$esperado"*) ;; *) echo "Version='$v' no empieza por '$esperado'"; return 1 ;; esac
	fi
}

chk_sin_recommends_17() {
	if dpkg-deb -f "$DEB" | grep -q 'openjdk-17'; then
		echo "el control menciona openjdk-17"
		return 1
	fi
}

# Solo el JAR de la aplicación: el configurador ya no se usa y no se distribuye
chk_jars() {
	local l
	l=$(dpkg-deb --fsys-tarfile "$DEB" | tar -tf -)
	grep -Fxq "./usr/lib/Autofirma/autofirma.jar" <<<"$l" || { echo "falta autofirma.jar en ./usr/lib/Autofirma/"; return 1; }
	! grep -Fxq "./usr/lib/Autofirma/autofirmaConfigurador.jar" <<<"$l" || { echo "autofirmaConfigurador.jar ya no debe distribuirse"; return 1; }
}

chk_permisos() {
	local malos="" perm prop nombre esperado
	while read -r perm prop nombre; do
		case $perm in l*) continue ;; esac
		nombre=${nombre#.}
		case $perm in
			d*) esperado=drwxr-xr-x ;;
			*) esperado=-rw-r--r--
			   case $nombre in
				   /usr/bin/*) esperado=-rwxr-xr-x ;;
			   esac ;;
		esac
		[ "$perm" = "$esperado" ] || malos="$malos $nombre($perm!=$esperado)"
		[ "$prop" = "root/root" ] || malos="$malos $nombre(propietario $prop)"
	done < <(listado)
	# Maintainer scripts
	while read -r perm prop nombre; do
		case $perm in d*) continue ;; esac
		nombre=${nombre#./}
		case $nombre in
			preinst|postinst|prerm|postrm) esperado=-rwxr-xr-x ;;
			*) esperado=-rw-r--r-- ;;
		esac
		[ "$perm" = "$esperado" ] || malos="$malos DEBIAN/$nombre($perm!=$esperado)"
	done < <(dpkg-deb --ctrl-tarfile "$DEB" | tar -tvf - | awk '{print $1, $2, $6}')
	[ -z "$malos" ] || { echo "permisos incorrectos:$malos"; return 1; }
}

chk_lintian() {
	local salida
	salida=$(lintian --fail-on error --suppress-tags-from-file "$AQUI/lintian-suprimir.txt" "$DEB" 2>&1) || {
		echo "$salida" | head -n 20
		return 1
	}
}

# Lista de rutas del paquete (formato ./ruta)
rutas() { dpkg-deb --fsys-tarfile "$DEB" | tar -tf -; }

chk_icono() {
	local l icono_desktop icono_meta falta=""
	l=$(rutas)
	grep -Fxq "./usr/lib/Autofirma/Autofirma.png" <<<"$l" || falta="$falta ./usr/lib/Autofirma/Autofirma.png"
	icono_desktop=$(sed -n 's/^Icon=//p' "$X/usr/share/applications/afirma.desktop" 2>/dev/null | head -n 1)
	icono_meta=$(sed -n 's|.*<icon[^>]*>\(.*\)</icon>.*|\1|p' "$X/usr/share/metainfo/es.gob.afirma.metainfo.xml" 2>/dev/null | head -n 1)
	[ -n "$icono_desktop" ] || falta="$falta Icon=(desktop)"
	[ -n "$icono_meta" ] || falta="$falta icon(metainfo)"
	[ -z "$icono_desktop" ] || grep -Fxq ".$icono_desktop" <<<"$l" || falta="$falta $icono_desktop"
	[ -z "$icono_meta" ] || grep -Fxq ".$icono_meta" <<<"$l" || falta="$falta $icono_meta"
	[ -z "$falta" ] || { echo "faltan en el paquete:$falta"; return 1; }
}

chk_sin_mayusculas() {
	local malas
	malas=$(rutas | grep -F '/AutoFirma/' || true)
	[ -z "$malas" ] || { echo "rutas con /AutoFirma/: $(echo "$malas" | tr '\n' ' ')"; return 1; }
}

chk_sin_pkill() {
	local hallazgos
	hallazgos=$(grep -nE 'pkill|killall' "$X"/DEBIAN/* || true)
	[ -z "$hallazgos" ] || { echo "$hallazgos"; return 1; }
}

# AutoFirma.js de Firefox: el paquete lo trae y restaurar --sistema enlaza el de firefox-esr; prerm borra ese enlace
chk_prefs_firefox() {
	grep -Fxq "./etc/firefox/pref/AutoFirma.js" <(rutas) || { echo "falta ./etc/firefox/pref/AutoFirma.js en el paquete"; return 1; }
	! grep -Eq '^[[:space:]]*ln[[:space:]]' "$X/DEBIAN/postinst" || { echo "postinst enlaza con ln: lo hace restaurar --sistema"; return 1; }
	! grep -q icecat "$X"/DEBIAN/* || { echo "los maintainer scripts mencionan icecat"; return 1; }
	grep -Eq '^[[:space:]]*rm[[:space:]].*/etc/firefox-esr/pref/AutoFirma\.js' "$X/DEBIAN/prerm" || { echo "prerm no borra /etc/firefox-esr/pref/AutoFirma.js"; return 1; }
}

# Solo los errores de desktop-file-validate; los avisos no cuentan
chk_desktop() {
	local salida errores
	salida=$(desktop-file-validate "$X/usr/share/applications/afirma.desktop" 2>&1 || true)
	errores=$(grep ': error:' <<<"$salida" || true)
	[ -z "$errores" ] || { echo "$errores"; return 1; }
}

chk_xpi() {
	local f=./usr/share/Autofirma/autofirma-csp-fix.xpi esperado=b66e27aab78bcd6be72dbedca10ddc370309f47f19dce2a753a8b7bfe08048a9 obtenido
	[ -f "$X/$f" ] || { echo "falta $f"; return 1; }
	obtenido=$(sha256sum < "$X/$f" | cut -d' ' -f1)
	[ "$obtenido" = "$esperado" ] || { echo "sha256 del xpi: $obtenido (esperado $esperado)"; return 1; }
}

# postinst llama a restaurar --sistema (con || aviso); prerm a restaurar --quitar-politicas solo en remove|deconfigure
# (postrm no puede: dpkg ya ha borrado el lanzador); ningún maintainer script usa ya los scripts por usuario ni el configurador
chk_restaurar() {
	local fuera dentro obsoletos
	grep -Eq '^/usr/bin/autofirma restaurar --sistema \|\| aviso ' "$X/DEBIAN/postinst" || { echo "postinst no llama a '/usr/bin/autofirma restaurar --sistema || aviso ...'"; return 1; }
	! grep -q 'restaurar' "$X/DEBIAN/postrm" || { echo "postrm llama a restaurar: dpkg ya ha borrado el lanzador en ese momento, debe ir en prerm"; return 1; }
	fuera=$(awk '/^[[:space:]]*remove\|deconfigure\)/ {d=1} d==0 && /restaurar[[:space:]]+--quitar-politicas/ {print} /;;/ {d=0}' "$X/DEBIAN/prerm")
	dentro=$(awk '/^[[:space:]]*remove\|deconfigure\)/ {d=1} d==1 && /^[[:space:]]*\/usr\/bin\/autofirma restaurar --quitar-politicas \|\| aviso / {print} /;;/ {d=0}' "$X/DEBIAN/prerm")
	[ -n "$dentro" ] || { echo "prerm no llama a '/usr/bin/autofirma restaurar --quitar-politicas || aviso ...' dentro de remove|deconfigure"; return 1; }
	[ -z "$fuera" ] || { echo "prerm llama a restaurar --quitar-politicas fuera de remove|deconfigure: $fuera"; return 1; }
	# El autostart retirado solo se nombra para quitar su conffile (chk_rm_conffile)
	obsoletos=$(grep -nE 'fisat-usuario|fisat-retirar|fisat-politicas|script\.sh|uninstall\.sh|autofirmaConfigurador' "$X"/DEBIAN/* \
		| grep -vF 'autofirma-fisat-usuario.desktop' || true)
	[ -z "$obsoletos" ] || { echo "los maintainer scripts usan piezas retiradas: $obsoletos"; return 1; }
}

# prerm borra autofirma.pfx, la CA y su copia en el almacén del sistema solo en remove|deconfigure:
# al actualizar se conservan y restaurar --sistema no los regenera
chk_prerm_ca() {
	local re='autofirma\.pfx|Autofirma_ROOT\.(cer|crt)|ca-certificates/Autofirma|update-ca-certificates' fuera f
	fuera=$(awk -v re="$re" '/^[[:space:]]*remove\|deconfigure\)/ {d=1} d==0 && $0 ~ re && !/^[[:space:]]*#/ {print} /;;/ {d=0}' "$X/DEBIAN/prerm")
	[ -z "$fuera" ] || { echo "prerm borra el almacén SSL o la CA fuera de remove|deconfigure: $fuera"; return 1; }
	for f in /usr/lib/Autofirma/autofirma.pfx /usr/lib/Autofirma/Autofirma_ROOT.cer; do
		awk -v f="$f" '/^[[:space:]]*remove\|deconfigure\)/ {d=1} d==1 && /^[[:space:]]*rm[[:space:]]/ && index($0, f) {e=1} /;;/ {d=0} END {exit !e}' "$X/DEBIAN/prerm" \
			|| { echo "prerm no borra $f en remove|deconfigure"; return 1; }
	done
}

# El lanzador arranca con exec java (restaurar con -cp a su clase, sin cargar SimpleAfirma) y no llama a ningún paso por usuario
chk_lanzador() {
	local f=$X/usr/bin/autofirma
	[ -f "$f" ] || { echo "falta ./usr/bin/autofirma"; return 1; }
	grep -Eq '^exec java .*-jar /usr/lib/Autofirma/autofirma\.jar "\$@"$' "$f" || { echo "el lanzador no arranca la aplicación con 'exec java ... -jar'"; return 1; }
	grep -Eq '^[[:space:]]+exec java .*-cp /usr/lib/Autofirma/autofirma\.jar' "$f" || { echo "el lanzador no ejecuta restaurar con 'exec java ... -cp'"; return 1; }
	! grep -Fq 'fisat-usuario' "$f" || { echo "el lanzador menciona fisat-usuario"; return 1; }
	grep -Eq '^[[:space:]]*es\.gob\.afirma\.standalone\.ui\.restoreconfig\.linux\.RestoreCommandLinux "\$@"' "$f" \
		|| { echo "el lanzador no lleva restaurar a RestoreCommandLinux"; return 1; }
}

# Sin scripts por usuario, ni su autostart ni ningún otro fichero bajo ./etc/xdg
chk_sin_scripts_usuario() {
	local l malos
	l=$(rutas)
	malos=$(grep -E 'fisat-usuario|fisat-retirar|fisat-politicas|/etc/xdg/|autofirmaConfigurador' <<<"$l" || true)
	[ -z "$malos" ] || { echo "rutas retiradas presentes: $(echo "$malos" | tr '\n' ' ')"; return 1; }
}

# Política de Chrome: un único fichero bajo ./etc/opt/ con el JSON exacto de AutoLaunchProtocolsFromOrigins
chk_politica_chrome() {
	local f=etc/opt/chrome/policies/managed/autofirma-fisat.json extra
	[ -f "$X/$f" ] || { echo "falta ./$f"; return 1; }
	jq -e '. == {"AutoLaunchProtocolsFromOrigins":[{"protocol":"afirma","allowed_origins":["*"]}]}' "$X/$f" >/dev/null || { echo "contenido de ./$f distinto del esperado"; return 1; }
	extra=$(rutas | grep '^\./etc/opt/' | grep -vxF -e ./etc/opt/ -e ./etc/opt/chrome/ -e ./etc/opt/chrome/policies/ -e ./etc/opt/chrome/policies/managed/ -e "./$f" || true)
	[ -z "$extra" ] || { echo "rutas extra bajo ./etc/opt/: $(echo "$extra" | tr '\n' ' ')"; return 1; }
}

# postinst avisa de reiniciar Chrome antes del último exit 0
chk_aviso_chrome() {
	local n e
	n=$(grep -nF 'reinicie Chrome' "$X/DEBIAN/postinst" | head -n 1 | cut -d: -f1 || true)
	e=$(grep -n '^exit 0' "$X/DEBIAN/postinst" | tail -n 1 | cut -d: -f1 || true)
	[ -n "$n" ] || { echo "postinst no avisa de reiniciar Chrome"; return 1; }
	[ "$n" -lt "${e:-0}" ] || { echo "el aviso de Chrome (línea $n) no va antes de exit 0 (línea ${e:-ninguna})"; return 1; }
}

chk_maintainer_shellcheck() {
	local s salida
	for s in preinst postinst prerm postrm; do
		salida=$(shellcheck -s bash -x "$X/DEBIAN/$s" 2>&1) || { echo "$s: $salida" | head -n 20; return 1; }
	done
}

# preinst, postinst y postrm retiran el conffile del autostart de las versiones anteriores a fisat5, siempre
# con la misma version de corte y sin que un fallo pare el script
chk_rm_conffile() {
	local s re='^[[:space:]]+dpkg-maintscript-helper rm_conffile /etc/xdg/autostart/autofirma-fisat-usuario\.desktop 1\.9\.1\+fisat5~ -- "\$@" \|\| aviso '
	for s in preinst postinst postrm; do
		grep -Eq "$re" "$X/DEBIAN/$s" || { echo "$s no lleva 'dpkg-maintscript-helper rm_conffile <autostart> 1.9.1+fisat5~ -- \"\$@\" || aviso ...'"; return 1; }
		grep -Eq '^if dpkg-maintscript-helper supports rm_conffile' "$X/DEBIAN/$s" || { echo "$s: rm_conffile sin 'if dpkg-maintscript-helper supports rm_conffile'"; return 1; }
	done
}

# Ejecuta las comprobaciones (todas o solo la pedida) y resume
if [ -n "$SOLO" ]; then
	declare -F "chk_$SOLO" >/dev/null || { echo "No existe la comprobación '$SOLO'" >&2; exit 2; }
	nombres=("$SOLO")
else
	mapfile -t nombres < <(declare -F | awk '$3 ~ /^chk_/ {sub(/^chk_/, "", $3); print $3}')
fi

fallos=0
for n in "${nombres[@]}"; do
	if detalle=$("chk_$n" 2>&1); then
		echo "OK $n"
	else
		echo "FALLO $n: $detalle"
		fallos=$((fallos + 1))
	fi
done
[ "$fallos" -eq 0 ] || exit 1
