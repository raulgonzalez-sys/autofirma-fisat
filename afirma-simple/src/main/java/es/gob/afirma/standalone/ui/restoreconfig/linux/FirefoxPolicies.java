/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.ui.restoreconfig.linux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;

import org.json.JSONException;
import org.json.JSONObject;

/** Fusiona o quita una entrada de {@code ExtensionSettings} en un {@code policies.json} de Firefox
 * sin tocar el resto de claves. La escritura es at&oacute;mica y respeta el destino real de un enlace
 * y los permisos y el due&ntilde;o del fichero previo; un fichero nuevo queda con modo 0644. */
public final class FirefoxPolicies {

	private static final String POLICIES = "policies"; //$NON-NLS-1$
	private static final String EXTENSION_SETTINGS = "ExtensionSettings"; //$NON-NLS-1$

	private FirefoxPolicies() {
		// No se permite la instanciacion
	}

	/** Registra una extensi&oacute;n instalada a la fuerza desde un XPI local.
	 * @param policiesJson Fichero {@code policies.json} (puede no existir; su directorio s&iacute;).
	 * @param id Identificador de la extensi&oacute;n.
	 * @param xpi Ruta absoluta del XPI.
	 * @return {@code true} si el fichero ha cambiado, {@code false} si ya ten&iacute;a esa entrada.
	 * @throws IOException Si el fichero no es un JSON con la estructura esperada o no se puede escribir;
	 *                     en ese caso no se modifica. */
	public static boolean addExtension(final Path policiesJson, final String id, final String xpi) throws IOException {
		final boolean existed = Files.exists(policiesJson);
		final JSONObject data = existed ? load(policiesJson) : new JSONObject();
		final JSONObject policies = data.optJSONObject(POLICIES) != null ? data.getJSONObject(POLICIES) : new JSONObject();
		final JSONObject settings = extensionSettings(policies, policiesJson);
		final JSONObject entry = new JSONObject()
			.put("installation_mode", "force_installed") //$NON-NLS-1$ //$NON-NLS-2$
			.put("install_url", "file://" + xpi); //$NON-NLS-1$ //$NON-NLS-2$
		if (entry.similar(settings.opt(id))) {
			return false;
		}
		settings.put(id, entry);
		policies.put(EXTENSION_SETTINGS, settings);
		data.put(POLICIES, policies);
		save(policiesJson, data, existed);
		return true;
	}

	/** Quita una extensi&oacute;n de {@code ExtensionSettings}; si {@code ExtensionSettings} queda vac&iacute;o
	 * se quita tambi&eacute;n y, si el fichero queda como {@code {"policies":{}}}, se borra.
	 * @param policiesJson Fichero {@code policies.json}.
	 * @param id Identificador de la extensi&oacute;n.
	 * @return {@code true} si el fichero ha cambiado o se ha borrado, {@code false} si no ten&iacute;a la entrada.
	 * @throws IOException Si el fichero no es un JSON con la estructura esperada o no se puede escribir o borrar. */
	public static boolean removeExtension(final Path policiesJson, final String id) throws IOException {
		if (!Files.exists(policiesJson)) {
			return false;
		}
		final JSONObject data = load(policiesJson);
		final JSONObject policies = data.optJSONObject(POLICIES) != null ? data.getJSONObject(POLICIES) : new JSONObject();
		final JSONObject settings = extensionSettings(policies, policiesJson);
		if (!settings.has(id)) {
			return false;
		}
		settings.remove(id);
		if (settings.isEmpty()) {
			policies.remove(EXTENSION_SETTINGS);
		}
		else {
			policies.put(EXTENSION_SETTINGS, settings);
		}
		data.put(POLICIES, policies);
		if (data.length() == 1 && policies.isEmpty()) {
			Files.delete(policiesJson.toRealPath());
			return true;
		}
		save(policiesJson, data, true);
		return true;
	}

	/** Lee el JSON; exige un objeto y que {@code policies}, si existe, sea un objeto. */
	private static JSONObject load(final Path file) throws IOException {
		final JSONObject data;
		try {
			data = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
		}
		catch (final JSONException e) {
			throw new IOException(file + " no es un JSON válido: " + e.getMessage(), e); //$NON-NLS-1$
		}
		if (data.has(POLICIES) && data.optJSONObject(POLICIES) == null) {
			throw new IOException(file + ": estructura inesperada (se esperaba {\"policies\":{...}})"); //$NON-NLS-1$
		}
		return data;
	}

	private static JSONObject extensionSettings(final JSONObject policies, final Path file) throws IOException {
		if (!policies.has(EXTENSION_SETTINGS)) {
			return new JSONObject();
		}
		final JSONObject settings = policies.optJSONObject(EXTENSION_SETTINGS);
		if (settings == null) {
			throw new IOException(file + ": ExtensionSettings no es un objeto"); //$NON-NLS-1$
		}
		return settings;
	}

	/** Temporal en el directorio del destino real y movimiento at&oacute;mico. */
	private static void save(final Path file, final JSONObject data, final boolean existed) throws IOException {
		final Path target = existed ? file.toRealPath() : file.toAbsolutePath();
		final Path tmp = Files.createTempFile(target.getParent(), ".policies.", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
		try {
			Files.write(tmp, (data.toString(2) + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
			if (existed) {
				final PosixFileAttributes attrs = Files.readAttributes(target, PosixFileAttributes.class);
				Files.setPosixFilePermissions(tmp, attrs.permissions());
				final PosixFileAttributeView view = Files.getFileAttributeView(tmp, PosixFileAttributeView.class);
				try {
					view.setOwner(attrs.owner());
					view.setGroup(attrs.group());
				}
				catch (final IOException e) {
					// Sin permiso para cambiar el dueno el fichero queda del usuario que escribe
				}
			}
			else {
				Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--")); //$NON-NLS-1$
			}
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
		}
		finally {
			Files.deleteIfExists(tmp);
		}
	}
}
