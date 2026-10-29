#!/bin/bash
# Paso por usuario de AutoFirma FISAT: certificado raíz en el NSS de cada perfil Mozilla del usuario,
# ~/.pki/nssdb enlazado al perfil activo y protocolo afirma:// en Firefox (user.js y handlers.json).
# Sin set -e: cada fallo se registra y se continúa. Cargable con source para probar sus funciones.

NICK=SocketAutoFirma
LEGACY="Autofirma ROOT"
LOG_MAX=262144
SALIDA=normal
NSS_MOTIVO=
MODO_CHECK=0
PEOR=0
CER=${AUTOFIRMA_FISAT_CER:-/usr/lib/Autofirma/Autofirma_ROOT.cer}
BIN=${AUTOFIRMA_FISAT_BIN:-/usr/bin/autofirma}
UJ_EXPOSE='user_pref("network.protocol-handler.expose.afirma", true);'
UJ_PERMISO='user_pref("security.external_protocol_requires_permission", false);'
# Líneas de user.js de esas dos preferencias, con cualquier valor
UJ_RE='^[[:space:]]*user_pref\([[:space:]]*["'"'"'](network\.protocol-handler\.expose\.afirma|security\.external_protocol_requires_permission)["'"'"']'
declare -gA NSS_VISTOS=()

# Python que fusiona schemes.afirma (comprobar|escribir <fichero> <lanzador>): 0 bien, 20 pendiente, 10 escrito, 2 ilegible, 3 sin escribir
PY_HANDLERS='
import json, os, stat, sys, tempfile
modo, f, lanzador = sys.argv[1:4]
afirma = {"action": 2, "handlers": [{"name": "AutoFirma", "path": lanzador}]}
nuevo = False
try:
	with open(f, encoding="utf-8") as h:
		datos = json.load(h)
except FileNotFoundError:
	datos = {"defaultHandlersVersion": {}, "mimeTypes": {}, "schemes": {}}
	nuevo = True
except (OSError, ValueError):
	sys.exit(2)
if not isinstance(datos, dict) or not isinstance(datos.setdefault("schemes", {}), dict):
	sys.exit(2)
if not nuevo and datos["schemes"].get("afirma") == afirma:
	sys.exit(0)
if modo != "escribir":
	sys.exit(20)
datos["schemes"]["afirma"] = afirma
try:
	fd, tmp = tempfile.mkstemp(prefix=".handlers.json.", dir=os.path.dirname(f))
except OSError:
	sys.exit(3)
try:
	with os.fdopen(fd, "w", encoding="utf-8") as h:
		json.dump(datos, h, separators=(",", ":"), ensure_ascii=False)
	if not nuevo:
		os.chmod(tmp, stat.S_IMODE(os.stat(f).st_mode))
	os.replace(tmp, f)
except OSError:
	try:
		os.unlink(tmp)
	except OSError:
		pass
	sys.exit(3)
sys.exit(10)
'

uso() {
	cat <<'EOF'
Uso: fisat-usuario.sh [--quiet|--verbose] [--check] [--help]
Instala el certificado raíz de AutoFirma en los perfiles Mozilla del usuario,
enlaza ~/.pki/nssdb al perfil activo y registra afirma:// en Firefox.
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

# nss_tratar <db>: certificado en <db>, una sola vez por destino real; en --check solo informa (0 bien, 1 pendiente)
nss_tratar() {
	local db=$1 real r
	real=$(realpath -e -- "$db" 2>/dev/null) || return 0
	[ -z "${NSS_VISTOS["$real"]:-}" ] || return 0
	NSS_VISTOS["$real"]=1
	if [ "$MODO_CHECK" = 1 ]; then
		nss_estado "$db" "$CER"
		r=$?
		case $r in
			0) [ "$SALIDA" = verbose ] && salida "ok $db" ;;
			2) salida "omitido $db: contraseña maestra" ;;
			*)
				salida "pendiente $db"
				return 1
				;;
		esac
		return 0
	fi
	nss_sync "$db" "$CER"
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
	return 0
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

# perfil_activo: ACTIVO de la primera raíz con profiles.ini, solo si tiene cert9.db
perfil_activo() {
	local raiz
	local -a l
	while IFS= read -r raiz; do
		[ -f "$raiz/profiles.ini" ] || continue
		mapfile -t l < <(ff_perfiles "$raiz")
		if [[ ${l[0]:-} == "ACTIVO "* ]] && [ -f "${l[0]#ACTIVO }/cert9.db" ]; then
			printf '%s\n' "${l[0]#ACTIVO }"
		fi
		return 0
	done < <(ff_raices)
	return 0
}

