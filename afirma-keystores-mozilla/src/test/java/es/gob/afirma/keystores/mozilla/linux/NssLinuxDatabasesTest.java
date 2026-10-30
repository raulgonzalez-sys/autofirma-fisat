package es.gob.afirma.keystores.mozilla.linux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Pruebas del localizador de bases NSS de Firefox y Chrome en Linux. */
public class NssLinuxDatabasesTest {

	private static final String PROFILE = "default-esr"; //$NON-NLS-1$

	/** Directorio temporal que hace de directorio de usuario. */
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File home() throws IOException {
		return this.tmp.newFolder("home");
	}

	/** Crea una raiz de Firefox con un perfil valido para la logica de seleccion. */
	private static File crearRaizFirefox(final File home, final String rel, final boolean conCert9) throws IOException {
		final File root = new File(home, rel);
		final File profile = new File(root, PROFILE);
		Files.createDirectories(profile.toPath());
		Files.write(
			new File(root, "profiles.ini").toPath(), //$NON-NLS-1$
			"[Profile0]\nName=default-esr\nIsRelative=1\nPath=default-esr\nDefault=1\n".getBytes("UTF-8") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		);
		// El perfil necesita al menos 10 ficheros y un almacen de claves legible
		for (int i = 0; i < 10; i++) {
			Files.write(new File(profile, "relleno" + i).toPath(), new byte[0]); //$NON-NLS-1$
		}
		Files.write(new File(profile, "key4.db").toPath(), new byte[0]); //$NON-NLS-1$
		if (conCert9) {
			Files.write(new File(profile, "cert9.db").toPath(), new byte[0]); //$NON-NLS-1$
		}
		return root;
	}

	private static File crearBase(final File home, final String rel, final boolean conCert9) throws IOException {
		final File db = new File(home, rel);
		Files.createDirectories(db.toPath());
		if (conCert9) {
			Files.write(new File(db, "cert9.db").toPath(), new byte[0]); //$NON-NLS-1$
		}
		return db;
	}

	/** Solo existe la raiz tradicional. @throws Exception En cualquier error. */
	@Test
	public void soloAntigua() throws Exception {
		final File home = home();
		final File root = crearRaizFirefox(home, ".mozilla/firefox", true); //$NON-NLS-1$
		assertEquals(root, NssLinuxDatabases.firefoxRoot(home));
		assertEquals(Collections.singletonList(root), NssLinuxDatabases.firefoxRoots(home));
	}

	/** Solo existe la raiz XDG. @throws Exception En cualquier error. */
	@Test
	public void soloXdg() throws Exception {
		final File home = home();
		final File root = crearRaizFirefox(home, ".config/mozilla/firefox", true); //$NON-NLS-1$
		assertEquals(root, NssLinuxDatabases.firefoxRoot(home));
	}

	/** Con las dos raices se lee la tradicional, como en Firefox, y firefoxRoots devuelve las dos. @throws Exception En cualquier error. */
	@Test
	public void ambas() throws Exception {
		final File home = home();
		final File antigua = crearRaizFirefox(home, ".mozilla/firefox", true); //$NON-NLS-1$
		final File xdg = crearRaizFirefox(home, ".config/mozilla/firefox", true); //$NON-NLS-1$
		assertEquals(antigua, NssLinuxDatabases.firefoxRoot(home));
		assertEquals(Arrays.asList(antigua, xdg), NssLinuxDatabases.firefoxRoots(home));
	}

	/** Un directorio tradicional sin profiles.ini tapa a la XDG al leer, pero firefoxRoots la sigue devolviendo. @throws Exception En cualquier error. */
	@Test
	public void tradicionalSinIniYXdg() throws Exception {
		final File home = home();
		Files.createDirectories(new File(home, ".mozilla/firefox").toPath()); //$NON-NLS-1$
		final File xdg = crearRaizFirefox(home, ".config/mozilla/firefox", true); //$NON-NLS-1$
		assertNull(NssLinuxDatabases.firefoxRoot(home));
		assertEquals(Collections.singletonList(xdg), NssLinuxDatabases.firefoxRoots(home));
		assertTrue(NssLinuxDatabases.findDatabases(home).isEmpty());
	}

