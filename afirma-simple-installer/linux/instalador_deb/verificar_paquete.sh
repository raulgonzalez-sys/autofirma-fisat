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
	local d esperado='libnss3-tools, openjdk-21-jre | default-jre, openssl, ca-certificates, python3'
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

chk_jars() {
	local l falta=""
	l=$(dpkg-deb --fsys-tarfile "$DEB" | tar -tf -)
	for j in autofirma.jar autofirmaConfigurador.jar; do
		grep -Fxq "./usr/lib/Autofirma/$j" <<<"$l" || falta="$falta $j"
	done
	[ -z "$falta" ] || { echo "faltan en ./usr/lib/Autofirma/:$falta"; return 1; }
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
				   /usr/bin/*|/usr/lib/Autofirma/*.sh|/usr/lib/Autofirma/*.py) esperado=-rwxr-xr-x ;;
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

# Destinos de ln en postinst frente a los AutoFirma.js que borra prerm
chk_enlaces_prefs() {
	local enlazados borrados linea l
	enlazados=$(
		while IFS= read -r linea; do
			linea=${linea%%||*}
			read -ra w <<<"$linea"
			echo "${w[${#w[@]}-1]}"
		done < <(grep -E '^[[:space:]]*ln[[:space:]]' "$X/DEBIAN/postinst" || true) | sort -u
	)
	borrados=$(grep -E '^[[:space:]]*rm[[:space:]]' "$X/DEBIAN/prerm" | grep -oE '/etc/[^ ;"]+/AutoFirma\.js' | sort -u || true)
	[ -n "$enlazados" ] || { echo "postinst no enlaza nada con ln"; return 1; }
	[ "$enlazados" = "$borrados" ] || {
		echo "postinst enlaza: $(echo "$enlazados" | tr '\n' ' ')| prerm borra: $(echo "$borrados" | tr '\n' ' ')"
		return 1
	}
	l=$(rutas)
	grep -Fxq "./etc/firefox/pref/AutoFirma.js" <<<"$l" || { echo "falta ./etc/firefox/pref/AutoFirma.js en el paquete"; return 1; }
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

# fisat-politicas.py ejecutable; postinst lo llama con add; prerm con remove --delete-if-empty solo en remove|deconfigure
# (postrm no puede: dpkg ya ha borrado el ayudante) y postrm no lo llama
chk_politicas() {
	local perm fuera dentro
	perm=$(listado | awk '$3 == "./usr/lib/Autofirma/fisat-politicas.py" {print $1}')
	[ "$perm" = "-rwxr-xr-x" ] || { echo "fisat-politicas.py ausente o con permisos '$perm' (esperado -rwxr-xr-x)"; return 1; }
	grep -Eq 'fisat-politicas\.py[[:space:]]+add[[:space:]]' "$X/DEBIAN/postinst" || { echo "postinst no llama a fisat-politicas.py add"; return 1; }
	! grep -q 'fisat-politicas\.py' "$X/DEBIAN/postrm" || { echo "postrm llama a fisat-politicas.py: dpkg ya lo ha borrado en ese momento, debe ir en prerm"; return 1; }
	# prerm: llamadas a remove fuera y dentro de la rama remove|deconfigure del case
	fuera=$(awk '/^[[:space:]]*remove\|deconfigure\)/ {d=1} d==0 && /fisat-politicas\.py[[:space:]]+remove/ {print} /;;/ {d=0}' "$X/DEBIAN/prerm")
	dentro=$(awk '/^[[:space:]]*remove\|deconfigure\)/ {d=1} d==1 && /fisat-politicas\.py[[:space:]]+remove/ {print} /;;/ {d=0}' "$X/DEBIAN/prerm")
	[ -n "$dentro" ] || { echo "prerm no llama a fisat-politicas.py remove dentro de remove|deconfigure"; return 1; }
	[ -z "$fuera" ] || { echo "prerm llama a remove fuera de remove|deconfigure: $fuera"; return 1; }
	grep -q -- '--delete-if-empty' <<<"$dentro" || { echo "prerm llama a remove sin --delete-if-empty: $dentro"; return 1; }
}

# El lanzador del paquete arranca con exec java y pasa antes por fisat-usuario.sh
chk_lanzador() {
	local f=$X/usr/bin/autofirma
	[ -f "$f" ] || { echo "falta ./usr/bin/autofirma"; return 1; }
	grep -Eq '^[[:space:]]*exec java' "$f" || { echo "el lanzador no contiene 'exec java'"; return 1; }
	grep -Fq 'fisat-usuario.sh' "$f" || { echo "el lanzador no menciona fisat-usuario.sh"; return 1; }
}

# Autostart de sesión: presente en ./etc/xdg/autostart/ y válido para desktop-file-validate
chk_autostart() {
	local f=autofirma-fisat-usuario.desktop salida
	salida=$(rutas)
	grep -Fxq "./etc/xdg/autostart/$f" <<<"$salida" || { echo "falta ./etc/xdg/autostart/$f"; return 1; }
	salida=$(desktop-file-validate "$X/etc/xdg/autostart/$f" 2>&1) || { echo "$salida"; return 1; }
	[ -z "$salida" ] || { echo "$salida"; return 1; }
}

# Paso por usuario y ayudante de políticas con modo 755
chk_usuario() {
	local f perm malos=""
	for f in fisat-usuario.sh fisat-politicas.py; do
		perm=$(listado | awk -v n="./usr/lib/Autofirma/$f" '$3 == n {print $1}')
		[ "$perm" = "-rwxr-xr-x" ] || malos="$malos $f('$perm')"
	done
	[ -z "$malos" ] || { echo "ausentes o sin modo 755 en ./usr/lib/Autofirma/:$malos"; return 1; }
}

chk_maintainer_shellcheck() {
	local s salida
	for s in preinst postinst prerm postrm; do
		salida=$(shellcheck -s bash -x "$X/DEBIAN/$s" 2>&1) || { echo "$s: $salida" | head -n 20; return 1; }
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
