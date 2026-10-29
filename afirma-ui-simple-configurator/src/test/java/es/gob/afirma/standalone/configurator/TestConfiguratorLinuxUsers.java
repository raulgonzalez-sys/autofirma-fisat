package es.gob.afirma.standalone.configurator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Pruebas de la enumeracion de directorios de usuario en Linux.
 * Para que la clase sea probada, {@code ConfiguratorLinux} expone sus metodos
 * con visibilidad de paquete. */
public final class TestConfiguratorLinuxUsers {

	/** Directorio temporal. */
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static String lines(final String... lines) {
		final StringBuilder sb = new StringBuilder();
		for (final String line : lines) {
			sb.append(line).append('\n');
		}
		return sb.toString();
	}

	/** Un /etc/passwd tipico: solo se devuelven los homes bajo /home. */
	@Test
	public void parse_tipico() {
		final List<File> homes = ConfiguratorLinux.parsePasswdHomes(lines(
			"root:x:0:0::/root:/bin/bash", //$NON-NLS-1$
			"daemon:x:1:1::/usr/sbin:/usr/sbin/nologin", //$NON-NLS-1$
			"ana:x:1000:1000:Ana:/home/ana:/bin/bash", //$NON-NLS-1$
			"luis:x:1001:1001::/home/luis:/bin/bash" //$NON-NLS-1$
		));
		assertEquals(Arrays.asList(new File("/home/ana"), new File("/home/luis")), homes); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Homes de usuarios de red con arroba y espacios: la ruta se trata entera. */
	@Test
	public void parse_getent() {
		final List<File> homes = ConfiguratorLinux.parsePasswdHomes(lines(
			"usuario1@example.org:*:1100001:1100001:Usuario Uno:/home/usuario1@example.org:/bin/bash", //$NON-NLS-1$
			"udos:*:1100002:1100002:Usuaria:/home/dom/Usuaria \u00D1\u00FA\u00F1ez:/bin/bash" //$NON-NLS-1$
		));
		assertEquals(
			Arrays.asList(new File("/home/usuario1@example.org"), new File("/home/dom/Usuaria \u00D1\u00FA\u00F1ez")), //$NON-NLS-1$ //$NON-NLS-2$
			homes
		);
	}

	/** Un home repetido, con o sin barra final, aparece una sola vez. */
	@Test
	public void parse_duplicados_y_barra_final() {
		final List<File> homes = ConfiguratorLinux.parsePasswdHomes(lines(
			"ana:x:1000:1000::/home/ana:/bin/bash", //$NON-NLS-1$
			"ana2:x:1002:1002::/home/ana/:/bin/bash" //$NON-NLS-1$
		));
		assertEquals(1, homes.size());
		assertEquals(new File("/home/ana"), homes.get(0)); //$NON-NLS-1$
	}

	/** Las lineas malformadas se ignoran sin excepcion. */
	@Test
	public void parse_malformadas() {
		final List<File> homes = ConfiguratorLinux.parsePasswdHomes(lines(
			"", //$NON-NLS-1$
			"#x", //$NON-NLS-1$
			"solo:dos", //$NON-NLS-1$
			"vacio:x:1:1::   :/bin/bash", //$NON-NLS-1$
			"rel:x:2:2::home/x:/bin/bash" //$NON-NLS-1$
		));
		assertNotNull(homes);
		assertTrue(homes.isEmpty());

		final List<File> nulo = ConfiguratorLinux.parsePasswdHomes(null);
		assertNotNull(nulo);
		assertTrue(nulo.isEmpty());
	}

	/** Miles de usuarios (directorio que enumera todos) no abortan el analisis. */
	@Test
	public void parse_muchas_lineas() {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			sb.append("u").append(i).append(":x:").append(i).append(':').append(i) //$NON-NLS-1$ //$NON-NLS-2$
				.append("::/home/u").append(i).append(":/bin/bash\n"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		final List<File> homes = ConfiguratorLinux.parsePasswdHomes(sb.toString());
		assertEquals(5000, homes.size());
	}

	/** Solo se conservan los homes que son directorios existentes.
	 * @throws IOException En cualquier error. */
	@Test
	public void filterExisting() throws IOException {
		final File a = this.tmp.newFolder("a"); //$NON-NLS-1$
		final File b = this.tmp.newFile("b"); //$NON-NLS-1$
		final File c = new File(this.tmp.getRoot(), "c"); //$NON-NLS-1$
		final List<File> filtered = ConfiguratorLinux.filterExistingHomes(Arrays.asList(a, b, c));
		assertEquals(Arrays.asList(a), filtered);
	}
}
