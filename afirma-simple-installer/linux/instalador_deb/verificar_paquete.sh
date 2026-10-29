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
	local d esperado='libnss3-tools, openjdk-21-jre | default-jre, openssl, ca-certificates'
	d=$(dpkg-deb -f "$DEB" Depends)
	[ "$d" = "$esperado" ] || { echo "Depends='$d'"; return 1; }
	case $d in *headless*) echo "Depends contiene headless"; return 1 ;; esac
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
