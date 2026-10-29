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
import java.util.List;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
}
