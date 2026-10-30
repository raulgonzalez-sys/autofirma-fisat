package es.gob.afirma.standalone.ui.restoreconfig.linux;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Pruebas de la parte de usuario de Restaurar en Linux con bases NSS reales. */
public final class RestoreUserLinuxTest {

	private static final String CERTUTIL = "/usr/bin/certutil"; //$NON-NLS-1$
	private static final String NICK = "SocketAutoFirma"; //$NON-NLS-1$
	private static final String LEGACY = "Autofirma ROOT"; //$NON-NLS-1$
	private static final String EXPOSE = "user_pref(\"network.protocol-handler.expose.afirma\", true);"; //$NON-NLS-1$
	private static final String PERMISSION = "user_pref(\"security.external_protocol_requires_permission\", false);"; //$NON-NLS-1$
	private static final String LEGACY_ROOT = ".mozilla/firefox"; //$NON-NLS-1$
	private static final String XDG_ROOT = ".config/mozilla/firefox"; //$NON-NLS-1$
	private static final Pattern TRUST = Pattern.compile(" +([A-Za-z]*,[A-Za-z]*,[A-Za-z]*) *"); //$NON-NLS-1$

	/** Directorio de la CA y de las bases auxiliares que generan certificados. */
	@ClassRule
	public static final TemporaryFolder SHARED = new TemporaryFolder();

	/** Directorio que hace de home en cada prueba. */
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private static File ca;
	private static int serial = 100;

	/** Genera la CA de prueba (se salta todo si no hay {@code certutil}).
	 * @throws Exception Si falla la generaci&oacute;n. */
	@BeforeClass
	public static void createCa() throws Exception {
		assumeTrue("Falta " + CERTUTIL, new File(CERTUTIL).canExecute()); //$NON-NLS-1$
		ca = new File(SHARED.getRoot(), "Autofirma_ROOT.cer"); //$NON-NLS-1$
		Files.write(ca.toPath(), newCert("CN=AutoFirma ROOT prueba")); //$NON-NLS-1$
	}

