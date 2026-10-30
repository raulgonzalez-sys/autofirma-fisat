package es.gob.afirma.standalone.configurator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import es.gob.afirma.standalone.configurator.common.ConfiguratorUtil;

/** Pruebas de la localizacion de perfiles Mozilla en Linux.
 * Para que la clase sea probada, {@code ConfiguratorFirefoxLinux} expone sus metodos
 * con visibilidad de paquete. */
public final class TestConfiguratorFirefoxLinux {

	private static final String SNAP_DIR = "snap/firefox/common/.mozilla/firefox"; //$NON-NLS-1$
	private static final String XDG_DIR = ".config/mozilla/firefox"; //$NON-NLS-1$
	private static final String ESR_DIR = ".mozilla/firefox"; //$NON-NLS-1$

	/** Directorio temporal usado como home. */
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** Crea un <i>profiles.ini</i> con un unico perfil relativo y, opcionalmente, el directorio del perfil.
	 * @param home Directorio de usuario.
	 * @param iniDir Directorio (relativo al home) del <i>profiles.ini</i>.
	 * @param profileName Nombre del perfil.
	 * @param createProfileDir <code>true</code> para crear tambien el directorio del perfil.
	 * @return Directorio del perfil (exista o no).
	 * @throws IOException En cualquier error. */
	private static File createProfile(final File home, final String iniDir, final String profileName,
			final boolean createProfileDir) throws IOException {
		final File dir = new File(home, iniDir);
		assertTrue(dir.isDirectory() || dir.mkdirs());
		Files.write(
			new File(dir, "profiles.ini").toPath(), //$NON-NLS-1$
			("[Profile0]\nPath=" + profileName + "\nIsRelative=1\n").getBytes(StandardCharsets.UTF_8) //$NON-NLS-1$ //$NON-NLS-2$
		);
		final File profile = new File(dir, profileName);
		if (createProfileDir) {
			assertTrue(profile.mkdirs());
		}
		return profile;
	}

	private static boolean containsCanonical(final List<File> files, final File expected) throws IOException {
		for (final File f : files) {
			if (f.getCanonicalFile().equals(expected.getCanonicalFile())) {
				return true;
			}
		}
		return false;
	}

	/** Los perfiles de las tres rutas se acumulan.
	 * @throws Exception En cualquier error. */
	@Test
	public void perfiles_tres_rutas_acumulan() throws Exception { //$NON-NLS-1$
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		final File snap = createProfile(home, SNAP_DIR, "snap.default", true); //$NON-NLS-1$
		final File xdg = createProfile(home, XDG_DIR, "xdg.default-release", true); //$NON-NLS-1$
		final File esr = createProfile(home, ESR_DIR, "esr.default-esr", true); //$NON-NLS-1$

		final List<File> profiles = ConfiguratorFirefoxLinux.getProfiles(new String[] { home.getAbsolutePath() });
		assertNotNull(profiles);
		assertEquals(3, profiles.size());
		assertTrue(containsCanonical(profiles, snap));
		assertTrue(containsCanonical(profiles, xdg));
		assertTrue(containsCanonical(profiles, esr));
	}

	/** Un perfil declarado cuyo directorio no existe no aparece.
	 * @throws Exception En cualquier error. */
	@Test
	public void perfil_declarado_inexistente() throws Exception {
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		final File ghost = createProfile(home, ESR_DIR, "fantasma.default", false); //$NON-NLS-1$

		final List<File> profiles = ConfiguratorFirefoxLinux.getProfiles(new String[] { home.getAbsolutePath() });
		assertNotNull(profiles);
		assertFalse(containsCanonical(profiles, ghost));
		assertTrue(profiles.isEmpty());
	}

	/** Los perfiles de varios usuarios aparecen todos.
	 * @throws Exception En cualquier error. */
	@Test
	public void varios_usuarios() throws Exception {
		final File home1 = this.tmp.newFolder("home1"); //$NON-NLS-1$
		final File home2 = this.tmp.newFolder("home2"); //$NON-NLS-1$
		final File p1 = createProfile(home1, XDG_DIR, "u1.default-release", true); //$NON-NLS-1$
		final File p2 = createProfile(home2, ESR_DIR, "u2.default-esr", true); //$NON-NLS-1$

		final List<File> profiles = ConfiguratorFirefoxLinux.getProfiles(
			new String[] { home1.getAbsolutePath(), home2.getAbsolutePath() }
		);
		assertNotNull(profiles);
		assertEquals(2, profiles.size());
		assertTrue(containsCanonical(profiles, p1));
		assertTrue(containsCanonical(profiles, p2));
	}

