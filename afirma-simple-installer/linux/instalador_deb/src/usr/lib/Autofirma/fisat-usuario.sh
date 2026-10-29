#!/bin/bash
# Paso por usuario de AutoFirma FISAT: certificado raíz en el NSS de cada perfil Mozilla del usuario.
# Sin set -e: cada fallo se registra y se continúa. Cargable con source para probar sus funciones.

NICK=SocketAutoFirma
LEGACY="Autofirma ROOT"
LOG_MAX=262144
SALIDA=normal
NSS_MOTIVO=

uso() {
	cat <<'EOF'
Uso: fisat-usuario.sh [--quiet|--verbose] [--check] [--help]
Instala el certificado raíz de AutoFirma en los perfiles Mozilla del usuario.
  --quiet    sin salida por pantalla
  --verbose  registra también lo que ya estaba bien
  --check    solo comprueba, sin escribir: sale 0 (bien), 1 (pendiente) o 2 (error)
Entorno: AUTOFIRMA_FISAT_CER, AUTOFIRMA_FISAT_BIN
EOF
}

# log <nivel> <mensaje>: línea en el log del usuario (rota a .1 al pasar de 256 KiB) y en stderr salvo --quiet
log() {
	local dir="${XDG_STATE_HOME:-$HOME/.local/state}/autofirma-fisat" f linea
	f=$dir/usuario.log
	printf -v linea '%(%FT%T%z)T [%s] %s' -1 "$1" "$2"
	[ "$SALIDA" = quiet ] || printf 'fisat-usuario: [%s] %s\n' "$1" "$2" >&2
	mkdir -p "$dir" 2>/dev/null || return 0
	if [ "$(stat -c%s "$f" 2>/dev/null || echo 0)" -gt "$LOG_MAX" ]; then
		mv -f "$f" "$f.1" 2>/dev/null
	fi
	printf '%s\n' "$linea" >>"$f" 2>/dev/null
	return 0
}

# detalle <mensaje>: solo con --verbose
detalle() {
	[ "$SALIDA" = verbose ] && log info "$1"
	return 0
}

# _nss <args...>: certutil sin stdin y con límite de tiempo, para que nunca pida contraseña ni se cuelgue
_nss() { timeout 10 certutil "$@" </dev/null; }

# nss_entradas <db> <apodo>: flags de confianza de cada certificado con ese apodo exacto
nss_entradas() {
	_nss -L -d "sql:$1" 2>/dev/null | APODO="$2" awk '
		{ n = ENVIRON["APODO"]; r = substr($0, length(n) + 1) }
		substr($0, 1, length(n)) == n && r ~ /^ +[A-Za-z]*,[A-Za-z]*,[A-Za-z]* *$/ { print $NF }'
}

# nss_der_igual <db> <apodo> <cer>: 0 si el certificado con ese apodo tiene el DER de <cer>
nss_der_igual() {
	local sz
	sz=$(stat -c%s "$3" 2>/dev/null) || return 1
	# NSS 3.110 imprime el DER duplicado con -r: se comparan solo los primeros bytes
	{ _nss -L -d "sql:$1" -n "$2" -r 2>/dev/null || true; } | head -c "$sz" | cmp -s - "$3"
}

# nss_contrasena <db>: 0 si la BD tiene contraseña maestra
nss_contrasena() {
	local out
	out=$(_nss -K -d "sql:$1" -f /dev/null 2>&1)
	[[ $out == *SEC_ERROR_BAD_PASSWORD* ]]
}

# nss_correcto <db> <cer>: 0 si hay un único SocketAutoFirma con el DER de <cer> y C en SSL, y no queda el apodo antiguo
nss_correcto() {
	local e
	mapfile -t e < <(nss_entradas "$1" "$NICK")
	[ "${#e[@]}" -eq 1 ] || return 1
	[[ ${e[0]%%,*} == *C* ]] || return 1
	[ -z "$(nss_entradas "$1" "$LEGACY")" ] || return 1
	nss_der_igual "$1" "$NICK" "$2"
}

# nss_estado <db> <cer>: 0 correcto, 1 falta o difiere, 2 contraseña maestra; no escribe
nss_estado() {
	nss_contrasena "$1" && return 2
	nss_correcto "$1" "$2" && return 0
	return 1
}

# nss_sync <db> <cer>: 0 sin cambios, 10 cambiado, 2 error u omitido (motivo en NSS_MOTIVO)
nss_sync() {
	local db=$1 cer=$2 n i
	NSS_MOTIVO=
	[ -f "$db/cert9.db" ] || return 0
	if nss_contrasena "$db"; then
		NSS_MOTIVO="contraseña maestra"
		return 2
	fi
	nss_correcto "$db" "$cer" && return 0
	# Fuera el apodo antiguo y cualquier SocketAutoFirma que no valga (puede haber varios con el mismo apodo)
	for n in "$LEGACY" "$NICK"; do
		for i in 1 2 3 4 5; do
			[ -n "$(nss_entradas "$db" "$n")" ] || break
			if ! _nss -D -d "sql:$db" -n "$n" -f /dev/null >/dev/null 2>&1; then
				NSS_MOTIVO="certutil -D -n '$n' falló (intento $i)"
				return 2
			fi
		done
	done
	if ! _nss -A -d "sql:$db" -n "$NICK" -t "C,," -f /dev/null -i "$cer" >/dev/null 2>&1; then
		NSS_MOTIVO="certutil -A falló"
		return 2
	fi
	if ! nss_correcto "$db" "$cer"; then
		NSS_MOTIVO="tras certutil -A el certificado sigue sin estar bien"
		return 2
	fi
	return 10
}

