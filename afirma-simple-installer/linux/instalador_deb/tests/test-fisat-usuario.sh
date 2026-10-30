#!/bin/bash
# Pruebas de fisat-usuario.sh: certificado en NSS, lectura de profiles.ini y script completo.
set -euo pipefail

DEB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=lib.sh
. "$DEB/tests/lib.sh"

S=$DEB/src/usr/lib/Autofirma/fisat-usuario.sh

mk_cer "$T/A.cer"
mk_cer "$T/B.cer"
mk_cer "$T/X.cer" "Otra CA"

# fn <función> <args...>: ejecuta una función del script en un bash limpio (sin set -euo)
fn() { bash -c '. "$1"; shift; "$@"' fn "$S" "$@"; }
# rc <comando...>: imprime el código de salida sin abortar
rc() { local r=0; "$@" >/dev/null 2>&1 || r=$?; echo "$r"; }
# confianza <db> <apodo>: flags de cada entrada con ese apodo exacto
confianza() { certutil -L -d "sql:$1" | APODO="$2" awk '{ n = ENVIRON["APODO"]; r = substr($0, length(n) + 1) } substr($0, 1, length(n)) == n && r ~ /^ +[A-Za-z]*,[A-Za-z]*,[A-Za-z]* *$/ { print $NF }'; }
# entradas <db> <apodo>: número de certificados con ese apodo
entradas() { confianza "$1" "$2" | wc -l; }
# der <db> <apodo> <cer>: "igual" si el certificado con ese apodo tiene el DER de <cer>
der() {
	certutil -L -d "sql:$1" -n "$2" -r >"$T/der.out" 2>/dev/null || true
	if head -c "$(stat -c%s "$3")" "$T/der.out" | cmp -s - "$3"; then echo igual; else echo distinto; fi
}
# huella <dirs...>: sha256 de todos los ficheros bajo los directorios
huella() { find "$@" -type f -print0 | sort -z | xargs -0 -r sha256sum; }
# ms: milisegundos desde la época
ms() { echo $(($(date +%s%N) / 1000000)); }
# mt <fichero>: mtime con nanosegundos
mt() { stat -c %.9Y "$1"; }
# sha <fichero>: sha256 del contenido
sha() { sha256sum <"$1" | cut -d' ' -f1; }
# existe <ruta>: 1 si existe (o es un enlace), 0 si no
existe() { if [ -e "$1" ] || [ -L "$1" ]; then echo 1; else echo 0; fi; }
# es_dir_real <ruta>: 1 si es un directorio y no un enlace
es_dir_real() { if [ -d "$1" ] && [ ! -L "$1" ]; then echo 1; else echo 0; fi; }
# lineas <fichero> <línea>: veces que aparece exactamente esa línea
lineas() { grep -cFx -- "$2" "$1" || true; }
# fn_check <función> <args...>: como fn, en modo --check
fn_check() { bash -c '. "$1"; shift; MODO_CHECK=1; "$@"' fn "$S" "$@"; }
# nuevo_caso: HOME, estado y runtime vacíos; sin XDG_DATA_HOME para no tocar el almacén real de Chrome
nuevo_caso() {
	rm -rf "${T:?}/home" "${T:?}/state" "${T:?}/run"
	mkdir -p "$T/home" "$T/state" "$T/run"
	export HOME=$T/home XDG_STATE_HOME=$T/state XDG_RUNTIME_DIR=$T/run AUTOFIRMA_FISAT_CER=$T/A.cer
	unset XDG_DATA_HOME
}
# mk_ff <raíz> <perfil...>: profiles.ini con esos perfiles relativos y sus directorios (sin NSS)
mk_ff() {
	local raiz=$1 i=0 ini="" p
	shift
	for p in "$@"; do
		ini+="[Profile$i]"$'\n'"Name=$p"$'\n'"IsRelative=1"$'\n'"Path=$p"$'\n\n'
		mkdir -p "$raiz/$p"
		i=$((i + 1))
	done
	mk_ini "$raiz" "$ini"
}

# Líneas que exige user.js y entrada esperada de handlers.json
UJ1='user_pref("network.protocol-handler.expose.afirma", true);'
UJ2='user_pref("security.external_protocol_requires_permission", false);'
AFIRMA='{"action":2,"handlers":[{"name":"AutoFirma","path":"/usr/bin/autofirma"}]}'
# handlers.json de un perfil real de Firefox ESR 140
HANDLERS_ESR='{"defaultHandlersVersion":{},"mimeTypes":{"application/pdf":{"action":3,"extensions":["pdf"]},"image/webp":{"action":3,"extensions":["webp"]}},"schemes":{"mailto":{"stubEntry":true,"handlers":[null,{"name":"Gmail","uriTemplate":"https://mail.google.com/mail/?extsrc=mailto&url=%s"}]}},"isDownloadsImprovementsAlreadyMigrated":false}'

nuevo_caso

# --- Certificados (nss_sync) ---

# C1_bd_vacia
mk_nss "$T/c1"
chk C1_bd_vacia_rc 10 "$(rc fn nss_sync "$T/c1" "$T/A.cer")"
chk C1_bd_vacia_flags "C,," "$(confianza "$T/c1" SocketAutoFirma)"
chk C1_bd_vacia_der igual "$(der "$T/c1" SocketAutoFirma "$T/A.cer")"
chk C1_estado 0 "$(rc fn nss_estado "$T/c1" "$T/A.cer")"

# C2_segunda_vez: sin cambios y sin escribir
m1=$(stat -c %.9Y "$T/c1/cert9.db")
chk C2_segunda_vez_rc 0 "$(rc fn nss_sync "$T/c1" "$T/A.cer")"
chk C2_segunda_vez_mtime "$m1" "$(stat -c %.9Y "$T/c1/cert9.db")"

# C3_reinstalacion_otro_cert: la CA nueva sustituye a la vieja (mismo sujeto, otro DER)
chk C3_estado_pendiente 1 "$(rc fn nss_estado "$T/c1" "$T/B.cer")"
chk C3_reinstalacion_rc 10 "$(rc fn nss_sync "$T/c1" "$T/B.cer")"
chk C3_reinstalacion_una_entrada 1 "$(entradas "$T/c1" SocketAutoFirma)"
chk C3_reinstalacion_der_b igual "$(der "$T/c1" SocketAutoFirma "$T/B.cer")"
chk C3_reinstalacion_flags "C,," "$(confianza "$T/c1" SocketAutoFirma)"

