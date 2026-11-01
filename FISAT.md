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
  -pl afirma-simple -am clean package
```

Produce `afirma-simple/target/autofirma.jar` (JAR con todas las dependencias,
`maven-shade-plugin` 3.2.1, funciona con JDK 21). El paquete ya no incluye
`autofirmaConfigurador.jar`: la CA, `AutoFirma.js`, la extensión CSP y la
parte de cada usuario las hace el propio programa (`autofirma restaurar`).
Comprobación de que las clases salen del reactor y no de Maven Central:

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

Ejecuta los `test-*.sh` y `shellcheck` sobre los scripts del paquete; sale 1 si
algo falla.

### Verificación del paquete

```bash
afirma-simple-installer/linux/instalador_deb/verificar_paquete.sh <deb>
```

Comprueba el `.deb` (estructura, `Depends`, `.desktop`, política de Chrome,
llamadas a `restaurar` en los scripts de mantenedor, ausencia de scripts por
usuario, etc.) y sale distinto de 0 si algo falla.

## Release y CI

Workflow: `.github/workflows/autofirma-fisat.yml`.

- En cada PR a `fisat` se ejecuta el job `pruebas` (tests Java bajo
  `xvfb-run -a`, para que el test del panel de Restaurar no se salte, y tests
  de shell). Es el check obligatorio para fusionar.
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
  -pl afirma-simple -am clean package
D=afirma-simple-installer/linux/instalador_deb
"$D/construir_deb.sh" 1.9.1+fisat0 /tmp/dist \
  afirma-simple/target/autofirma.jar
"$D/verificar_paquete.sh" /tmp/dist/autofirma_1.9.1+fisat0_all.deb
```

## Tests JUnit del fork

Clases que ejecuta el job `pruebas` (`-Dtest` del workflow). JUnit 4, nombre
`*Test` o `Test*`, en el mismo paquete que la clase probada. La columna
"Origen" es la fase o el cambio que la añadió.

| Clase | Módulo | Origen |
|---|---|---|
| `AfirmaWebSocketServerTest` | `afirma-simple` | I2 |
| `SecureSocketUtilsTest` | `afirma-simple` | I3 |
| `ServiceInvocationManagerTest` | `afirma-simple` | socket heredado solo en 127.0.0.1 |
| `TestRestoreConfigFirefox` | `afirma-simple` | I7 |
| `RestoreConfigLinuxTest` | `afirma-simple` | f24 recurso autofirma.js |
| `RestoreUserLinuxTest` | `afirma-simple` | Restaurar: parte de usuario |
| `RestoreSystemLinuxTest` | `afirma-simple` | Restaurar: parte de sistema |
| `FirefoxPoliciesTest` | `afirma-simple` | Restaurar: extensión CSP en `policies.json` |
| `RestoreCommandLinuxTest` | `afirma-simple` | Restaurar: comando `restaurar` |
| `RestoreConfigPanelLinuxTest` | `afirma-simple` | Restaurar: panel con dos botones (necesita pantalla, `xvfb-run`) |
| `NssLinuxDatabasesTest` | `afirma-keystores-mozilla` | Lectura: bases NSS de Firefox y Chrome |
| `NssMultiSlotLoaderTest` | `afirma-keystores-mozilla` | Lectura: varias bases en una inicialización |
| `NssFirefoxChromeKeyStoreManagerTest` | `afirma-keystores-mozilla` | Lectura: un almacén con Firefox y Chrome; origen en el selector |
| `CertificateOriginsTest` | `afirma-core-keystores` | Lectura: origen en el selector en una pasada |
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
- Las versiones anteriores a `fisat5` llevaban el conffile
  `/etc/xdg/autostart/autofirma-fisat-usuario.desktop`; `preinst`, `postinst`
  y `postrm` lo retiran con `dpkg-maintscript-helper rm_conffile` (corte
  `1.9.1+fisat5~`). Si se modificó a mano, queda como `.dpkg-bak`, que el
  autostart no lee.