	/** Certificado autofirmado nuevo, en DER, generado en una base auxiliar. */
	private static byte[] newCert(final String subject) throws Exception {
		final File dir = SHARED.newFolder();
		run(CERTUTIL, "-N", "--empty-password", "-d", "sql:" + dir); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		final File noise = new File(dir, "noise"); //$NON-NLS-1$
		Files.write(noise.toPath(), subject.concat("0123456789abcdefghijklmnopqrstuvwxyz0123456789").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		run(CERTUTIL, "-S", "-n", "c", "-s", subject, "-x", "-t", "C,,", "-m", Integer.toString(serial++), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
				"-z", noise.getPath(), "-f", "/dev/null", "-d", "sql:" + dir); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		final File der = new File(dir, "c.der"); //$NON-NLS-1$
		run(CERTUTIL, "-L", "-n", "c", "-r", "-o", der.getPath(), "-d", "sql:" + dir); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
		return Files.readAllBytes(der.toPath());
	}

	/** Ejecuta un comando sin entrada; falla si no sale con 0. */
	private static String run(final String... cmd) throws Exception {
		final Result r = exec(cmd);
		assertEquals("Fallo de " + Arrays.toString(cmd) + ": " + r.out, 0, r.code); //$NON-NLS-1$ //$NON-NLS-2$
		return r.out;
	}

	private static Result exec(final String... cmd) throws Exception {
		final Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
				.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start(); //$NON-NLS-1$
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (InputStream is = p.getInputStream()) {
			final byte[] buf = new byte[4096];
			int n;
			while ((n = is.read(buf)) != -1) {
				out.write(buf, 0, n);
			}
		}
		assertTrue("Sin terminar: " + Arrays.toString(cmd), p.waitFor(30, TimeUnit.SECONDS)); //$NON-NLS-1$
		return new Result(p.exitValue(), new String(out.toByteArray(), StandardCharsets.UTF_8));
	}

	private static final class Result {
		final int code;
		final String out;
		Result(final int code, final String out) {
			this.code = code;
			this.out = out;
		}
	}

	private File home() throws IOException {
		return this.tmp.newFolder("home de prueba"); //$NON-NLS-1$
	}

	/** Crea una ra&iacute;z con un {@code profiles.ini} de un perfil y la base NSS del perfil. */
	private static File profile(final File home, final String root, final String name) throws Exception {
		final File rootDir = new File(home, root);
		final File dir = new File(rootDir, name);
		assertTrue(dir.mkdirs());
		Files.write(new File(rootDir, "profiles.ini").toPath(), //$NON-NLS-1$
				("[Profile0]\nName=default\nIsRelative=1\nPath=" + name + "\nDefault=1\n\n[General]\nStartWithLastProfile=1\n") //$NON-NLS-1$ //$NON-NLS-2$
						.getBytes(StandardCharsets.UTF_8));
		newDb(dir);
		return dir;
	}

	private static void newDb(final File dir) throws Exception {
		assertTrue(dir.isDirectory() || dir.mkdirs());
		run(CERTUTIL, "-N", "--empty-password", "-d", "sql:" + dir); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	private static void add(final File db, final String nick, final byte[] der) throws Exception {
		final File f = File.createTempFile("cert", ".der", SHARED.getRoot()); //$NON-NLS-1$ //$NON-NLS-2$
		Files.write(f.toPath(), der);
		run(CERTUTIL, "-A", "-n", nick, "-t", "C,,", "-i", f.getPath(), "-d", "sql:" + db); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}

	/** Banderas de confianza de cada entrada con ese apodo exacto. */
	private static List<String> entries(final File db, final String nick) throws Exception {
		final List<String> trust = new ArrayList<>();
		for (final String line : run(CERTUTIL, "-L", "-d", "sql:" + db).split("\n")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			if (line.startsWith(nick)) {
				final Matcher m = TRUST.matcher(line.substring(nick.length()));
				if (m.matches()) {
					trust.add(m.group(1));
				}
			}
		}
		return trust;
	}

	private static void assertCaInstalled(final File db) throws Exception {
		assertEquals(Collections.singletonList("C,,"), entries(db, NICK)); //$NON-NLS-1$
		assertEquals(Collections.emptyList(), entries(db, LEGACY));
		final File der = new File(SHARED.newFolder(), "salida.der"); //$NON-NLS-1$
		run(CERTUTIL, "-L", "-n", NICK, "-r", "-o", der.getPath(), "-d", "sql:" + db); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
		final byte[] expected = Files.readAllBytes(ca.toPath());
		assertArrayEquals(expected, Arrays.copyOf(Files.readAllBytes(der.toPath()), expected.length));
	}

	private static RestoreReport restore(final File home) {
		final RestoreReport report = new RestoreReport(null);
		RestoreUserLinux.restore(home, ca, report);
		return report;
	}

	private static boolean hasLine(final RestoreReport report, final String prefix, final String text) {
		for (final String line : report.getLines()) {
			if (line.startsWith(prefix) && line.contains(text)) {
				return true;
			}
		}
		return false;
	}

	private static List<String> tree(final File dir) throws IOException {
		try (Stream<Path> s = Files.walk(dir.toPath())) {
			return s.map(Path::toString).sorted().collect(Collectors.toList());
		}
	}

	/** La CA queda en el perfil de Firefox y en la base de Chrome con confianza {@code C,,}.
	 * @throws Exception En cualquier error. */
	@Test
	public void instalaEnFirefoxYChrome() throws Exception {
		final File home = home();
		final File ff = profile(home, LEGACY_ROOT, "abc.default-esr"); //$NON-NLS-1$
		final File chrome = new File(home, ".local/share/pki/nssdb"); //$NON-NLS-1$
		newDb(chrome);
		final RestoreReport report = restore(home);
		assertFalse(report.getLines().toString(), report.hasErrors());
		assertCaInstalled(ff);
		assertCaInstalled(chrome);
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.OK, chrome.getPath()));
	}