# ff_raices: raíces de perfiles Mozilla existentes, en orden fijo
ff_raices() {
	local r
	for r in "$HOME/.mozilla/firefox" "$HOME/.config/mozilla/firefox" "$HOME/snap/firefox/common/.mozilla/firefox"; do
		[ -d "$r" ] && printf '%s\n' "$r"
	done
	return 0
}

# ff_perfiles <raíz>: "ACTIVO <ruta>" y una línea "PERFIL <ruta>" por perfil de <raíz>/profiles.ini
ff_perfiles() {
	[ -f "$1/profiles.ini" ] || return 0
	RAIZ="$1" awk '
		function ruta(p) { return (substr(p, 1, 1) == "/") ? p : ENVIRON["RAIZ"] "/" p }
		function cierra() {
			if (sec ~ /^Profile/ && path != "") {
				n++
				todos[n] = (rel == "0") ? path : ENVIRON["RAIZ"] "/" path
				if (def == "1" && pordef == "") pordef = todos[n]
			}
			path = ""; rel = ""; def = ""
		}
		{ sub(/\r$/, ""); sub(/^[ \t]+/, ""); sub(/[ \t]+$/, "") }
		/^\[.*\]$/ { cierra(); sec = substr($0, 2, length($0) - 2); next }
		{
			i = index($0, "=")
			if (i == 0) next
			k = substr($0, 1, i - 1); v = substr($0, i + 1)
			sub(/[ \t]+$/, "", k); sub(/^[ \t]+/, "", v)
		}
		sec ~ /^Install/ && k == "Default" && inst == "" && v != "" { inst = ruta(v) }
		sec ~ /^Profile/ && k == "Path" { path = v }
		sec ~ /^Profile/ && k == "IsRelative" { rel = v }
		sec ~ /^Profile/ && k == "Default" { def = v }
		END {
			cierra()
			if (n == 0) exit
			print "ACTIVO " ((inst != "") ? inst : ((pordef != "") ? pordef : todos[1]))
			for (i = 1; i <= n; i++) print "PERFIL " todos[i]
		}' <"$1/profiles.ini"
}

# perfiles_nss: perfiles con cert9.db de todas las raíces, sin repetir el mismo destino real
perfiles_nss() {
	local raiz linea p real
	local -A vistos=()
	while IFS= read -r raiz; do
		while IFS= read -r linea; do
			[[ $linea == "PERFIL "* ]] || continue
			p=${linea#PERFIL }
			[ -f "$p/cert9.db" ] || continue
			real=$(realpath -e -- "$p" 2>/dev/null) || continue
			[ -z "${vistos["$real"]:-}" ] || continue
			vistos["$real"]=1
			printf '%s\n' "$p"
		done < <(ff_perfiles "$raiz")
	done < <(ff_raices)
}

# candado: espera hasta 15 s al candado del usuario; lo mantiene hasta salir
candado() {
	local f="${XDG_RUNTIME_DIR:-/tmp}/autofirma-fisat-$UID.lock"
	if { exec {CANDADO_FD}>>"$f"; } 2>/dev/null && flock -w 15 "$CANDADO_FD"; then
		return 0
	fi
	log aviso "no se obtuvo el candado $f"
	return 1
}

# salida <línea>: resultado de --check, salvo --quiet
salida() {
	[ "$SALIDA" = quiet ] || printf '%s\n' "$1"
}

# comprobar <cer>: modo --check; no escribe nada
comprobar() {
	local cer=$1 db r estado=0
	if [ ! -r "$cer" ]; then
		salida "error certificado no legible: $cer"
		return 2
	fi
	if ! command -v certutil >/dev/null; then
		salida "error falta certutil (libnss3-tools)"
		return 2
	fi
	while IFS= read -r db; do
		nss_estado "$db" "$cer"
		r=$?
		case $r in
			0) [ "$SALIDA" = verbose ] && salida "ok $db" ;;
			2) salida "omitido $db: contraseña maestra" ;;
			*)
				salida "pendiente $db"
				estado=1
				;;
		esac
	done < <(perfiles_nss)
	return "$estado"
}

main() {
	local check=0 quiet=0 verbose=0 a cer bin db r
	for a in "$@"; do
		case $a in
			--quiet) quiet=1 ;;
			--verbose) verbose=1 ;;
			--check) check=1 ;;
			--help)
				uso
				return 0
				;;
			*)
				uso >&2
				return 64
				;;
		esac
	done
	if [ "$quiet" = 1 ] && [ "$verbose" = 1 ]; then
		uso >&2
		return 64
	fi
	[ "$quiet" = 1 ] && SALIDA=quiet
	[ "$verbose" = 1 ] && SALIDA=verbose
	cer=${AUTOFIRMA_FISAT_CER:-/usr/lib/Autofirma/Autofirma_ROOT.cer}
	bin=${AUTOFIRMA_FISAT_BIN:-/usr/bin/autofirma}

	if [ "$check" = 1 ]; then
		comprobar "$cer"
		return $?
	fi

	candado || return 0
	# Salida rápida: sin certificado no hay nada que hacer
	[ -r "$cer" ] || return 0
	if ! command -v certutil >/dev/null; then
		log error "falta certutil (libnss3-tools)"
		return 0
	fi
	detalle "certificado $cer, lanzador $bin"
	while IFS= read -r db; do
		nss_sync "$db" "$cer"
		r=$?
		case $r in
			0) detalle "sin cambios: $db" ;;
			10) log info "certificado instalado en $db" ;;
			*)
				if [ "$NSS_MOTIVO" = "contraseña maestra" ]; then
					log aviso "omitido $db: contraseña maestra"
				else
					log error "$db: ${NSS_MOTIVO:-certutil falló (rc $r)}"
				fi
				;;
		esac
	done < <(perfiles_nss)
	return 0
}

[[ "${BASH_SOURCE[0]}" == "$0" ]] && main "$@"
