# CLAUDE.md

Guía para Claude Code en este repositorio. Los comandos verificados, el
proceso de release y las notas operativas viven en `FISAT.md`: aquí van las
reglas que hay que tener presentes al cambiar código, con un enlace a
`FISAT.md` en lugar de repetirlo.

## Qué es

Fork de `ctt-gob-es/clienteafirma` (AutoFirma 1.9.1) empaquetado como `.deb`
para escritorios basados en Debian. En Linux, toda la instalación y la
reparación viven en el propio programa: lectura simultánea de los almacenes NSS
de Firefox y Chrome, y «Restaurar instalación» (`autofirma restaurar
--sistema|--usuario|--quitar-politicas`). El paquete no lleva scripts por
usuario ni autostart. Objetivo a largo plazo: que funcione en cualquier
distribución basada en Debian, no solo en los equipos para los que se hizo.

## Repositorio público

- Nada de datos internos de la organización: sedes, nombres de servidores,
  rutas personales, detalles de directorio (SSSD/LDAP), enlaces a sesiones de
  chat. Tampoco en mensajes de commit, PR ni tests (ejemplos con
  `example.org`).
- Commits con autor y committer `Claude <noreply@anthropic.com>` (ya fijado en
  el clon). Mensajes en castellano.

## Ramas, CI y release

- `master` es espejo exacto del upstream; nunca se commitea en ella. La rama de
  trabajo y por defecto es `fisat`, y los cambios entran por PR a `fisat` con
  el job `pruebas` en verde. Sincronización con el upstream: ver `FISAT.md`
  § «Ramas y sincronización con upstream».
- Versión `X.Y.Z+fisatN`: `X.Y.Z` es la del `pom.xml`. La release la publica el
  CI (tag o «Run workflow» con `version`): ver `FISAT.md` § «Release y CI».
- Toda clase de test nueva se añade al `-Dtest` de
  `.github/workflows/autofirma-fisat.yml` y a la tabla de `FISAT.md`
  § «Tests JUnit del fork»; si no, el CI no la ejecuta.

## Reglas de código

- **Divergencia mínima con el upstream:** lo nuevo va en clases nuevas
  (`es.gob.afirma.keystores.mozilla.linux`,
  `es.gob.afirma.standalone.ui.restoreconfig.linux`, …); en las clases del
  upstream solo enganches de pocas líneas. Así sobreviven las fusiones con el
  upstream.
- **Saltos de línea:** muchos ficheros del upstream son CRLF. Al editarlos se
  conserva CRLF y el diff se limita a las líneas cambiadas.
- **Fuente Java 8** (`-source 8 -target 8`), compilado con JDK 21: nada de API
  posterior a Java 8 (ni `ProcessHandle`).
- **NSS una sola vez por proceso:** SunPKCS11 inicializa NSS una vez y cachea
  el módulo por ruta de librería. Las bases de Firefox y Chrome se abren juntas
  con `tokens=` (sin `configdir` de primer nivel) en `NssMultiSlotLoader`;
  no abrir otra inicialización de NSS en Linux. Los tests que arrancan NSS
  corren en JVM hijas.
- **Lectura en solo lectura** (`flags=readOnly`) en todas las bases.
- **Restaurar nunca destruye datos del usuario:** solo borra entradas con
  apodo exacto `SocketAutoFirma` o `Autofirma ROOT`; nunca claves ni
  certificados personales. `--usuario` nunca genera certificados ni escribe en
  `~/.afirma`, y como root rechaza homes ajenos. `--sistema` trata cada home
  con `runuser` como su dueño, entorno limpio y tiempo límite; nunca escribe
  como root dentro de un home de usuario.
- **Sin propiedades del sistema que cambien lo que se ejecuta como root**: los
  tests inyectan dobles con campos de paquete (`testCertutil`,
  `testUserCommand`, `testEtcDir`).
- **Scripts de mantenedor:** siempre salen con 0 (cada paso con `|| aviso`);
  un fallo no puede dejar `dpkg` a medias. `prerm` solo borra en
  `remove|deconfigure`, nunca en `upgrade` (si no, cada actualización
  regeneraría la CA). Ficheros retirados del paquete que fueran conffiles: con
  `dpkg-maintscript-helper rm_conffile`.
- **Raíz de Firefox:** la misma regla que Firefox (`~/.mozilla` si existe;
  XDG si no). Ver `FISAT.md` § «Raíz de Firefox en Linux».

## Pruebas

- Tests JUnit 4 con bases NSS reales creadas con `certutil` en directorios
  temporales; se saltan con `Assume` si falta `certutil` o `libsoftokn3.so`.
  Nada de mocks del código bajo prueba.
- TDD: el test se ve fallar por la funcionalidad que falta antes de escribir
  el código.
- Antes de dar algo por hecho: el comando Java del workflow bajo
  `xvfb-run -a`, `afirma-simple-installer/linux/instalador_deb/tests/run.sh`
  y, si se toca el paquete, el build local y `verificar_paquete.sh`: ver
  `FISAT.md` § «Comandos verificados».