	/** Las entradas previas con los dos apodos se borran y queda una sola; el resto no se toca.
	 * @throws Exception En cualquier error. */
	@Test
	public void borraAntiguas() throws Exception {
		final File home = home();
		final File ff = profile(home, LEGACY_ROOT, "abc.default"); //$NON-NLS-1$
		add(ff, NICK, newCert("CN=Vieja")); //$NON-NLS-1$
		add(ff, NICK, newCert("CN=Vieja")); //$NON-NLS-1$
		add(ff, LEGACY, newCert("CN=Antigua")); //$NON-NLS-1$
		final File noise = new File(SHARED.getRoot(), "noise-personal"); //$NON-NLS-1$
		Files.write(noise.toPath(), "ruido-personal-0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		run(CERTUTIL, "-S", "-n", "personal", "-s", "CN=Persona", "-x", "-t", "u,u,u", "-m", "9", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
				"-z", noise.getPath(), "-f", "/dev/null", "-d", "sql:" + ff); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		assertEquals(2, entries(ff, NICK).size());
		final String keysBefore = run(CERTUTIL, "-K", "-f", "/dev/null", "-d", "sql:" + ff); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

		final RestoreReport report = restore(home);
		assertFalse(report.getLines().toString(), report.hasErrors());
		assertCaInstalled(ff);
		assertEquals(Collections.singletonList("u,u,u"), entries(ff, "personal")); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(keysBefore, run(CERTUTIL, "-K", "-f", "/dev/null", "-d", "sql:" + ff)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** Dos restauraciones seguidas dejan una sola entrada y la segunda no cambia nada.
	 * @throws Exception En cualquier error. */
	@Test
	public void idempotente() throws Exception {
		final File home = home();
		final File ff = profile(home, LEGACY_ROOT, "abc.default"); //$NON-NLS-1$
		restore(home);
		final byte[] userJs = Files.readAllBytes(new File(ff, "user.js").toPath()); //$NON-NLS-1$
		final byte[] handlers = Files.readAllBytes(new File(ff, "handlers.json").toPath()); //$NON-NLS-1$
		final RestoreReport second = restore(home);
		assertFalse(second.getLines().toString(), second.hasErrors());
		assertCaInstalled(ff);
		assertArrayEquals(userJs, Files.readAllBytes(new File(ff, "user.js").toPath())); //$NON-NLS-1$
		assertArrayEquals(handlers, Files.readAllBytes(new File(ff, "handlers.json").toPath())); //$NON-NLS-1$
		assertTrue(second.getLines().toString(), hasLine(second, RestoreReport.OK, "sin cambios")); //$NON-NLS-1$
	}

	/** Un perfil en la ra&iacute;z tradicional y otro en la XDG reciben los dos la CA.
	 * @throws Exception En cualquier error. */
	@Test
	public void todasLasRaices() throws Exception {
		final File home = home();
		final File legacy = profile(home, LEGACY_ROOT, "uno.default"); //$NON-NLS-1$
		final File xdg = profile(home, XDG_ROOT, "dos.default"); //$NON-NLS-1$
		final RestoreReport report = restore(home);
		assertFalse(report.getLines().toString(), report.hasErrors());
		assertCaInstalled(legacy);
		assertCaInstalled(xdg);
	}

	/** {@code user.js} lleva las dos l&iacute;neas exactas una sola vez y conserva el resto.
	 * @throws Exception En cualquier error. */
	@Test
	public void userJsExacto() throws Exception {
		final File home = home();
		final File ff = profile(home, LEGACY_ROOT, "abc.default"); //$NON-NLS-1$
		final Path userJs = new File(ff, "user.js").toPath(); //$NON-NLS-1$
		final String other1 = "user_pref(\"browser.startup.homepage\", \"about:blank\");"; //$NON-NLS-1$
		final String other2 = "// comentario con tilde: canción"; //$NON-NLS-1$
		Files.write(userJs, Arrays.asList(other1,
				"user_pref(\"network.protocol-handler.expose.afirma\", false);", //$NON-NLS-1$
				other2,
				PERMISSION), StandardCharsets.UTF_8);
		restore(home);
		final List<String> first = Files.readAllLines(userJs, StandardCharsets.UTF_8);
		assertEquals(Arrays.asList(other1, other2, EXPOSE, PERMISSION), first);
		restore(home);
		assertEquals(first, Files.readAllLines(userJs, StandardCharsets.UTF_8));
	}

	/** {@code handlers.json} recibe {@code schemes.afirma} y conserva las dem&aacute;s entradas.
	 * @throws Exception En cualquier error. */
	@Test
	public void handlersFusiona() throws Exception {
		final File home = home();
		final File ff = profile(home, LEGACY_ROOT, "abc.default"); //$NON-NLS-1$
		final Path handlers = new File(ff, "handlers.json").toPath(); //$NON-NLS-1$
		Files.write(handlers, ("{\"defaultHandlersVersion\":{\"es-ES\":4}," //$NON-NLS-1$
				+ "\"mimeTypes\":{\"application/pdf\":{\"action\":3,\"extensions\":[\"pdf\"]}}," //$NON-NLS-1$
				+ "\"schemes\":{\"mailto\":{\"stubEntry\":true,\"handlers\":[null]}," //$NON-NLS-1$
				+ "\"afirma\":{\"action\":4}}}").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		final RestoreReport report = restore(home);
		assertFalse(report.getLines().toString(), report.hasErrors());
		final JSONObject json = new JSONObject(new String(Files.readAllBytes(handlers), StandardCharsets.UTF_8));
		assertTrue(json.getJSONObject("defaultHandlersVersion").similar(new JSONObject("{\"es-ES\":4}"))); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(json.getJSONObject("mimeTypes").similar( //$NON-NLS-1$
				new JSONObject("{\"application/pdf\":{\"action\":3,\"extensions\":[\"pdf\"]}}"))); //$NON-NLS-1$
		final JSONObject schemes = json.getJSONObject("schemes"); //$NON-NLS-1$
		assertTrue(schemes.getJSONObject("mailto").similar(new JSONObject("{\"stubEntry\":true,\"handlers\":[null]}"))); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(schemes.getJSONObject("afirma").toString(), schemes.getJSONObject("afirma").similar( //$NON-NLS-1$ //$NON-NLS-2$
				new JSONObject("{\"action\":2,\"handlers\":[{\"name\":\"AutoFirma\",\"path\":\"/usr/bin/autofirma\"}]}"))); //$NON-NLS-1$
		assertEquals(2, schemes.length());
	}

	/** Con Firefox abierto en el perfil, {@code handlers.json} no se toca y se avisa;
	 * la CA y {@code user.js} s&iacute; se escriben.
	 * @throws Exception En cualquier error. */
	@Test
	public void perfilAbierto() throws Exception {
		final File home = home();
		final File ff = profile(home, LEGACY_ROOT, "abc.default"); //$NON-NLS-1$
		final String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0]; //$NON-NLS-1$
		Files.createSymbolicLink(new File(ff, "lock").toPath(), Paths.get("127.0.0.1:+" + pid)); //$NON-NLS-1$ //$NON-NLS-2$
		final Path handlers = new File(ff, "handlers.json").toPath(); //$NON-NLS-1$
		final byte[] before = "{\"defaultHandlersVersion\":{},\"mimeTypes\":{},\"schemes\":{}}".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
		Files.write(handlers, before);
		final RestoreReport report = restore(home);
		assertArrayEquals(before, Files.readAllBytes(handlers));
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.WARNING, handlers.toString()));
		assertFalse(report.getLines().toString(), report.hasErrors());
		assertCaInstalled(ff);
		assertEquals(Arrays.asList(EXPOSE, PERMISSION),
				Files.readAllLines(new File(ff, "user.js").toPath(), StandardCharsets.UTF_8)); //$NON-NLS-1$
	}