# C4_migra_apodo_antiguo: mismo certificado con el apodo de la flota
mk_nss "$T/c4"
certutil -A -d "sql:$T/c4" -n "Autofirma ROOT" -t "CT,C," -i "$T/A.cer"
chk C4_estado_pendiente 1 "$(rc fn nss_estado "$T/c4" "$T/A.cer")"
chk C4_migra_rc 10 "$(rc fn nss_sync "$T/c4" "$T/A.cer")"
chk C4_migra_sin_antiguo 0 "$(entradas "$T/c4" "Autofirma ROOT")"
chk C4_migra_flags "C,," "$(confianza "$T/c4" SocketAutoFirma)"
chk C4_migra_der igual "$(der "$T/c4" SocketAutoFirma "$T/A.cer")"

# C5_apodo_antiguo_otro_cert
mk_nss "$T/c5"
certutil -A -d "sql:$T/c5" -n "Autofirma ROOT" -t "CT,C," -i "$T/B.cer"
chk C5_rc 10 "$(rc fn nss_sync "$T/c5" "$T/A.cer")"
chk C5_sin_antiguo 0 "$(entradas "$T/c5" "Autofirma ROOT")"
chk C5_queda_a igual "$(der "$T/c5" SocketAutoFirma "$T/A.cer")"
chk C5_una_entrada 1 "$(entradas "$T/c5" SocketAutoFirma)"

# C6_flags_vacios
mk_nss "$T/c6"
certutil -A -d "sql:$T/c6" -n SocketAutoFirma -t ",," -i "$T/A.cer"
chk C6_estado_pendiente 1 "$(rc fn nss_estado "$T/c6" "$T/A.cer")"
chk C6_rc 10 "$(rc fn nss_sync "$T/c6" "$T/A.cer")"
chk C6_flags "C,," "$(confianza "$T/c6" SocketAutoFirma)"
chk C6_una_entrada 1 "$(entradas "$T/c6" SocketAutoFirma)"

# C7_contrasena_maestra: rc 2 rápido y sin importar nada
mkdir -p "$T/c7"
echo secreta >"$T/c7.pw"
certutil -N -d "sql:$T/c7" -f "$T/c7.pw"
t0=$(ms)
chk C7_rc 2 "$(rc fn nss_sync "$T/c7" "$T/A.cer")"
chk C7_rapido 1 "$([ $(($(ms) - t0)) -lt 5000 ] && echo 1 || echo 0)"
chk C7_no_importado 0 "$(entradas "$T/c7" SocketAutoFirma)"
chk C7_estado 2 "$(rc fn nss_estado "$T/c7" "$T/A.cer")"

# C8_sin_cert9: no crea nada
mkdir -p "$T/c8"
chk C8_rc 0 "$(rc fn nss_sync "$T/c8" "$T/A.cer")"
chk C8_sigue_vacio 0 "$(find "$T/c8" -mindepth 1 | wc -l)"

# C9_enlace: el certificado queda en el perfil al que apunta el enlace
mk_nss "$T/c9real"
ln -s c9real "$T/c9"
chk C9_rc 10 "$(rc fn nss_sync "$T/c9" "$T/A.cer")"
chk C9_destino igual "$(der "$T/c9real" SocketAutoFirma "$T/A.cer")"
chk C9_sigue_enlace 1 "$([ -L "$T/c9" ] && echo 1 || echo 0)"

# C10_apodo_con_prefijo: SocketAutoFirma2 no cuenta y no se toca
mk_nss "$T/c10"
certutil -A -d "sql:$T/c10" -n SocketAutoFirma2 -t "C,," -i "$T/X.cer"
chk C10_estado_pendiente 1 "$(rc fn nss_estado "$T/c10" "$T/A.cer")"
chk C10_rc 10 "$(rc fn nss_sync "$T/c10" "$T/A.cer")"
chk C10_otro_intacto igual "$(der "$T/c10" SocketAutoFirma2 "$T/X.cer")"
chk C10_nuevo igual "$(der "$T/c10" SocketAutoFirma "$T/A.cer")"

# --- Perfiles (ff_perfiles) ---

# profiles.ini real del skel de la flota
INI_SKEL="[Profile0]
Name=fisat
IsRelative=1
Default=1
Path=default-esr

[General]
StartWithLastProfile=1

[Install3B6073811A6ABF12]
Default=default-esr
Locked=1"
mk_ini "$T/p1" "$INI_SKEL"
chk P1_skel_real "ACTIVO $T/p1/default-esr
PERFIL $T/p1/default-esr" "$(fn ff_perfiles "$T/p1")"

mk_ini "$T/p2" "[Profile0]
Name=default
IsRelative=1
Path=viejo.default

[Profile1]
Name=default-release
IsRelative=1
Path=xyz.default-release
Default=1"
chk P2_default_no_primero "ACTIVO $T/p2/xyz.default-release" "$(fn ff_perfiles "$T/p2" | head -n 1)"

chk P3_dos_perfiles "PERFIL $T/p2/viejo.default
PERFIL $T/p2/xyz.default-release" "$(fn ff_perfiles "$T/p2" | grep '^PERFIL')"

mk_ini "$T/p4" "[Profile1]
Name=otro
IsRelative=1
Path=Profiles/otro perfil

[Profile0]
Name=abs
IsRelative=0
Path=/srv/perfil abs

[Install111]
Default=/srv/perfil abs

[Install222]
Default=Profiles/otro perfil"
chk P4_absoluto_con_espacios_y_dos_install "ACTIVO /srv/perfil abs
PERFIL $T/p4/Profiles/otro perfil
PERFIL /srv/perfil abs" "$(fn ff_perfiles "$T/p4")"

mk_ini "$T/p5" "[General]
StartWithLastProfile=1
Version=2"
chk P5_ini_sin_perfiles "" "$(fn ff_perfiles "$T/p5")"

