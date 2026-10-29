#!/bin/bash
# Pruebas de fisat-politicas.py con ficheros temporales.
set -euo pipefail

DEB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=lib.sh
. "$DEB/tests/lib.sh"

H=$DEB/src/usr/lib/Autofirma/fisat-politicas.py
F=$T/policies.json
ID=autofirma-csp-fix@local
X=/usr/lib/Autofirma/autofirma-csp-fix.xpi

# rc <comando...>: imprime el código de salida sin abortar
rc() { local r=0; "$@" >/dev/null 2>&1 || r=$?; echo "$r"; }
# jv <fichero> <filtro jq>
jv() { jq -r "$2" "$1"; }

# Fichero ausente: se crea 644 con la clave de la extensión en la ruta pedida
chk crea_rc 0 "$(rc "$H" add "$F" "$ID" "$X")"
chk crea_url "file://$X" "$(jv "$F" ".policies.ExtensionSettings[\"$ID\"].install_url")"
chk crea_modo 644 "$(stat -c %a "$F")"

# Segunda llamada: sin cambios y sin reescribir (misma mtime)
m1=$(stat -c %Y.%N "$F")
sleep 1.1
chk idem_msg "sin cambios" "$("$H" add "$F" "$ID" "$X")"
chk idem_mtime "$m1" "$(stat -c %Y.%N "$F")"

# Fusión: conserva el resto de políticas, '*' y otras extensiones, y el modo 640
cat > "$F" <<J
{"policies":{"DisableTelemetry":true,"Preferences":{"a":{"Value":false,"Status":"locked"}},"ExtensionSettings":{"*":{"installation_mode":"allowed"},"otra@x":{"installation_mode":"blocked"}}}}
J
chmod 640 "$F"
"$H" add "$F" "$ID" "$X" >/dev/null
chk f_telemetria true "$(jv "$F" .policies.DisableTelemetry)"
chk f_pref false "$(jv "$F" .policies.Preferences.a.Value)"
chk f_otra blocked "$(jv "$F" '.policies.ExtensionSettings["otra@x"].installation_mode')"
chk f_star allowed "$(jv "$F" '.policies.ExtensionSettings["*"].installation_mode')"
chk f_modo_conservado 640 "$(stat -c %a "$F")"

# Ruta antigua de la XPI: se corrige a la del paquete
jq ".policies.ExtensionSettings[\"$ID\"].install_url=\"file:///usr/local/lib/autofirma-fix/autofirma-csp-fix.xpi\"" "$F" > "$F.n"
mv "$F.n" "$F"
"$H" add "$F" "$ID" "$X" >/dev/null
chk ruta_nueva "file://$X" "$(jv "$F" ".policies.ExtensionSettings[\"$ID\"].install_url")"

# remove deja el resto intacto
"$H" remove "$F" "$ID" >/dev/null
chk rm_clave null "$(jv "$F" ".policies.ExtensionSettings[\"$ID\"]")"
chk rm_resto true "$(jv "$F" .policies.DisableTelemetry)"
chk rm_otra blocked "$(jv "$F" '.policies.ExtensionSettings["otra@x"].installation_mode')"

# remove: si ExtensionSettings queda vacío desaparece
cat > "$F" <<J
{"policies":{"ExtensionSettings":{"$ID":{"installation_mode":"force_installed","install_url":"file://x"}},"DisablePocket":true}}
J
"$H" remove "$F" "$ID" >/dev/null
chk vacio_sin_es false "$(jv "$F" '.policies|has("ExtensionSettings")')"
chk vacio_resto true "$(jv "$F" .policies.DisablePocket)"

# --delete-if-empty borra el fichero que quedaría como {"policies":{}}
echo "{\"policies\":{\"ExtensionSettings\":{\"$ID\":{}}}}" > "$F"
"$H" remove "$F" "$ID" --delete-if-empty >/dev/null
chk borra_vacio 0 "$([ ! -e "$F" ] && echo 0 || echo 1)"

# remove sobre fichero ausente: éxito
chk rm_ausente 0 "$(rc "$H" remove "$T/no.json" "$ID")"

# JSON inválido: rc 2 y el fichero no se toca
printf '{"policies": {oops' > "$F"
s1=$(sha256sum < "$F")
chk json_malo_rc 2 "$(rc "$H" add "$F" "$ID" "$X")"
chk json_malo_intacto "$s1" "$(sha256sum < "$F")"

# Raíz o policies que no son objeto: rc 2
echo '[1]' > "$F"
chk raiz_lista 2 "$(rc "$H" add "$F" "$ID" "$X")"
echo '{"policies":[]}' > "$F"
chk policies_lista 2 "$(rc "$H" add "$F" "$ID" "$X")"

# Enlace simbólico: se conserva y se modifica el destino
echo '{"policies":{}}' > "$T/real.json"
ln -s real.json "$T/link.json"
"$H" add "$T/link.json" "$ID" "$X" >/dev/null
chk symlink_conservado 0 "$([ -L "$T/link.json" ] && echo 0 || echo 1)"
chk symlink_destino "file://$X" "$(jv "$T/real.json" ".policies.ExtensionSettings[\"$ID\"].install_url")"

# Sin temporales residuales
chk sin_tmp 0 "$(find "$T" -maxdepth 1 -name '.policies.*' | wc -l)"

# Sin argumentos: uso incorrecto
chk sin_args 64 "$(rc "$H")"

fin
