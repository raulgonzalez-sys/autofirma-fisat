/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.ui.restoreconfig;

import java.awt.event.KeyListener;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import es.gob.afirma.standalone.SimpleAfirmaMessages;
import es.gob.afirma.standalone.ui.restoreconfig.linux.RestoreCommandLinux;
import es.gob.afirma.standalone.ui.restoreconfig.linux.RestoreReport;
import es.gob.afirma.standalone.ui.restoreconfig.linux.RestoreUserLinux;

/** Botones &laquo;Todo el sistema&raquo; y &laquo;Solo este usuario&raquo; del panel de
 * &laquo;Restaurar instalaci&oacute;n&raquo; en Linux. */
final class RestoreConfigPanelLinux {

	private static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

	private static final String PKEXEC = "/usr/bin/pkexec"; //$NON-NLS-1$

	private static final String LAUNCHER = "/usr/bin/autofirma"; //$NON-NLS-1$

	/** C&oacute;digos de salida de {@code pkexec} cuando se cancela la petici&oacute;n de contrase&ntilde;a
	 * (126) o no se puede autorizar (127). */
	private static final int PKEXEC_NOT_AUTHORIZED = 126;
	private static final int PKEXEC_NOT_AUTHENTICATED = 127;

	private RestoreConfigPanelLinux() {
		// No se permite la instanciacion
	}