chk P6_sin_ini "" "$(fn ff_perfiles "$T/noexiste")"
chk P6_sin_ini_rc 0 "$(rc fn ff_perfiles "$T/noexiste")"

mk_ini "$T/p7" $'[Profile0]\r\nName=win\r\nIsRelative=1\r\nPath=win\r\nDefault=1\r'
chk P7_crlf "ACTIVO $T/p7/win
PERFIL $T/p7/win" "$(fn ff_perfiles "$T/p7")"

# ff_raices: solo las existentes y en su orden
nuevo_caso
mkdir -p "$HOME/snap/firefox/common/.mozilla/firefox" "$HOME/.mozilla/firefox"
chk R1_raices "$HOME/.mozilla/firefox
$HOME/snap/firefox/common/.mozilla/firefox" "$(fn ff_raices)"

# --- Script completo ---

# U0_uso
chk U0_opcion_desconocida 64 "$(rc "$S" --nada)"
chk U0_quiet_y_verbose 64 "$(rc "$S" --quiet --verbose)"
chk U0_help 0 "$(rc "$S" --help)"

# U1_home_vacio
nuevo_caso
chk U1_rc 0 "$(rc "$S")"
chk U1_sin_ficheros 0 "$(find "$HOME" "$XDG_STATE_HOME" -mindepth 1 | wc -l)"
chk U1_check 0 "$(rc "$S" --check)"

# U9_dos_raices
nuevo_caso
mk_ff "$HOME/.mozilla/firefox" a.default
mk_nss "$HOME/.mozilla/firefox/a.default"
mk_ff "$HOME/.config/mozilla/firefox" b.default
mk_nss "$HOME/.config/mozilla/firefox/b.default"
chk U9_rc 0 "$(rc "$S")"
chk U9_mozilla igual "$(der "$HOME/.mozilla/firefox/a.default" SocketAutoFirma "$T/A.cer")"
chk U9_config igual "$(der "$HOME/.config/mozilla/firefox/b.default" SocketAutoFirma "$T/A.cer")"

# U10_varios_perfiles
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno dos tres
mk_nss "$R/uno"
mk_nss "$R/dos"
chk U10_rc 0 "$(rc "$S")"
chk U10_uno igual "$(der "$R/uno" SocketAutoFirma "$T/A.cer")"
chk U10_dos igual "$(der "$R/dos" SocketAutoFirma "$T/A.cer")"
chk U10_tres_intacto 0 "$(find "$R/tres" -mindepth 1 | wc -l)"

# U11_check_no_escribe
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
mk_nss "$R/uno"
h1=$(huella "$HOME")
chk U11_check_pendiente 1 "$(rc "$S" --check)"
"$S" --check >"$T/u11.out" || true
chk U11_check_linea 1 "$(lineas "$T/u11.out" "pendiente $R/uno")"
chk U11_check_linea_pki 1 "$(lineas "$T/u11.out" "pendiente $HOME/.pki/nssdb")"
chk U11_check_linea_userjs 1 "$(lineas "$T/u11.out" "pendiente $R/uno/user.js")"
chk U11_check_linea_handlers 1 "$(lineas "$T/u11.out" "pendiente $R/uno/handlers.json")"
chk U11_home_igual "$h1" "$(huella "$HOME")"
chk U11_sin_pki 0 "$(existe "$HOME/.pki")"
chk U11_sin_log 0 "$(find "$XDG_STATE_HOME" -mindepth 1 | wc -l)"
"$S" --quiet
chk U11_check_tras_arreglo 0 "$(rc "$S" --check)"
chk U11_check_tras_arreglo_sin_salida "" "$("$S" --check)"

# U11b: enlace colgante, user.js con otro valor y handlers.json sin afirma; --check no toca nada
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
mk_nss "$R/uno"
"$S" --quiet
mkdir -p "$HOME/.pki"
ln -sfn "$R/borrado" "$HOME/.pki/nssdb"
printf '%s\n' 'user_pref("network.protocol-handler.expose.afirma", false);' >"$R/uno/user.js"
printf '%s' "$HANDLERS_ESR" >"$R/uno/handlers.json"
h1=$(huella "$HOME")
chk U11b_check 1 "$(rc "$S" --check)"
"$S" --check >"$T/u11b.out" || true
chk U11b_linea_pki 1 "$(lineas "$T/u11b.out" "pendiente $HOME/.pki/nssdb")"
chk U11b_linea_userjs 1 "$(lineas "$T/u11b.out" "pendiente $R/uno/user.js")"
chk U11b_linea_handlers 1 "$(lineas "$T/u11b.out" "pendiente $R/uno/handlers.json")"
chk U11b_home_igual "$h1" "$(huella "$HOME")"
chk U11b_enlace_igual "$R/borrado" "$(readlink "$HOME/.pki/nssdb")"
chk U11b_userjs_fn 1 "$(rc fn_check userjs_sync "$R/uno")"
chk U11b_handlers_fn 1 "$(rc fn_check handlers_sync "$R/uno")"
chk U11b_pki_fn 1 "$(rc fn_check pki_sync "$R/uno")"
chk U11b_home_igual_fn "$h1" "$(huella "$HOME")"
"$S" --quiet
chk U11b_check_tras_arreglo 0 "$(rc "$S" --check)"

# U11c: ~/.pki/nssdb directorio real sin el certificado; --check lo cuenta y no escribe
nuevo_caso
mk_nss "$HOME/.pki/nssdb"
h1=$(huella "$HOME")
chk U11c_check 1 "$(rc "$S" --check)"
chk U11c_linea "pendiente $HOME/.pki/nssdb" "$("$S" --check || true)"
chk U11c_home_igual "$h1" "$(huella "$HOME")"
"$S" --quiet
chk U11c_check_tras_arreglo 0 "$(rc "$S" --check)"

# U11d: handlers.json ilegible; --check da error (2)
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
"$S" --quiet
printf '{"schemes":' >"$R/uno/handlers.json"
chk U11d_check 2 "$(rc "$S" --check)"
chk U11d_linea 1 "$("$S" --check | grep -c "^error $R/uno/handlers.json" || true)"

