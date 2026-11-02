#!/bin/bash
# Pruebas del lanzador usr/bin/autofirma con un java falso en PATH.
set -euo pipefail

DEB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=lib.sh
. "$DEB/tests/lib.sh"

L=$DEB/src/usr/bin/autofirma
mkdir -p "$T/bin"
# java falso: vuelca un argumento por línea y el locale recibido
cat > "$T/bin/java" <<'J'
#!/bin/bash
printf '%s\n' "$@" > "$AF_SALIDA"
printf 'LC_ALL=%s\nLC_CTYPE=%s\nLANG=%s\n' "${LC_ALL-<sin definir>}" "${LC_CTYPE-<sin definir>}" "${LANG-<sin definir>}" > "$AF_SALIDA.env"
J
chmod +x "$T/bin/java"

# lanzar: ejecuta el lanzador con una URL con espacio; stdout y stderr a ficheros
lanzar() {
	rm -f "$T/salida"
	AF_SALIDA=$T/salida PATH="$T/bin:$PATH" "$L" 'afirma://sign?a=b c' >"$T/stdout" 2>&1 </dev/null || true
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
lanzar
chk L1_argumentos "$esperado" "$(cat "$T/salida" 2>/dev/null || true)"

# L2: el lanzador no llama a ningún paso por usuario, aunque la variable antigua apunte a uno
printf '#!/bin/bash\ntouch "%s/marca"\n' "$T" > "$T/usuario.sh"
chmod +x "$T/usuario.sh"
rm -f "$T/marca"
AF_SALIDA=$T/salida PATH="$T/bin:$PATH" AUTOFIRMA_FISAT_USUARIO=$T/usuario.sh \
	"$L" 'afirma://sign?a=b c' >"$T/stdout" 2>&1 </dev/null || true
chk L2_java_arranca "$esperado" "$(cat "$T/salida" 2>/dev/null || true)"
chk L2_sin_paso_usuario no "$([ -e "$T/marca" ] && echo si || echo no)"

# L3: el texto del lanzador no menciona el paso por usuario ni su timeout
chk L3_sin_fisat_usuario 0 "$(grep -c 'fisat-usuario\|AUTOFIRMA_FISAT_USUARIO\|AUTOFIRMA_FISAT_TIMEOUT\|timeout' "$L" || true)"

# L4: restaurar va a RestoreCommandLinux con -cp (sin -jar, que cargaría SimpleAfirma) y conserva sus argumentos
opciones=$(sed -n '1,6p' <<<"$esperado")
esperado_restaurar=$(printf '%s\n' "$opciones" \
	-cp /usr/lib/Autofirma/autofirma.jar \
	es.gob.afirma.standalone.ui.restoreconfig.linux.RestoreCommandLinux \
	restaurar --usuario)
rm -f "$T/salida"
AF_SALIDA=$T/salida PATH="$T/bin:$PATH" "$L" restaurar --usuario >"$T/stdout" 2>&1 </dev/null || true
chk L4_restaurar_directo "$esperado_restaurar" "$(cat "$T/salida" 2>/dev/null || true)"

# L5: solo el primer argumento cuenta: una URL que contiene restaurar sigue yendo a la aplicación con -jar
rm -f "$T/salida"
AF_SALIDA=$T/salida PATH="$T/bin:$PATH" "$L" 'afirma://restaurar' >"$T/stdout" 2>&1 </dev/null || true
chk L5_restaurar_no_primero "-jar" "$(grep -x -- '-jar' "$T/salida" 2>/dev/null || true)"

# L6: restaurar también ajusta el locale (bajo pkexec no hay LANG)
rm -f "$T/salida" "$T/salida.env"
AF_SALIDA=$T/salida PATH="$T/bin:$PATH" env -u LANG -u LC_ALL -u LC_CTYPE "$L" restaurar --sistema >"$T/stdout" 2>&1 </dev/null || true
chk L6_restaurar_locale C.UTF-8 "$(grep '^LC_CTYPE=' "$T/salida.env" 2>/dev/null | cut -d= -f2- || true)"

# lanzar_env <env...>: ejecuta el lanzador con el entorno que da env (java vuelca su locale en salida.env)
lanzar_env() {
	rm -f "$T/salida" "$T/salida.env"
	AF_SALIDA=$T/salida PATH="$T/bin:$PATH" \
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
