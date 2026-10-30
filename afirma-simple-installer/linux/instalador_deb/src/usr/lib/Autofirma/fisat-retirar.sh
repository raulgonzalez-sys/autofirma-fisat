#!/bin/bash
# Retirada por usuario de AutoFirma FISAT: desde prerm (root) ejecuta fisat-usuario.sh --remove como cada usuario.
# Sin set -e: cada fallo se avisa por stderr y se continúa; sale 0 siempre. Cargable con source para probar sus funciones.

USUARIO=${AUTOFIRMA_FISAT_USUARIO:-/usr/lib/Autofirma/fisat-usuario.sh}
HOMES=${AUTOFIRMA_FISAT_HOMES:-/home}

# Mismo criterio de uid que el resto del paquete: usuarios reales, sin nobody
uid_valido() {
	[[ "$1" =~ ^[0-9]+$ ]] && [ "$1" -ge 1000 ] && [ "$1" -ne 65534 ]
}

# leer_passwd: getent passwd (incluye usuarios de red) con timeout; si falla o sale vacío, /etc/passwd
leer_passwd() {
	local salida
	if salida=$(timeout 10 getent passwd 2>/dev/null) && [ -n "$salida" ]; then
		printf '%s\n' "$salida"
	else
		cat /etc/passwd
	fi
}

# candidatos <base>: stdin líneas passwd; stdout "usuario<TAB>home" sin repetir destino real, passwd primero y luego <base>/*
candidatos() {
	local base=${1%/} nombre uid home d real propietario
	local -A vistos=()
	while IFS=: read -r nombre _ uid _ _ home _; do
		uid_valido "$uid" || continue
		[[ "$nombre" == -* || "$nombre$home" == *$'\t'* ]] && continue
		[[ "$home" == /* && "${home,,}" == *home/* && -d "$home" ]] || continue
		real=$(realpath -e -- "$home" 2>/dev/null) || continue
		[ -z "${vistos[$real]:-}" ] || continue
		vistos[$real]=1
		printf '%s\t%s\n' "$nombre" "$home"
	done
	for d in "$base"/*; do
		[ -d "$d" ] || continue
		[ ! -L "$d" ] || continue
		uid=$(stat -c %u -- "$d" 2>/dev/null) || continue
		uid_valido "$uid" || continue
		propietario=$(stat -c %U -- "$d" 2>/dev/null) || continue
		[[ "$propietario" == UNKNOWN || "$propietario" == -* || "$d$propietario" == *$'\t'* ]] && continue
		real=$(realpath -e -- "$d" 2>/dev/null) || continue
		[ -z "${vistos[$real]:-}" ] || continue
		vistos[$real]=1
		printf '%s\t%s\n' "$propietario" "$d"
	done
}

# como_usuario <usuario> <home>: fisat-usuario.sh --remove como ese usuario, con entorno limpio y timeout
como_usuario() {
	local usuario=$1 home=$2 rt='' uid
	# Sin XDG_RUNTIME_DIR heredado (/run/user/0): dejaría al usuario sin candado
	if uid=$(id -u "$usuario" 2>/dev/null) && [ -d "/run/user/$uid" ]; then
		rt=/run/user/$uid
	fi
	timeout 30 runuser -u "$usuario" -- env -u XDG_DATA_HOME -u XDG_STATE_HOME -u XDG_CONFIG_HOME \
		HOME="$home" XDG_RUNTIME_DIR="$rt" "$USUARIO" --remove --quiet </dev/null
}

main() {
	local usuario home
	# El bucle lee de un descriptor aparte: como_usuario no puede consumir la lista
	while IFS=$'\t' read -r usuario home <&3; do
		como_usuario "$usuario" "$home" || echo "fisat-retirar.sh: AVISO: no se pudo retirar la CA de $usuario ($home)" >&2
	done 3< <(leer_passwd | candidatos "$HOMES")
	return 0
}

[[ "${BASH_SOURCE[0]}" == "$0" ]] && main "$@"
