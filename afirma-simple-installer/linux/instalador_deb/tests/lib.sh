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
		echo "$salida" | tail -n 5 | sed 's/^/    /'
		FALLA=$((FALLA + 1))
	fi
}

# Resumen final; sale con 1 si hubo fallos
fin() {
	echo "$PASA correctos, $FALLA fallos"
	[ "$FALLA" -eq 0 ] || exit 1
}
