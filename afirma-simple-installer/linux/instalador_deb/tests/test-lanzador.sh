#!/bin/bash
# Pruebas del lanzador usr/bin/autofirma con un java falso en PATH y pasos por usuario falsos.
set -euo pipefail

DEB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=lib.sh
. "$DEB/tests/lib.sh"

L=$DEB/src/usr/bin/autofirma
mkdir -p "$T/bin"
# java falso: vuelca un argumento por línea, el locale recibido y si la marca del paso por usuario ya existía
cat > "$T/bin/java" <<'J'
#!/bin/bash
printf '%s\n' "$@" > "$AF_SALIDA"
printf 'LC_ALL=%s\nLC_CTYPE=%s\nLANG=%s\n' "${LC_ALL-<sin definir>}" "${LC_CTYPE-<sin definir>}" "${LANG-<sin definir>}" > "$AF_SALIDA.env"
[ -e "$AF_MARCA" ] && echo si > "$AF_SALIDA.marca" || echo no > "$AF_SALIDA.marca"
J
chmod +x "$T/bin/java"

# lanzar <usuario>: ejecuta el lanzador con una URL con espacio; stdout y stderr a ficheros
lanzar() {
	rm -f "$T/salida" "$T/salida.marca" "$T/marca"
	AF_SALIDA=$T/salida AF_MARCA=$T/marca PATH="$T/bin:$PATH" \
		AUTOFIRMA_FISAT_USUARIO=$1 "$L" 'afirma://sign?a=b c' >"$T/stdout" 2>&1 </dev/null || true
}
# fake <nombre> <cuerpo>: script de usuario falso ejecutable
fake() {
	printf '#!/bin/bash\n%s\n' "$2" > "$T/$1"
	chmod +x "$T/$1"
}

esperado=$(printf '%s\n' \
	-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel \
	-Dswing.systemlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel \
	-Dswing.crossplatformlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel \
	-Dawt.useSystemAAFontSettings=on \
	-Dswing.aatext=true \
	-Djdk.tls.maxHandshakeMessageSize=65536 \
	-jar /usr/lib/Autofirma/autofirma.jar \
	'afirma://sign?a=b c')

# L1: java recibe las seis -D, el jar y la URL como un solo argumento
fake ok.sh 'exit 0'
lanzar "$T/ok.sh"
chk L1_argumentos "$esperado" "$(cat "$T/salida" 2>/dev/null || true)"

# L2: el paso por usuario falla y java arranca igual
fake falla.sh 'exit 1'
lanzar "$T/falla.sh"
chk L2_usuario_falla "$esperado" "$(cat "$T/salida" 2>/dev/null || true)"

# L3: el paso por usuario se cuelga; con timeout de 1 s java arranca en menos de 3 s
fake cuelga.sh 'sleep 60'
rm -f "$T/salida"
t0=$(date +%s.%N)
AF_SALIDA=$T/salida AF_MARCA=$T/marca PATH="$T/bin:$PATH" AUTOFIRMA_FISAT_TIMEOUT=1 \
	AUTOFIRMA_FISAT_USUARIO=$T/cuelga.sh "$L" 'afirma://sign?a=b c' >"$T/stdout" 2>&1 </dev/null || true
t1=$(date +%s.%N)
chk L3_arranca "$esperado" "$(cat "$T/salida" 2>/dev/null || true)"
chk L3_rapido si "$(awk -v a="$t0" -v b="$t1" 'BEGIN {print (b - a < 3) ? "si" : "no"}')"

# L4: el script de usuario no existe y java arranca igual
lanzar "$T/no-existe.sh"
chk L4_sin_usuario "$esperado" "$(cat "$T/salida" 2>/dev/null || true)"

# L5: el paso por usuario es síncrono; su marca existe cuando arranca java
fake marca.sh "touch '$T/marca'"
lanzar "$T/marca.sh"
chk L5_orden si "$(cat "$T/salida.marca" 2>/dev/null || true)"

# El lanzador pasa --quiet al paso por usuario
fake args.sh "printf '%s\n' \"\$@\" > '$T/args-usuario'"
lanzar "$T/args.sh"
chk L6_quiet --quiet "$(cat "$T/args-usuario" 2>/dev/null || true)"

# lanzar_env <env...>: ejecuta el lanzador con el entorno que da env (java vuelca su locale en salida.env)
lanzar_env() {
	rm -f "$T/salida" "$T/salida.env"
	AF_SALIDA=$T/salida AF_MARCA=$T/marca PATH="$T/bin:$PATH" AUTOFIRMA_FISAT_USUARIO=$T/no-existe.sh \
		env "$@" "$L" 'afirma://sign?a=b c' >"$T/stdout" 2>&1 </dev/null || true
}
# locale_java <variable>: valor de la variable que recibió java
locale_java() {
	grep "^$1=" "$T/salida.env" 2>/dev/null | head -n 1 | cut -d= -f2- || true
}

# L7: sin locale, java recibe LC_CTYPE=C.UTF-8
lanzar_env -u LANG -u LC_ALL -u LC_CTYPE
chk L7_sin_locale C.UTF-8 "$(locale_java LC_CTYPE)"

# L8: LANG=C, java recibe LC_CTYPE=C.UTF-8 y LANG sigue siendo C
lanzar_env -u LC_ALL -u LC_CTYPE LANG=C
chk L8_locale_C C.UTF-8 "$(locale_java LC_CTYPE)"
chk L8_lang_intacto C "$(locale_java LANG)"

# L9: locale UTF-8, java no recibe LC_CTYPE y LANG sigue igual
lanzar_env -u LC_ALL -u LC_CTYPE LANG=es_ES.UTF-8
chk L9_utf8_intacto '<sin definir>' "$(locale_java LC_CTYPE)"
chk L9_lang_intacto es_ES.UTF-8 "$(locale_java LANG)"

# L10: LC_ALL no UTF-8 tiene prioridad sobre LC_CTYPE, java recibe LC_ALL=C.UTF-8
lanzar_env -u LC_CTYPE -u LANG LC_ALL=C
chk L10_lc_all_no_utf8 C.UTF-8 "$(locale_java LC_ALL)"

fin
