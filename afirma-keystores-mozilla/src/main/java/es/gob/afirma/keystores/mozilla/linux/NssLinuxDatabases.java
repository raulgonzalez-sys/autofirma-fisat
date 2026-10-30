/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.keystores.mozilla.linux;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import es.gob.afirma.keystores.mozilla.NSPreferences;

/** Localiza en Linux las bases NSS (formato SQLite, {@code cert9.db}) que usan
 * Firefox y Chrome dentro de un directorio de usuario, aplicando en todos los
 * casos una &uacute;nica regla de ra&iacute;z de Firefox. */
public final class NssLinuxDatabases {

	private static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

	private static final String PROFILES_INI = "profiles.ini"; //$NON-NLS-1$
	private static final String CERT_DB = "cert9.db"; //$NON-NLS-1$

	private NssLinuxDatabases() {
		// No se permite la instanciacion
	}

	private static File legacyRoot(final File home) {
		return new File(home, ".mozilla/firefox"); //$NON-NLS-1$
	}

	private static File xdgRoot(final File home) {
		return new File(home, ".config/mozilla/firefox"); //$NON-NLS-1$
	}

	private static File snapRoot(final File home) {
		return new File(home, "snap/firefox/common/.mozilla/firefox"); //$NON-NLS-1$
	}

	private static boolean hasProfilesIni(final File root) {
		return new File(root, PROFILES_INI).isFile();
	}

	/** Obtiene todas las ra&iacute;ces de Firefox que contienen un {@code profiles.ini}, en
	 * el orden tradicional ({@code ~/.mozilla/firefox}), XDG ({@code ~/.config/mozilla/firefox})
	 * y Snap ({@code ~/snap/firefox/common/.mozilla/firefox}). No aplica la regla de Firefox
	 * ({@link #firefoxRoot(File)}): sirve para recorrer los perfiles de todas las ra&iacute;ces,
	 * por ejemplo al restaurar tras una migraci&oacute;n a la ra&iacute;z XDG.
	 * @param home Directorio del usuario.
	 * @return Todas las ra&iacute;ces con {@code profiles.ini} (lista vac&iacute;a si no hay ninguna). */
	public static List<File> firefoxRoots(final File home) {
		final List<File> roots = new ArrayList<>();
		if (home == null) {
			return roots;
		}
		for (final File root : new File[] { legacyRoot(home), xdgRoot(home), snapRoot(home) }) {
			if (hasProfilesIni(root)) {
				roots.add(root);
			}
		}
		return roots;
	}

	/** Obtiene la ra&iacute;z de Firefox que se debe usar para leer, con la regla del propio
	 * Firefox: si existe el directorio tradicional esa es la ra&iacute;z y nunca se usa la XDG,
	 * aunque el tradicional no tenga {@code profiles.ini}; la XDG solo cuenta si el directorio
	 * tradicional no existe. La de Snap va al final.
	 * @param home Directorio del usuario.
	 * @return Directorio que contiene el {@code profiles.ini}, o {@code null} si no hay. */
	public static File firefoxRoot(final File home) {
		if (home == null) {
			return null;
		}
		final File legacy = legacyRoot(home);
		if (hasProfilesIni(legacy)) {
			return legacy;
		}
		if (!legacy.isDirectory() && hasProfilesIni(xdgRoot(home))) {
			return xdgRoot(home);
		}
		return hasProfilesIni(snapRoot(home)) ? snapRoot(home) : null;
	}

	/** Obtiene el directorio de la base NSS de Chrome: {@code ~/.pki/nssdb} si existe y,
	 * si no, {@code ~/.local/share/pki/nssdb}. El directorio devuelto puede no existir.
	 * @param home Directorio del usuario.
	 * @return Directorio de la base de Chrome, o {@code null} si {@code home} es nulo. */
	public static File chromeDatabase(final File home) {
		if (home == null) {
			return null;
		}
		final File legacy = new File(home, ".pki/nssdb"); //$NON-NLS-1$
		if (legacy.isDirectory()) {
			return legacy;
		}
		return new File(home, ".local/share/pki/nssdb"); //$NON-NLS-1$
	}

	/** Obtiene las bases NSS con {@code cert9.db} del perfil activo de Firefox y de Chrome,
	 * sin repetidas (se comparan por ruta real) y con Firefox primero.
	 * @param home Directorio del usuario.
	 * @return Directorios de las bases (lista vac&iacute;a si no hay ninguna). */
	public static List<File> findDatabases(final File home) {
		final List<File> databases = new ArrayList<>();
		final Set<File> seen = new HashSet<>();
		addIfValid(firefoxProfile(home), databases, seen);
		addIfValid(chromeDatabase(home), databases, seen);
		return databases;
	}

	private static File firefoxProfile(final File home) {
		final File root = firefoxRoot(home);
		if (root == null) {
			return null;
		}
		try {
			final String profile = NSPreferences.getFireFoxUserProfileDirectory(new File(root, PROFILES_INI));
			return profile == null ? null : new File(profile);
		}
		catch (final IOException | RuntimeException e) {
			LOGGER.warning("No se ha podido determinar el perfil de Firefox: " + e); //$NON-NLS-1$
			return null;
		}
	}

	private static void addIfValid(final File dir, final List<File> databases, final Set<File> seen) {
		if (dir == null || !new File(dir, CERT_DB).isFile()) {
			return;
		}
		try {
			if (seen.add(dir.toPath().toRealPath().toFile())) {
				databases.add(dir);
			}
		}
		catch (final IOException e) {
			LOGGER.warning("No se ha podido resolver la ruta real de una base NSS: " + e); //$NON-NLS-1$
		}
	}
}
