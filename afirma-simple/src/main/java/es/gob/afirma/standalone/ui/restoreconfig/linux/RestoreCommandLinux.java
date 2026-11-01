/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.ui.restoreconfig.linux;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.Consumer;

import com.sun.security.auth.module.UnixSystem;

/** Orden {@code autofirma restaurar} de Linux, sin interfaz gr&aacute;fica:
 * <ul>
 *  <li>{@code --sistema}: parte de sistema y de todos los usuarios (exige root).</li>
 *  <li>{@code --usuario}: parte de usuario sobre el home de quien la ejecuta (como root, solo si
 *  ese home es de root).</li>
 *  <li>{@code --quitar-politicas}: quita la extensi&oacute;n CSP de los {@code policies.json} de Firefox (exige root).</li>
 * </ul>
 * Cada l&iacute;nea de salida es un paso {@code OK}, {@code AVISO} o {@code ERROR} en castellano.
 * C&oacute;digo de salida: 0 correcto, 1 con errores y 2 uso incorrecto o falta de permisos. */
public final class RestoreCommandLinux {

	/** Directorio que sustituye {@code /etc} en las pruebas; nulo en producci&oacute;n. Es un campo y no
	 * una propiedad del sistema para que nada externo pueda cambiar lo que se escribe como root. */
	static volatile File testEtcDir = null;

	/** Directorio de la aplicaci&oacute;n. */
	public static final String APP_DIR = "/usr/lib/Autofirma"; //$NON-NLS-1$

	/** Certificado de la CA local de AutoFirma que genera la parte de sistema. */
	public static final String CA_FILE = APP_DIR + "/Autofirma_ROOT.cer"; //$NON-NLS-1$

	private static final String SYSTEM = "--sistema"; //$NON-NLS-1$
	private static final String USER = "--usuario"; //$NON-NLS-1$
	private static final String REMOVE_POLICIES = "--quitar-politicas"; //$NON-NLS-1$
	private static final String COMMAND = "restaurar"; //$NON-NLS-1$
	private static final String ETC = "/etc"; //$NON-NLS-1$
	private static final String USAGE = "Uso: autofirma restaurar " + SYSTEM + " | " + USER + " | " + REMOVE_POLICIES; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	private static final int EXIT_OK = 0;
	private static final int EXIT_ERRORS = 1;
	private static final int EXIT_USAGE = 2;

	private RestoreCommandLinux() {
		// No se permite la instanciacion
	}

	/** Ejecuta la orden y sale del proceso con su c&oacute;digo.
	 * @param args Argumentos de la l&iacute;nea de comandos, con {@code restaurar} en primer lugar. */
	public static void main(final String[] args) {
		PrintStream stdout;
		try {
			// UTF-8 siempre: bajo pkexec el entorno no trae locale y la JVM escribiria ASCII
			stdout = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"); //$NON-NLS-1$
		}
		catch (final UnsupportedEncodingException e) {
			stdout = System.out;
		}
		final PrintStream out = stdout;
		System.exit(run(args, out::println));
	}

	/** Ejecuta la orden con el entorno real.
	 * @param args Argumentos, con o sin {@code restaurar} en primer lugar.
	 * @param out Recibe cada l&iacute;nea de salida.
	 * @return C&oacute;digo de salida. */
	public static int run(final String[] args, final Consumer<String> out) {
		final File test = testEtcDir;
		return run(args, out, isRoot(), System.getenv("HOME"), new File(CA_FILE), test != null ? test : new File(ETC)); //$NON-NLS-1$
	}

	static int run(final String[] args, final Consumer<String> out, final boolean root, final String envHome,
			final File caCert, final File etcDir) {
		return run(args, out, root, envHome, caCert, etcDir, RestoreSystemLinux.UidReader.FILES);
	}

