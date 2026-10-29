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
# nuevo_caso: HOME, estado y runtime vacíos
nuevo_caso() {
	rm -rf "${T:?}/home" "${T:?}/state" "${T:?}/run"
	mkdir -p "$T/home" "$T/state" "$T/run"
	export HOME=$T/home XDG_STATE_HOME=$T/state XDG_RUNTIME_DIR=$T/run AUTOFIRMA_FISAT_CER=$T/A.cer
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

mk_ini "$T/p1" "[Profile0]
Name=fisat
IsRelative=1
Default=1
Path=default-esr

[General]
StartWithLastProfile=1

[Install3B6073811A6ABF12]
Default=default-esr
Locked=1"
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
chk U11_check_linea "pendiente $R/uno" "$("$S" --check || true)"
chk U11_home_igual "$h1" "$(huella "$HOME")"
chk U11_sin_log 0 "$(find "$XDG_STATE_HOME" -mindepth 1 | wc -l)"
"$S" --quiet
chk U11_check_tras_arreglo 0 "$(rc "$S" --check)"

# U12_quiet
nuevo_caso
mk_ff "$HOME/.mozilla/firefox" uno
mk_nss "$HOME/.mozilla/firefox/uno"
"$S" --quiet >"$T/u12.out" 2>"$T/u12.err"
chk U12_stdout_vacio 0 "$(stat -c%s "$T/u12.out")"
chk U12_stderr_vacio 0 "$(stat -c%s "$T/u12.err")"
chk U12_instalado igual "$(der "$HOME/.mozilla/firefox/uno" SocketAutoFirma "$T/A.cer")"
chk U12_log 1 "$(grep -c '\[info\]' "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"

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
chk U15_log_nuevo 1 "$(wc -l <"$L")"
chk U15_formato 1 "$(grep -cE '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{4} \[info\] ' "$L")"

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
chk U17_log 1 "$(grep -c "\[info\] .*$HOME/.mozilla/firefox/perfil fisat" "$HOME/.local/state/autofirma-fisat/usuario.log")"

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
chk U19_una_linea 1 "$(grep -c '\[info\]' "$XDG_STATE_HOME/autofirma-fisat/usuario.log")"

fin