# perfiles_activos: el perfil activo de cada raíz que exista como directorio, sin repetir el mismo destino real
perfiles_activos() {
	local raiz p real
	local -a l
	local -A vistos=()
	while IFS= read -r raiz; do
		mapfile -t l < <(ff_perfiles "$raiz")
		[[ ${l[0]:-} == "ACTIVO "* ]] || continue
		p=${l[0]#ACTIVO }
		[ -d "$p" ] || continue
		real=$(realpath -e -- "$p" 2>/dev/null) || continue
		[ -z "${vistos["$real"]:-}" ] || continue
		vistos["$real"]=1
		printf '%s\n' "$p"
	done < <(ff_raices)
}

# es_perfil_ff <ruta>: 0 si su destino real es un perfil de algún profiles.ini o está dentro de una raíz de perfiles
es_perfil_ff() {
	local real raiz linea
	real=$(realpath -e -- "$1" 2>/dev/null) || return 1
	while IFS= read -r raiz; do
		[ "$(realpath -e -- "$raiz" 2>/dev/null)" = "${real%/*}" ] && return 0
		while IFS= read -r linea; do
			[[ $linea == "PERFIL "* ]] || continue
			[ "$(realpath -e -- "${linea#PERFIL }" 2>/dev/null)" = "$real" ] && return 0
		done < <(ff_perfiles "$raiz")
	done < <(ff_raices)
	return 1
}

# pki_sync <perfil_activo>: ~/.pki/nssdb enlazado al perfil activo; un directorio real nunca se borra ni se mueve
pki_sync() {
	local activo=$1 pki=$HOME/.pki/nssdb destino tmp
	if [ -L "$pki" ]; then
		destino=$(readlink -f -- "$pki")
		if [ -e "$pki" ] && [ -n "$activo" ] && [ "$destino" = "$(realpath -e -- "$activo" 2>/dev/null)" ]; then
			nss_tratar "$pki"
			return
		fi
		if [ -e "$pki" ] && ! es_perfil_ff "$pki"; then
			if [ "$MODO_CHECK" = 1 ]; then
				salida "omitido $pki: apunta a $destino, que no es un perfil Firefox"
			else
				log aviso "no se toca $pki: apunta a $destino, que no es un perfil Firefox"
			fi
			return 0
		fi
		# Enlace colgante o a otro perfil Firefox: se repunta de forma atómica
		if [ -z "$activo" ]; then
			detalle "$pki apunta a $destino y no hay perfil activo con cert9.db"
			return 0
		fi
		if [ "$MODO_CHECK" = 1 ]; then
			salida "pendiente $pki"
			return 1
		fi
		tmp=$HOME/.pki/.nssdb.fisat-tmp
		if ln -sfn -- "$activo" "$tmp" 2>/dev/null && mv -T -- "$tmp" "$pki" 2>/dev/null; then
			log info "$pki apuntaba a $destino; ahora a $activo"
		else
			rm -f -- "$tmp"
			log error "no se pudo repuntar $pki a $activo"
			return 0
		fi
	elif [ -d "$pki" ]; then
		if [ -f "$pki/cert9.db" ]; then
			nss_tratar "$pki" || return
		fi
		[ "$MODO_CHECK" = 1 ] || log aviso "no se enlaza: directorio real $pki"
		return 0
	elif [ -e "$pki" ]; then
		if [ "$MODO_CHECK" = 1 ]; then
			salida "omitido $pki: no es enlace ni directorio"
		else
			log aviso "no se toca $pki: no es enlace ni directorio"
		fi
		return 0
	else
		[ -n "$activo" ] || return 0
		if [ "$MODO_CHECK" = 1 ]; then
			salida "pendiente $pki"
			return 1
		fi
		if { [ -d "$HOME/.pki" ] || mkdir -m 700 -- "$HOME/.pki" 2>/dev/null; } && ln -s -- "$activo" "$pki" 2>/dev/null; then
			log info "$pki enlazado a $activo"
		else
			log error "no se pudo enlazar $pki a $activo"
			return 0
		fi
	fi
	nss_tratar "$pki"
	return 0
}

# userjs_sync <perfil>: user.js con cada preferencia de afirma:// exactamente una vez y con su valor
userjs_sync() {
	local f=$1/user.js tmp r=0
	[ -d "$1" ] || return 0
	# Si user.js es un enlace válido, se escribe en su destino
	if [ -L "$f" ] && [ -e "$f" ]; then
		f=$(realpath -e -- "$f")
	fi
	if [ -f "$f" ] &&
		[ "$(LC_ALL=C grep -acE -- "$UJ_RE" "$f")" = 2 ] &&
		[ "$(LC_ALL=C grep -acFx -- "$UJ_EXPOSE" "$f")" = 1 ] &&
		[ "$(LC_ALL=C grep -acFx -- "$UJ_PERMISO" "$f")" = 1 ]; then
		detalle "sin cambios: $f"
		return 0
	fi
	if [ "$MODO_CHECK" = 1 ]; then
		salida "pendiente $f"
		return 1
	fi
	if ! tmp=$(mktemp -- "${f%/*}/.user.js.XXXXXX" 2>/dev/null); then
		log error "no se pudo crear un temporal junto a $f"
		return 0
	fi
	# Fuera las líneas de las dos preferencias (grep sale 1 si no queda ninguna otra); se añaden al final
	if [ -e "$f" ]; then
		LC_ALL=C grep -avE -- "$UJ_RE" "$f" >"$tmp" 2>/dev/null || r=$?
		if [ "$r" -le 1 ] && ! chmod --reference="$f" -- "$tmp" 2>/dev/null; then
			r=2
		fi
	fi
	if [ "$r" -le 1 ] && printf '%s\n%s\n' "$UJ_EXPOSE" "$UJ_PERMISO" >>"$tmp" && mv -f -- "$tmp" "$f" 2>/dev/null; then
		log info "preferencias de afirma:// en $f"
	else
		rm -f -- "$tmp"
		log error "no se pudo escribir $f"
	fi
	return 0
}

# perfil_abierto <perfil>: 0 si el enlace lock apunta a un pid vivo (lo que va tras el último +)
perfil_abierto() {
	local l
	l=$(readlink -- "$1/lock" 2>/dev/null) || return 1
	[[ ${l##*+} =~ ^[0-9]+$ ]] || return 1
	kill -0 "${l##*+}" 2>/dev/null
}

# handlers_sync <perfil>: entrada afirma de handlers.json hacia el lanzador, conservando el resto
handlers_sync() {
	local f=$1/handlers.json r
	[ -d "$1" ] || return 0
	python3 -I -c "$PY_HANDLERS" comprobar "$f" "$BIN" 2>/dev/null
	r=$?
	case $r in
		0)
			detalle "sin cambios: $f"
			return 0
			;;
		20) ;;
		*)
			if [ "$r" = 2 ]; then r="JSON ilegible, no se toca"; else r="python3 falló (rc $r)"; fi
			if [ "$MODO_CHECK" = 1 ]; then
				salida "error $f: $r"
				return 2
			fi
			log error "$f: $r"
			return 0
			;;
	esac
	if [ "$MODO_CHECK" = 1 ]; then
		salida "pendiente $f"
		return 1
	fi
	# Firefox reescribe handlers.json al cerrarse: con el perfil abierto el cambio se perdería
	if perfil_abierto "$1"; then
		log aviso "pendiente: perfil abierto, no se escribe $f"
		return 0
	fi
	python3 -I -c "$PY_HANDLERS" escribir "$f" "$BIN" 2>/dev/null
	r=$?
	case $r in
		0) ;;
		10) log info "afirma:// registrado en $f" ;;
		*) log error "no se pudo escribir $f (rc $r)" ;;
	esac
	return 0
}

