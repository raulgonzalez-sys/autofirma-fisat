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
import java.lang.reflect.Method;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import es.gob.afirma.core.misc.LoggerUtil;
import es.gob.afirma.keystores.mozilla.MozillaKeyStoreUtilities;

/** Abre varias bases NSS (formato SQLite) en una &uacute;nica inicializaci&oacute;n de NSS,
 * con una ranura por base ({@code tokens=}) y un proveedor SunPKCS11 por ranura.
 * NSS solo se inicializa una vez por proceso, as&iacute; que {@link #load(String, List)}
 * guarda los proveedores que crea y los devuelve en las llamadas siguientes. */
public final class NssMultiSlotLoader {

	private static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

	private static final String SOFTOKN3_SO = "libsoftokn3.so"; //$NON-NLS-1$
	private static final String PROVIDER_NAME_PREFIX = "NSSCrypto-AFirma-"; //$NON-NLS-1$

	/** Caracteres que romper&iacute;an la sintaxis de {@code nssArgs} o de la configuraci&oacute;n
	 * de SunPKCS11 si aparecen en la ruta de una base. */
	private static final String UNSAFE_PATH_CHARS = "'\"[]<>\\\r\n"; //$NON-NLS-1$

	/** Proveedores ya cargados en este proceso ({@code null} si a&uacute;n no se ha abierto ninguno). */
	private static List<Provider> loaded = null;

	/** Bases con las que se cargaron los proveedores de {@link #loaded}. */
	private static List<File> loadedDbs = null;

	/** Base que abre cada proveedor registrado. */
	private static final Map<Provider, File> DATABASES = new IdentityHashMap<>();

	private NssMultiSlotLoader() {
		// No se permite la instanciacion
	}

	/** N&uacute;mero de ranura de la base en la posici&oacute;n indicada: 2, 4, 6&hellip; */
	private static int slot(final int index) {
		return 2 * (index + 1);
	}

	/** Construye los par&aacute;metros de NSS que abren cada base en su propia ranura
	 * ({@code 0x02}, {@code 0x04}&hellip; en el orden de la lista), en solo lectura y sin
	 * {@code configdir} de primer nivel.
	 * @param dbs Directorios de las bases NSS.
	 * @return Valor de {@code nssArgs} para la configuraci&oacute;n de SunPKCS11. */
	static String buildNssArgs(final List<File> dbs) {
		final StringBuilder sb = new StringBuilder("flags='readOnly' tokens=<"); //$NON-NLS-1$
		for (int i = 0; i < dbs.size(); i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(String.format("0x%02x", Integer.valueOf(slot(i)))) //$NON-NLS-1$
				.append("=[configdir='sql:") //$NON-NLS-1$
				.append(dbs.get(i).getAbsolutePath())
				.append("' flags=readOnly]"); //$NON-NLS-1$
		}
		return sb.append('>').toString();
	}

	/** Abre las bases NSS indicadas en una sola inicializaci&oacute;n de NSS y registra un
	 * proveedor por base con {@link Security#addProvider(Provider)}. Sus nombres
	 * ({@link Provider#getName()}) son {@code SunPKCS11-NSSCrypto-AFirma-1},
	 * {@code SunPKCS11-NSSCrypto-AFirma-2}&hellip;, consecutivos y en el orden de las bases abiertas.
	 * Si la inicializaci&oacute;n falla, se reintenta en el mismo proceso sin las bases que la
	 * hacen fallar (se prueban primero los conjuntos m&aacute;s grandes, respetando el orden) y
	 * se registran en el log las bases descartadas. Las bases cuya ruta contiene comillas,
	 * corchetes, {@code <}, {@code >}, barra invertida o saltos de l&iacute;nea se omiten con un aviso.
	 * NSS solo se inicializa una vez por proceso: en cuanto se abre alguna base, las llamadas
	 * siguientes devuelven los mismos proveedores e ignoran la lista de bases recibida.
	 * @param libDir Directorio que contiene {@code libsoftokn3.so}.
	 * @param dbs Directorios de las bases NSS, en orden de preferencia.
	 * @return Proveedores ya registrados, uno por base abierta (lista vac&iacute;a si no se
	 *         abre ninguna). */
	static synchronized List<Provider> load(final String libDir, final List<File> dbs) {
		if (loaded != null) {
			if (!loadedDbs.equals(dbs)) {
				LOGGER.warning(
					"NSS ya esta iniciado en este proceso: se reutilizan sus proveedores y se ignora la nueva lista de bases" //$NON-NLS-1$
				);
			}
			return loaded;
		}
		final List<Provider> providers = loadOnce(libDir, dbs);
		if (providers.isEmpty()) {
			return providers;
		}
		loaded = Collections.unmodifiableList(providers);
		loadedDbs = new ArrayList<>(dbs);
		return loaded;
	}

	/** Obtiene la base NSS que abre un proveedor devuelto por {@link #load(String, List)}.
	 * @param provider Proveedor.
	 * @return Directorio de la base, o {@code null} si el proveedor no es de este cargador. */
	static synchronized File database(final Provider provider) {
		return DATABASES.get(provider);
	}

