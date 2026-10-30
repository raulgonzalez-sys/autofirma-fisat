/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.ui.restoreconfig.linux;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import es.gob.afirma.keystores.mozilla.linux.NssLinuxDatabases;
import es.gob.afirma.standalone.configurator.common.ConfiguratorUtil;

/** Parte de usuario de &laquo;Restaurar instalaci&oacute;n&raquo; en Linux sobre un directorio de usuario:
 * instala la CA local de AutoFirma con el {@code certutil} del sistema en cada perfil de Firefox
 * (de todas sus ra&iacute;ces) y en la base NSS de Chrome, y registra {@code afirma://} en
 * {@code user.js} y {@code handlers.json} de cada perfil. Nunca genera certificados ni escribe
 * fuera de esas bases y ficheros; solo borra entradas NSS con los apodos exactos
 * {@code SocketAutoFirma} y {@code Autofirma ROOT}. */
public final class RestoreUserLinux {

	/** Ruta por defecto de {@code certutil} (paquete {@code libnss3-tools}). */
	public static final String CERTUTIL = "/usr/bin/certutil"; //$NON-NLS-1$

	/** Propiedad del sistema que sustituye la ruta de {@code certutil}. */
	public static final String CERTUTIL_PROPERTY = "es.gob.afirma.fisat.certutil"; //$NON-NLS-1$

	static final String NICK = ConfiguratorUtil.CERT_ALIAS;
	static final String LEGACY_NICK = "Autofirma ROOT"; //$NON-NLS-1$
	static final String TRUST = "C,,"; //$NON-NLS-1$

	static final String USER_JS_EXPOSE = "user_pref(\"network.protocol-handler.expose.afirma\", true);"; //$NON-NLS-1$
	static final String USER_JS_PERMISSION = "user_pref(\"security.external_protocol_requires_permission\", false);"; //$NON-NLS-1$

	/** L&iacute;neas de {@code user.js} de las dos preferencias de {@code afirma://}, con cualquier valor. */
	private static final Pattern USER_JS_PREFS = Pattern.compile(
			"^\\s*user_pref\\(\\s*[\"'](network\\.protocol-handler\\.expose\\.afirma|security\\.external_protocol_requires_permission)[\"']"); //$NON-NLS-1$

	static final String LAUNCHER = "/usr/bin/autofirma"; //$NON-NLS-1$

	private static final String PROFILES_INI = "profiles.ini"; //$NON-NLS-1$
	private static final String CERT_DB = "cert9.db"; //$NON-NLS-1$
	private static final String USER_JS = "user.js"; //$NON-NLS-1$
	private static final String HANDLERS_JSON = "handlers.json"; //$NON-NLS-1$
	private static final String SCHEMES = "schemes"; //$NON-NLS-1$
	private static final String AFIRMA = "afirma"; //$NON-NLS-1$
	private static final String SQL = "sql:"; //$NON-NLS-1$
	private static final String DEV_NULL = "/dev/null"; //$NON-NLS-1$

	/** Tiempo m&aacute;ximo de cada llamada a {@code certutil}, para que nunca se quede esperando una contrase&ntilde;a. */
	private static final long TIMEOUT_SECONDS = 10;

	/** Borrados m&aacute;ximos por apodo ({@code certutil -D} borra una entrada en cada llamada). */
	private static final int MAX_DELETES = 5;

	/** Resto de una l&iacute;nea de {@code certutil -L} tras el apodo: espacios y banderas de confianza. */
	private static final Pattern TRUST_FLAGS = Pattern.compile(" +([A-Za-z]*),([A-Za-z]*),([A-Za-z]*) *"); //$NON-NLS-1$

	private RestoreUserLinux() {
		// No se permite la instanciacion
	}