# anota <rc>: guarda en PEOR el peor resultado (0 bien, 1 pendiente, 2 error)
anota() {
	[ "$1" -gt "$PEOR" ] && PEOR=$1
	return 0
}

# sincronizar: certificados, ~/.pki/nssdb y afirma:// en Firefox; en --check devuelve el peor resultado
sincronizar() {
	local db p
	PEOR=0
	while IFS= read -r db; do
		nss_tratar "$db" || anota $?
	done < <(perfiles_nss)
	pki_sync "$(perfil_activo)" || anota $?
	while IFS= read -r p; do
		userjs_sync "$p" || anota $?
		handlers_sync "$p" || anota $?
	done < <(perfiles_activos)
	return "$PEOR"
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

# comprobar: modo --check; no escribe nada
comprobar() {
	if [ ! -r "$CER" ]; then
		salida "error certificado no legible: $CER"
		return 2
	fi
	if ! command -v certutil >/dev/null; then
		salida "error falta certutil (libnss3-tools)"
		return 2
	fi
	MODO_CHECK=1
	sincronizar
}

main() {
	local check=0 quiet=0 verbose=0 a
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

	if [ "$check" = 1 ]; then
		comprobar
		return $?
	fi

	candado || return 0
	# Salida rápida: sin certificado no hay nada que hacer
	[ -r "$CER" ] || return 0
	if ! command -v certutil >/dev/null; then
		log error "falta certutil (libnss3-tools)"
		return 0
	fi
	detalle "certificado $CER, lanzador $BIN"
	MODO_CHECK=0
	sincronizar
	return 0
}

[[ "${BASH_SOURCE[0]}" == "$0" ]] && main "$@"