# U12_quiet
nuevo_caso
mk_ff "$HOME/.mozilla/firefox" uno
mk_nss "$HOME/.mozilla/firefox/uno"
"$S" --quiet >"$T/u12.out" 2>"$T/u12.err"
chk U12_stdout_vacio 0 "$(stat -c%s "$T/u12.out")"
chk U12_stderr_vacio 0 "$(stat -c%s "$T/u12.err")"
chk U12_instalado igual "$(der "$HOME/.mozilla/firefox/uno" SocketAutoFirma "$T/A.cer")"
chk U12_log 1 "$(grep -c '\[info\] certificado instalado' "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"

# U13_candado: espera a que el otro proceso suelte el candado
nuevo_caso
mk_ff "$HOME/.mozilla/firefox" uno
mk_nss "$HOME/.mozilla/firefox/uno"
(
	flock 9
	touch "$T/u13.tomado"
	sleep 2
) 9>"$XDG_RUNTIME_DIR/autofirma-fisat-$UID.lock" &
while [ ! -e "$T/u13.tomado" ]; do sleep 0.05; done
t0=$(ms)
chk U13_rc 0 "$(rc "$S" --quiet)"
chk U13_espera 1 "$([ $(($(ms) - t0)) -ge 1000 ] && echo 1 || echo 0)"
wait
chk U13_bd_sana 0 "$(rc certutil -L -d "sql:$HOME/.mozilla/firefox/uno")"
chk U13_instalado igual "$(der "$HOME/.mozilla/firefox/uno" SocketAutoFirma "$T/A.cer")"

# U14_sin_cer: no toca nada
nuevo_caso
mk_ff "$HOME/.mozilla/firefox" uno
mk_nss "$HOME/.mozilla/firefox/uno"
export AUTOFIRMA_FISAT_CER=$T/noexiste.cer
h1=$(huella "$HOME")
chk U14_rc 0 "$(rc "$S")"
chk U14_home_igual "$h1" "$(huella "$HOME")"
chk U14_sin_log 0 "$(find "$XDG_STATE_HOME" -mindepth 1 | wc -l)"
chk U14_check 2 "$(rc "$S" --check)"

# U15_rotacion
nuevo_caso
mk_ff "$HOME/.mozilla/firefox" uno
mk_nss "$HOME/.mozilla/firefox/uno"
L=$XDG_STATE_HOME/autofirma-fisat/usuario.log
mkdir -p "$(dirname "$L")"
head -c 300000 /dev/zero | tr '\0' 'x' >"$L"
"$S" --quiet
chk U15_log1 300000 "$(stat -c%s "$L.1")"
chk U15_log_nuevo 0 "$(grep -c '^x' "$L" || true)"
chk U15_log_no_vacio 1 "$([ "$(wc -l <"$L")" -ge 1 ] && echo 1 || echo 0)"
chk U15_formato "$(wc -l <"$L")" "$(grep -cE '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{4} \[info\] ' "$L")"

# U16_rapido: todo correcto con 3 perfiles, menos de 1 s y sin escribir el log
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno dos tres
mk_nss "$R/uno"
mk_nss "$R/dos"
mk_nss "$R/tres"
"$S" --quiet
L=$XDG_STATE_HOME/autofirma-fisat/usuario.log
n1=$(wc -l <"$L")
t0=$(ms)
"$S" --quiet
dt=$(($(ms) - t0))
chk U16_rapido 1 "$([ "$dt" -lt 1000 ] && echo 1 || echo "0 ($dt ms)")"
chk U16_log_sin_cambios "$n1" "$(wc -l <"$L")"

# U17_home_con_arroba_y_espacio: log en el propio home (sin XDG_STATE_HOME)
nuevo_caso
export HOME="$T/home/usuaria dos@example.org"
unset XDG_STATE_HOME
mk_ff "$HOME/.mozilla/firefox" "perfil fisat"
mk_nss "$HOME/.mozilla/firefox/perfil fisat"
chk U17_rc 0 "$(rc "$S" --quiet)"
chk U17_instalado igual "$(der "$HOME/.mozilla/firefox/perfil fisat" SocketAutoFirma "$T/A.cer")"
chk U17_log 1 "$(grep -c "\[info\] certificado instalado en $HOME/.mozilla/firefox/perfil fisat" "$HOME/.local/state/autofirma-fisat/usuario.log")"
chk U17_pki "$HOME/.mozilla/firefox/perfil fisat" "$(readlink -f "$HOME/.pki/nssdb")"
chk U17_userjs 1 "$(lineas "$HOME/.mozilla/firefox/perfil fisat/user.js" "$UJ1")"
chk U17_handlers '/usr/bin/autofirma' "$(jq -r '.schemes.afirma.handlers[0].path' "$HOME/.mozilla/firefox/perfil fisat/handlers.json")"

# U18_contrasena_maestra: se omite con aviso y --check lo cuenta como bien
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno pw
mk_nss "$R/uno"
certutil -N -d "sql:$R/pw" -f "$T/c7.pw"
chk U18_rc 0 "$(rc "$S" --quiet)"
chk U18_aviso 1 "$(grep -c "\[aviso\] omitido $R/pw" "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"
chk U18_check 0 "$(rc "$S" --check)"
chk U18_check_linea "omitido $R/pw: contraseña maestra" "$("$S" --check)"

# U19_dedup_enlace: un perfil alcanzable por dos rutas se trata una vez
nuevo_caso
mk_ff "$HOME/.mozilla/firefox" uno
mk_nss "$HOME/.mozilla/firefox/uno"
mkdir -p "$HOME/.config/mozilla"
ln -s ../../.mozilla/firefox "$HOME/.config/mozilla/firefox"
"$S" --quiet
chk U19_una_linea 1 "$(grep -c '\[info\] certificado instalado' "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"
chk U19_userjs_una_vez 1 "$(grep -c '\[info\] .*user.js' "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"

# --- ~/.pki/nssdb (pki_sync) ---

