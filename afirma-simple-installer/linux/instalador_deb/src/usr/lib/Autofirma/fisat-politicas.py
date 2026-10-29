#!/usr/bin/python3 -I
"""Fusiona o quita ExtensionSettings[<id>] en un policies.json sin tocar el resto."""
import json
import os
import sys
import tempfile

USO = ("uso: fisat-politicas.py add <policies.json> <id> <ruta-xpi> | "
       "remove <policies.json> <id> [--delete-if-empty]")


def die(codigo, msg):
    print("fisat-politicas: " + msg, file=sys.stderr)
    sys.exit(codigo)


def cargar(ruta):
    """Devuelve el JSON del fichero, o None si no existe; sale con 2 si no es válido."""
    if not os.path.exists(ruta):
        return None
    try:
        with open(ruta, encoding="utf-8") as f:
            datos = json.load(f)
    except (OSError, ValueError) as e:
        die(2, "%s ilegible o JSON inválido: %s" % (ruta, e))
    if not isinstance(datos, dict) or not isinstance(datos.get("policies", {}), dict):
        die(2, '%s: estructura inesperada (se esperaba {"policies":{...}})' % ruta)
    return datos


def guardar(ruta, datos, existia):
    """Escritura atómica: temporal en el mismo directorio y os.replace sobre el destino real."""
    real = os.path.realpath(ruta)
    tmp = None
    try:
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(real), prefix=".policies.", suffix=".tmp")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(datos, f, indent=2, ensure_ascii=False)
            f.write("\n")
        if existia:
            st = os.stat(real)
            os.chmod(tmp, st.st_mode & 0o7777)
            try:
                os.chown(tmp, st.st_uid, st.st_gid)
            except PermissionError:
                pass
        else:
            os.chmod(tmp, 0o644)
        os.replace(tmp, real)
    except OSError as e:
        if tmp is not None:
            try:
                os.unlink(tmp)
            except OSError:
                pass
        die(3, "no se pudo escribir %s: %s" % (ruta, e))


def main(argv):
    if len(argv) < 4 or argv[1] not in ("add", "remove"):
        die(64, USO)
    op, ruta, ext = argv[1:4]
    resto = argv[4:]
    if op == "add" and len(resto) != 1:
        die(64, USO)
    if op == "remove" and resto not in ([], ["--delete-if-empty"]):
        die(64, USO)

    datos = cargar(ruta)
    existia = datos is not None
    if datos is None:
        datos = {}
    pol = datos.get("policies", {})
    es = pol.get("ExtensionSettings", {})
    if not isinstance(es, dict):
        die(2, "%s: ExtensionSettings no es un objeto" % ruta)

    if op == "add":
        quiero = {"installation_mode": "force_installed", "install_url": "file://" + resto[0]}
        if es.get(ext) == quiero:
            print("sin cambios")
            return 0
        es[ext] = quiero
        pol["ExtensionSettings"] = es
        datos["policies"] = pol
    else:
        if ext not in es:
            print("sin cambios")
            return 0
        del es[ext]
        if es:
            pol["ExtensionSettings"] = es
        else:
            pol.pop("ExtensionSettings", None)
        datos["policies"] = pol
        if datos == {"policies": {}} and resto:
            try:
                os.unlink(os.path.realpath(ruta))
            except OSError as e:
                die(3, "no se pudo borrar %s: %s" % (ruta, e))
            print("fichero eliminado")
            return 0
    guardar(ruta, datos, existia)
    print("cambiado")
    return 0


sys.exit(main(sys.argv))
