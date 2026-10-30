/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.ui.restoreconfig.linux;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import es.gob.afirma.standalone.ui.restoreconfig.RestoreConfigLinuxHooks;

/** Parte de sistema de &laquo;Restaurar instalaci&oacute;n&raquo; en Linux (como root): CA local y
 * {@code autofirma.pfx} en el directorio de la aplicaci&oacute;n si faltan, {@code AutoFirma.js} de
 * Firefox, la extensi&oacute;n CSP en {@code policies.json} y, despu&eacute;s, la parte de usuario de
 * {@code /etc/skel} (en este proceso) y de cada home (en un subproceso con la identidad de su due&ntilde;o). */
public final class RestoreSystemLinux {

	/** Ejecutable que sustituye al subproceso por usuario en las pruebas (recibe el nombre del
	 * due&ntilde;o como &uacute;nico argumento y el home en {@code HOME}); nulo en producci&oacute;n.
	 * Es un campo y no una propiedad del sistema para que nada externo pueda cambiar lo que se
	 * ejecuta como root. */
	static volatile String testUserCommand = null;

	/** Identificador de la extensi&oacute;n CSP de Firefox. */
	public static final String EXTENSION_ID = "autofirma-csp-fix@local"; //$NON-NLS-1$

	/** XPI de la extensi&oacute;n CSP de Firefox que instala el paquete. */
	public static final String EXTENSION_XPI = "/usr/share/Autofirma/autofirma-csp-fix.xpi"; //$NON-NLS-1$

	private static final String RUNUSER = "/usr/sbin/runuser"; //$NON-NLS-1$
	private static final String ENV = "/usr/bin/env"; //$NON-NLS-1$
	private static final String TIMEOUT = "/usr/bin/timeout"; //$NON-NLS-1$
	private static final String SAFE_PATH = "/usr/sbin:/usr/bin:/sbin:/bin"; //$NON-NLS-1$
	private static final String DEFAULT_LOCALE = "C.UTF-8"; //$NON-NLS-1$
	private static final String GETENT = "/usr/bin/getent"; //$NON-NLS-1$
	private static final String HOME_ROOT = "/home"; //$NON-NLS-1$
	private static final String DEV_NULL = "/dev/null"; //$NON-NLS-1$
	private static final String PUBLIC_FILE = "rw-r--r--"; //$NON-NLS-1$

	/** Primer UID de las cuentas de persona en Debian. */
	private static final int FIRST_USER_UID = 1000;

	/** Tiempo m&aacute;ximo de la parte de usuario de un home, para que uno bloqueado no detenga al resto;
	 * es la &uacute;ltima defensa: {@code timeout} dentro de {@code runuser} acota antes todo el &aacute;rbol. */
	private static final long HOME_TIMEOUT_SECONDS = 60;

	/** {@code timeout} dentro de {@code runuser}: TERM a los 50 s y KILL 5 s despu&eacute;s, antes del l&iacute;mite exterior. */
	private static final String INNER_TIMEOUT_SECONDS = "50"; //$NON-NLS-1$
	private static final String INNER_KILL_AFTER_SECONDS = "5"; //$NON-NLS-1$

	/** Tiempo m&aacute;ximo de {@code getent}. */
	private static final long GETENT_TIMEOUT_SECONDS = 10;

	/** Espera tras pedir la terminaci&oacute;n de un subproceso antes de matarlo. */
	private static final long TERM_GRACE_SECONDS = 5;

	/** Lee el UID del due&ntilde;o de un directorio. */
	interface UidReader {
		/** Lectura con {@code unix:uid} de NIO, que sigue los enlaces. */
		UidReader FILES = p -> ((Integer) Files.getAttribute(p, "unix:uid")).intValue(); //$NON-NLS-1$

		int uid(Path path) throws IOException;
	}

	/** Resuelve el nombre de usuario de un UID. */
	interface UserNames {
		/** Por {@code getent passwd}. */
		UserNames GETENT = RestoreSystemLinux::userName;

		/** Nombre de usuario de un UID.
		 * @param uid UID.
		 * @return Nombre o {@code null} si el UID no resuelve. */
		String name(int uid);
	}

	private RestoreSystemLinux() {
		// No se permite la instanciacion
	}