# U2_skel_recien_copiado: el enlace relativo del skel ya apunta al perfil activo y no se reescribe
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ini "$R" "$INI_SKEL"
mk_nss "$R/default-esr"
mkdir -p "$HOME/.pki"
ln -s ../.mozilla/firefox/default-esr "$HOME/.pki/nssdb"
chk U2_rc 0 "$(rc "$S" --quiet)"
chk U2_flags "C,," "$(confianza "$R/default-esr" SocketAutoFirma)"
chk U2_der igual "$(der "$R/default-esr" SocketAutoFirma "$T/A.cer")"
chk U2_enlace_relativo ../.mozilla/firefox/default-esr "$(readlink "$HOME/.pki/nssdb")"
chk U2_check 0 "$(rc "$S" --check)"

# U2b_ausente: se crea ~/.pki y el enlace al perfil activo
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ini "$R" "$INI_SKEL"
mk_nss "$R/default-esr"
chk U2b_rc 0 "$(rc "$S" --quiet)"
chk U2b_enlace 1 "$([ -L "$HOME/.pki/nssdb" ] && echo 1 || echo 0)"
chk U2b_destino "$(realpath "$R/default-esr")" "$(readlink -f "$HOME/.pki/nssdb")"

# U2c_activo_sin_cert9: sin cert9.db en el perfil activo no se crea el enlace
nuevo_caso
mk_ini "$HOME/.mozilla/firefox" "$INI_SKEL"
mkdir -p "$HOME/.mozilla/firefox/default-esr"
chk U2c_rc 0 "$(rc "$S" --quiet)"
chk U2c_sin_pki 0 "$(existe "$HOME/.pki")"

# U3_pki_directorio_real: nunca se borra ni se mueve; recibe el certificado y conserva el del usuario
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
mk_nss "$R/uno"
P=$HOME/.pki/nssdb
mk_nss "$P"
head -c 64 /dev/urandom >"$T/ruido"
certutil -S -d "sql:$P" -n "Usuario Prueba" -s "CN=Usuario Prueba" -x -t "u,u,u" -k rsa -g 2048 -z "$T/ruido" -f /dev/null </dev/null >/dev/null 2>&1
chk U3_rc 0 "$(rc "$S" --quiet)"
chk U3_sigue_directorio 1 "$(es_dir_real "$P")"
chk U3_cert_usuario 1 "$(entradas "$P" "Usuario Prueba")"
chk U3_clave_usuario 1 "$(certutil -K -d "sql:$P" -f /dev/null 2>/dev/null | grep -c 'Usuario Prueba' || true)"
chk U3_socket igual "$(der "$P" SocketAutoFirma "$T/A.cer")"
chk U3_socket_flags "C,," "$(confianza "$P" SocketAutoFirma)"
chk U3_perfil igual "$(der "$R/uno" SocketAutoFirma "$T/A.cer")"
chk U3_log 1 "$(grep -c '\[aviso\] no se enlaza: directorio real' "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"
chk U3_check 0 "$(rc "$S" --check)"

# U4_pki_colgante: se repunta al perfil activo
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
mk_nss "$R/uno"
mkdir -p "$HOME/.pki"
ln -s "$R/borrado" "$HOME/.pki/nssdb"
chk U4_check 1 "$(rc "$S" --check)"
chk U4_rc 0 "$(rc "$S" --quiet)"
chk U4_enlace 1 "$([ -L "$HOME/.pki/nssdb" ] && echo 1 || echo 0)"
chk U4_destino "$(realpath "$R/uno")" "$(readlink -f "$HOME/.pki/nssdb")"
chk U4_sin_temporales 1 "$(find "$HOME/.pki" -mindepth 1 | wc -l)"
chk U4_check_tras_arreglo 0 "$(rc "$S" --check)"

# U5_pki_otro_perfil_y_ajeno: otro perfil Firefox se repunta; algo ajeno se deja y se registra
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno dos
mk_nss "$R/uno"
mk_nss "$R/dos"
mkdir -p "$HOME/.pki"
ln -s "$R/dos" "$HOME/.pki/nssdb"
chk U5_otro_rc 0 "$(rc "$S" --quiet)"
chk U5_otro_destino "$(realpath "$R/uno")" "$(readlink -f "$HOME/.pki/nssdb")"

nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
mk_nss "$R/uno"
mk_nss "$T/otra"
mkdir -p "$HOME/.pki"
ln -s "$T/otra" "$HOME/.pki/nssdb"
h1=$(huella "$T/otra")
chk U5_ajeno_rc 0 "$(rc "$S" --quiet)"
chk U5_ajeno_igual "$T/otra" "$(readlink "$HOME/.pki/nssdb")"
chk U5_ajeno_intacto "$h1" "$(huella "$T/otra")"
chk U5_ajeno_log 1 "$(grep -c "\[aviso\] .*$T/otra" "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"
chk U5_ajeno_check 0 "$(rc "$S" --check)"

# --- user.js (userjs_sync) ---

# U6_userjs
nuevo_caso
P=$HOME/perfil
mkdir -p "$P"
fn userjs_sync "$P" 2>/dev/null
chk U6_ausente "$UJ1
$UJ2" "$(cat "$P/user.js")"

printf '%s\n' 'user_pref("browser.startup.homepage", "https://example.org");' '// comentario' >"$P/user.js"
chmod 640 "$P/user.js"
fn userjs_sync "$P" 2>/dev/null
chk U6_previo 'user_pref("browser.startup.homepage", "https://example.org");
// comentario
'"$UJ1
$UJ2" "$(cat "$P/user.js")"
chk U6_modo 640 "$(stat -c %a "$P/user.js")"

printf '%s\n' 'user_pref("network.protocol-handler.expose.afirma", false);' 'user_pref("a", 1);' "$UJ2" >"$P/user.js"
fn userjs_sync "$P" 2>/dev/null
chk U6_valor_falso 'user_pref("a", 1);'"
$UJ1
$UJ2" "$(cat "$P/user.js")"

printf '%s\n' "$UJ1" "$UJ2" "$UJ1" 'user_pref("b", 2);' "$UJ2" 'user_pref("security.external_protocol_requires_permission", true);' >"$P/user.js"
fn userjs_sync "$P" 2>/dev/null
chk U6_duplicadas_uj1 1 "$(lineas "$P/user.js" "$UJ1")"
chk U6_duplicadas_uj2 1 "$(lineas "$P/user.js" "$UJ2")"
chk U6_duplicadas_sin_otro_valor 0 "$(grep -c 'requires_permission", true' "$P/user.js" || true)"
chk U6_duplicadas_conserva 1 "$(lineas "$P/user.js" 'user_pref("b", 2);')"

