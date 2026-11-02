/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.keystores;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** A&ntilde;ade al nombre que se muestra de cada certificado el origen que indique su gestor
 * ({@link CertificateOriginProvider}). Solo cambia el texto mostrado, nunca el alias. */
final class CertificateOrigins {

	private static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

	private CertificateOrigins() {
		// No se permite la instanciacion
	}

	/** Obtiene los nombres que se muestran de los certificados con su origen entre
	 * par&eacute;ntesis, por ejemplo {@code Nombre (Firefox, Chrome)}. Los alias de cada
	 * gestor se piden una sola vez.
	 * @param ksm Gestor por el que se accede a los certificados.
	 * @param names Nombre que se muestra de cada alias.
	 * @return Nombre de cada alias con su origen, o sin cambios si ning&uacute;n gestor lo indica. */
	static Map<String, String> withOrigins(final AOKeyStoreManager ksm, final Map<String, String> names) {
		final Map<String, String> origins = new HashMap<>();
		collectOrigins(ksm, new HashSet<>(names.keySet()), origins);
		final Map<String, String> ret = new LinkedHashMap<>();
		for (final Map.Entry<String, String> e : names.entrySet()) {
			final String origin = origins.get(e.getKey());
			ret.put(e.getKey(), origin != null ? e.getValue() + " (" + origin + ")" : e.getValue()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return ret;
	}

	/** Busca el origen de cada alias en el gestor o, si es un agregado, en el primero de sus
	 * gestores que contiene el alias, que es el que resuelve el agregado. */
	private static void collectOrigins(final AOKeyStoreManager ksm,
			                           final Set<String> aliases,
			                           final Map<String, String> origins) {
		if (aliases.isEmpty()) {
			return;
		}
		if (ksm instanceof CertificateOriginProvider) {
			for (final String alias : aliases) {
				final String origin = ((CertificateOriginProvider) ksm).getCertificateOrigin(alias);
				if (origin != null) {
					origins.put(alias, origin);
				}
			}
			return;
		}
		if (ksm instanceof AggregatedKeyStoreManager) {
			final Set<String> pending = new HashSet<>(aliases);
			for (final AOKeyStoreManager child : ((AggregatedKeyStoreManager) ksm).getKeyStoreManagers()) {
				final Set<String> own = new HashSet<>(pending);
				try {
					own.retainAll(Arrays.asList(child.getAliases()));
				}
				catch (final Exception e) {
					LOGGER.warning("No se pudieron obtener los alias de un almacen: " + e); //$NON-NLS-1$
					continue;
				}
				pending.removeAll(own);
				collectOrigins(child, own, origins);
			}
		}
	}
}