	/** Un perfil de Firefox sin {@code cert9.db} deja un AVISO con su ruta (no se instala la CA en &eacute;l)
	 * y recibe {@code user.js} y {@code handlers.json}; el perfil con base no avisa y recibe la CA.
	 * @throws Exception En cualquier error. */
	@Test
	public void perfilSinCert9Avisa() throws Exception {
		final File home = home();
		final File root = new File(home, LEGACY_ROOT);
		final File sinBase = new File(root, "sin.base"); //$NON-NLS-1$
		final File conBase = new File(root, "con.base"); //$NON-NLS-1$
		assertTrue(sinBase.mkdirs());
		newDb(conBase);
		Files.write(new File(root, "profiles.ini").toPath(), //$NON-NLS-1$
				"[Profile0]\nName=a\nIsRelative=1\nPath=sin.base\n\n[Profile1]\nName=b\nIsRelative=1\nPath=con.base\n" //$NON-NLS-1$
						.getBytes(StandardCharsets.UTF_8));
		final RestoreReport report = restore(home);
		assertFalse(report.getLines().toString(), report.hasErrors());
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.WARNING, sinBase.getPath() + " no tiene base de certificados (cert9.db)")); //$NON-NLS-1$
		assertFalse(report.getLines().toString(), hasLine(report, RestoreReport.WARNING, conBase.getPath()));
		assertFalse(new File(sinBase, "cert9.db").exists()); //$NON-NLS-1$
		assertTrue(new File(sinBase, "user.js").isFile()); //$NON-NLS-1$
		assertTrue(new File(sinBase, "handlers.json").isFile()); //$NON-NLS-1$
		assertCaInstalled(conBase);
	}

	/** Una base con contrase&ntilde;a maestra se salta con un aviso y sin colgarse; las dem&aacute;s se restauran.
	 * @throws Exception En cualquier error. */
	@Test(timeout = 60000)
	public void contrasenaMaestra() throws Exception {
		final File home = home();
		final File ff = new File(home, ".mozilla/firefox/abc.default"); //$NON-NLS-1$
		assertTrue(ff.mkdirs());
		Files.write(new File(home, ".mozilla/firefox/profiles.ini").toPath(), //$NON-NLS-1$
				"[Profile0]\nName=default\nIsRelative=1\nPath=abc.default\nDefault=1\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		final File pw = new File(SHARED.getRoot(), "pw"); //$NON-NLS-1$
		Files.write(pw.toPath(), "secreto\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		run(CERTUTIL, "-N", "-f", pw.getPath(), "-d", "sql:" + ff); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		final File chrome = new File(home, ".pki/nssdb"); //$NON-NLS-1$
		newDb(chrome);

		final RestoreReport report = restore(home);
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.WARNING, ff.getPath()));
		assertEquals(Collections.emptyList(), entries(ff, NICK));
		assertCaInstalled(chrome);
	}

	/** Sin la CA del sistema no se crea nada en el home y se indica usar &laquo;Todo el sistema&raquo;.
	 * @throws Exception En cualquier error. */
	@Test
	public void sinCaNoGenera() throws Exception {
		final File home = home();
		final File ff = profile(home, LEGACY_ROOT, "abc.default"); //$NON-NLS-1$
		final List<String> before = tree(home);
		final RestoreReport report = new RestoreReport(null);
		RestoreUserLinux.restore(home, new File(SHARED.getRoot(), "no-existe.cer"), report); //$NON-NLS-1$
		assertEquals(before, tree(home));
		assertFalse(new File(home, ".afirma").exists()); //$NON-NLS-1$
		assertEquals(Collections.emptyList(), entries(ff, NICK));
		assertTrue(report.hasErrors());
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.ERROR, "«Todo el sistema»")); //$NON-NLS-1$
	}

	/** Sin {@code certutil} se informa del error y las preferencias se escriben igual.
	 * @throws Exception En cualquier error. */
	@Test
	public void sinCertutil() throws Exception {
		final File home = home();
		final File ff = profile(home, LEGACY_ROOT, "abc.default"); //$NON-NLS-1$
		System.setProperty(RestoreUserLinux.CERTUTIL_PROPERTY, new File(SHARED.getRoot(), "no-certutil").getPath()); //$NON-NLS-1$
		final RestoreReport report;
		try {
			report = restore(home);
		}
		finally {
			System.clearProperty(RestoreUserLinux.CERTUTIL_PROPERTY);
		}
		assertTrue(report.hasErrors());
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.ERROR, "certutil")); //$NON-NLS-1$
		assertEquals(Collections.emptyList(), entries(ff, NICK));
		assertEquals(Arrays.asList(EXPOSE, PERMISSION),
				Files.readAllLines(new File(ff, "user.js").toPath(), StandardCharsets.UTF_8)); //$NON-NLS-1$
	}
}
