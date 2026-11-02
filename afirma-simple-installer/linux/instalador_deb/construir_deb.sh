#!/bin/bash
# Construye el .deb versionado: construir_deb.sh <versión> <dir_salida> <autofirma.jar>
set -euo pipefail

if [ "$#" -ne 3 ]; then
	echo "Uso: $0 <versión> <dir_salida> <autofirma.jar>" >&2
	exit 2
fi

VERSION=$1
SALIDA=$2
JAR_APP=$3

if ! [[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+\+fisat[0-9]+$ ]]; then
	echo "Versión no válida '$VERSION': se espera X.Y.Z+fisatN" >&2
	exit 1
fi
[ -f "$JAR_APP" ] || { echo "No existe el JAR: $JAR_APP" >&2; exit 1; }

AQUI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
mkdir -p "$SALIDA"
SALIDA=$(cd "$SALIDA" && pwd)
JAR_APP=$(cd "$(dirname "$JAR_APP")" && pwd)/$(basename "$JAR_APP")

STAGING=$(mktemp -d)
trap 'rm -rf "$STAGING"' EXIT

cp -a "$AQUI/src/." "$STAGING/"
mkdir -p "$STAGING/usr/lib/Autofirma"
cp "$JAR_APP" "$STAGING/usr/lib/Autofirma/autofirma.jar"

# Permisos: directorios 755, ficheros 644 y 755 para los maintainer scripts y el lanzador
find "$STAGING" -type d -exec chmod 755 {} +
find "$STAGING" -type f -exec chmod 644 {} +
for s in preinst postinst prerm postrm; do
	[ -f "$STAGING/DEBIAN/$s" ] && chmod 755 "$STAGING/DEBIAN/$s"
done
find "$STAGING/usr/bin" -type f -exec chmod 755 {} +

# Version e Installed-Size en el control del staging
TAM=$(du -sk --exclude=DEBIAN "$STAGING" | cut -f1)
sed -i -e "s/^Version:.*/Version: $VERSION/" -e "s/^Installed-Size:.*/Installed-Size: $TAM/" "$STAGING/DEBIAN/control"
grep -q "^Version: $VERSION\$" "$STAGING/DEBIAN/control" || { echo "No se pudo fijar Version en control" >&2; exit 1; }
grep -q "^Installed-Size: $TAM\$" "$STAGING/DEBIAN/control" || { echo "No se pudo fijar Installed-Size en control" >&2; exit 1; }

DEB="autofirma_${VERSION}_all.deb"
dpkg-deb --root-owner-group -Zgzip --build "$STAGING" "$SALIDA/$DEB"
(cd "$SALIDA" && sha256sum "$DEB" > "$DEB.sha256")
echo "Generado $SALIDA/$DEB"
