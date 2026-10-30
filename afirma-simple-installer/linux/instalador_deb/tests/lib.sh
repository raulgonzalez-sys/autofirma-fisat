#!/bin/bash
# Utilidades de test: chk, chk_rc, fin y el directorio temporal T (se borra al salir).

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
PASA=0
FALLA=0

# chk <nombre> <esperado> <obtenido>
chk() {
	if [ "$2" = "$3" ]; then
		echo "OK $1"
		PASA=$((PASA + 1))
	else
		echo "FALLO $1: esperado '$2', obtenido '$3'"
		FALLA=$((FALLA + 1))
	fi
}

# chk_rc <nombre> <rc_esperado> <comando...>
chk_rc() {
	local nombre=$1 esperado=$2 rc=0 salida
	shift 2
	salida=$("$@" 2>&1) || rc=$?
	if [ "$rc" = "$esperado" ]; then
		echo "OK $nombre"
		PASA=$((PASA + 1))
	else
		echo "FALLO $nombre: rc esperado $esperado, obtenido $rc"
		echo "$salida" | tail -n 25 | sed 's/^/    /'
		FALLA=$((FALLA + 1))
	fi
}

# Resumen final; sale con 1 si hubo fallos
fin() {
	echo "$PASA correctos, $FALLA fallos"
	[ "$FALLA" -eq 0 ] || exit 1
}

# mk_cer <ruta.cer> [cn]: certificado autofirmado en DER (CN por defecto el de la CA de AutoFirma)
mk_cer() {
	openssl req -x509 -newkey rsa:2048 -nodes -keyout /dev/null -subj "/CN=${2:-AutoFirma ROOT}" -days 30 2>/dev/null |
		openssl x509 -outform der -out "$1"
}

# mk_nss <dir>: BD NSS nueva sin contraseña
mk_nss() {
	mkdir -p "$1"
	certutil -N -d "sql:$1" --empty-password
}

# mk_ini <raíz> <contenido>: escribe <raíz>/profiles.ini
mk_ini() {
	mkdir -p "$1"
	printf '%s\n' "$2" >"$1/profiles.ini"
}

# mk_personal <db> [apodo]: certificado autofirmado con clave privada (apodo por defecto "Usuario Prueba")
mk_personal() {
	local apodo=${2:-Usuario Prueba} ruido=$T/ruido.$RANDOM
	head -c 64 /dev/urandom >"$ruido"
	certutil -S -d "sql:$1" -n "$apodo" -s "CN=$apodo" -x -t "u,u,u" -k rsa -g 2048 -z "$ruido" -f /dev/null </dev/null >/dev/null 2>&1
}