	/** Sin ninguna raiz no hay resultado. @throws Exception En cualquier error. */
	@Test
	public void sinFirefox() throws Exception {
		final File home = home();
		assertNull(NssLinuxDatabases.firefoxRoot(home));
		assertTrue(NssLinuxDatabases.firefoxRoots(home).isEmpty());
		assertTrue(NssLinuxDatabases.findDatabases(home).isEmpty());
	}

	/** La base de Chrome heredada tiene preferencia. @throws Exception En cualquier error. */
	@Test
	public void chromeLegacyPrimero() throws Exception {
		final File home = home();
		final File legacy = crearBase(home, ".pki/nssdb", true); //$NON-NLS-1$
		crearBase(home, ".local/share/pki/nssdb", true); //$NON-NLS-1$
		assertEquals(legacy, NssLinuxDatabases.chromeDatabase(home));
	}

	/** ~/.pki/nssdb existe sin cert9.db y la XDG esta poblada: se elige la heredada y findDatabases la descarta
	 * sin caer a la XDG. @throws Exception En cualquier error. */
	@Test
	public void pkiSinCert9ConXdg() throws Exception {
		final File home = home();
		final File legacy = crearBase(home, ".pki/nssdb", false); //$NON-NLS-1$
		crearBase(home, ".local/share/pki/nssdb", true); //$NON-NLS-1$
		assertEquals(legacy, NssLinuxDatabases.chromeDatabase(home));
		assertTrue(NssLinuxDatabases.findDatabases(home).isEmpty());
	}

	/** Sin base heredada se usa la XDG. @throws Exception En cualquier error. */
	@Test
	public void chromeXdg() throws Exception {
		final File home = home();
		final File xdg = crearBase(home, ".local/share/pki/nssdb", true); //$NON-NLS-1$
		assertEquals(xdg, NssLinuxDatabases.chromeDatabase(home));
	}

	/** Usuario que nunca abrio Chrome: solo Firefox. @throws Exception En cualquier error. */
	@Test
	public void sinChrome() throws Exception {
		final File home = home();
		final File root = crearRaizFirefox(home, ".mozilla/firefox", true); //$NON-NLS-1$
		final List<File> dbs = NssLinuxDatabases.findDatabases(home);
		assertEquals(Collections.singletonList(new File(root, PROFILE)), dbs);
	}

	/** Firefox va primero y Chrome despues. @throws Exception En cualquier error. */
	@Test
	public void firefoxYChrome() throws Exception {
		final File home = home();
		final File root = crearRaizFirefox(home, ".mozilla/firefox", true); //$NON-NLS-1$
		final File chrome = crearBase(home, ".pki/nssdb", true); //$NON-NLS-1$
		assertEquals(Arrays.asList(new File(root, PROFILE), chrome), NssLinuxDatabases.findDatabases(home));
	}

	/** Una base que es un enlace al perfil de Firefox no se repite. @throws Exception En cualquier error. */
	@Test
	public void enlaceDeduplicado() throws Exception {
		final File home = home();
		final File root = crearRaizFirefox(home, ".mozilla/firefox", true); //$NON-NLS-1$
		final File pki = new File(home, ".pki");
		Files.createDirectories(pki.toPath());
		final Path link = new File(pki, "nssdb").toPath(); //$NON-NLS-1$
		Files.createSymbolicLink(link, new File(root, PROFILE).toPath());
		final List<File> dbs = NssLinuxDatabases.findDatabases(home);
		assertEquals(Collections.singletonList(new File(root, PROFILE)), dbs);
	}

	/** Un directorio sin cert9.db no entra. @throws Exception En cualquier error. */
	@Test
	public void sinCert9() throws Exception {
		final File home = home();
		crearRaizFirefox(home, ".mozilla/firefox", false); //$NON-NLS-1$
		crearBase(home, ".pki/nssdb", false); //$NON-NLS-1$
		assertTrue(NssLinuxDatabases.findDatabases(home).isEmpty());
	}
}