printf '%s' 'user_pref("c", 3);' >"$P/user.js"
fn userjs_sync "$P" 2>/dev/null
chk U6_sin_salto_final 'user_pref("c", 3);'"
$UJ1
$UJ2" "$(cat "$P/user.js")"
chk U6_acaba_en_salto 0a "$(tail -c 1 "$P/user.js" | od -An -tx1 | tr -d ' ')"

m1=$(mt "$P/user.js")
fn userjs_sync "$P" 2>/dev/null
chk U6_segunda_misma_mtime "$m1" "$(mt "$P/user.js")"

printf '%s\n' "$UJ2" 'user_pref("d", 4);' "$UJ1" >"$P/user.js"
m1=$(mt "$P/user.js")
fn userjs_sync "$P" 2>/dev/null
chk U6_bien_en_otro_orden "$m1" "$(mt "$P/user.js")"
chk U6_sin_temporales 1 "$(find "$P" -mindepth 1 | wc -l)"

# --- handlers.json (handlers_sync) ---

# U7_handlers
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
P=$R/uno
chk U7_rc 0 "$(rc "$S" --quiet)"
chk U7_ausente_afirma "$AFIRMA" "$(jq -c .schemes.afirma "$P/handlers.json")"
chk U7_ausente_base '{"defaultHandlersVersion":{},"mimeTypes":{},"schemes":{"afirma":'"$AFIRMA"'}}' "$(cat "$P/handlers.json")"

nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
P=$R/uno
printf '%s' "$HANDLERS_ESR" >"$P/handlers.json"
chmod 640 "$P/handlers.json"
"$S" --quiet
chk U7_afirma "$AFIRMA" "$(jq -c .schemes.afirma "$P/handlers.json")"
chk U7_mimetypes "$(jq -c .mimeTypes <<<"$HANDLERS_ESR")" "$(jq -c .mimeTypes "$P/handlers.json")"
chk U7_mailto "$(jq -c .schemes.mailto <<<"$HANDLERS_ESR")" "$(jq -c .schemes.mailto "$P/handlers.json")"
chk U7_migrated false "$(jq -c .isDownloadsImprovementsAlreadyMigrated "$P/handlers.json")"
chk U7_compacto "$(jq -c . "$P/handlers.json")" "$(cat "$P/handlers.json")"
chk U7_modo 640 "$(stat -c %a "$P/handlers.json")"
m1=$(mt "$P/handlers.json")
"$S" --quiet
chk U7_segunda_misma_mtime "$m1" "$(mt "$P/handlers.json")"
chk U7_sin_temporales 0 "$(find "$P" -name '.handlers*' | wc -l)"

printf '{"schemes":' >"$P/handlers.json"
h1=$(sha "$P/handlers.json")
chk U7_invalido_rc 0 "$(rc "$S" --quiet)"
chk U7_invalido_intacto "$h1" "$(sha "$P/handlers.json")"
chk U7_invalido_log 1 "$(grep -c "\[error\] .*$P/handlers.json" "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"

# U8_perfil_abierto: con Firefox abierto no se escribe handlers.json; user.js sí
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
P=$R/uno
printf '%s' "$HANDLERS_ESR" >"$P/handlers.json"
sleep 60 &
vivo=$!
ln -s "127.0.1.1:+$vivo" "$P/lock"
h1=$(sha "$P/handlers.json")
chk U8_abierto_rc 0 "$(rc "$S" --quiet)"
chk U8_abierto_handlers_igual "$h1" "$(sha "$P/handlers.json")"
chk U8_abierto_log 1 "$(grep -c '\[aviso\] pendiente: perfil abierto' "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"
chk U8_abierto_userjs 1 "$(lineas "$P/user.js" "$UJ1")"
chk U8_abierto_check 1 "$(rc "$S" --check)"
kill "$vivo"
wait "$vivo" 2>/dev/null || true
muerto=$(bash -c 'echo $$')
ln -sfn "127.0.1.1:+$muerto" "$P/lock"
chk U8_cerrado_rc 0 "$(rc "$S" --quiet)"
chk U8_cerrado_afirma "$AFIRMA" "$(jq -c .schemes.afirma "$P/handlers.json")"
chk U8_cerrado_userjs 1 "$(lineas "$P/user.js" "$UJ1")"
chk U8_cerrado_check 0 "$(rc "$S" --check)"

# --- Almacén XDG de Chrome ---

# clave <db> <apodo>: claves privadas de ese apodo exacto
clave() { certutil -K -d "sql:$1" -f /dev/null 2>/dev/null | APODO=":$2" awk '{ n = ENVIRON["APODO"] } length($0) >= length(n) && substr($0, length($0) - length(n) + 1) == n' | wc -l; }
# Mismo sujeto que A y B y otro DER: se admiten varios con el mismo apodo
mk_cer "$T/B2.cer"
mk_cer "$T/L.cer"

# X1_xdg_recibe_ca: sin Firefox, el almacén XDG recibe la CA y no se crea ~/.pki
nuevo_caso
XD=$HOME/.local/share/pki/nssdb
mk_nss "$XD"
chk X1_rc 0 "$(rc "$S" --quiet)"
chk X1_xdg_recibe_ca igual "$(der "$XD" SocketAutoFirma "$T/A.cer")"
chk X1_flags "C,," "$(confianza "$XD" SocketAutoFirma)"
chk X1_sin_pki 0 "$(existe "$HOME/.pki")"

# X2_xdg_data_home: con XDG_DATA_HOME absoluto se usa ese y no el de ~/.local/share
nuevo_caso
export XDG_DATA_HOME=$T/datos
mk_nss "$T/datos/pki/nssdb"
mk_nss "$XD"
chk X2_rc 0 "$(rc "$S" --quiet)"
chk X2_xdg_data_home igual "$(der "$T/datos/pki/nssdb" SocketAutoFirma "$T/A.cer")"
chk X2_local_share_intacto 0 "$(entradas "$XD" SocketAutoFirma)"