- No hay paso por usuario al arrancar: el lanzador solo ajusta el locale y
  ejecuta `java`. La configuración de cada usuario la hace `autofirma
  restaurar` (ver «Restaurar instalación»). Con `restaurar` como primer
  argumento el lanzador ejecuta directamente `RestoreCommandLinux` (`-cp`, no
  `-jar`): la clase de la aplicación instala el registro en `~/.afirma` al
  cargarse y `--usuario` no debe escribir ahí.

### Restaurar instalación

- «Restaurar instalación» (Linux) tiene dos botones. «Todo el sistema» lanza
  `pkexec /usr/bin/autofirma restaurar --sistema` y vuelca su salida al panel;
  pide la contraseña del administrador, de ahí la dependencia
  `pkexec | policykit-1` (en Debian 11, Ubuntu 22.04 y anteriores y las Mint
  basadas en ellas, `pkexec` lo trae `policykit-1`). «Solo este usuario»
  restaura el home de quien ejecuta la aplicación, sin privilegios.
- Orden, sin interfaz gráfica; una línea `OK`, `AVISO` o `ERROR` por paso, en
  castellano. Salida 0 correcto, 1 con errores, 2 uso incorrecto o falta de
  permisos:
  - `autofirma restaurar --sistema` (root): CA y almacén SSL si faltan,
    `AutoFirma.js`, extensión CSP en `policies.json` de Firefox, `/etc/skel` y
    el home de cada usuario (con `runuser`, como su dueño; un home cuyo
    directorio es de root o de una cuenta de sistema, UID < 1000, se salta con
    aviso). Cada posible home se comprueba con 5 s de límite y la búsqueda
    entera con 30 s: lo que no responde (un montaje NFS `hard` caído) se salta
    con aviso. `postinst` la ejecuta en cada instalación o actualización.
  - `autofirma restaurar --usuario`: solo bases NSS, `user.js` y
    `handlers.json` del usuario. Nunca genera certificados. Como root (por
    ejemplo con `su` sin guion o `sudo -E`, que conservan el `HOME` de la
    persona) se niega con salida 2 salvo que el home sea de root: dejaría esos
    ficheros a nombre de root y Firefox no podría leerlos. Lo mismo hace «Solo
    este usuario» si la aplicación se ejecuta como root.
  - `autofirma restaurar --quitar-politicas` (root): quita la extensión CSP.
    `prerm` la ejecuta solo en `remove` o `deconfigure`, nunca al actualizar.
- Con un perfil de Firefox abierto no se escribe `handlers.json` (Firefox lo
  pisaría al cerrar) y se avisa; con contraseña maestra la base se salta con
  aviso; un perfil sin `cert9.db` no recibe la CA y se avisa.

### Lectura de certificados: Firefox y Chrome

- Un único almacén lee a la vez la base NSS del perfil activo de Firefox y la
  de Chrome (`NssFirefoxChromeKeyStoreManager`): una sola inicialización NSS
  con `tokens=<...>` y un proveedor SunPKCS11 por ranura (`slot=2`, `slot=4`).
  Solo se usan bases con `cert9.db`, sin repetidas por ruta real.
- Usuario sin base de Chrome: se carga solo Firefox, sin error ni diálogo.
- Base de Chrome: `~/.pki/nssdb` si existe; si no, `~/.local/share/pki/nssdb`.
- El selector muestra el origen tras el nombre: «(Firefox)», «(Chrome)» o «(Firefox, Chrome)» si
  el certificado está en las dos bases o las dos rutas llevan a la misma (enlace). Solo cambia el
  texto mostrado (`AOKeyStoreDialog` → `CertificateOriginProvider`); el alias y la firma, no.

### Objetivo a largo plazo

El paquete debe funcionar en cualquier distribución basada en Debian (Debian,
Ubuntu, Linux Mint…), no solo en los equipos para los que se hizo. Límites
conocidos, sin probar:

- Firefox como snap (Ubuntu): su perfil vive en `~/snap/firefox/common/.mozilla/firefox`
  y el confinamiento puede impedir leer las bases NSS o aplicar `handlers.json`.
