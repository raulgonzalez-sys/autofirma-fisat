package es.gob.afirma.standalone.ui.restoreconfig.linux;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Pruebas de la parte de sistema de Restaurar en Linux, sobre directorios temporales y sin root. */
public final class RestoreSystemLinuxTest {

	private static final String CERTUTIL = "/usr/bin/certutil"; //$NON-NLS-1$
	private static final String PFX = "autofirma.pfx"; //$NON-NLS-1$
	private static final String CER = "Autofirma_ROOT.cer"; //$NON-NLS-1$
	private static final String ID = "autofirma-csp-fix@local"; //$NON-NLS-1$
	private static final String PUBLIC = "rw-r--r--"; //$NON-NLS-1$

	/** Directorio temporal de cada prueba. */
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private File appDir() throws IOException {
		return this.tmp.newFolder("app"); //$NON-NLS-1$
	}

	private File etcDir() throws IOException {
		return this.tmp.newFolder("etc"); //$NON-NLS-1$
	}

	private static String perms(final File f) throws IOException {
		return PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()));
	}

	private static boolean hasLine(final RestoreReport report, final String prefix, final String text) {
		for (final String line : report.getLines()) {
			if (line.startsWith(prefix) && line.contains(text)) {
				return true;
			}
		}
		return false;
	}

	private static String run(final String... cmd) throws Exception {
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
		assertTrue(p.waitFor(30, TimeUnit.SECONDS));
		final String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
		assertEquals(Arrays.toString(cmd) + ": " + text, 0, p.exitValue()); //$NON-NLS-1$
		return text;
	}

	/** Sin {@code autofirma.pfx} se generan el almac&eacute;n y la CA, legibles por todos.
	 * @throws Exception En cualquier error. */
	@Test
	public void generaCaSiFalta() throws Exception {
		final File app = appDir();
		final RestoreReport report = new RestoreReport(null);
		assertEquals(report.getLines().toString(), 0,
				RestoreSystemLinux.restore(app, etcDir(), Collections.<File>emptyList(), report));
		assertFalse(report.getLines().toString(), report.hasErrors());
		assertTrue(new File(app, PFX).isFile());
		assertTrue(new File(app, CER).isFile());
		assertEquals(PUBLIC, perms(new File(app, PFX)));
		assertEquals(PUBLIC, perms(new File(app, CER)));
	}

	/** Dos restauraciones seguidas conservan la CA y el almac&eacute;n.
	 * @throws Exception En cualquier error. */
	@Test
	public void noRegeneraSiExiste() throws Exception {
		final File app = appDir();
		final File etc = etcDir();
		RestoreSystemLinux.restore(app, etc, Collections.<File>emptyList(), new RestoreReport(null));
		final byte[] cer = Files.readAllBytes(new File(app, CER).toPath());
		final byte[] pfx = Files.readAllBytes(new File(app, PFX).toPath());
		final RestoreReport report = new RestoreReport(null);
		assertEquals(0, RestoreSystemLinux.restore(app, etc, Collections.<File>emptyList(), report));
		assertFalse(report.getLines().toString(), report.hasErrors());
		assertArrayEquals(cer, Files.readAllBytes(new File(app, CER).toPath()));
		assertArrayEquals(pfx, Files.readAllBytes(new File(app, PFX).toPath()));
	}

	/** Con {@code autofirma.pfx} pero sin la CA, la CA se extrae del almac&eacute;n en vez de regenerar.
	 * @throws Exception En cualquier error. */
	@Test
	public void extraeCaDelPfx() throws Exception {
		final File app = appDir();
		final File etc = etcDir();
		RestoreSystemLinux.restore(app, etc, Collections.<File>emptyList(), new RestoreReport(null));
		final byte[] cer = Files.readAllBytes(new File(app, CER).toPath());
		final byte[] pfx = Files.readAllBytes(new File(app, PFX).toPath());
		Files.delete(new File(app, CER).toPath());
		assertEquals(0, RestoreSystemLinux.restore(app, etc, Collections.<File>emptyList(), new RestoreReport(null)));
		assertArrayEquals(cer, Files.readAllBytes(new File(app, CER).toPath()));
		assertArrayEquals(pfx, Files.readAllBytes(new File(app, PFX).toPath()));
		assertEquals(PUBLIC, perms(new File(app, CER)));
	}

	/** {@code AutoFirma.js} es el recurso del JAR y Firefox ESR lo enlaza si existe su directorio.
	 * @throws Exception En cualquier error. */
	@Test
	public void autofirmaJs() throws Exception {
		final File etc = etcDir();
		assertTrue(new File(etc, "firefox-esr").mkdir()); //$NON-NLS-1$
		final RestoreReport report = new RestoreReport(null);
		assertEquals(0, RestoreSystemLinux.restore(appDir(), etc, Collections.<File>emptyList(), report));
		final File js = new File(etc, "firefox/pref/AutoFirma.js"); //$NON-NLS-1$
		final byte[] expected;
		try (InputStream is = RestoreSystemLinuxTest.class.getResourceAsStream("/linux/autofirma.js")) { //$NON-NLS-1$
			assertNotNull(is);
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			final byte[] buf = new byte[4096];
			int n;
			while ((n = is.read(buf)) != -1) {
				baos.write(buf, 0, n);
			}
			expected = baos.toByteArray();
		}
		assertArrayEquals(expected, Files.readAllBytes(js.toPath()));
		assertEquals(PUBLIC, perms(js));
		final Path esr = new File(etc, "firefox-esr/pref/AutoFirma.js").toPath(); //$NON-NLS-1$
		assertTrue(Files.isSymbolicLink(esr));
		assertEquals(js.toPath().toAbsolutePath(), Files.readSymbolicLink(esr));

		final RestoreReport second = new RestoreReport(null);
		assertEquals(0, RestoreSystemLinux.restore(new File(this.tmp.getRoot(), "app"), etc, Collections.<File>emptyList(), second)); //$NON-NLS-1$
		assertTrue(second.getLines().toString(), hasLine(second, RestoreReport.OK, "sin cambios")); //$NON-NLS-1$
	}

	/** La extensi&oacute;n CSP va siempre a {@code firefox/policies} y a {@code firefox-esr/policies} solo
	 * si ese {@code policies.json} ya existe, conservando sus claves.
	 * @throws Exception En cualquier error. */
	@Test
	public void politicasFusiona() throws Exception {
		final File app = appDir();
		final File etc = etcDir();
		final File esr = new File(etc, "firefox-esr/policies/policies.json"); //$NON-NLS-1$
		assertEquals(0, RestoreSystemLinux.restore(app, etc, Collections.<File>emptyList(), new RestoreReport(null)));
		final File ff = new File(etc, "firefox/policies/policies.json"); //$NON-NLS-1$
		assertTrue(read(ff).getJSONObject("policies").getJSONObject("ExtensionSettings").has(ID)); //$NON-NLS-1$ //$NON-NLS-2$
		assertFalse(esr.exists());

		assertTrue(esr.getParentFile().mkdirs());
		Files.write(esr.toPath(), "{\"policies\":{\"DisableTelemetry\":true}}".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		assertEquals(0, RestoreSystemLinux.restore(app, etc, Collections.<File>emptyList(), new RestoreReport(null)));
		final JSONObject pol = read(esr).getJSONObject("policies"); //$NON-NLS-1$
		assertTrue(pol.getBoolean("DisableTelemetry")); //$NON-NLS-1$
		assertEquals("file:///usr/share/Autofirma/autofirma-csp-fix.xpi", //$NON-NLS-1$
				pol.getJSONObject("ExtensionSettings").getJSONObject(ID).getString("install_url")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static JSONObject read(final File f) throws IOException {
		return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
	}

	/** {@code /etc/skel} se restaura en el propio proceso como un home: CA en la base NSS y preferencias.
	 * @throws Exception En cualquier error. */
	@Test
	public void skelComoHome() throws Exception {
		assumeTrue("Falta " + CERTUTIL, new File(CERTUTIL).canExecute()); //$NON-NLS-1$
		final File etc = etcDir();
		final File root = new File(etc, "skel/.mozilla/firefox"); //$NON-NLS-1$
		final File profile = new File(root, "abc.default-esr"); //$NON-NLS-1$
		assertTrue(profile.mkdirs());
		Files.write(new File(root, "profiles.ini").toPath(), //$NON-NLS-1$
				"[Profile0]\nName=default\nIsRelative=1\nPath=abc.default-esr\nDefault=1\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		run(CERTUTIL, "-N", "--empty-password", "-d", "sql:" + profile); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

		final RestoreReport report = new RestoreReport(null);
		assertEquals(0, RestoreSystemLinux.restore(appDir(), etc, Collections.<File>emptyList(), report));
		assertFalse(report.getLines().toString(), report.hasErrors());
		assertTrue(run(CERTUTIL, "-L", "-d", "sql:" + profile).contains("SocketAutoFirma")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		final String userJs = new String(Files.readAllBytes(new File(profile, "user.js").toPath()), StandardCharsets.UTF_8); //$NON-NLS-1$
		assertTrue(userJs, userJs.contains("network.protocol-handler.expose.afirma")); //$NON-NLS-1$
		assertTrue(new File(profile, "handlers.json").isFile()); //$NON-NLS-1$
	}

	/** Escribe el sustituto del subproceso por usuario: deja una marca en {@code HOME} con el
	 * due&ntilde;o recibido y reenv&iacute;a una l&iacute;nea {@code OK}; si el home tiene {@code falla}, sale con error. */
	private File fakeUserCommand() throws IOException {
		final File script = new File(this.tmp.getRoot(), "restaurar-usuario"); //$NON-NLS-1$
		Files.write(script.toPath(), ("#!/bin/sh\n" //$NON-NLS-1$
				+ "if [ -e \"$HOME/falla\" ]; then echo \"ERROR: fallo simulado en $HOME\"; echo traza >&2; exit 3; fi\n" //$NON-NLS-1$
				+ "echo \"OK: restaurado $HOME por $1\"\n" //$NON-NLS-1$
				+ "echo sin prefijo\n" //$NON-NLS-1$
				+ "touch \"$HOME/restaurado-por-$1\"\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		Files.setPosixFilePermissions(script.toPath(), PosixFilePermissions.fromString("rwxr-xr-x")); //$NON-NLS-1$
		return script;
	}

	/** Nombres de prueba: 1000 es {@code ana} y 1001 {@code bea}; el resto, por {@code getent passwd}. */
	private static final RestoreSystemLinux.UserNames NAMES = uid -> uid == 1000 ? "ana" //$NON-NLS-1$
			: uid == 1001 ? "bea" : RestoreSystemLinux.UserNames.GETENT.name(uid); //$NON-NLS-1$

	private static int restoreWithCommand(final File command, final File app, final File etc, final List<File> homes,
			final RestoreReport report, final RestoreSystemLinux.UidReader uids) {
		RestoreSystemLinux.testUserCommand = command.getPath();
		try {
			return RestoreSystemLinux.restore(app, etc, homes, report, uids, NAMES);
		}
		finally {
			RestoreSystemLinux.testUserCommand = null;
		}
	}

	/** Un home cuyo UID no est&aacute; en {@code getent passwd} se salta con un AVISO con su ruta;
	 * los dem&aacute;s se restauran con su due&ntilde;o y el retorno es 0.
	 * @throws Exception En cualquier error. */
	@Test
	public void homeSinDueno() throws Exception {
		final File a = this.tmp.newFolder("home a"); //$NON-NLS-1$
		final File orphan = this.tmp.newFolder("sin dueno"); //$NON-NLS-1$
		final File b = this.tmp.newFolder("home b"); //$NON-NLS-1$
		final RestoreReport report = new RestoreReport(null);
		final int code = restoreWithCommand(fakeUserCommand(), appDir(), etcDir(), Arrays.asList(a, orphan, b), report,
				p -> p.equals(orphan.toPath()) ? 2147000000 : p.equals(a.toPath()) ? 1000 : 1001);

		assertEquals(report.getLines().toString(), 0, code);
		assertFalse(report.getLines().toString(), report.hasErrors());
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.WARNING, orphan.getPath()));
		assertTrue(report.getLines().toString(), new File(a, "restaurado-por-ana").isFile()); //$NON-NLS-1$
		assertTrue(new File(b, "restaurado-por-bea").isFile()); //$NON-NLS-1$
		assertEquals(0, orphan.list().length);
		assertTrue(report.getLines().toString(), report.getLines().contains(RestoreReport.OK + "restaurado " + a.getPath() + " por ana")); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(report.getLines().toString(), report.getLines().contains(RestoreReport.WARNING + "sin prefijo")); //$NON-NLS-1$
	}

	/** Un home que falla deja su ERROR y la salida de error, los dem&aacute;s siguen y el retorno es 0.
	 * @throws Exception En cualquier error. */
	@Test
	public void homeQueFallaNoCambiaRetorno() throws Exception {
		final File bad = this.tmp.newFolder("malo"); //$NON-NLS-1$
		assertTrue(new File(bad, "falla").createNewFile()); //$NON-NLS-1$
		final File good = this.tmp.newFolder("bueno"); //$NON-NLS-1$
		final RestoreReport report = new RestoreReport(null);
		final int code = restoreWithCommand(fakeUserCommand(), appDir(), etcDir(), Arrays.asList(bad, good), report,
				p -> 1000);
		assertEquals(report.getLines().toString(), 0, code);
		assertTrue(report.hasErrors());
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.ERROR, "fallo simulado en " + bad.getPath())); //$NON-NLS-1$
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.ERROR, "traza")); //$NON-NLS-1$
		assertTrue(new File(good, "restaurado-por-ana").isFile()); //$NON-NLS-1$
	}

	/** Un home de passwd cuyo directorio es de root (UID 0) o de una cuenta de sistema (UID &lt; 1000) se
	 * salta con un AVISO con su ruta aunque el UID resuelva: la parte de usuario nunca corre como root.
	 * Los dem&aacute;s se restauran y el retorno es 0.
	 * @throws Exception En cualquier error. */
	@Test
	public void homeDeRootSeSalta() throws Exception {
		final File rootOwned = this.tmp.newFolder("de root"); //$NON-NLS-1$
		final File system = this.tmp.newFolder("de sistema"); //$NON-NLS-1$
		final File good = this.tmp.newFolder("bueno"); //$NON-NLS-1$
		final RestoreSystemLinux.UserNames names = uid -> uid == 0 ? "root" : uid == 999 ? "servicio" : "ana"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final RestoreReport report = new RestoreReport(null);
		RestoreSystemLinux.testUserCommand = fakeUserCommand().getPath();
		final int code;
		try {
			code = RestoreSystemLinux.restore(appDir(), etcDir(), Arrays.asList(rootOwned, system, good), report,
					p -> p.equals(rootOwned.toPath()) ? 0 : p.equals(system.toPath()) ? 999 : 1000, names);
		}
		finally {
			RestoreSystemLinux.testUserCommand = null;
		}
		assertEquals(report.getLines().toString(), 0, code);
		assertFalse(report.getLines().toString(), report.hasErrors());
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.WARNING, rootOwned.getPath() + " es de root")); //$NON-NLS-1$
		assertTrue(report.getLines().toString(), hasLine(report, RestoreReport.WARNING, system.getPath() + " es de root")); //$NON-NLS-1$
		assertEquals(0, rootOwned.list().length);
		assertEquals(0, system.list().length);
		assertTrue(report.getLines().toString(), new File(good, "restaurado-por-ana").isFile()); //$NON-NLS-1$
	}

	/** Homes de {@code passwd} con UID &ge; 1000 que existen y directorios de la ra&iacute;z de homes con
	 * due&ntilde;o de UID &ge; 1000 que no salen de ella, sin repetir la misma ruta real.
	 * @throws Exception En cualquier error. */
	@Test
	public void homesDelSistema() throws Exception {
		final File homeRoot = this.tmp.newFolder("home"); //$NON-NLS-1$
		final File a = new File(homeRoot, "a"); //$NON-NLS-1$
		final File b = new File(homeRoot, "b"); //$NON-NLS-1$
		assertTrue(a.mkdir() && b.mkdir());
		assertTrue(new File(homeRoot, "fichero").createNewFile()); //$NON-NLS-1$
		assertTrue(new File(homeRoot, "lost+found").mkdir()); //$NON-NLS-1$
		assertTrue(new File(homeRoot, "servicio").mkdir()); //$NON-NLS-1$
		final File sys = this.tmp.newFolder("sistema"); //$NON-NLS-1$
		final File other = this.tmp.newFolder("otro"); //$NON-NLS-1$
		final File link = new File(this.tmp.getRoot(), "enlace"); //$NON-NLS-1$
		Files.createSymbolicLink(link.toPath(), a.toPath());
		Files.createSymbolicLink(new File(homeRoot, "fuera").toPath(), other.toPath()); //$NON-NLS-1$
		Files.createSymbolicLink(new File(homeRoot, "dentro").toPath(), b.toPath()); //$NON-NLS-1$
		final Map<String, Integer> owners = new HashMap<>();
		owners.put("a", Integer.valueOf(1000)); //$NON-NLS-1$
		owners.put("b", Integer.valueOf(1001)); //$NON-NLS-1$
		owners.put("dentro", Integer.valueOf(1001)); //$NON-NLS-1$
		owners.put("fuera", Integer.valueOf(1003)); //$NON-NLS-1$
		owners.put("lost+found", Integer.valueOf(0)); //$NON-NLS-1$
		owners.put("servicio", Integer.valueOf(999)); //$NON-NLS-1$
		final RestoreSystemLinux.UidReader uids = p -> owners.get(p.getFileName().toString()).intValue();
		final List<String> passwd = Arrays.asList(
				"root:x:0:0:root:/root:/bin/bash", //$NON-NLS-1$
				"sys:x:999:999::" + sys + ":/usr/sbin/nologin", //$NON-NLS-1$ //$NON-NLS-2$
				"otro:x:1001:1001::" + other + ":/bin/bash", //$NON-NLS-1$ //$NON-NLS-2$
				"ana:x:1000:1000:Ana:" + link + ":/bin/bash", //$NON-NLS-1$ //$NON-NLS-2$
				"nadie:x:1002:1002::" + new File(this.tmp.getRoot(), "no-existe") + ":/bin/bash", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"roto"); //$NON-NLS-1$
		assertEquals(Arrays.asList(other, link, b), RestoreSystemLinux.systemHomes(passwd, homeRoot, uids));
	}

	/** El comando por home lleva {@code timeout} dentro de {@code runuser} y {@code HOME} del home.
	 * @throws Exception En cualquier error. */
	@Test
	public void comandoPorHome() throws Exception {
		assertEquals(Arrays.asList("/usr/sbin/runuser", "-u", "ana", "--", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				"/usr/bin/timeout", "-k", "5", "50", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				"/usr/bin/env", "HOME=/home/ana", //$NON-NLS-1$ //$NON-NLS-2$
				"/usr/bin/autofirma", "restaurar", "--usuario"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				RestoreSystemLinux.userCommand("ana", new File("/home/ana"))); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** El subproceso no hereda el entorno de root: solo {@code HOME}, {@code PATH} y la configuraci&oacute;n regional.
	 * @throws Exception En cualquier error. */
	@Test
	public void entornoPorHome() throws Exception {
		final Map<String, String> env = new HashMap<>();
		env.put("SSH_AUTH_SOCK", "/run/root/agent"); //$NON-NLS-1$ //$NON-NLS-2$
		env.put("PATH", "/root/bin:/usr/bin"); //$NON-NLS-1$ //$NON-NLS-2$
		RestoreSystemLinux.userEnvironment(env, new File("/home/ana"), null, ""); //$NON-NLS-1$ //$NON-NLS-2$
		final Map<String, String> expected = new HashMap<>();
		expected.put("HOME", "/home/ana"); //$NON-NLS-1$ //$NON-NLS-2$
		expected.put("PATH", "/usr/sbin:/usr/bin:/sbin:/bin"); //$NON-NLS-1$ //$NON-NLS-2$
		expected.put("LANG", "C.UTF-8"); //$NON-NLS-1$ //$NON-NLS-2$
		expected.put("LC_ALL", "C.UTF-8"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(expected, env);
		RestoreSystemLinux.userEnvironment(env, new File("/home/ana"), "es_ES.UTF-8", "es_ES.UTF-8"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertEquals("es_ES.UTF-8", env.get("LANG")); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals("es_ES.UTF-8", env.get("LC_ALL")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Las l&iacute;neas reenviadas recuperan su tipo por el prefijo; sin prefijo son AVISO.
	 * @throws Exception En cualquier error. */
	@Test
	public void informeDesdeLineas() throws Exception {
		final RestoreReport report = new RestoreReport(null);
		report.addLine("OK: bien"); //$NON-NLS-1$
		report.addLine("AVISO: regular"); //$NON-NLS-1$
		report.addLine(""); //$NON-NLS-1$
		report.addLine("suelta"); //$NON-NLS-1$
		assertFalse(report.hasErrors());
		report.addLine("ERROR: mal"); //$NON-NLS-1$
		assertTrue(report.hasErrors());
		assertEquals(Arrays.asList("OK: bien", "AVISO: regular", "AVISO: suelta", "ERROR: mal"), report.getLines()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}
}
