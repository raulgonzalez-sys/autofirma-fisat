#!/bin/bash
# Pruebas de fisat-retirar.sh: enumeración de usuarios, tolerancia a fallos y orden de ejecución como usuario.
set -euo pipefail

DEB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=lib.sh
. "$DEB/tests/lib.sh"

R=$DEB/src/usr/lib/Autofirma/fisat-retirar.sh

# fn <función> <args...>: ejecuta una función del script en un bash limpio (sin set -euo)
fn() { bash -c '. "$1"; shift; "$@"' fn "$R" "$@"; }

mkdir -p "$T/home/ana" "$T/otros/home/bea" "$T/srv/otro" "$T/vacio"
cat >"$T/passwd" <<PASSWD
root:x:0:0::/root:/bin/bash
daemon:x:1:1::/usr/sbin:/usr/sbin/nologin
nobody:x:65534:65534::/nonexistent:/usr/sbin/nologin
ana:x:1500:1500::$T/home/ana:/bin/bash
luis:x:1501:1501::$T/home/no-existe:/bin/bash
ana2:x:1502:1502::$T/home/ana:/bin/bash
otro:x:1503:1503::$T/srv/otro:/bin/bash
PASSWD

# --- candidatos: reglas de passwd ---
chk candidatos_passwd "$(printf 'ana\t%s/home/ana' "$T")" "$(fn candidatos "$T/vacio" <"$T/passwd")"

# --- candidatos: directorios de la base ---
mkdir -p "$T/home/pepe"
ln -s pepe "$T/home/alias"
if [ "$(id -u)" -ge 1000 ] && [ "$(id -u)" -ne 65534 ]; then
	esperado=$(printf 'ana\t%s/home/ana\n%s\t%s/home/pepe' "$T" "$(id -un)" "$T")
	chk candidatos_base "$esperado" "$(fn candidatos "$T/home" <"$T/passwd")"
else
	echo "OK candidatos_base (omitido: uid < 1000)"
	PASA=$((PASA + 1))
fi

# --- leer_passwd: getent y respaldo ---
mkdir -p "$T/bin-falla" "$T/bin-fake"
printf '#!/bin/sh\nexit 1\n' >"$T/bin-falla/getent"
printf '#!/bin/sh\necho "fake:x:1000:1000::/home/fake:/bin/bash"\n' >"$T/bin-fake/getent"
chmod +x "$T/bin-falla/getent" "$T/bin-fake/getent"
chk leer_passwd_getent "fake:x:1000:1000::/home/fake:/bin/bash" "$(PATH=$T/bin-fake:$PATH fn leer_passwd)"
chk leer_passwd_respaldo "$(cat /etc/passwd)" "$(PATH=$T/bin-falla:$PATH fn leer_passwd)"

# --- main: sale 0 aunque falle un usuario y los intenta todos ---
cat >"$T/main.sh" <<'MAIN'
. "$1"
leer_passwd() { cat "$PASSWD_FALSO"; }
como_usuario() { echo "$1 $2" >>"$LLAMADAS"; [ "$1" != "$USUARIO_FALLA" ]; }
main
MAIN
cat >"$T/passwd2" <<PASSWD
ana:x:1500:1500::$T/home/ana:/bin/bash
bea:x:1501:1501::$T/otros/home/bea:/bin/bash
PASSWD
export PASSWD_FALSO=$T/passwd2 LLAMADAS=$T/llamadas AUTOFIRMA_FISAT_HOMES=$T/vacio
: >"$LLAMADAS"
rc=0
USUARIO_FALLA=ana bash "$T/main.sh" "$R" 2>"$T/stderr" || rc=$?
chk main_rc_con_fallo 0 "$rc"
chk main_intenta_todos "$(printf 'ana %s/home/ana\nbea %s/otros/home/bea' "$T" "$T")" "$(cat "$LLAMADAS")"
chk main_aviso_por_stderr 1 "$(grep -c 'ana' "$T/stderr" || true)"
: >"$LLAMADAS"
rc=0
USUARIO_FALLA=ana bash -c '. "$1"; leer_passwd() { cat "$PASSWD_FALSO"; }; como_usuario() { return 1; }; main' x "$R" 2>"$T/stderr" || rc=$?
chk main_rc_todos_fallan 0 "$rc"

# --- como_usuario con un runuser falso: argumentos y entorno ---
mkdir -p "$T/bin-runuser"
cat >"$T/bin-runuser/runuser" <<'SHIM'
#!/bin/sh
printf '%s\n' "$@" >"$LLAMADAS"
SHIM
chmod +x "$T/bin-runuser/runuser"
rt=
[ -d "/run/user/$(id -u)" ] && rt=/run/user/$(id -u)
: >"$LLAMADAS"
AUTOFIRMA_FISAT_USUARIO=/falso/fisat-usuario.sh PATH=$T/bin-runuser:$PATH fn como_usuario "$(id -un)" "$T/home/ana"
esperado=$(printf '%s\n' -u "$(id -un)" -- env -u XDG_DATA_HOME -u XDG_STATE_HOME -u XDG_CONFIG_HOME "HOME=$T/home/ana" "XDG_RUNTIME_DIR=$rt" /falso/fisat-usuario.sh --remove --quiet)
chk como_usuario_argumentos "$esperado" "$(cat "$LLAMADAS")"

# --- el script real usa runuser, el entorno limpio y timeout ---
for t in 'runuser -u' '--remove --quiet' 'env -u XDG_DATA_HOME' 'timeout 30'; do
	chk "texto_${t// /_}" 1 "$(grep -cF -- "$t" "$R" || true)"
done
! grep -qE '^set -[a-z]*e' "$R" || { echo "FALLO sin_set_e"; FALLA=$((FALLA + 1)); }

fin
