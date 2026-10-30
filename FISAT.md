# AutoFirma FISAT: guía del fork

Fork de `ctt-gob-es/clienteafirma` para Debian 13 + KDE Plasma 6.

## Ramas y sincronización con upstream

- `master` es espejo exacto de `upstream/master` (`ctt-gob-es/clienteafirma`);
  nunca se commitea en ella.
- `fisat` es la rama de trabajo y la rama por defecto del repo.
- Sincronizar con upstream: `git merge upstream/master` en `master` y luego
  merge de `master` en `fisat`. Nunca rebase ni `--force`.
- Los cambios portados de upstream citan su origen en el cuerpo del commit:
  `Origen: ctt-gob-es/clienteafirma#NNN` o el SHA de `develop`.
- Paquete `autofirma`, versión `<upstream>+fisat<N>`, tag `v<versión>`.
  Primera versión: `1.9.1+fisat1`.

## Entorno de build

Verificado el 2026-10-29: Maven 3.9.9, OpenJDK 21.0.13 (`javac 21.0.13`). El
código fuente es Java 8 (`-source 8 -target 8`); el aviso de opciones obsoletas
del compilador es esperable.

## Comandos verificados

Todos desde la raíz del repo.

### Tests Java

```bash
mvn -B -ntp -P env-dev,autofirma -pl <directorios> -am test \
  -Dtest='<Clases>' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

Ejemplo verificado (línea base): `-pl afirma-ui-simple-configurator` con
`-Dtest='TestCertUtil#testCertGeneration'` da `Tests run: 1, Failures: 0,
Errors: 0`.

Nota: el módulo `afirma-ui-simple-configurator` no declara `junit` en su
`pom.xml`; JUnit 4.13.2 le llega en ámbito `test` de forma transitiva
(`com.madgag.spongycastle:prov`), por lo que `TestCertUtil` compila y pasa sin
cambios. Si esa dependencia transitiva desapareciera, habría que declarar
`junit:junit` (sin versión, `<scope>test</scope>`) en ese `pom.xml`; la versión
4.13.2 se hereda del `dependencyManagement` raíz.

### Build de JAR

```bash
mvn -B -ntp -DskipTests -P env-dev,env-install \
  -pl afirma-simple,afirma-ui-simple-configurator -am clean package
```

Produce `afirma-simple/target/autofirma.jar` y
`afirma-ui-simple-configurator/target/autofirmaConfigurador.jar` (JAR con todas
las dependencias, `maven-shade-plugin` 3.2.1, funciona con JDK 21). Comprobación
de que las clases salen del reactor y no de Maven Central:

```bash
c=es/gob/afirma/core/misc/SecureXmlBuilder.class
cmp <(unzip -p afirma-simple/target/autofirma.jar "$c") afirma-core/target/classes/"$c" && echo REACTOR_OK
```

Importante: `-P env-dev,autofirma` sin `env-install` produce JAR finos, que no
sirven para el `.deb`. El paquete siempre se construye con `env-install`.

### Tests de shell

```bash
bash afirma-simple-installer/linux/instalador_deb/tests/run.sh
```

Ejecuta los `test-*.sh`, `shellcheck` sobre los scripts del paquete y
`py_compile`; sale 1 si algo falla.

### Verificación del paquete

```bash
afirma-simple-installer/linux/instalador_deb/verificar_paquete.sh <deb>
```

Comprueba el `.deb` (estructura, `.desktop`, políticas de Firefox, scripts de
mantenedor, etc.) y sale distinto de 0 si algo falla.

## Release y CI

Workflow: `.github/workflows/autofirma-fisat.yml`.

- En cada PR a `fisat` se ejecuta el job `pruebas` (tests Java y tests de
  shell). Es el check obligatorio para fusionar.
- Un tag `vX.Y.Z+fisatN` dispara además el job `deb`: valida que `X.Y.Z` sea la
  versión del `pom.xml`, construye los JAR, genera y verifica el `.deb` y
  publica `autofirma_X.Y.Z+fisatN_all.deb` y su `.sha256` en Releases.

Cómo etiquetar, desde `fisat` ya actualizada y con `pruebas` en verde:

```bash
git tag -a v1.9.1+fisat1 -m "AutoFirma 1.9.1+fisat1 (fork FISAT)"
git push origin v1.9.1+fisat1
```

Comprobación local del job `deb` sin publicar:

```bash
mvn -B -ntp -DskipTests -P env-dev,env-install \
  -pl afirma-simple,afirma-ui-simple-configurator -am clean package
