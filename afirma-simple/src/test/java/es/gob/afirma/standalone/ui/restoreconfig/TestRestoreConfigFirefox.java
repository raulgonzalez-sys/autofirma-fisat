package es.gob.afirma.standalone.ui.restoreconfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import es.gob.afirma.core.misc.Platform;

/** Pruebas del restaurador de configuracion para Firefox y de la deteccion de root en Linux. */
public final class TestRestoreConfigFirefox {

	/** Directorio temporal que hace de home. */
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** El inicializador estatico de la clase lanza excepcion en sistemas no soportados. */
	@Before
	public void onlyLinux() {
		Assume.assumeTrue(Platform.getOS() == Platform.OS.LINUX);
	}

	private static void touch(final File home, final String relative) throws Exception {
		final File f = new File(home, relative);
		Files.createDirectories(f.getParentFile().toPath());
		Files.write(f.toPath(), "[General]\n".getBytes("UTF-8")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Comprueba que se localizan los <i>profiles.ini</i> de snap, .config/mozilla y .mozilla. */
	@Test
	public void tres_rutas() throws Exception {
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		touch(home, "snap/firefox/common/.mozilla/firefox/profiles.ini"); //$NON-NLS-1$
		touch(home, ".config/mozilla/firefox/profiles.ini"); //$NON-NLS-1$
		touch(home, ".mozilla/firefox/profiles.ini"); //$NON-NLS-1$
		final List<File> found = RestoreConfigFirefox.getMozillaUsersProfilesPath(Arrays.asList(home.getAbsolutePath()));
		assertEquals(found.toString(), 3, found.size());
	}

	/** Comprueba que la salida de <code>id -u</code> con salto de linea se reconoce como root. */
	@Test
	public void isRootUid_con_salto() {
		assertTrue(RestoreConfigLinux.isRootUid("0\n")); //$NON-NLS-1$
		assertFalse(RestoreConfigLinux.isRootUid("1000\n")); //$NON-NLS-1$
		assertFalse(RestoreConfigLinux.isRootUid(null));
	}
}