	/** Comando que restaura todo el sistema con permisos de administrador.
	 * @return Argumentos del comando. */
	static List<String> systemCommand() {
		return Arrays.asList(PKEXEC, LAUNCHER, "restaurar", "--sistema"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Mensaje final de la restauraci&oacute;n del sistema seg&uacute;n el c&oacute;digo de salida de {@code pkexec}
	 * y las l&iacute;neas {@code ERROR} de su salida: {@code --sistema} sale con 0 aunque falle alg&uacute;n home.
	 * @param exitCode C&oacute;digo de salida.
	 * @param errorLines L&iacute;neas {@code ERROR} le&iacute;das de la salida.
	 * @return Mensaje en el idioma de la aplicaci&oacute;n. */
	static String messageForExit(final int exitCode, final int errorLines) {
		switch (exitCode) {
			case 0:
				return SimpleAfirmaMessages.getString(errorLines > 0
						? "RestoreConfigPanelLinux.7" : "RestoreConfigPanelLinux.6"); //$NON-NLS-1$ //$NON-NLS-2$
			case 1:
				return SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.7"); //$NON-NLS-1$
			case PKEXEC_NOT_AUTHORIZED:
			case PKEXEC_NOT_AUTHENTICATED:
				return SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.5"); //$NON-NLS-1$
			default:
				return SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.8", Integer.toString(exitCode)); //$NON-NLS-1$
		}
	}

	/** A&ntilde;ade al panel los dos botones de restauraci&oacute;n, con sus acciones.
	 * @param buttons Panel de botones donde se a&ntilde;aden.
	 * @param panel Panel de restauraci&oacute;n que recibe los mensajes.
	 * @param keys Receptor de teclado de los botones. */
	static void addButtons(final JPanel buttons, final RestoreConfigPanel panel, final KeyListener keys) {
		final JButton system = new JButton(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.1")); //$NON-NLS-1$
		system.setMnemonic('T');
		system.setToolTipText(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.2")); //$NON-NLS-1$
		system.getAccessibleContext().setAccessibleDescription(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.2")); //$NON-NLS-1$
		system.addKeyListener(keys);

		final JButton user = new JButton(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.3")); //$NON-NLS-1$
		user.setMnemonic('S');
		user.setToolTipText(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.4")); //$NON-NLS-1$
		user.getAccessibleContext().setAccessibleDescription(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.4")); //$NON-NLS-1$
		user.addKeyListener(keys);

		system.addActionListener(ae -> start(panel, system, user, () -> restoreSystem(panel)));
		user.addActionListener(ae -> start(panel, system, user, () -> restoreUser(panel)));
		buttons.add(system);
		buttons.add(user);
	}

	/** Limpia el &aacute;rea de mensajes, deshabilita los dos botones y lanza la tarea en un hilo;
	 * al acabar los vuelve a habilitar. */
	private static void start(final RestoreConfigPanel panel, final JButton system, final JButton user, final Runnable task) {
		panel.taskOutput.setText(null);
		system.setEnabled(false);
		user.setEnabled(false);
		new Thread(() -> {
			try {
				task.run();
			}
			catch (final RuntimeException e) {
				LOGGER.log(Level.SEVERE, "Error inesperado en la restauracion", e); //$NON-NLS-1$
				panel.appendMessage(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.8", e.toString())); //$NON-NLS-1$
			}
			finally {
				SwingUtilities.invokeLater(() -> {
					system.setEnabled(true);
					user.setEnabled(true);
				});
			}
		}, "restaurar-linux").start(); //$NON-NLS-1$
	}

	/** Restaura todo el sistema con {@code pkexec} y vuelca su salida al panel l&iacute;nea a l&iacute;nea. */
	private static void restoreSystem(final RestoreConfigPanel panel) {
		panel.appendMessage(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.9")); //$NON-NLS-1$
		final ProcessBuilder pb = new ProcessBuilder(systemCommand());
		pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))); //$NON-NLS-1$
		final Process p;
		try {
			p = pb.start();
		}
		catch (final IOException e) {
			LOGGER.log(Level.SEVERE, "No se ha podido lanzar pkexec", e); //$NON-NLS-1$
			panel.appendMessage(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.5")); //$NON-NLS-1$
			return;
		}
		final ByteArrayOutputStream err = new ByteArrayOutputStream();
		final Thread errReader = new Thread(() -> drain(p.getErrorStream(), err));
		errReader.setDaemon(true);
		errReader.start();
		int errorLines = 0;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.startsWith(RestoreReport.ERROR)) {
					errorLines++;
				}
				panel.appendMessage(line);
			}
		}
		catch (final IOException e) {
			LOGGER.log(Level.WARNING, "Se ha interrumpido la lectura de la salida de la restauracion", e); //$NON-NLS-1$
		}
		final int code;
		try {
			code = p.waitFor();
			errReader.join(2000);
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroy();
			return;
		}
		if (code != 0) {
			LOGGER.warning("La restauracion del sistema ha terminado con codigo " + code + ": " //$NON-NLS-1$ //$NON-NLS-2$
					+ new String(err.toByteArray(), StandardCharsets.UTF_8).trim());
		}
		panel.appendMessage(messageForExit(code, errorLines));
	}

	private static void drain(final InputStream is, final ByteArrayOutputStream out) {
		final byte[] buf = new byte[1024];
		int n;
		try (InputStream in = is) {
			while ((n = in.read(buf)) != -1) {
				synchronized (out) {
					// Solo para el registro: no hace falta guardar mas
					if (out.size() < 4096) {
						out.write(buf, 0, n);
					}
				}
			}
		}
		catch (final IOException e) {
			// El proceso ha terminado
		}
	}

	/** Restaura la parte de usuario sobre el home de quien ejecuta la aplicaci&oacute;n. */
	private static void restoreUser(final RestoreConfigPanel panel) {
		final File home = RestoreCommandLinux.resolveHome(System.getenv("HOME"), System.getProperty("user.home")); //$NON-NLS-1$ //$NON-NLS-2$
		panel.appendMessage(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.10", home.getPath())); //$NON-NLS-1$
		final RestoreReport report = new RestoreReport(panel::appendMessage);
		try {
			RestoreUserLinux.restore(home, new File(RestoreCommandLinux.CA_FILE), report);
		}
		catch (final RuntimeException e) {
			LOGGER.log(Level.SEVERE, "Error al restaurar el usuario", e); //$NON-NLS-1$
			report.error(e.toString());
		}
		panel.appendMessage(SimpleAfirmaMessages.getString(report.hasErrors()
				? "RestoreConfigPanelLinux.12" : "RestoreConfigPanelLinux.11")); //$NON-NLS-1$ //$NON-NLS-2$
		panel.appendMessage(SimpleAfirmaMessages.getString("RestoreConfigPanelLinux.13")); //$NON-NLS-1$
	}
}
