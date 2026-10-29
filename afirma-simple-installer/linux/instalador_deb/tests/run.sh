#!/bin/bash
# Ejecuta los tests de shell, shellcheck y py_compile del paquete; sale 1 si algo falla.
set -euo pipefail

DEB=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
fallos=0

for t in "$DEB"/tests/test-*.sh; do
	[ -e "$t" ] || continue
	echo "== $t"
	bash "$t" || { echo "FALLO test: $t"; fallos=$((fallos + 1)); }
done

# Scripts de shell a analizar: los .sh de DEB, los maintainer scripts y el lanzador
mapfile -t scripts < <(find "$DEB" -name '*.sh' -type f | sort)
for s in preinst postinst prerm postrm; do
	[ -f "$DEB/src/DEBIAN/$s" ] && scripts+=("$DEB/src/DEBIAN/$s")
done
[ -f "$DEB/src/usr/bin/autofirma" ] && scripts+=("$DEB/src/usr/bin/autofirma")

echo "== shellcheck"
for s in "${scripts[@]}"; do
	shellcheck -x -P SCRIPTDIR "$s" || { echo "FALLO shellcheck: $s"; fallos=$((fallos + 1)); }
done

echo "== py_compile"
while IFS= read -r p; do
	python3 -m py_compile "$p" || { echo "FALLO py_compile: $p"; fallos=$((fallos + 1)); }
	rm -rf "$(dirname "$p")/__pycache__"
done < <(find "$DEB" -name '*.py' -type f | sort)

if [ "$fallos" -ne 0 ]; then
	echo "run.sh: $fallos fallo(s)"
	exit 1
fi
echo "run.sh: todo correcto"