	static int run(final String[] args, final Consumer<String> out, final boolean root, final String envHome,
			final File caCert, final File etcDir, final RestoreSystemLinux.UidReader uids) {
		final RestoreReport report = new RestoreReport(out);
		final String option = option(args);
		if (option == null) {
			report.error("Opción no válida: " + (args == null ? "" : String.join(" ", args))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			out.accept(USAGE);
			return EXIT_USAGE;
		}
		if (!USER.equals(option) && !root) {
			report.error("Esta opción debe ejecutarla el administrador del equipo (root)"); //$NON-NLS-1$
			return EXIT_USAGE;
		}
		switch (option) {
			case SYSTEM:
				return RestoreSystemLinux.restore(new File(APP_DIR), etcDir, RestoreSystemLinux.systemHomes(report), report);
			case USER:
				final File home = resolveHome(envHome, System.getProperty("user.home")); //$NON-NLS-1$
				if (!userHomeAllowed(home, root, uids, report)) {
					return EXIT_USAGE;
				}
				RestoreUserLinux.restore(home, caCert, report);
				return report.hasErrors() ? EXIT_ERRORS : EXIT_OK;
			default:
				return removePolicies(etcDir, report) ? EXIT_OK : EXIT_ERRORS;
		}
	}

	/** Opci&oacute;n de la orden: el &uacute;nico argumento, sin contar un {@code restaurar} inicial.
	 * @return La opci&oacute;n o {@code null} si no es una de las tres. */
	private static String option(final String[] args) {
		if (args == null) {
			return null;
		}
		final int first = args.length > 0 && COMMAND.equals(args[0]) ? 1 : 0;
		if (args.length - first != 1) {
			return null;
		}
		final String option = args[first];
		return SYSTEM.equals(option) || USER.equals(option) || REMOVE_POLICIES.equals(option) ? option : null;
	}

	/** Home que se restaura: {@code HOME} si est&aacute; definido y es un directorio (&uacute;til con {@code runuser},
	 * que lo cambia al de passwd, y que la parte de sistema vuelve a poner) y, si no, {@code user.home}.
	 * @param envHome Valor de {@code HOME} (puede ser nulo).
	 * @param userHome Valor de {@code user.home}.
	 * @return Directorio del usuario. */
	public static File resolveHome(final String envHome, final String userHome) {
		if (envHome != null && !envHome.isEmpty() && new File(envHome).isDirectory()) {
			return new File(envHome);
		}
		return new File(userHome);
	}

	/** Comprueba que la parte de usuario puede restaurar ese home con la identidad de este proceso.
	 * Sin ser root siempre puede; como root solo si el home es de root: con {@code su} sin guion o
	 * {@code sudo -E}, {@code HOME} sigue siendo el de la persona y root dejar&iacute;a a su nombre
	 * {@code user.js}, {@code handlers.json} y las bases NSS de esa persona, que Firefox ya no
	 * podr&iacute;a leer. Si lo rechaza, deja el error en el informe.
	 * @param home Home que se va a restaurar.
	 * @param root Si este proceso es root.
	 * @param uids Lector del due&ntilde;o del home.
	 * @param report Informe que recibe el error.
	 * @return {@code true} si se puede restaurar. */
	static boolean userHomeAllowed(final File home, final boolean root, final RestoreSystemLinux.UidReader uids,
			final RestoreReport report) {
		if (!root) {
			return true;
		}
		String owner;
		try {
			final int uid = uids.uid(home.toPath());
			if (uid == 0) {
				return true;
			}
			owner = "UID " + uid; //$NON-NLS-1$
		}
		catch (final IOException | RuntimeException e) {
			owner = "dueño desconocido: " + e; //$NON-NLS-1$
		}
		report.error("Como root no se restaura " + home + " (" + owner + "), que no es de root: ejecute " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				+ "«autofirma restaurar " + USER + "» como ese usuario, sin sudo ni su, o use «Todo el sistema» " //$NON-NLS-1$ //$NON-NLS-2$
				+ "(autofirma restaurar " + SYSTEM + ")"); //$NON-NLS-1$ //$NON-NLS-2$
		return false;
	}

	/** Comprobaci&oacute;n de {@link #userHomeAllowed(File, boolean, RestoreSystemLinux.UidReader, RestoreReport)}
	 * con la identidad de este proceso y el due&ntilde;o real del home.
	 * @param home Home que se va a restaurar.
	 * @param report Informe que recibe el error.
	 * @return {@code true} si se puede restaurar. */
	public static boolean userHomeAllowed(final File home, final RestoreReport report) {
		return userHomeAllowed(home, isRoot(), RestoreSystemLinux.UidReader.FILES, report);
	}

	/** Indica si este proceso se ejecuta como root: UID real por JAAS ({@code getuid}, sin depender de
	 * {@code /proc}) y, si el m&oacute;dulo {@code jdk.security.auth} no est&aacute; disponible, el
	 * due&ntilde;o de {@code /proc/self}.
	 * @return {@code true} si su UID es 0. */
	static boolean isRoot() {
		try {
			return new UnixSystem().getUid() == 0;
		}
		catch (final LinkageError | RuntimeException e) {
			// Sin jdk.security.auth o sin su biblioteca nativa
		}
		try {
			return ((Integer) Files.getAttribute(Paths.get("/proc/self"), "unix:uid")).intValue() == 0; //$NON-NLS-1$ //$NON-NLS-2$
		}
		catch (final IOException | RuntimeException e) {
			return false;
		}
	}

	private static boolean removePolicies(final File etcDir, final RestoreReport report) {
		boolean ok = true;
		for (final String browser : new String[] { "firefox", "firefox-esr" }) { //$NON-NLS-1$ //$NON-NLS-2$
			final File file = new File(etcDir, browser + "/policies/policies.json"); //$NON-NLS-1$
			try {
				if (FirefoxPolicies.removeExtension(file.toPath(), RestoreSystemLinux.EXTENSION_ID)) {
					report.ok("Extensión CSP de Firefox quitada de " + file); //$NON-NLS-1$
				}
				else {
					report.ok("Sin extensión CSP de Firefox en " + file); //$NON-NLS-1$
				}
			}
			catch (final IOException | RuntimeException e) {
				report.error("No se ha podido quitar la extensión CSP de " + file + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
				ok = false;
			}
		}
		return ok;
	}
}