# X3_xdg_relativo: un XDG_DATA_HOME relativo se ignora
nuevo_caso
export XDG_DATA_HOME=datos
mk_nss "$XD"
chk X3_rc 0 "$(rc "$S" --quiet)"
chk X3_xdg_relativo igual "$(der "$XD" SocketAutoFirma "$T/A.cer")"

# X4_xdg_sin_cert9: un directorio sin almacén no se toca
nuevo_caso
mkdir -p "$XD"
chk X4_rc 0 "$(rc "$S" --quiet)"
chk X4_xdg_sin_cert9 0 "$(find "$XD" -mindepth 1 | wc -l)"

# X5_xdg_duplicados: varias SocketAutoFirma y el apodo antiguo quedan en una sola CA correcta
nuevo_caso
mk_nss "$XD"
for c in B B2 A; do certutil -A -d "sql:$XD" -n SocketAutoFirma -t "C,," -i "$T/$c.cer"; done
certutil -A -d "sql:$XD" -n "Autofirma ROOT" -t "CT,C," -i "$T/L.cer"
chk X5_antes_socket 3 "$(entradas "$XD" SocketAutoFirma)"
chk X5_antes_antiguo 1 "$(entradas "$XD" "Autofirma ROOT")"
chk X5_rc 0 "$(rc "$S" --quiet)"
chk X5_una_entrada 1 "$(entradas "$XD" SocketAutoFirma)"
chk X5_xdg_duplicados igual "$(der "$XD" SocketAutoFirma "$T/A.cer")"
chk X5_sin_antiguo 0 "$(entradas "$XD" "Autofirma ROOT")"

# X6_xdg_check: --check informa del almacén XDG pendiente
nuevo_caso
mk_nss "$XD"
chk X6_check_pendiente 1 "$(rc "$S" --check)"
"$S" --check >"$T/x6.out" || true
chk X6_xdg_check 1 "$(lineas "$T/x6.out" "pendiente $XD")"
"$S" --quiet
chk X6_check_tras_arreglo 0 "$(rc "$S" --check)"

# X7_xdg_enlace_a_perfil: el XDG que apunta al perfil se trata una sola vez
nuevo_caso
mk_ff "$HOME/.mozilla/firefox" uno
mk_nss "$HOME/.mozilla/firefox/uno"
mkdir -p "${XD%/*}"
ln -s "$HOME/.mozilla/firefox/uno" "$XD"
chk X7_rc 0 "$(rc "$S" --quiet)"
chk X7_xdg_enlace_a_perfil 1 "$(grep -c '\[info\] certificado instalado' "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"

# X8_xdg_contrasena: con contraseña maestra se omite con aviso
nuevo_caso
mkdir -p "$XD"
certutil -N -d "sql:$XD" -f "$T/c7.pw"
chk X8_rc 0 "$(rc "$S" --quiet)"
chk X8_no_importado 0 "$(entradas "$XD" SocketAutoFirma)"
chk X8_xdg_contrasena 1 "$(grep -cF "[aviso] omitido $XD: contraseña maestra" "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"

# X9_invariante_sync: la sincronización solo toca SocketAutoFirma; certificados personales y claves intactos
nuevo_caso
mk_nss "$XD"
mk_personal "$XD"
mk_personal "$XD" "SocketAutoFirma copia"
certutil -A -d "sql:$XD" -n SocketAutoFirma2 -t "C,," -i "$T/X.cer"
for c in B B2; do certutil -A -d "sql:$XD" -n SocketAutoFirma -t "C,," -i "$T/$c.cer"; done
chk X9_antes_personal 1 "$(entradas "$XD" "Usuario Prueba")"
chk X9_antes_personal_clave 1 "$(clave "$XD" "Usuario Prueba")"
chk X9_antes_copia 1 "$(entradas "$XD" "SocketAutoFirma copia")"
chk X9_antes_copia_clave 1 "$(clave "$XD" "SocketAutoFirma copia")"
chk X9_antes_socket 2 "$(entradas "$XD" SocketAutoFirma)"
certutil -L -d "sql:$XD" >"$T/x9.antes.L"
certutil -K -d "sql:$XD" -f /dev/null >"$T/x9.antes.K" 2>/dev/null
chk X9_rc 0 "$(rc "$S" --quiet)"
certutil -L -d "sql:$XD" >"$T/x9.despues.L"
certutil -K -d "sql:$XD" -f /dev/null >"$T/x9.despues.K" 2>/dev/null
chk X9_personal 1 "$(entradas "$XD" "Usuario Prueba")"
chk X9_personal_clave 1 "$(clave "$XD" "Usuario Prueba")"
chk X9_copia 1 "$(entradas "$XD" "SocketAutoFirma copia")"
chk X9_copia_clave 1 "$(clave "$XD" "SocketAutoFirma copia")"
chk X9_prefijo 1 "$(entradas "$XD" SocketAutoFirma2)"
chk X9_prefijo_intacto igual "$(der "$XD" SocketAutoFirma2 "$T/X.cer")"
chk X9_socket 1 "$(entradas "$XD" SocketAutoFirma)"
chk X9_invariante_sync igual "$(der "$XD" SocketAutoFirma "$T/A.cer")"
chk X9_claves_iguales "$(cat "$T/x9.antes.K")" "$(cat "$T/x9.despues.K")"

# X10_borrar_acotado: con más de 5 duplicados nss_borrar para y devuelve error, sin falso éxito
mk_nss "$T/x10"
for i in 1 2 3 4 5 6; do
	mk_cer "$T/x10.$i.cer"
	certutil -A -d "sql:$T/x10" -n SocketAutoFirma -t "C,," -i "$T/x10.$i.cer"
done
chk X10_antes 6 "$(entradas "$T/x10" SocketAutoFirma)"
chk X10_rc 2 "$(rc fn nss_borrar "$T/x10")"
chk X10_queda_una 1 "$(entradas "$T/x10" SocketAutoFirma)"
chk X10_vacia_rc 0 "$(rc fn nss_borrar "$T/c8")"

# --- Retirada (--remove) ---

# R0_opciones
chk R0_remove_y_check 64 "$(rc "$S" --remove --check)"
chk R0_help 0 "$(rc "$S" --help)"
chk R0_help_remove 1 "$("$S" --help | grep -qF -- '--remove' && echo 1 || echo 0)"

