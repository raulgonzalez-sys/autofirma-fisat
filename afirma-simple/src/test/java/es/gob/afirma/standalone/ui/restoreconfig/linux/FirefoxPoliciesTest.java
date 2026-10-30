package es.gob.afirma.standalone.ui.restoreconfig.linux;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Pruebas de la fusi&oacute;n de {@code ExtensionSettings} en {@code policies.json}. */
public final class FirefoxPoliciesTest {

	private static final String ID = "autofirma-csp-fix@local"; //$NON-NLS-1$
	private static final String XPI = "/usr/share/Autofirma/autofirma-csp-fix.xpi"; //$NON-NLS-1$

	/** Directorio temporal de cada prueba. */
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private Path policies(final String json) throws IOException {
		final Path p = this.tmp.getRoot().toPath().resolve("policies.json"); //$NON-NLS-1$
		if (json != null) {
			Files.write(p, json.getBytes(StandardCharsets.UTF_8));
		}
		return p;
	}

	private static JSONObject read(final Path p) throws IOException {
		return new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
	}

	private static JSONObject expectedEntry() {
		return new JSONObject()
			.put("installation_mode", "force_installed") //$NON-NLS-1$ //$NON-NLS-2$
			.put("install_url", "file://" + XPI); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Se a&ntilde;ade la extensi&oacute;n y se conservan las dem&aacute;s claves, los permisos y
	 * la segunda llamada no cambia nada.
	 * @throws Exception En cualquier error. */
	@Test
	public void politicasFusiona() throws Exception {
		final Path p = policies("{\"policies\":{\"DisableTelemetry\":true," //$NON-NLS-1$
				+ "\"ExtensionSettings\":{\"otra@ejemplo\":{\"installation_mode\":\"blocked\"}}}}"); //$NON-NLS-1$
		Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-r-----")); //$NON-NLS-1$

		assertTrue(FirefoxPolicies.addExtension(p, ID, XPI));

		final JSONObject pol = read(p).getJSONObject("policies"); //$NON-NLS-1$
		assertTrue(pol.getBoolean("DisableTelemetry")); //$NON-NLS-1$
		final JSONObject es = pol.getJSONObject("ExtensionSettings"); //$NON-NLS-1$
		assertEquals("blocked", es.getJSONObject("otra@ejemplo").getString("installation_mode")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertTrue(es.getJSONObject(ID).toString(), expectedEntry().similar(es.getJSONObject(ID)));
		assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(p))); //$NON-NLS-1$

		final byte[] before = Files.readAllBytes(p);
		assertFalse(FirefoxPolicies.addExtension(p, ID, XPI));
		assertArrayEquals(before, Files.readAllBytes(p));
	}

	/** Sin fichero previo se crea legible por todos con solo la extensi&oacute;n.
	 * @throws Exception En cualquier error. */
	@Test
	public void politicasCreaFichero() throws Exception {
		final Path p = policies(null);
		assertTrue(FirefoxPolicies.addExtension(p, ID, XPI));
		final JSONObject expected = new JSONObject().put("policies", //$NON-NLS-1$
				new JSONObject().put("ExtensionSettings", new JSONObject().put(ID, expectedEntry()))); //$NON-NLS-1$
		assertTrue(read(p).toString(), expected.similar(read(p)));
		assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(p))); //$NON-NLS-1$
	}

	/** Quitar deja las dem&aacute;s claves; si solo queda {@code {"policies":{}}} se borra el fichero.
	 * @throws Exception En cualquier error. */
	@Test
	public void politicasQuita() throws Exception {
		final Path p = policies("{\"policies\":{\"DisableTelemetry\":true}}"); //$NON-NLS-1$
		FirefoxPolicies.addExtension(p, ID, XPI);
		assertTrue(FirefoxPolicies.removeExtension(p, ID));
		assertTrue(new JSONObject("{\"policies\":{\"DisableTelemetry\":true}}").similar(read(p))); //$NON-NLS-1$
		assertFalse(FirefoxPolicies.removeExtension(p, ID));

		Files.delete(p);
		FirefoxPolicies.addExtension(p, ID, XPI);
		assertTrue(FirefoxPolicies.removeExtension(p, ID));
		assertFalse(Files.exists(p));
		assertFalse(FirefoxPolicies.removeExtension(p, ID));
	}

	/** Un JSON inv&aacute;lido no se toca y se informa con una excepci&oacute;n.
	 * @throws Exception En cualquier error. */
	@Test
	public void politicasInvalidasNoSeTocan() throws Exception {
		final Path p = policies("{\"policies\": [1, 2"); //$NON-NLS-1$
		final byte[] before = Files.readAllBytes(p);
		try {
			FirefoxPolicies.addExtension(p, ID, XPI);
			fail("Se esperaba IOException"); //$NON-NLS-1$
		}
		catch (final IOException e) {
			// Esperado
		}
		assertArrayEquals(before, Files.readAllBytes(p));
	}
}
