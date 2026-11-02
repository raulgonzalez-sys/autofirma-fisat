package es.gob.afirma.standalone.ui.restoreconfig.linux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import es.gob.afirma.standalone.ui.restoreconfig.RestoreConfigLinuxHooks;

/** Pruebas del comando {@code autofirma restaurar} en Linux, sobre directorios temporales. */
public final class RestoreCommandLinuxTest {

	private static final String ID = "autofirma-csp-fix@local"; //$NON-NLS-1$
	private static final String XPI = "/usr/share/Autofirma/autofirma-csp-fix.xpi"; //$NON-NLS-1$

	/** Directorio temporal de cada prueba. */
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private static boolean contains(final List<String> lines, final String text) {
		for (final String line : lines) {
			if (line.contains(text)) {
				return true;
			}
		}
		return false;
	}

	private static String[] args(final String... a) {
		return a;
	}

	/** Sin ser root, {@code --sistema} sale con 2 y avisa en castellano sin tocar nada. */
	@Test
	public void sistemaSinRoot() {
		final List<String> out = new ArrayList<>();
		final int code = RestoreCommandLinux.run(args("--sistema"), out::add, false, null, new File("/nonexistent/ca.cer"), //$NON-NLS-1$ //$NON-NLS-2$
				new File("/nonexistent/etc")); //$NON-NLS-1$
		assertEquals(2, code);
		assertTrue(out.toString(), contains(out, "administrador")); //$NON-NLS-1$
		assertTrue(out.toString(), out.get(0).startsWith(RestoreReport.ERROR));
	}