	/** Restaura la parte de sistema y despu&eacute;s {@code /etc/skel} y los homes indicados. Ning&uacute;n
	 * fallo de un home detiene el resto; cada paso deja su l&iacute;nea en el informe.
	 * @param appDir Directorio de la aplicaci&oacute;n ({@code /usr/lib/Autofirma}).
	 * @param etcDir Directorio de configuraci&oacute;n del sistema ({@code /etc}).
	 * @param homes Homes de usuario que restaurar (normalmente {@link #systemHomes()}).
	 * @param report Informe que recibe las l&iacute;neas {@code OK}, {@code AVISO} y {@code ERROR}.
	 * @return 0 si la parte de sistema ha ido bien aunque falle alg&uacute;n home; 1 si ha fallado. */
	public static int restore(final File appDir, final File etcDir, final List<File> homes, final RestoreReport report) {
		return restore(appDir, etcDir, homes, report, UidReader.FILES, UserNames.GETENT);
	}

	static int restore(final File appDir, final File etcDir, final List<File> homes, final RestoreReport report,
			final UidReader uids, final UserNames names) {
		final File ca = restoreCa(appDir, report);
		final boolean jsOk = restoreProtocolHandler(etcDir, report);
		final boolean policiesOk = restorePolicies(etcDir, report);

		if (ca == null) {
			report.error("Sin certificado raíz no se restauran /etc/skel ni los directorios de usuario"); //$NON-NLS-1$
			return 1;
		}
		final File skel = new File(etcDir, "skel"); //$NON-NLS-1$
		if (skel.isDirectory()) {
			RestoreUserLinux.restore(skel, ca, report);
		}
		for (final File home : homes) {
			restoreHome(home, report, uids, names);
		}
		return jsOk && policiesOk ? 0 : 1;
	}

	// --- CA local ---