	/** Sin ningun <i>profiles.ini</i> se devuelve <code>null</code>.
	 * @throws Exception En cualquier error. */
	@Test
	public void sin_ini() throws Exception {
		final File homeVacio = this.tmp.newFolder("vacio"); //$NON-NLS-1$
		assertNull(ConfiguratorFirefoxLinux.getProfiles(new String[] { homeVacio.getAbsolutePath() }));
	}

	/** Un enlace simbolico entre dos rutas no duplica los perfiles.
	 * @throws Exception En cualquier error. */
	@Test
	public void dedupe_enlace() throws Exception {
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		final File esr = createProfile(home, ESR_DIR, "esr.default-esr", true); //$NON-NLS-1$
		final File xdgDir = new File(home, XDG_DIR);
		assertTrue(xdgDir.getParentFile().mkdirs());
		Files.createSymbolicLink(xdgDir.toPath(), new File(home, ESR_DIR).toPath());

		final List<File> profiles = ConfiguratorFirefoxLinux.getProfiles(new String[] { home.getAbsolutePath() });
		assertNotNull(profiles);
		assertEquals(1, profiles.size());
		assertTrue(containsCanonical(profiles, esr));
	}

	/** Variantes de almacen NSS reconocidas.
	 * @throws Exception En cualquier error. */
	@Test
	public void hasNssKeyStore_variantes() throws Exception {
		final File cert9 = this.tmp.newFolder("cert9"); //$NON-NLS-1$
		assertTrue(new File(cert9, "cert9.db").createNewFile()); //$NON-NLS-1$
		assertTrue(ConfiguratorFirefoxLinux.hasNssKeyStore(cert9));

		final File pkcs11 = this.tmp.newFolder("pkcs11"); //$NON-NLS-1$
		assertTrue(new File(pkcs11, "pkcs11.txt").createNewFile()); //$NON-NLS-1$
		assertTrue(ConfiguratorFirefoxLinux.hasNssKeyStore(pkcs11));

		final File cert8 = this.tmp.newFolder("cert8"); //$NON-NLS-1$
		assertTrue(new File(cert8, "cert8.db").createNewFile()); //$NON-NLS-1$
		assertTrue(ConfiguratorFirefoxLinux.hasNssKeyStore(cert8));

		assertFalse(ConfiguratorFirefoxLinux.hasNssKeyStore(this.tmp.newFolder("vacio"))); //$NON-NLS-1$
		assertFalse(ConfiguratorFirefoxLinux.hasNssKeyStore(new File(this.tmp.getRoot(), "inexistente"))); //$NON-NLS-1$
	}

	/** Los perfiles con contrasena maestra o sin almacen NSS no entran en los scripts.
	 * @throws Exception En cualquier error. */
	@Test
	public void integracion_omite_protegidos() throws Exception {
		Assume.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux")); //$NON-NLS-1$ //$NON-NLS-2$
		final File marker = new File(this.tmp.getRoot(), "marcador-C"); //$NON-NLS-1$
		final File fake = this.tmp.newFile("certutil-falso"); //$NON-NLS-1$
		Files.write(fake.toPath(), (
			"#!/bin/sh\n" //$NON-NLS-1$
			+ "case \"$*\" in\n" //$NON-NLS-1$
			+ "*perfilC*) touch '" + marker.getAbsolutePath() + "' ;;\n" //$NON-NLS-1$ //$NON-NLS-2$
			+ "*perfilB*) echo 'Enter Password or Pin for \"NSS Certificate DB\":'; sleep 30 ;;\n" //$NON-NLS-1$
			+ "*) echo 'certutil: no keys found' ;;\n" //$NON-NLS-1$
			+ "esac\n" //$NON-NLS-1$
		).getBytes(StandardCharsets.UTF_8));
		assertTrue(fake.setExecutable(true));
		Assume.assumeTrue(fake.canExecute());

		final File a = this.tmp.newFolder("perfilA"); //$NON-NLS-1$
		assertTrue(new File(a, "cert9.db").createNewFile()); //$NON-NLS-1$
		assertTrue(new File(a, "pkcs11.txt").createNewFile()); //$NON-NLS-1$
		final File b = this.tmp.newFolder("perfilB"); //$NON-NLS-1$
		assertTrue(new File(b, "cert9.db").createNewFile()); //$NON-NLS-1$
		assertTrue(new File(b, "pkcs11.txt").createNewFile()); //$NON-NLS-1$
		final File c = this.tmp.newFolder("perfilC"); //$NON-NLS-1$
		final File certDir = this.tmp.newFolder("certs"); //$NON-NLS-1$
		final File install = new File(this.tmp.getRoot(), "install.sh"); //$NON-NLS-1$
		final File uninstall = new File(this.tmp.getRoot(), "uninstall.sh"); //$NON-NLS-1$

		ConfiguratorFirefoxLinux.writeScriptsToMozillaKeyStore(
			fake.getAbsolutePath(), certDir, Arrays.asList(a, b, c), install, uninstall);

		final String inst = new String(Files.readAllBytes(install.toPath()), StandardCharsets.UTF_8);
		final String unin = new String(Files.readAllBytes(uninstall.toPath()), StandardCharsets.UTF_8);
		assertTrue(inst, inst.contains("-A -d sql:" + a.getAbsolutePath())); //$NON-NLS-1$
		assertTrue(unin, unin.contains("-D -d sql:" + a.getAbsolutePath())); //$NON-NLS-1$
		assertFalse(inst, inst.contains(b.getAbsolutePath()));
		assertFalse(unin, unin.contains(b.getAbsolutePath()));
		assertFalse(inst, inst.contains(c.getAbsolutePath()));
		assertFalse(unin, unin.contains(c.getAbsolutePath()));
		assertFalse(marker.exists());
	}