	/** Sin ser root, {@code --quitar-politicas} tampoco hace nada y sale con 2. */
	@Test
	public void quitarPoliticasSinRoot() throws Exception {
		final File etc = this.tmp.newFolder("etc"); //$NON-NLS-1$
		final Path json = policies(etc, "firefox"); //$NON-NLS-1$
		final byte[] before = Files.readAllBytes(json);
		final List<String> out = new ArrayList<>();
		assertEquals(2, RestoreCommandLinux.run(args("--quitar-politicas"), out::add, false, null, null, etc)); //$NON-NLS-1$
		assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(json)));
	}

	private static Path policies(final File etc, final String browser) throws IOException {
		final Path json = new File(etc, browser + "/policies/policies.json").toPath(); //$NON-NLS-1$
		Files.createDirectories(json.getParent());
		FirefoxPolicies.addExtension(json, ID, XPI);
		return json;
	}

	/** {@code --quitar-politicas} quita la extensi&oacute;n de los dos {@code policies.json}. */
	@Test
	public void quitarPoliticas() throws Exception {
		final File etc = this.tmp.newFolder("etc"); //$NON-NLS-1$
		final Path ff = policies(etc, "firefox"); //$NON-NLS-1$
		final Path esr = policies(etc, "firefox-esr"); //$NON-NLS-1$
		final List<String> out = new ArrayList<>();
		assertEquals(out.toString(), 0, RestoreCommandLinux.run(args("--quitar-politicas"), out::add, true, null, null, etc)); //$NON-NLS-1$
		assertFalse(Files.exists(ff));
		assertFalse(Files.exists(esr));
		assertTrue(out.toString(), contains(out, ff.toString()));
		assertTrue(out.toString(), contains(out, esr.toString()));
		assertFalse(out.toString(), contains(out, RestoreReport.ERROR));

		// Idempotente: una segunda vez no falla
		assertEquals(0, RestoreCommandLinux.run(args("--quitar-politicas"), s -> { /* nada */ }, true, null, null, etc)); //$NON-NLS-1$
	}

	/** Un {@code policies.json} ilegible deja error y c&oacute;digo 1 sin dejar de procesar el otro. */
	@Test
	public void quitarPoliticasConFicheroRoto() throws Exception {
		final File etc = this.tmp.newFolder("etc"); //$NON-NLS-1$
		final Path ff = new File(etc, "firefox/policies/policies.json").toPath(); //$NON-NLS-1$
		Files.createDirectories(ff.getParent());
		Files.write(ff, "{roto".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		final Path esr = policies(etc, "firefox-esr"); //$NON-NLS-1$
		final List<String> out = new ArrayList<>();
		assertEquals(1, RestoreCommandLinux.run(args("--quitar-politicas"), out::add, true, null, null, etc)); //$NON-NLS-1$
		assertFalse(Files.exists(esr));
		assertTrue(out.toString(), contains(out, RestoreReport.ERROR));
	}

	/** Con el entorno real, {@code --quitar-politicas} usa el directorio de pruebas en lugar de {@code /etc}. */
	@Test
	public void quitarPoliticasConEtcDePrueba() throws Exception {
		assumeTrue("Hace falta ser root", RestoreCommandLinux.isRoot()); //$NON-NLS-1$
		final File etc = this.tmp.newFolder("etc"); //$NON-NLS-1$
		final Path ff = policies(etc, "firefox"); //$NON-NLS-1$
		RestoreCommandLinux.testEtcDir = etc;
		try {
			assertEquals(0, RestoreCommandLinux.run(args("--quitar-politicas"), s -> { /* nada */ })); //$NON-NLS-1$
		}
		finally {
			RestoreCommandLinux.testEtcDir = null;
		}
		assertFalse(Files.exists(ff));
	}

	/** {@code --usuario} restaura el home indicado: aqu&iacute;, con {@code user.js} en un perfil de Firefox. */
	@Test
	public void usuarioRestauraHome() throws Exception {
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		final File root = new File(home, ".mozilla/firefox"); //$NON-NLS-1$
		final File profile = new File(root, "abc.default"); //$NON-NLS-1$
		assertTrue(profile.mkdirs());
		Files.write(new File(root, "profiles.ini").toPath(), //$NON-NLS-1$
				"[Profile0]\nName=default\nIsRelative=1\nPath=abc.default\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		final File ca = this.tmp.newFile("Autofirma_ROOT.cer"); //$NON-NLS-1$
		Files.write(ca.toPath(), RestoreConfigLinuxHooks.generateSslCertificates()[1]);

		final List<String> out = new ArrayList<>();
		final int code = RestoreCommandLinux.run(args("--usuario"), out::add, false, home.getPath(), ca, null); //$NON-NLS-1$
		assertEquals(out.toString(), 0, code);
		final String userJs = new String(Files.readAllBytes(new File(profile, "user.js").toPath()), StandardCharsets.UTF_8); //$NON-NLS-1$
		assertTrue(userJs, userJs.contains("network.protocol-handler.expose.afirma")); //$NON-NLS-1$
		assertTrue(out.toString(), contains(out, RestoreReport.OK));
	}

	/** Home con un perfil de Firefox y la CA de prueba; devuelve el perfil. */
	private static File homeConPerfil(final File home) throws Exception {
		final File root = new File(home, ".mozilla/firefox"); //$NON-NLS-1$
		final File profile = new File(root, "abc.default"); //$NON-NLS-1$
		assertTrue(profile.mkdirs());
		Files.write(new File(root, "profiles.ini").toPath(), //$NON-NLS-1$
				"[Profile0]\nName=default\nIsRelative=1\nPath=abc.default\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		return profile;
	}

	private File ca() throws Exception {
		final File ca = new File(this.tmp.getRoot(), "Autofirma_ROOT.cer"); //$NON-NLS-1$
		if (!ca.isFile()) {
			Files.write(ca.toPath(), RestoreConfigLinuxHooks.generateSslCertificates()[1]);
		}
		return ca;
	}

	/** Como root, {@code --usuario} no toca un home que no es de root ({@code su} sin guion o
	 * {@code sudo -E}): sale con 2, dice c&oacute;mo hacerlo y no escribe nada. */
	@Test
	public void usuarioComoRootRechazaHomeAjeno() throws Exception {
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		final File profile = homeConPerfil(home);
		final List<String> out = new ArrayList<>();
		final int code = RestoreCommandLinux.run(args("--usuario"), out::add, true, home.getPath(), ca(), null, //$NON-NLS-1$
				p -> 1000);
		assertEquals(out.toString(), 2, code);
		assertEquals(out.toString(), 1, out.size());
		assertTrue(out.toString(), out.get(0).startsWith(RestoreReport.ERROR));
		assertTrue(out.toString(), contains(out, "«Todo el sistema»")); //$NON-NLS-1$
		assertTrue(out.toString(), contains(out, "UID 1000")); //$NON-NLS-1$
		assertFalse(new File(profile, "user.js").exists()); //$NON-NLS-1$
	}

	/** Como root, un home cuyo due&ntilde;o no se puede leer tambi&eacute;n se rechaza. */
	@Test
	public void usuarioComoRootSinDueno() throws Exception {
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		final File profile = homeConPerfil(home);
		final List<String> out = new ArrayList<>();
		final int code = RestoreCommandLinux.run(args("--usuario"), out::add, true, home.getPath(), ca(), null, //$NON-NLS-1$
				p -> { throw new IOException("sin acceso"); }); //$NON-NLS-1$
		assertEquals(out.toString(), 2, code);
		assertTrue(out.toString(), contains(out, "sin acceso")); //$NON-NLS-1$
		assertFalse(new File(profile, "user.js").exists()); //$NON-NLS-1$
	}

	/** Como root, el propio home de root (due&ntilde;o UID 0) s&iacute; se restaura. */
	@Test
	public void usuarioComoRootConHomeDeRoot() throws Exception {
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		final File profile = homeConPerfil(home);
		final List<String> out = new ArrayList<>();
		final int code = RestoreCommandLinux.run(args("--usuario"), out::add, true, home.getPath(), ca(), null, //$NON-NLS-1$
				p -> 0);
		assertEquals(out.toString(), 0, code);
		assertTrue(new File(profile, "user.js").isFile()); //$NON-NLS-1$
	}

	/** Con el due&ntilde;o real del directorio: un home que no es de root se rechaza como root
	 * (siendo root, el home de prueba se pasa antes a otro UID). */
	@Test
	public void usuarioComoRootConDuenoReal() throws Exception {
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		final File profile = homeConPerfil(home);
		if (RestoreCommandLinux.isRoot()) {
			Files.setAttribute(home.toPath(), "unix:uid", Integer.valueOf(54321)); //$NON-NLS-1$
		}
		final List<String> out = new ArrayList<>();
		assertEquals(out.toString(), 2, RestoreCommandLinux.run(args("--usuario"), out::add, true, home.getPath(), ca(), null)); //$NON-NLS-1$
		assertFalse(new File(profile, "user.js").exists()); //$NON-NLS-1$
	}

	/** {@code isRoot} coincide con {@code id -u}. */
	@Test
	public void isRootComoIdU() throws Exception {
		final Process p = new ProcessBuilder("id", "-u").redirectErrorStream(true).start(); //$NON-NLS-1$ //$NON-NLS-2$
		final String uid;
		try (java.io.InputStream is = p.getInputStream()) {
			final byte[] b = new byte[64];
			final int n = is.read(b);
			uid = new String(b, 0, Math.max(n, 0), StandardCharsets.US_ASCII).trim();
		}
		assertTrue(p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
		assertEquals(uid, "0".equals(uid), RestoreCommandLinux.isRoot()); //$NON-NLS-1$
	}

	/** Sin certificado ra&iacute;z, {@code --usuario} sale con 1 y lo dice. */
	@Test
	public void usuarioSinCa() throws Exception {
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		final List<String> out = new ArrayList<>();
		final int code = RestoreCommandLinux.run(args("--usuario"), out::add, false, home.getPath(), //$NON-NLS-1$
				new File(this.tmp.getRoot(), "no-existe.cer"), null); //$NON-NLS-1$
		assertEquals(1, code);
		assertTrue(out.toString(), contains(out, RestoreReport.ERROR));
	}

	/** El home es {@code HOME} si es un directorio y, si no, {@code user.home}. */
	@Test
	public void resolveHome() throws Exception {
		final File dir = this.tmp.newFolder("h"); //$NON-NLS-1$
		assertEquals(dir, RestoreCommandLinux.resolveHome(dir.getPath(), "/otro")); //$NON-NLS-1$
		assertEquals(new File("/otro"), RestoreCommandLinux.resolveHome(null, "/otro")); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(new File("/otro"), RestoreCommandLinux.resolveHome("", "/otro")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertEquals(new File("/otro"), RestoreCommandLinux.resolveHome(new File(dir, "no-existe").getPath(), "/otro")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** Un argumento desconocido o ninguno sale con 2 y muestra el uso. */
	@Test
	public void argumentoDesconocido() {
		for (final String[] a : new String[][] { args("--nada"), args(), args("--usuario", "--sistema"), null }) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			final List<String> out = new ArrayList<>();
			assertEquals(2, RestoreCommandLinux.run(a, out::add));
			assertTrue(out.toString(), contains(out, "Uso: autofirma restaurar")); //$NON-NLS-1$
		}
	}

	/** La entrada real que usa el lanzador ({@code java -cp autofirma.jar RestoreCommandLinux restaurar --usuario}),
	 * con un home temporal y sin CA del sistema, no crea {@code ~/.afirma}: no carga {@code SimpleAfirma},
	 * cuyo arranque instala el registro en {@code ~/.afirma}.
	 * @throws Exception En cualquier error. */
	@Test
	public void entradaRealNoCreaAfirma() throws Exception {
		final File home = this.tmp.newFolder("home real"); //$NON-NLS-1$
		final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")); //$NON-NLS-1$ //$NON-NLS-2$
		final ProcessBuilder pb = new ProcessBuilder(
				new File(System.getProperty("java.home"), "bin/java").getPath(), //$NON-NLS-1$ //$NON-NLS-2$
				"-Duser.home=" + home.getPath(), //$NON-NLS-1$
				"-cp", classpath, //$NON-NLS-1$
				RestoreCommandLinux.class.getName(), "restaurar", "--usuario") //$NON-NLS-1$ //$NON-NLS-2$
			.redirectErrorStream(true)
			.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))); //$NON-NLS-1$
		pb.environment().put("HOME", home.getPath()); //$NON-NLS-1$
		final Process p = pb.start();
		final String out;
		try (java.io.InputStream is = p.getInputStream()) {
			final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			final byte[] b = new byte[4096];
			int n;
			while ((n = is.read(b)) != -1) {
				buf.write(b, 0, n);
			}
			out = new String(buf.toByteArray(), StandardCharsets.UTF_8);
		}
		assertTrue(out, p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
		final boolean ca = new File(RestoreCommandLinux.CA_FILE).isFile();
		assertEquals(out, ca ? 0 : 1, p.exitValue());
		if (!ca) {
			assertTrue(out, out.contains(RestoreReport.ERROR + "No se puede leer el certificado raíz")); //$NON-NLS-1$
		}
		assertFalse(out, new File(home, ".afirma").exists()); //$NON-NLS-1$
		assertEquals(out, 0, home.list().length);
	}

	/** El comando acepta el primer argumento {@code restaurar} que le pasa el lanzador. */
	@Test
	public void acepta_restaurar_como_primer_argumento() {
		final List<String> out = new ArrayList<>();
		assertEquals(2, RestoreCommandLinux.run(args("restaurar", "--nada"), out::add)); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(out.toString(), contains(out, "--nada")); //$NON-NLS-1$
	}
}
