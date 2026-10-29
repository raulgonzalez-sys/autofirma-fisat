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

El arnés de tests de shell lo crea la Tarea 11 de la fase 1; hasta entonces el
comando no existe.

### Verificación del paquete

```bash
afirma-simple-installer/linux/instalador_deb/verificar_paquete.sh <deb>
```

Disponible cuando se cree `verificar_paquete.sh` (fase 1, paquete).

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

## Tests JUnit nuevos del fork

Cada tarea de la fase 1 añade aquí sus clases de test (JUnit 4, nombre `*Test`
o `Test*`, en el mismo paquete que la clase probada).

| Clase | Módulo | Tarea |
|---|---|---|
| (se completa en cada tarea) | | |