- Sesiones Wayland: los diálogos de ficheros y el lanzamiento desde el navegador
  se han probado en X11.
- Distribuciones sin OpenJDK 21 en los repositorios: `Depends` acepta
  `default-jre`, pero la aplicación está probada con Java 21.

### Raíz de Firefox en Linux

- Firefox elige la raíz tradicional (`~/.mozilla/firefox`) si existe `~/.mozilla`, aunque solo tenga otra cosa (por ejemplo `native-messaging-hosts`) y aunque no haya `profiles.ini`, y usa la XDG (`~/.config/mozilla/firefox`) solo si `~/.mozilla` no existe: `nsXREDirProvider::GetLegacyOrXDGHomePath` llama a `LegacyHomeExists`, que mira `~/.mozilla/firefox` y, si no existe, `~/.mozilla` (`MOZ_USER_DIR`). Fuente: https://github.com/mozilla-firefox/firefox/blob/eed57099f5fd90b156725aeb1a25472dce631370/toolkit/xre/nsXREDirProvider.cpp (leído el 2026-10-30 y de nuevo el 2026-11-01). `MOZ_LEGACY_HOME=1` (entorno o compilación) fuerza la tradicional aunque no exista; es una variable del proceso de Firefox que el fork no ve, y no se contempla.
- Regla del fork para leer (`NssLinuxDatabases.firefoxRoot`), la misma: raíz tradicional con `profiles.ini`; la XDG con `profiles.ini` solo si `~/.mozilla` no existe; Snap (`~/snap/firefox/common/.mozilla/firefox`) al final. Con `~/.mozilla` presente y sin `~/.mozilla/firefox/profiles.ini`, no se devuelve la XDG: no hay perfil de Firefox que leer. `firefoxRoots` no aplica esa regla: devuelve todas las raíces con `profiles.ini` (tradicional, XDG, Snap) para que Restaurar cubra los perfiles de las migraciones a XDG.
- `findDatabases` devuelve el perfil activo de Firefox y la base de Chrome, solo con `cert9.db` y sin repetidas por ruta real, Firefox primero. Usuario sin base de Chrome: solo Firefox.

### Chrome y AutoFirma desde la web

- Almacén de Chrome: `~/.pki/nssdb` si existe; si no, `~/.local/share/pki/nssdb` (ruta literal). `restaurar` instala la CA solo en ese, el que usa Chrome.
- Chrome solo lee la CA al arrancar. Tras la primera instalación del paquete (o cuando falta `autofirma.pfx` y la CA se genera de nuevo) hay que reiniciar Chrome o cerrar la sesión: probado, Chrome no acepta en caliente una CA añadida a su almacén abierto. `postinst` lo avisa. Las actualizaciones conservan `autofirma.pfx` y la CA.
- Política `/etc/opt/chrome/policies/managed/autofirma-fisat.json` (`AutoLaunchProtocolsFromOrigins`, protocolo `afirma`, `allowed_origins: ["*"]`): Chrome lanza `afirma://` sin preguntar y cualquier origen puede hacerlo. Comprobar en `chrome://policy`. Chromium no está incluido.
- Ciclo de vida de AutoFirma lanzado desde la web: vive mientras la pestaña mantiene abierto el websocket. Se cierra al cerrar la pestaña o Chrome (código 1001), al cortarse la conexión o suspender el equipo (1006), o a los 30 s si nunca llega a conectarse. No es un fallo.
- Desinstalación: solo en `remove` o `deconfigure` (nunca al actualizar), `prerm` ejecuta `autofirma restaurar --quitar-politicas` (quita la extensión CSP de `policies.json`) y borra los ficheros del sistema (`autofirma.pfx`, la CA y su copia en el almacén del sistema, y el enlace de `firefox-esr`). La CA `SocketAutoFirma` de los almacenes NSS de cada usuario no se retira: no hay paso por usuario en la retirada. Los certificados personales y sus claves no se tocan.

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