# llenar_r <db>: personales con clave, apodo con prefijo, dos SocketAutoFirma y el apodo antiguo
llenar_r() {
	mk_personal "$1"
	mk_personal "$1" "SocketAutoFirma copia"
	certutil -A -d "sql:$1" -n SocketAutoFirma2 -t "C,," -i "$T/X.cer"
	certutil -A -d "sql:$1" -n SocketAutoFirma -t "C,," -i "$T/A.cer"
	certutil -A -d "sql:$1" -n SocketAutoFirma -t "C,," -i "$T/B.cer"
	certutil -A -d "sql:$1" -n "Autofirma ROOT" -t "CT,C," -i "$T/L.cer"
}

# R1_invariante_remove: en perfil Firefox, ~/.pki/nssdb real y XDG solo se retiran SocketAutoFirma y el apodo antiguo
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
declare -A R1_DB=([perfil]="$R/uno" [pki]="$HOME/.pki/nssdb" [xdg]="$XD")
for e in perfil pki xdg; do
	db=${R1_DB[$e]}
	mk_nss "$db"
	llenar_r "$db"
	chk "R1_${e}_antes_socket" 2 "$(entradas "$db" SocketAutoFirma)"
	chk "R1_${e}_antes_antiguo" 1 "$(entradas "$db" "Autofirma ROOT")"
	chk "R1_${e}_antes_claves" 2 "$(($(clave "$db" "Usuario Prueba") + $(clave "$db" "SocketAutoFirma copia")))"
	certutil -K -d "sql:$db" -f /dev/null >"$T/r1.$e.antes.K" 2>/dev/null
done
chk R1_rc 0 "$(rc "$S" --remove --quiet)"
for e in perfil pki xdg; do
	db=${R1_DB[$e]}
	certutil -K -d "sql:$db" -f /dev/null >"$T/r1.$e.despues.K" 2>/dev/null
	chk "R1_${e}_sin_socket" 0 "$(entradas "$db" SocketAutoFirma)"
	chk "R1_${e}_sin_antiguo" 0 "$(entradas "$db" "Autofirma ROOT")"
	chk "R1_${e}_personal" 1 "$(entradas "$db" "Usuario Prueba")"
	chk "R1_${e}_personal_clave" 1 "$(clave "$db" "Usuario Prueba")"
	chk "R1_${e}_copia" 1 "$(entradas "$db" "SocketAutoFirma copia")"
	chk "R1_${e}_copia_clave" 1 "$(clave "$db" "SocketAutoFirma copia")"
	chk "R1_${e}_prefijo" 1 "$(entradas "$db" SocketAutoFirma2)"
	chk "R1_${e}_claves_iguales" "$(cat "$T/r1.$e.antes.K")" "$(cat "$T/r1.$e.despues.K")"
done
chk R1_sigue_dir_real 1 "$(es_dir_real "$HOME/.pki/nssdb")"
chk R1_log_xdg 1 "$(grep -cF "[info] certificado retirado de $XD" "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"
chk R1_invariante_remove_check 1 "$(rc "$S" --check)"

# R2_no_crea_nada: home vacío
nuevo_caso
chk R2_rc 0 "$(rc "$S" --remove --quiet)"
chk R2_no_crea_nada 0 "$(find "$HOME" -mindepth 1 | wc -l)"

# R3_sin_cer: --remove no necesita el certificado
nuevo_caso
mk_nss "$XD"
certutil -A -d "sql:$XD" -n SocketAutoFirma -t "C,," -i "$T/A.cer"
export AUTOFIRMA_FISAT_CER=$T/no-existe
chk R3_rc 0 "$(rc "$S" --remove --quiet)"
chk R3_sin_cer 0 "$(entradas "$XD" SocketAutoFirma)"

# R4_contrasena: con contraseña maestra no se toca y se avisa
nuevo_caso
mk_nss "$XD"
certutil -A -d "sql:$XD" -n SocketAutoFirma -t "C,," -i "$T/A.cer"
printf '\n' >"$T/vacia.pw"
certutil -W -d "sql:$XD" -f "$T/vacia.pw" -@ "$T/c7.pw" </dev/null >/dev/null 2>&1
chk R4_tiene_contrasena 0 "$(rc fn nss_contrasena "$XD")"
chk R4_rc 0 "$(rc "$S" --remove --quiet)"
chk R4_sigue 1 "$(entradas "$XD" SocketAutoFirma)"
chk R4_contrasena 1 "$(grep -cF "[aviso] omitido $XD: contraseña maestra" "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"

# R5_no_toca_enlaces_ni_prefs: retira la CA pero deja el enlace, user.js y handlers.json
nuevo_caso
R=$HOME/.mozilla/firefox
mk_ff "$R" uno
mk_nss "$R/uno"
"$S" --quiet
l1=$(readlink "$HOME/.pki/nssdb")
u1=$(sha "$R/uno/user.js")
h1=$(sha "$R/uno/handlers.json")
chk R5_rc 0 "$(rc "$S" --remove --quiet)"
chk R5_retirada 0 "$(entradas "$R/uno" SocketAutoFirma)"
chk R5_enlace "$l1" "$(readlink "$HOME/.pki/nssdb")"
chk R5_userjs "$u1" "$(sha "$R/uno/user.js")"
chk R5_handlers "$h1" "$(sha "$R/uno/handlers.json")"

# R6_enlace_ajeno: ~/.pki/nssdb hacia un NSS fuera de Firefox no se toca
nuevo_caso
mk_ff "$HOME/.mozilla/firefox" uno
mk_nss "$HOME/.mozilla/firefox/uno"
mk_nss "$T/r6"
certutil -A -d "sql:$T/r6" -n SocketAutoFirma -t "C,," -i "$T/A.cer"
mkdir -p "$HOME/.pki"
ln -s "$T/r6" "$HOME/.pki/nssdb"
chk R6_rc 0 "$(rc "$S" --remove --quiet)"
chk R6_enlace_ajeno 1 "$(entradas "$T/r6" SocketAutoFirma)"
chk R6_enlace_igual "$T/r6" "$(readlink "$HOME/.pki/nssdb")"

fin