	/** Restaura la parte de usuario sobre un directorio de usuario. Ning&uacute;n fallo de un perfil
	 * o de una base detiene el resto; cada paso deja su l&iacute;nea en el informe.
	 * @param home Directorio del usuario (o {@code /etc/skel}).
	 * @param caCert Certificado de la CA local de AutoFirma que genera la parte de sistema.
	 * @param report Informe que recibe las l&iacute;neas {@code OK}, {@code AVISO} y {@code ERROR}. */
	public static void restore(final File home, final File caCert, final RestoreReport report) {
		final X509Certificate ca;
		try {
			ca = readCertificate(caCert);
		}
		catch (final IOException | CertificateException e) {
			report.error("No se puede leer el certificado raíz de AutoFirma " + caCert //$NON-NLS-1$
					+ " (" + e.getMessage() + "): use «Todo el sistema» para generarlo e instalarlo"); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}

		final List<File> profiles = firefoxProfiles(home, report);
		final List<File> databases = new ArrayList<>();
		final Set<Path> seen = new HashSet<>();
		for (final File profile : profiles) {
			if (!new File(profile, CERT_DB).isFile()) {
				report.warning("El perfil de Firefox " + profile + " no tiene base de certificados (" + CERT_DB //$NON-NLS-1$ //$NON-NLS-2$
						+ "): no se instala en él el certificado raíz; abra Firefox con ese perfil y vuelva a restaurar"); //$NON-NLS-1$
			}
			addDatabase(profile, databases, seen);
		}
		addDatabase(NssLinuxDatabases.chromeDatabase(home), databases, seen);

		if (profiles.isEmpty() && databases.isEmpty()) {
			report.ok("Sin perfiles de Firefox ni base NSS de Chrome en " + home); //$NON-NLS-1$
			return;
		}

		if (!databases.isEmpty()) {
			final String certutil = System.getProperty(CERTUTIL_PROPERTY, CERTUTIL);
			if (new File(certutil).canExecute()) {
				for (final File db : databases) {
					installCa(certutil, db, caCert, ca, report);
				}
			}
			else {
				report.error("No se encuentra " + certutil //$NON-NLS-1$
						+ " (paquete libnss3-tools): no se instala el certificado raíz en las bases NSS"); //$NON-NLS-1$
			}
		}

		for (final File profile : profiles) {
			userJs(profile, report);
			handlers(profile, report);
		}
	}

	private static X509Certificate readCertificate(final File file) throws IOException, CertificateException {
		if (file == null || !file.isFile()) {
			throw new IOException("no existe"); //$NON-NLS-1$
		}
		try (InputStream is = Files.newInputStream(file.toPath())) {
			return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(is); //$NON-NLS-1$
		}
	}

	// --- Perfiles y bases ---

