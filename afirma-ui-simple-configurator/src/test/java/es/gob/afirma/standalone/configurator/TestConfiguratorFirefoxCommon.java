package es.gob.afirma.standalone.configurator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Pruebas de la deteccion de almacenes NSS protegidos por contrasena maestra.
 * Usan un <i>certutil</i> falso (script de shell), por lo que solo se ejecutan en Linux. */
public final class TestConfiguratorFirefoxCommon {

	/** Directorio temporal para el certutil falso y los perfiles. */
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** Crea un certutil falso ejecutable con el cuerpo indicado.
	 * @param body Cuerpo del script.
	 * @return Ruta del script.
	 * @throws Exception En cualquier error. */
	private File fakeCertUtil(final String body) throws Exception {
		Assume.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux")); //$NON-NLS-1$ //$NON-NLS-2$
		final File script = this.tmp.newFile("certutil-" + System.nanoTime()); //$NON-NLS-1$
		Files.write(script.toPath(), ("#!/bin/sh\n" + body + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(script.setExecutable(true));
		// Por si el directorio temporal esta montado noexec
		Assume.assumeTrue(script.canExecute());
		assumeExecutable();
		return script;
	}

	/** Comprueba que el directorio temporal permite ejecutar scripts (no esta montado noexec).
	 * @throws Exception En cualquier error. */
	private void assumeExecutable() throws Exception {
		final File probe = this.tmp.newFile("sonda-" + System.nanoTime()); //$NON-NLS-1$
		Files.write(probe.toPath(), "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		assertTrue(probe.setExecutable(true));
		try {
			Assume.assumeTrue(new ProcessBuilder(probe.getAbsolutePath()).start().waitFor() == 0);
		}
		catch (final IOException e) {
			Assume.assumeNoException(e);
		}
	}

	/** Un certutil que pide la contrasena indica almacen protegido, sin esperar al timeout.
	 * @throws Exception En cualquier error. */
	@Test
	public void protegido_por_prompt() throws Exception {
		final File fake = fakeCertUtil("echo 'Enter Password or Pin for \"NSS Certificate DB\":'; sleep 30"); //$NON-NLS-1$
		final File profile = this.tmp.newFolder("perfil"); //$NON-NLS-1$
		final long start = System.nanoTime();
		assertTrue(ConfiguratorFirefoxCommon.isNssDbPasswordProtected(fake.getAbsolutePath(), profile, 3000L));
		assertTrue("Tardo demasiado: " + (System.nanoTime() - start) / 1000000L + " ms", //$NON-NLS-1$ //$NON-NLS-2$
				System.nanoTime() - start < 2000L * 1000000L);
	}

	/** Un certutil que termina sin pedir contrasena indica almacen no protegido.
	 * @throws Exception En cualquier error. */
	@Test
	public void no_protegido() throws Exception {
		final File fake = fakeCertUtil("echo 'certutil: no keys found'; exit 0"); //$NON-NLS-1$
		final File profile = this.tmp.newFolder("perfil"); //$NON-NLS-1$
		assertFalse(ConfiguratorFirefoxCommon.isNssDbPasswordProtected(fake.getAbsolutePath(), profile, 3000L));
		assertFalse(ConfiguratorFirefoxCommon.isNssDbPasswordProtected(fake.getAbsolutePath(), profile));
	}

	/** Un certutil colgado sin salida se trata como protegido (falla en cerrado).
	 * @throws Exception En cualquier error. */
	@Test
	public void silencioso_timeout() throws Exception {
		final File fake = fakeCertUtil("sleep 30"); //$NON-NLS-1$
		final File profile = this.tmp.newFolder("perfil"); //$NON-NLS-1$
		final long start = System.nanoTime();
		assertTrue(ConfiguratorFirefoxCommon.isNssDbPasswordProtected(fake.getAbsolutePath(), profile, 300L));
		assertTrue("Tardo demasiado: " + (System.nanoTime() - start) / 1000000L + " ms", //$NON-NLS-1$ //$NON-NLS-2$
				System.nanoTime() - start < 2000L * 1000000L);
	}

	/** Un certutil inexistente se trata como protegido, sin excepcion.
	 * @throws Exception En cualquier error. */
	@Test
	public void ruta_inexistente() throws Exception {
		final File profile = this.tmp.newFolder("perfil"); //$NON-NLS-1$
		assertTrue(ConfiguratorFirefoxCommon.isNssDbPasswordProtected("/no/existe", profile)); //$NON-NLS-1$
		assertTrue(ConfiguratorFirefoxCommon.isNssDbPasswordProtected("/no/existe", profile, 300L)); //$NON-NLS-1$
	}

	/** Se usa el prefijo <code>sql:</code> solo cuando existe pkcs11.txt.
	 * @throws Exception En cualquier error. */
	@Test
	public void usa_sql_si_hay_pkcs11() throws Exception {
		final File args = new File(this.tmp.getRoot(), "args.txt"); //$NON-NLS-1$
		final File fake = fakeCertUtil("printf '%s\\n' \"$@\" > '" + args.getAbsolutePath() + "'; exit 0"); //$NON-NLS-1$ //$NON-NLS-2$

		final File sinSql = this.tmp.newFolder("sin-sql"); //$NON-NLS-1$
		assertFalse(ConfiguratorFirefoxCommon.isNssDbPasswordProtected(fake.getAbsolutePath(), sinSql, 3000L));
		List<String> lines = Files.readAllLines(args.toPath(), StandardCharsets.UTF_8);
		assertEquals(sinSql.getAbsolutePath(), lines.get(lines.indexOf("-d") + 1)); //$NON-NLS-1$

		final File conSql = this.tmp.newFolder("con-sql"); //$NON-NLS-1$
		assertTrue(new File(conSql, "pkcs11.txt").createNewFile()); //$NON-NLS-1$
		assertFalse(ConfiguratorFirefoxCommon.isNssDbPasswordProtected(fake.getAbsolutePath(), conSql, 3000L));
		lines = Files.readAllLines(args.toPath(), StandardCharsets.UTF_8);
		assertEquals("sql:" + conSql.getAbsolutePath(), lines.get(lines.indexOf("-d") + 1)); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