	private static final String PERSONAL = "Usuario Prueba"; //$NON-NLS-1$
	private static final String PERSONAL_PARECIDO = "SocketAutoFirma copia"; //$NON-NLS-1$
	private static final String OTRO_APODO = "SocketAutoFirma2"; //$NON-NLS-1$

	/** Linea de certutil -L: apodo, espacios y columna de confianza. */
	private static final Pattern LISTADO = Pattern.compile("^(.*?)\\s+\\S*,\\S*,\\S*\\s*$"); //$NON-NLS-1$

	/** Ejecuta un comando con la entrada de /dev/null y devuelve su salida (con stderr).
	 * @param cmd Comando y argumentos.
	 * @return Salida del comando.
	 * @throws Exception Si no arranca o tarda mas de 60 s. */
	private String run(final String... cmd) throws Exception {
		final File out = this.tmp.newFile();
		final Process p = new ProcessBuilder(cmd)
			.redirectInput(new File("/dev/null")) //$NON-NLS-1$
			.redirectErrorStream(true)
			.redirectOutput(out)
			.start();
		if (!p.waitFor(60, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IOException("Tiempo agotado: " + Arrays.toString(cmd)); //$NON-NLS-1$
		}
		return new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
	}

	/** Comprueba que hay certutil y bash; si no, se omite la prueba. */
	private void assumeHerramientas() {
		Assume.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux")); //$NON-NLS-1$ //$NON-NLS-2$
		try {
			run("certutil", "-H"); //$NON-NLS-1$ //$NON-NLS-2$
			run("bash", "--version"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		catch (final Exception e) {
			Assume.assumeNoException(e);
		}
	}

	/** Crea un almacen NSS vacio sin contrasena. */
	private void nssDb(final File dir) throws Exception {
		assertTrue(dir.isDirectory() || dir.mkdirs());
		run("certutil", "-N", "-d", "sql:" + dir.getAbsolutePath(), "--empty-password"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		assertTrue(new File(dir, "cert9.db").isFile()); //$NON-NLS-1$
	}

	/** Anade una CA nueva (otro DER, mismo sujeto) con el apodo SocketAutoFirma. */
	private void addCa(final File db) throws Exception {
		final File cer = this.tmp.newFile();
		Files.write(cer.toPath(),
			CertUtil.getCertPackForLocalhostSsl(ConfiguratorUtil.CERT_ALIAS, "654321").getCaCertificate().getEncoded()); //$NON-NLS-1$
		run("certutil", "-A", "-d", "sql:" + db.getAbsolutePath(), "-n", ConfiguratorUtil.CERT_ALIAS, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			"-t", "C,,", "-i", cer.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** Anade un certificado autofirmado con clave privada. */
	private void addPersonal(final File db, final String nick) throws Exception {
		final File ruido = this.tmp.newFile();
		final byte[] bytes = new byte[2048];
		new Random().nextBytes(bytes);
		Files.write(ruido.toPath(), bytes);
		run("certutil", "-S", "-d", "sql:" + db.getAbsolutePath(), "-n", nick, "-s", "CN=" + nick, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
			"-x", "-t", "u,u,u", "-k", "rsa", "-g", "2048", "-z", ruido.getAbsolutePath(), "-f", "/dev/null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
		assertEquals(1, keys(db, nick));
	}

	/** Entradas de certutil -L con apodo exacto. */
	private int count(final File db, final String nick) throws Exception {
		int n = 0;
		for (final String line : run("certutil", "-L", "-d", "sql:" + db.getAbsolutePath()).split("\n")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			final Matcher m = LISTADO.matcher(line);
			if (m.matches() && m.group(1).equals(nick)) {
				n++;
			}
		}
		return n;
	}

	/** Claves de certutil -K cuyo apodo es exactamente el indicado. */
	private int keys(final File db, final String nick) throws Exception {
		int n = 0;
		for (final String line : run("certutil", "-K", "-d", "sql:" + db.getAbsolutePath(), "-f", "/dev/null").split("\n")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
			if (line.trim().endsWith(":" + nick)) { //$NON-NLS-1$
				n++;
			}
		}
		return n;
	}

	/** Prepara un almacen con tres SocketAutoFirma y certificados ajenos con clave. */
	private void poblar(final File db) throws Exception {
		nssDb(db);
		addCa(db);
		addCa(db);
		addCa(db);
		addPersonal(db, PERSONAL);
		addPersonal(db, PERSONAL_PARECIDO);
		addPersonal(db, OTRO_APODO);
		assertEquals(3, count(db, ConfiguratorUtil.CERT_ALIAS));
	}

	/** Tras desinstalar no queda ninguna SocketAutoFirma y el resto sigue intacto. */
	private void comprobarDesinstalado(final File db) throws Exception {
		assertEquals(0, count(db, ConfiguratorUtil.CERT_ALIAS));
		assertEquals(1, count(db, PERSONAL));
		assertEquals(1, keys(db, PERSONAL));
		assertEquals(1, count(db, PERSONAL_PARECIDO));
		assertEquals(1, keys(db, PERSONAL_PARECIDO));
		assertEquals(1, count(db, OTRO_APODO));
		assertEquals(1, keys(db, OTRO_APODO));
	}

	/** La linea de desinstalacion es un bucle acotado sobre el apodo exacto. */
	@Test
	public void desinstalacion_linea_bucle() {
		assertEquals(
			"for i in 1 2 3 4 5; do certutil -L -d sql:/home/ana/.pki/nssdb -n \"SocketAutoFirma\" </dev/null >/dev/null 2>&1 || break; " //$NON-NLS-1$
			+ "certutil -D -d sql:/home/ana/.pki/nssdb -n \"SocketAutoFirma\" </dev/null || break; done", //$NON-NLS-1$
			String.join(" ", ConfiguratorFirefoxLinux.buildUninstallCommand("certutil", "sql:/home/ana/.pki/nssdb"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

		final String r = "sql:/home/ana\\ lopez/.pki/nssdb"; //$NON-NLS-1$
		final String linea = String.join(" ", ConfiguratorFirefoxLinux.buildUninstallCommand("certutil", r)); //$NON-NLS-1$ //$NON-NLS-2$
		final int primera = linea.indexOf(" -L -d " + r + " -n "); //$NON-NLS-1$ //$NON-NLS-2$
		final int segunda = linea.indexOf(" -D -d " + r + " -n "); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(linea, primera > 0);
		assertTrue(linea, segunda > primera);
		assertEquals(linea, 2, linea.split(Pattern.quote(r), -1).length - 1);
	}

	/** El uninstall.sh de Mozilla borra todas las SocketAutoFirma y nada mas.
	 * @throws Exception En cualquier error. */
	@Test
	public void desinstalacion_mozilla_borra_duplicados_y_conserva_personal() throws Exception {
		assumeHerramientas();
		final File perfil = new File(this.tmp.getRoot(), "perfilA"); //$NON-NLS-1$
		poblar(perfil);
		final File certDir = this.tmp.newFolder("certs"); //$NON-NLS-1$
		final File install = new File(this.tmp.getRoot(), "install.sh"); //$NON-NLS-1$
		final File uninstall = new File(this.tmp.getRoot(), "uninstall.sh"); //$NON-NLS-1$

		ConfiguratorFirefoxLinux.writeScriptsToMozillaKeyStore(
			"certutil", certDir, Collections.singletonList(perfil), install, uninstall); //$NON-NLS-1$
		run("bash", uninstall.getAbsolutePath()); //$NON-NLS-1$

		comprobarDesinstalado(perfil);
	}

	/** El uninstall.sh del almacen de sistema borra todas las SocketAutoFirma y nada mas.
	 * @throws Exception En cualquier error. */
	@Test
	public void desinstalacion_sistema_borra_duplicados_y_conserva_personal() throws Exception {
		assumeHerramientas();
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		final File db = new File(home, ".pki/nssdb"); //$NON-NLS-1$
		poblar(db);
		final File appDir = this.tmp.newFolder("app"); //$NON-NLS-1$
		final File install = new File(this.tmp.getRoot(), "install.sh"); //$NON-NLS-1$
		final File uninstall = new File(this.tmp.getRoot(), "uninstall.sh"); //$NON-NLS-1$

		ConfiguratorFirefoxLinux.createScriptsToSystemKeyStore(
			appDir, new String[] { home.getAbsolutePath() }, install, uninstall);
		run("bash", uninstall.getAbsolutePath()); //$NON-NLS-1$

		comprobarDesinstalado(db);
	}
}
