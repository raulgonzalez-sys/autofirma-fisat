/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.configurator;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Locale;
import java.util.logging.Logger;


/**
 * Contiene la l&oacute;gica com&uacute;n para todos los sistemas que permite realizar las tareas de restauraci&oacute;n
 * asociadas al navegador Firefox.
 */
final class ConfiguratorFirefoxCommon {

	private static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$
	private static final long NSS_PASSWORD_CHECK_TIMEOUT_MILLIS = 5000L;
	private static final long NSS_PASSWORD_CHECK_POLL_MILLIS = 100L;
	private static final int NSS_PASSWORD_CHECK_READ_BUFFER_SIZE = 256;
	private static final int NSS_PASSWORD_CHECK_MAX_OUTPUT_SIZE = 8192;

	private ConfiguratorFirefoxCommon() {
		// No instanciable
	}

	/** Comprueba si el almac&eacute;n NSS de un perfil est&aacute; protegido por contrase&ntilde;a maestra,
	 * con un tiempo m&aacute;ximo de 5 segundos.
	 * @param certUtilPath Ruta del ejecutable certutil.
	 * @param profileDir Directorio del perfil.
	 * @return <code>true</code> si est&aacute; protegido o no se pudo determinar (falla en cerrado). */
	static boolean isNssDbPasswordProtected(final String certUtilPath, final File profileDir) {
		return isNssDbPasswordProtected(certUtilPath, profileDir, NSS_PASSWORD_CHECK_TIMEOUT_MILLIS);
	}

	/** Comprueba si el almac&eacute;n NSS de un perfil est&aacute; protegido por contrase&ntilde;a maestra.
	 * Si certutil no responde a tiempo, falla o no existe se devuelve <code>true</code>: un perfil
	 * omitido lo cubre el paso por usuario, pero un <code>certutil -A</code> a la espera de la
	 * contrase&ntilde;a dejar&iacute;a colgado el <i>postinst</i>.
	 * @param certUtilPath Ruta del ejecutable certutil.
	 * @param profileDir Directorio del perfil.
	 * @param timeoutMillis Tiempo m&aacute;ximo de espera en milisegundos.
	 * @return <code>true</code> si est&aacute; protegido o no se pudo determinar (falla en cerrado). */
	static boolean isNssDbPasswordProtected(final String certUtilPath, final File profileDir, final long timeoutMillis) {
		Process process = null;
		Thread outputReader = null;
		try {
			final boolean sqlDb = new File(profileDir, "pkcs11.txt").exists(); //$NON-NLS-1$
			final String profileReference = (sqlDb ? "sql:" : "") //$NON-NLS-1$ //$NON-NLS-2$
					+ profileDir.getAbsolutePath();
			final Process startedProcess = new ProcessBuilder(certUtilPath, "-K", "-d", profileReference) //$NON-NLS-1$ //$NON-NLS-2$
					.redirectErrorStream(true)
					.start();
			process = startedProcess;

			final StringBuilder processOutput = new StringBuilder();
			outputReader = new Thread(() -> {
				try (final Reader reader = new InputStreamReader(startedProcess.getInputStream())) {
					final char[] buffer = new char[NSS_PASSWORD_CHECK_READ_BUFFER_SIZE];
					int charsRead;
					while ((charsRead = reader.read(buffer)) != -1) {
						synchronized (processOutput) {
							processOutput.append(buffer, 0, charsRead);
							if (containsPasswordPrompt(processOutput)) {
								return;
							}
							if (processOutput.length() > NSS_PASSWORD_CHECK_MAX_OUTPUT_SIZE) {
								processOutput.delete(0, processOutput.length() - NSS_PASSWORD_CHECK_MAX_OUTPUT_SIZE);
							}
						}
					}
				}
				catch (final IOException e) {
					// Puede ocurrir cuando se cierra el flujo al finalizar el tiempo limite
					LOGGER.fine("No se pudo leer la salida de CertUtil"); //$NON-NLS-1$
				}
			}, "certutil-output-reader"); //$NON-NLS-1$
			outputReader.setDaemon(true);
			outputReader.start();

			final long deadline = System.nanoTime() + timeoutMillis * 1000000L;
			boolean passwordProtected;
			while (outputReader.isAlive()) {
				synchronized (processOutput) {
					passwordProtected = containsPasswordPrompt(processOutput);
				}
				if (passwordProtected) {
					return true;
				}

				final long remainingNanos = deadline - System.nanoTime();
				if (remainingNanos <= 0) {
					LOGGER.warning("Se agoto el tiempo de espera al comprobar la contrasena del almacen NSS; se considera protegido"); //$NON-NLS-1$
					return true;
				}
				try {
					final long remainingMillis = (remainingNanos + 999999L) / 1000000L;
					outputReader.join(Math.min(NSS_PASSWORD_CHECK_POLL_MILLIS, Math.max(1L, remainingMillis)));
				}
				catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					LOGGER.warning("Se interrumpio la comprobacion de la contrasena del almacen NSS; se considera protegido"); //$NON-NLS-1$
					return true;
				}
			}

			synchronized (processOutput) {
				return containsPasswordPrompt(processOutput);
			}
		}
		catch (final Exception e) {
			LOGGER.warning("No se pudo determinar si el almacen NSS esta protegido por contrasena; se considera protegido: " + e); //$NON-NLS-1$
			return true;
		}
		finally {
			if (process != null) {
				process.destroyForcibly();
				try {
					process.getInputStream().close();
				}
				catch (final IOException e) {
					LOGGER.fine("No se pudo cerrar la salida de CertUtil"); //$NON-NLS-1$
				}
			}
			if (outputReader != null && outputReader.isAlive()) {
				try {
					outputReader.join(NSS_PASSWORD_CHECK_POLL_MILLIS);
				}
				catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	private static boolean containsPasswordPrompt(final StringBuilder processOutput) {
		final String output = processOutput.toString();
		return output.contains("Enter Password") || output.toLowerCase(Locale.ENGLISH).contains("password"); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