D=afirma-simple-installer/linux/instalador_deb
"$D/construir_deb.sh" 1.9.1+fisat0 /tmp/dist \
  afirma-simple/target/autofirma.jar \
  afirma-ui-simple-configurator/target/autofirmaConfigurador.jar
"$D/verificar_paquete.sh" /tmp/dist/autofirma_1.9.1+fisat0_all.deb
```

## Tests JUnit del fork

Clases que ejecuta el job `pruebas` (`-Dtest` del workflow). JUnit 4, nombre
`*Test` o `Test*`, en el mismo paquete que la clase probada. La columna
"Origen" es el identificador del commit de la fase 1 que la añadió.

| Clase | Módulo | Origen |
|---|---|---|
| `AfirmaWebSocketServerTest` | `afirma-simple` | I2 |
| `SecureSocketUtilsTest` | `afirma-simple` | I3 |
| `ServiceInvocationManagerTest` | `afirma-simple` | socket heredado solo en 127.0.0.1 |
| `TestRestoreConfigFirefox` | `afirma-simple` | I7 |
| `RestoreConfigLinuxTest` | `afirma-simple` | f24 recurso autofirma.js |
| `CertificateSelectionPanelTest` | `afirma-ui-core-jse-keystores` | I5 |
| `TestConfiguratorLinuxUsers` | `afirma-ui-simple-configurator` | I1 |
| `TestConfiguratorFirefoxLinux` | `afirma-ui-simple-configurator` | I7 |
| `TestConfiguratorFirefoxCommon` | `afirma-ui-simple-configurator` | D1 |
| `TestCertUtil` | `afirma-ui-simple-configurator` | línea base (ya existía) |
| `NativeFileDialogsTest` | `afirma-ui-core-jse` | f22 diálogos nativos |
| `JSEUIManagerNativeDialogsTest` | `afirma-ui-core-jse` | f22 diálogos nativos |

## Notas operativas

- Al migrar desde el paquete oficial hay que cerrar Firefox antes: el `prerm`
  del paquete oficial hace `pkill firefox`.
- El paso por usuario registra en `~/.local/state/autofirma-fisat/usuario.log`
  y se diagnostica con `/usr/lib/Autofirma/fisat-usuario.sh --check` (sale 0
  si está todo bien, 1 si hay algo pendiente, 2 si hay error).

### Raíz de Firefox en Linux

- Firefox elige la raíz tradicional (`~/.mozilla/firefox`) si el directorio existe, aunque no tenga `profiles.ini`, y usa la XDG (`~/.config/mozilla/firefox`) solo en su ausencia: `nsXREDirProvider::GetLegacyOrXDGHomePath` llama a `LegacyHomeExists`, que solo mira las rutas tradicionales. Fuente: https://github.com/mozilla-firefox/firefox/blob/eed57099f5fd90b156725aeb1a25472dce631370/toolkit/xre/nsXREDirProvider.cpp (leído el 2026-10-30). `MOZ_LEGACY_HOME=1` (entorno o compilación) fuerza la tradicional aunque no exista; es una variable del proceso de Firefox que el fork no ve, y no se contempla.
- Regla del fork (`NssLinuxDatabases.firefoxRoots`/`firefoxRoot`), la misma: raíz tradicional con `profiles.ini`; la XDG con `profiles.ini` solo si el directorio tradicional no existe; Snap (`~/snap/firefox/common/.mozilla/firefox`) al final. Con el directorio tradicional presente y sin `profiles.ini`, no se devuelve la XDG: no hay perfil de Firefox que tocar.
- `findDatabases` devuelve el perfil activo de Firefox y la base de Chrome, solo con `cert9.db` y sin repetidas por ruta real, Firefox primero. Usuario sin base de Chrome: solo Firefox.

### Chrome y AutoFirma desde la web

- Almacén de Chrome: `~/.pki/nssdb` si existe; si no, `~/.local/share/pki/nssdb` (ruta literal). El paso por usuario instala la CA en los dos.
- Chrome solo lee la CA al arrancar. Tras instalar o actualizar el paquete (la CA se regenera en cada actualización) hay que reiniciar Chrome o cerrar la sesión: probado, Chrome no acepta en caliente una CA añadida a su almacén abierto. `postinst` lo avisa.
- Política `/etc/opt/chrome/policies/managed/autofirma-fisat.json` (`AutoLaunchProtocolsFromOrigins`, protocolo `afirma`, `allowed_origins: ["*"]`): Chrome lanza `afirma://` sin preguntar y cualquier origen puede hacerlo. Comprobar en `chrome://policy`. Chromium no está incluido.
- Ciclo de vida de AutoFirma lanzado desde la web: vive mientras la pestaña mantiene abierto el websocket. Se cierra al cerrar la pestaña o Chrome (código 1001), al cortarse la conexión o suspender el equipo (1006), o a los 30 s si nunca llega a conectarse. No es un fallo.
- Desinstalación: el `uninstall.sh` del Configurador y `fisat-retirar.sh` (que ejecuta `fisat-usuario.sh --remove` como cada usuario) borran solo `SocketAutoFirma` y `Autofirma ROOT`, en los almacenes de cada usuario, incluido el XDG de Chrome. Los certificados personales y sus claves no se tocan. Los usuarios de red que no salen en `getent passwd` ni tienen el home en `/home/*` no se limpian.
- Diagnóstico: `fisat-usuario.sh --check` incluye el almacén XDG.