	/** Genera la CA y el almac&eacute;n solo si falta {@code autofirma.pfx}; si solo falta la CA, la extrae
	 * del almac&eacute;n. Devuelve el certificado de la CA o {@code null} si no se ha podido obtener. */
	private static File restoreCa(final File appDir, final RestoreReport report) {
		final File pfx = new File(appDir, RestoreConfigLinuxHooks.KS_FILENAME);
		final File cer = new File(appDir, RestoreConfigLinuxHooks.CA_FILENAME);
		try {
			if (!pfx.isFile()) {
				final byte[][] pack = RestoreConfigLinuxHooks.generateSslCertificates();
				writePublic(pfx.toPath(), pack[0]);
				writePublic(cer.toPath(), pack[1]);
				report.ok("Generados el certificado raíz " + cer + " y el almacén SSL " + pfx); //$NON-NLS-1$ //$NON-NLS-2$
			}
			else if (!cer.isFile()) {
				writePublic(cer.toPath(), RestoreConfigLinuxHooks.caFromPkcs12(pfx));
				report.ok("Certificado raíz " + cer + " extraído de " + pfx); //$NON-NLS-1$ //$NON-NLS-2$
			}
			else {
				report.ok("Certificado raíz y almacén SSL sin cambios en " + appDir); //$NON-NLS-1$
				// La parte de usuario lee la CA con la identidad de cada usuario
				try {
					Files.setPosixFilePermissions(cer.toPath(), PosixFilePermissions.fromString(PUBLIC_FILE));
				}
				catch (final IOException | RuntimeException e) {
					report.warning("No se ha podido dar permiso de lectura a " + cer + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
			return cer;
		}
		catch (final IOException | GeneralSecurityException | RuntimeException e) {
			report.error("No se ha podido generar o leer el certificado raíz en " + appDir + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
			return null;
		}
	}

	// --- Firefox ---

	/** {@code <etc>/firefox/pref/AutoFirma.js} con el recurso del JAR y, si existe {@code <etc>/firefox-esr},
	 * un enlace a &eacute;l en {@code <etc>/firefox-esr/pref}. */
	private static boolean restoreProtocolHandler(final File etcDir, final RestoreReport report) {
		final Path js = new File(etcDir, "firefox/pref/" + RestoreConfigLinuxHooks.PROTOCOL_HANDLER_FILENAME).toPath().toAbsolutePath(); //$NON-NLS-1$
		try {
			final byte[] content = RestoreConfigLinuxHooks.protocolHandlerConfig();
			if (Files.isRegularFile(js) && Arrays.equals(content, Files.readAllBytes(js))) {
				report.ok(js + " sin cambios"); //$NON-NLS-1$
			}
			else {
				Files.createDirectories(js.getParent());
				writePublic(js, content);
				report.ok("afirma:// registrado para Firefox en " + js); //$NON-NLS-1$
			}
		}
		catch (final IOException | RuntimeException e) {
			report.error("No se ha podido escribir " + js + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
			return false;
		}

		final File esr = new File(etcDir, "firefox-esr"); //$NON-NLS-1$
		if (!esr.isDirectory()) {
			return true;
		}
		final Path link = new File(esr, "pref/" + RestoreConfigLinuxHooks.PROTOCOL_HANDLER_FILENAME).toPath(); //$NON-NLS-1$
		try {
			if (Files.isSymbolicLink(link) && js.equals(Files.readSymbolicLink(link))) {
				report.ok(link + " sin cambios"); //$NON-NLS-1$
				return true;
			}
			Files.createDirectories(link.getParent());
			Files.deleteIfExists(link);
			Files.createSymbolicLink(link, js);
			report.ok(link + " enlazado a " + js); //$NON-NLS-1$
			return true;
		}
		catch (final IOException | RuntimeException e) {
			report.error("No se ha podido enlazar " + link + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
			return false;
		}
	}

	/** {@code policies.json} donde va la extensi&oacute;n CSP: el de {@code firefox} siempre y el de
	 * {@code firefox-esr} solo si ya existe.
	 * @param etcDir Directorio de configuraci&oacute;n del sistema.
	 * @return Rutas de los ficheros. */
	static List<Path> policiesFiles(final File etcDir) {
		final List<Path> files = new ArrayList<>();
		files.add(new File(etcDir, "firefox/policies/policies.json").toPath()); //$NON-NLS-1$
		final Path esr = new File(etcDir, "firefox-esr/policies/policies.json").toPath(); //$NON-NLS-1$
		if (Files.isRegularFile(esr)) {
			files.add(esr);
		}
		return files;
	}

	private static boolean restorePolicies(final File etcDir, final RestoreReport report) {
		boolean ok = true;
		for (final Path file : policiesFiles(etcDir)) {
			try {
				Files.createDirectories(file.toAbsolutePath().getParent());
				if (FirefoxPolicies.addExtension(file, EXTENSION_ID, EXTENSION_XPI)) {
					report.ok("Extensión CSP de Firefox registrada en " + file); //$NON-NLS-1$
				}
				else {
					report.ok("Extensión CSP de Firefox sin cambios en " + file); //$NON-NLS-1$
				}
			}
			catch (final IOException | RuntimeException e) {
				report.error("No se ha podido registrar la extensión CSP en " + file + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
				ok = false;
			}
		}
		return ok;
	}

	// --- Homes ---

	/** Restaura un home con la identidad de su due&ntilde;o; si el directorio es de root o de una cuenta de
	 * sistema (UID &lt; 1000), o su due&ntilde;o no resuelve, lo salta con AVISO. */
	private static void restoreHome(final File home, final RestoreReport report, final UidReader uids,
			final UserNames names) {
		final int uid;
		try {
			uid = uids.uid(home.toPath());
		}
		catch (final IOException | RuntimeException e) {
			report.warning("No se puede leer el dueño de " + home + " (" + e + "); se salta"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			return;
		}
		// Un home de passwd cuyo directorio es de root ejecutaria la parte de usuario como root
		if (uid < FIRST_USER_UID) {
			report.warning("El directorio " + home + " es de root o de una cuenta de sistema (UID " + uid + "); se salta"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			return;
		}
		final String owner = names.name(uid);
		if (owner == null) {
			report.warning("El dueño de " + home + " (UID " + uid + ") no está en getent passwd; se salta"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			return;
		}

		final String test = testUserCommand;
		runUserRestore(test != null ? Arrays.asList(test, owner) : userCommand(owner, home), home, report);
	}

	/** Comando de la parte de usuario de un home con la identidad de su due&ntilde;o. {@code timeout} va
	 * dentro de {@code runuser} para que alcance al lanzador, a la JVM y a {@code certutil}; {@code env}
	 * vuelve a poner {@code HOME} al home que se restaura, porque {@code runuser} lo fija al de passwd.
	 * @param owner Nombre del due&ntilde;o.
	 * @param home Home que se restaura.
	 * @return Argumentos del comando. */
	static List<String> userCommand(final String owner, final File home) {
		return Arrays.asList(RUNUSER, "-u", owner, "--", //$NON-NLS-1$ //$NON-NLS-2$
				TIMEOUT, "-k", INNER_KILL_AFTER_SECONDS, INNER_TIMEOUT_SECONDS, //$NON-NLS-1$
				ENV, "HOME=" + home.getAbsolutePath(), //$NON-NLS-1$
				RestoreUserLinux.LAUNCHER, "restaurar", "--usuario"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Entorno m&iacute;nimo del subproceso: nada del entorno de root salvo la configuraci&oacute;n regional.
	 * @param env Entorno del {@link ProcessBuilder}, que se vac&iacute;a.
	 * @param home Home que se restaura.
	 * @param lang {@code LANG} de este proceso (puede ser nulo).
	 * @param lcAll {@code LC_ALL} de este proceso (puede ser nulo). */
	static void userEnvironment(final Map<String, String> env, final File home, final String lang, final String lcAll) {
		env.clear();
		env.put("HOME", home.getAbsolutePath()); //$NON-NLS-1$
		env.put("PATH", SAFE_PATH); //$NON-NLS-1$
		env.put("LANG", lang == null || lang.isEmpty() ? DEFAULT_LOCALE : lang); //$NON-NLS-1$
		env.put("LC_ALL", lcAll == null || lcAll.isEmpty() ? DEFAULT_LOCALE : lcAll); //$NON-NLS-1$
	}

	/** Ejecuta la parte de usuario de un home reenviando cada l&iacute;nea de su salida al informe. */
	private static void runUserRestore(final List<String> cmd, final File home, final RestoreReport report) {
		final ProcessBuilder pb = new ProcessBuilder(cmd)
			// "/" y no el home: root puede no tener acceso a un home NFS con root_squash
			.directory(new File("/")) //$NON-NLS-1$
			.redirectInput(ProcessBuilder.Redirect.from(new File(DEV_NULL)));
		userEnvironment(pb.environment(), home, System.getenv("LANG"), System.getenv("LC_ALL")); //$NON-NLS-1$ //$NON-NLS-2$
		final Process p;
		try {
			p = pb.start();
		}
		catch (final IOException e) {
			report.error("No se ha podido restaurar " + home + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}
		final LineForwarder out = new LineForwarder(p.getInputStream(), report);
		final Collector err = new Collector(p.getErrorStream());
		out.start();
		err.start();
		final boolean finished = waitFor(p, HOME_TIMEOUT_SECONDS);
		if (!finished) {
			// Primero TERM para que runuser lo pase a su hijo; si no basta, KILL
			p.destroy();
			if (!waitFor(p, TERM_GRACE_SECONDS)) {
				p.destroyForcibly();
			}
		}
		out.finish();
		err.finish();
		if (!finished) {
			report.error("La restauración de " + home + " no ha terminado en " + HOME_TIMEOUT_SECONDS + " s y se ha detenido"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		else if (p.exitValue() != 0) {
			report.error("La restauración de " + home + " ha terminado con código " + p.exitValue() //$NON-NLS-1$ //$NON-NLS-2$
					+ tail(err.text()));
		}
	}

	private static String tail(final String text) {
		final String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return ""; //$NON-NLS-1$
		}
		final int max = 1000;
		return ": " + (trimmed.length() > max ? "..." + trimmed.substring(trimmed.length() - max) : trimmed); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Nombre de usuario de un UID por {@code getent passwd}, o {@code null} si no resuelve. */
	private static String userName(final int uid) {
		final List<String> lines = getent("passwd", Integer.toString(uid)); //$NON-NLS-1$
		if (lines.isEmpty()) {
			return null;
		}
		final String name = lines.get(0).split(":", -1)[0]; //$NON-NLS-1$
		return name.isEmpty() ? null : name;
	}

	/** Homes de usuario del sistema: los de {@code getent passwd} con UID &ge; 1000 que existen y los
	 * directorios de {@code /home} con due&ntilde;o de UID &ge; 1000 que no salen de {@code /home}
	 * (fuera quedan {@code lost+found} y los directorios de root o de cuentas de sistema), sin repetir
	 * la misma ruta real.
	 * @return Homes en ese orden. */
	public static List<File> systemHomes() {
		return systemHomes(getent("passwd"), new File(HOME_ROOT), UidReader.FILES); //$NON-NLS-1$
	}

	static List<File> systemHomes(final List<String> passwd, final File homeRoot, final UidReader uids) {
		final List<File> homes = new ArrayList<>();
		final Set<Path> seen = new HashSet<>();
		for (final String line : passwd) {
			final String[] fields = line.split(":", -1); //$NON-NLS-1$
			if (fields.length < 7 || fields[5].isEmpty()) {
				continue;
			}
			final int uid;
			try {
				uid = Integer.parseInt(fields[2]);
			}
			catch (final NumberFormatException e) {
				continue;
			}
			final File home = new File(fields[5]);
			if (uid >= FIRST_USER_UID && home.isDirectory() && seen.add(realPath(home))) {
				homes.add(home);
			}
		}
		final File[] dirs = homeRoot.listFiles(File::isDirectory);
		if (dirs != null) {
			Arrays.sort(dirs);
			final Path root = realPath(homeRoot);
			for (final File dir : dirs) {
				final Path real = realPath(dir);
				if (real.startsWith(root) && ownerUid(dir, uids) >= FIRST_USER_UID && seen.add(real)) {
					homes.add(dir);
				}
			}
		}
		return homes;
	}

	// --- Utilidades ---

	/** UID del due&ntilde;o, o -1 si no se puede leer. */
	private static int ownerUid(final File dir, final UidReader uids) {
		try {
			return uids.uid(dir.toPath());
		}
		catch (final IOException | RuntimeException e) {
			return -1;
		}
	}

	private static Path realPath(final File f) {
		try {
			return f.toPath().toRealPath();
		}
		catch (final IOException e) {
			return f.toPath().toAbsolutePath().normalize();
		}
	}

	/** Escribe con un temporal en el mismo directorio y un movimiento at&oacute;mico, con modo 0644. */
	private static void writePublic(final Path file, final byte[] data) throws IOException {
		final Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "." + file.getFileName() + ".", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		try {
			Files.write(tmp, data);
			Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString(PUBLIC_FILE));
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
		}
		finally {
			Files.deleteIfExists(tmp);
		}
	}

	/** L&iacute;neas de {@code getent} con esos argumentos; vac&iacute;o si no hay entradas o falla. */
	private static List<String> getent(final String... args) {
		final List<String> cmd = new ArrayList<>();
		cmd.add(GETENT);
		cmd.addAll(Arrays.asList(args));
		try {
			final Process p = new ProcessBuilder(cmd)
				.redirectInput(ProcessBuilder.Redirect.from(new File(DEV_NULL)))
				.redirectError(ProcessBuilder.Redirect.to(new File(DEV_NULL)))
				.start();
			final Collector out = new Collector(p.getInputStream());
			out.start();
			final boolean finished = waitFor(p, GETENT_TIMEOUT_SECONDS);
			if (!finished) {
				p.destroyForcibly();
			}
			out.finish();
			if (!finished || p.exitValue() != 0) {
				return new ArrayList<>();
			}
			final List<String> lines = new ArrayList<>();
			for (final String line : out.text().split("\n")) { //$NON-NLS-1$
				if (!line.isEmpty()) {
					lines.add(line);
				}
			}
			return lines;
		}
		catch (final IOException e) {
			return new ArrayList<>();
		}
	}

	private static boolean waitFor(final Process p, final long seconds) {
		try {
			return p.waitFor(seconds, TimeUnit.SECONDS);
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Reenv&iacute;a al informe cada l&iacute;nea de la salida est&aacute;ndar de un subproceso; deja de
	 * hacerlo al cerrarse para que un proceso hu&eacute;rfano no escriba en el informe de otro home. */
	private static final class LineForwarder extends Thread {
		private final InputStream is;
		private final RestoreReport report;
		private volatile boolean closed = false;

		LineForwarder(final InputStream is, final RestoreReport report) {
			this.is = is;
			this.report = report;
			setDaemon(true);
		}

		@Override
		public void run() {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.is, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null && !this.closed) {
					this.report.addLine(line);
				}
			}
			catch (final IOException e) {
				// El proceso ha terminado o se ha matado
			}
		}

		void finish() {
			try {
				join(TimeUnit.SECONDS.toMillis(2));
			}
			catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			this.closed = true;
		}
	}

	/** Lee un flujo del proceso en su propio hilo para que la espera nunca se bloquee. */
	private static final class Collector extends Thread {
		private final InputStream is;
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		Collector(final InputStream is) {
			this.is = is;
			setDaemon(true);
		}

		@Override
		public void run() {
			final byte[] buf = new byte[4096];
			int n;
			try (InputStream in = this.is) {
				while ((n = in.read(buf)) != -1) {
					synchronized (this.buffer) {
						this.buffer.write(buf, 0, n);
					}
				}
			}
			catch (final IOException e) {
				// El proceso ha terminado o se ha matado
			}
		}

		void finish() {
			try {
				join(TimeUnit.SECONDS.toMillis(2));
			}
			catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		String text() {
			synchronized (this.buffer) {
				return new String(this.buffer.toByteArray(), StandardCharsets.UTF_8);
			}
		}
	}
}