	/** Perfiles de Firefox de todas las ra&iacute;ces que existen como directorio, sin repetir el mismo destino real. */
	private static List<File> firefoxProfiles(final File home, final RestoreReport report) {
		final List<File> profiles = new ArrayList<>();
		final Set<Path> seen = new HashSet<>();
		for (final File root : NssLinuxDatabases.firefoxRoots(home)) {
			final File ini = new File(root, PROFILES_INI);
			final List<File> listed;
			try {
				listed = parseProfilesIni(root, Files.readAllLines(ini.toPath(), StandardCharsets.UTF_8));
			}
			catch (final IOException | RuntimeException e) {
				report.warning("No se puede leer " + ini + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			for (final File profile : listed) {
				if (profile.isDirectory() && seen.add(realPath(profile))) {
					profiles.add(profile);
				}
			}
		}
		return profiles;
	}

	/** Rutas de las secciones {@code [Profile*]} de un {@code profiles.ini}; una ruta es relativa a la
	 * ra&iacute;z salvo con {@code IsRelative=0}. */
	private static List<File> parseProfilesIni(final File root, final List<String> lines) {
		final List<File> profiles = new ArrayList<>();
		String section = null;
		String path = null;
		String relative = null;
		for (final String raw : lines) {
			final String line = raw.trim();
			if (line.startsWith("[") && line.endsWith("]")) { //$NON-NLS-1$ //$NON-NLS-2$
				addProfile(root, section, path, relative, profiles);
				section = line.substring(1, line.length() - 1);
				path = null;
				relative = null;
				continue;
			}
			final int eq = line.indexOf('=');
			if (eq <= 0 || !isProfileSection(section)) {
				continue;
			}
			final String key = line.substring(0, eq).trim();
			final String value = line.substring(eq + 1).trim();
			if ("Path".equals(key)) { //$NON-NLS-1$
				path = value;
			}
			else if ("IsRelative".equals(key)) { //$NON-NLS-1$
				relative = value;
			}
		}
		addProfile(root, section, path, relative, profiles);
		return profiles;
	}

	private static boolean isProfileSection(final String section) {
		return section != null && section.startsWith("Profile"); //$NON-NLS-1$
	}

	private static void addProfile(final File root, final String section, final String path, final String relative,
			final List<File> profiles) {
		if (isProfileSection(section) && path != null && !path.isEmpty()) {
			profiles.add("0".equals(relative) ? new File(path) : new File(root, path)); //$NON-NLS-1$
		}
	}

	private static void addDatabase(final File dir, final List<File> databases, final Set<Path> seen) {
		if (dir != null && new File(dir, CERT_DB).isFile() && seen.add(realPath(dir))) {
			databases.add(dir);
		}
	}

	private static Path realPath(final File file) {
		try {
			return file.toPath().toRealPath();
		}
		catch (final IOException e) {
			return file.toPath().toAbsolutePath().normalize();
		}
	}

	// --- Certificado en NSS ---

	private static void installCa(final String certutil, final File db, final File caFile, final X509Certificate ca,
			final RestoreReport report) {
		try {
			// Con -f /dev/null y sin entrada nunca pide contrasena: una base con contrasena maestra da SEC_ERROR_BAD_PASSWORD
			final Result keys = run(certutil, "-K", "-d", SQL + db, "-f", DEV_NULL); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (keys.timedOut || keys.text().contains("SEC_ERROR_BAD_PASSWORD")) { //$NON-NLS-1$
				report.warning("Se salta " + db + ": la base tiene contraseña maestra"); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
			if (isInstalled(certutil, db, ca)) {
				report.ok("Certificado raíz sin cambios en " + db); //$NON-NLS-1$
				return;
			}
			for (final String nick : new String[] { LEGACY_NICK, NICK }) {
				for (int i = 0; i < MAX_DELETES && !trustOf(certutil, db, nick).isEmpty(); i++) {
					run(certutil, "-D", "-d", SQL + db, "-n", nick, "-f", DEV_NULL) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
						.check("certutil -D -n '" + nick + "'"); //$NON-NLS-1$ //$NON-NLS-2$
				}
				if (!trustOf(certutil, db, nick).isEmpty()) {
					throw new IOException("quedan entradas '" + nick + "' tras " + MAX_DELETES + " borrados"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
			}
			run(certutil, "-A", "-d", SQL + db, "-n", NICK, "-t", TRUST, "-f", DEV_NULL, "-i", caFile.getPath()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				.check("certutil -A"); //$NON-NLS-1$
			if (!isInstalled(certutil, db, ca)) {
				throw new IOException("tras certutil -A el certificado no ha quedado bien"); //$NON-NLS-1$
			}
			report.ok("Certificado raíz instalado en " + db); //$NON-NLS-1$
		}
		catch (final IOException e) {
			report.error("No se ha podido instalar el certificado raíz en " + db + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/** Un &uacute;nico {@code SocketAutoFirma} con la CA y confianza SSL {@code C}, y ning&uacute;n apodo antiguo. */
	private static boolean isInstalled(final String certutil, final File db, final X509Certificate ca) throws IOException {
		final List<String> trust = trustOf(certutil, db, NICK);
		if (trust.size() != 1 || trust.get(0).indexOf('C') < 0 || !trustOf(certutil, db, LEGACY_NICK).isEmpty()) {
			return false;
		}
		final Result der = run(certutil, "-L", "-d", SQL + db, "-n", NICK, "-r"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		der.check("certutil -L -n " + NICK); //$NON-NLS-1$
		try {
			// Algunas versiones de NSS repiten el DER con -r: basta con el primer certificado
			return Arrays.equals(ca.getEncoded(), CertificateFactory.getInstance("X.509") //$NON-NLS-1$
					.generateCertificate(new ByteArrayInputStream(der.out)).getEncoded());
		}
		catch (final CertificateException e) {
			return false;
		}
	}

	/** Confianza SSL de cada entrada con ese apodo exacto. */
	private static List<String> trustOf(final String certutil, final File db, final String nick) throws IOException {
		final Result list = run(certutil, "-L", "-d", SQL + db); //$NON-NLS-1$ //$NON-NLS-2$
		list.check("certutil -L"); //$NON-NLS-1$
		final List<String> trust = new ArrayList<>();
		for (final String line : new String(list.out, StandardCharsets.UTF_8).split("\n")) { //$NON-NLS-1$
			if (line.startsWith(nick)) {
				final Matcher m = TRUST_FLAGS.matcher(line.substring(nick.length()));
				if (m.matches()) {
					trust.add(m.group(1));
				}
			}
		}
		return trust;
	}

	// --- user.js ---

	private static void userJs(final File profile, final RestoreReport report) {
		Path file = new File(profile, USER_JS).toPath();
		try {
			if (Files.isSymbolicLink(file) && Files.exists(file)) {
				file = file.toRealPath();
			}
			final List<String> lines = new ArrayList<>();
			if (Files.exists(file)) {
				// ISO-8859-1 conserva cada byte tal cual al reescribir
				lines.addAll(Arrays.asList(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).split("\n", -1))); //$NON-NLS-1$
				if (lines.get(lines.size() - 1).isEmpty()) {
					lines.remove(lines.size() - 1);
				}
			}
			int prefs = 0;
			int expose = 0;
			int permission = 0;
			final StringBuilder kept = new StringBuilder();
			for (final String line : lines) {
				if (USER_JS_PREFS.matcher(line).find()) {
					prefs++;
					expose += USER_JS_EXPOSE.equals(line) ? 1 : 0;
					permission += USER_JS_PERMISSION.equals(line) ? 1 : 0;
				}
				else {
					kept.append(line).append('\n');
				}
			}
			if (prefs == 2 && expose == 1 && permission == 1) {
				report.ok("user.js sin cambios en " + profile); //$NON-NLS-1$
				return;
			}
			kept.append(USER_JS_EXPOSE).append('\n').append(USER_JS_PERMISSION).append('\n');
			writeAtomically(file, kept.toString().getBytes(StandardCharsets.ISO_8859_1));
			report.ok("Preferencias de afirma:// en " + file); //$NON-NLS-1$
		}
		catch (final IOException e) {
			report.error("No se ha podido escribir " + file + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	// --- handlers.json ---

	private static JSONObject afirmaHandler() {
		return new JSONObject()
			.put("action", 2) //$NON-NLS-1$
			.put("handlers", new JSONArray().put(new JSONObject() //$NON-NLS-1$
				.put("name", "AutoFirma") //$NON-NLS-1$ //$NON-NLS-2$
				.put("path", LAUNCHER))); //$NON-NLS-1$
	}

	private static void handlers(final File profile, final RestoreReport report) {
		final Path file = new File(profile, HANDLERS_JSON).toPath();
		final boolean exists = Files.exists(file);
		final JSONObject data;
		final JSONObject schemes;
		try {
			if (exists) {
				data = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			}
			else {
				data = new JSONObject()
					.put("defaultHandlersVersion", new JSONObject()) //$NON-NLS-1$
					.put("mimeTypes", new JSONObject()); //$NON-NLS-1$
			}
			if (!data.has(SCHEMES)) {
				data.put(SCHEMES, new JSONObject());
			}
			schemes = data.getJSONObject(SCHEMES);
		}
		catch (final IOException | JSONException e) {
			report.error(file + " es ilegible y no se toca: " + e.getMessage()); //$NON-NLS-1$
			return;
		}
		if (exists && afirmaHandler().similar(schemes.opt(AFIRMA))) {
			report.ok("handlers.json sin cambios en " + profile); //$NON-NLS-1$
			return;
		}
		// Firefox reescribe handlers.json al cerrarse: con el perfil abierto el cambio se perderia
		if (isProfileOpen(profile)) {
			report.warning("Firefox está abierto con el perfil " + profile + ": no se escribe " + file //$NON-NLS-1$ //$NON-NLS-2$
					+ "; ciérrelo y vuelva a restaurar"); //$NON-NLS-1$
			return;
		}
		schemes.put(AFIRMA, afirmaHandler());
		try {
			writeAtomically(file, data.toString().getBytes(StandardCharsets.UTF_8));
			report.ok("afirma:// registrado en " + file); //$NON-NLS-1$
		}
		catch (final IOException e) {
			report.error("No se ha podido escribir " + file + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/** Firefox abierto con el perfil: el enlace {@code lock} apunta a algo que acaba en {@code +<pid>} y ese proceso existe. */
	private static boolean isProfileOpen(final File profile) {
		final Path lock = new File(profile, "lock").toPath(); //$NON-NLS-1$
		if (!Files.isSymbolicLink(lock)) {
			return false;
		}
		try {
			final String target = Files.readSymbolicLink(lock).toString();
			final String pid = target.substring(target.lastIndexOf('+') + 1);
			return pid.matches("[0-9]+") && new File("/proc/" + pid).exists(); //$NON-NLS-1$ //$NON-NLS-2$
		}
		catch (final IOException e) {
			return false;
		}
	}

	// --- Utilidades ---

	/** Escribe con un temporal en el mismo directorio y un movimiento at&oacute;mico; conserva los permisos del fichero previo. */
	private static void writeAtomically(final Path file, final byte[] data) throws IOException {
		final Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "." + file.getFileName() + ".", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		try {
			Files.write(tmp, data);
			if (Files.exists(file)) {
				Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(file));
			}
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
		}
		finally {
			Files.deleteIfExists(tmp);
		}
	}

	/** Ejecuta un comando sin entrada y con tiempo l&iacute;mite; si se agota, lo mata. */
	private static Result run(final String... cmd) throws IOException {
		final Process p = new ProcessBuilder(cmd)
			.redirectInput(ProcessBuilder.Redirect.from(new File(DEV_NULL)))
			.start();
		final Collector out = new Collector(p.getInputStream());
		final Collector err = new Collector(p.getErrorStream());
		out.start();
		err.start();
		boolean finished;
		try {
			finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			finished = false;
		}
		if (!finished) {
			p.destroyForcibly();
		}
		out.finish();
		err.finish();
		return new Result(finished ? p.exitValue() : -1, !finished, out.bytes(), err.bytes());
	}

	private static final class Result {
		final int code;
		final boolean timedOut;
		final byte[] out;
		final byte[] err;

		Result(final int code, final boolean timedOut, final byte[] out, final byte[] err) {
			this.code = code;
			this.timedOut = timedOut;
			this.out = out;
			this.err = err;
		}

		String text() {
			return new String(this.out, StandardCharsets.UTF_8) + new String(this.err, StandardCharsets.UTF_8);
		}

		void check(final String what) throws IOException {
			if (this.timedOut) {
				throw new IOException(what + " no ha respondido en " + TIMEOUT_SECONDS + " s"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (this.code != 0) {
				throw new IOException(what + " ha fallado (" + this.code + "): " //$NON-NLS-1$ //$NON-NLS-2$
						+ new String(this.err, StandardCharsets.UTF_8).trim());
			}
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

		byte[] bytes() {
			synchronized (this.buffer) {
				return this.buffer.toByteArray();
			}
		}
	}
}