### Diálogos de ficheros (Linux)

- Orden: `kdialog` si la sesión es KDE (`XDG_CURRENT_DESKTOP` contiene `KDE`),
  si no `zenity`, y si no hay ninguno el `JFileChooser` de Java. Cubre abrir
  uno o varios ficheros, guardar y elegir carpeta.
- Cancelar en el diálogo nativo cancela la operación (no se abre después el de
  Java).
- Se cae al `JFileChooser` si la herramienta nativa no arranca, se muere, sale
  con un código distinto de 0 (aceptar) o 1 (cancelar) o devuelve una ruta no
  local (p. ej. `smb://`).
- Forzar el de Java: `AUTOFIRMA_FISAT_DIALOGOS=java` en el entorno o
  `-Des.gob.afirma.fisat.dialogos=java` en la JVM.
- El paquete recomienda (`Recommends`, no `Depends`) `kdialog | zenity`.
- Mientras el diálogo nativo está abierto, las ventanas de AutoFirma no
  admiten clics ni teclado (como con el `JFileChooser` modal) pero siguen
  repintándose: el proceso se espera en un hilo auxiliar con un bucle
  secundario de eventos.
- Filtros de `kdialog`: `Desc (*.a *.A)|Todos (*)`; `kdialog` cambia `|` por
  salto de línea y Qt separa ahí los filtros (comprobado en el código de
  `kdialog` v25.04.0, `setFileDialogFilter`).
- Limitaciones: el diálogo nativo no tiene ventana padre; con `kdialog` puede
  salir un doble aviso de sobrescritura (el de KDE y el de AutoFirma); al
  guardar sin extensión se añade la del filtro por defecto (kdialog y zenity no
  informan del filtro elegido).
- Diagnóstico: el log de AutoFirma registra la herramienta nativa usada y el
  motivo de cada caída al `JFileChooser` (código de salida o error al
  ejecutarla).