	private static List<Provider> loadOnce(final String libDir, final List<File> dbs) {
		final List<File> candidates = new ArrayList<>();
		for (final File db : dbs) {
			if (isSafePath(db.getAbsolutePath())) {
				candidates.add(db);
			}
			else {
				LOGGER.warning(
					"Se omite la base NSS por contener caracteres no admitidos en su ruta: " + clean(db) //$NON-NLS-1$
				);
			}
		}
		if (candidates.isEmpty()) {
			return Collections.emptyList();
		}

		List<Provider> providers = tryLoad(libDir, candidates);
		if (providers != null) {
			return providers;
		}

		LOGGER.warning("No se ha podido iniciar NSS con todas las bases, se reintenta precargando sus dependencias"); //$NON-NLS-1$
		MozillaKeyStoreUtilities.loadNSSDependencies(libDir);

		// Hasta 2^n intentos: quien llama pasa como mucho dos bases (Firefox y Chrome)
		for (int size = candidates.size(); size > 0; size--) {
			for (final List<File> subset : subsets(candidates, size)) {
				providers = tryLoad(libDir, subset);
				if (providers != null) {
					for (final File db : candidates) {
						if (!subset.contains(db)) {
							LOGGER.warning("No se ha podido abrir la base NSS, se descarta: " + clean(db)); //$NON-NLS-1$
						}
					}
					return providers;
				}
			}
		}
		LOGGER.warning("No se ha podido abrir ninguna de las bases NSS: " + candidates.size()); //$NON-NLS-1$
		return Collections.emptyList();
	}

	/** Intenta inicializar NSS con las bases indicadas y crear un proveedor por ranura.
	 * @return Proveedores registrados, o {@code null} si la inicializaci&oacute;n de NSS falla. */
	private static List<Provider> tryLoad(final String libDir, final List<File> dbs) {
		final Provider sunPkcs11 = Security.getProvider("SunPKCS11"); //$NON-NLS-1$
		if (sunPkcs11 == null) {
			LOGGER.warning("No se encuentra el proveedor SunPKCS11"); //$NON-NLS-1$
			return null;
		}
		final Method configure;
		try {
			configure = Provider.class.getMethod("configure", String.class); //$NON-NLS-1$
		}
		catch (final NoSuchMethodException e) {
			LOGGER.warning("La version de Java no permite configurar SunPKCS11 en tiempo de ejecucion: " + e); //$NON-NLS-1$
			return null;
		}

		final String nssArgs = buildNssArgs(dbs);
		final List<Provider> providers = new ArrayList<>();
		for (int i = 0; i < dbs.size(); i++) {
			final String config = "--name=" + PROVIDER_NAME_PREFIX + (providers.size() + 1) + "\n" //$NON-NLS-1$ //$NON-NLS-2$
				+ "library=" + libDir + File.separator + SOFTOKN3_SO + "\n" //$NON-NLS-1$ //$NON-NLS-2$
				+ "attributes=compatibility\n" //$NON-NLS-1$
				+ "slot=" + slot(i) + "\n" //$NON-NLS-1$ //$NON-NLS-2$
				+ "nssArgs=\"" + nssArgs + "\"\n"; //$NON-NLS-1$ //$NON-NLS-2$
			final Provider p;
			try {
				p = (Provider) configure.invoke(sunPkcs11, config);
			}
			catch (final Exception | Error e) {
				if (i == 0) {
					// La primera ranura falla si falla la inicializacion de NSS
					LOGGER.info("No se ha podido iniciar NSS con " + dbs.size() + " bases: " + rootCause(e)); //$NON-NLS-1$ //$NON-NLS-2$
					return null;
				}
				LOGGER.warning("No se ha podido abrir la base NSS " + clean(dbs.get(i)) + ": " + rootCause(e)); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			Security.addProvider(p);
			providers.add(p);
			DATABASES.put(p, dbs.get(i));
			LOGGER.info("Anadido proveedor PKCS#11 de NSS " + p.getName() + " para " + clean(dbs.get(i))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return providers;
	}

	/** Subconjuntos de {@code size} elementos de la lista, respetando el orden. */
	private static List<List<File>> subsets(final List<File> list, final int size) {
		final List<List<File>> ret = new ArrayList<>();
		if (size == 0) {
			ret.add(new ArrayList<File>());
			return ret;
		}
		for (int i = 0; i <= list.size() - size; i++) {
			for (final List<File> tail : subsets(list.subList(i + 1, list.size()), size - 1)) {
				tail.add(0, list.get(i));
				ret.add(tail);
			}
		}
		return ret;
	}

	private static boolean isSafePath(final String path) {
		for (final char c : UNSAFE_PATH_CHARS.toCharArray()) {
			if (path.indexOf(c) >= 0) {
				return false;
			}
		}
		return true;
	}

	private static String clean(final File db) {
		return LoggerUtil.getCleanUserHomePath(db.getAbsolutePath());
	}

	private static Throwable rootCause(final Throwable t) {
		Throwable cause = t;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}
}
