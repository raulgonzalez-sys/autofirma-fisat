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
import java.util.logging.Logger;

/** A&ntilde;ade al nombre que se muestra de un certificado el origen que indique su gestor
 * ({@link CertificateOriginProvider}). Solo cambia el texto mostrado, nunca el alias. */
final class CertificateOrigins {

	private static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

	private CertificateOrigins() {
		// No se permite la instanciacion
	}

	/** Obtiene el nombre que se muestra de un certificado con su origen entre
	 * par&eacute;ntesis, por ejemplo {@code Nombre (Firefox, Chrome)}.
	 * @param ksm Gestor por el que se accede al certificado.
	 * @param alias Alias del certificado en ese gestor.
	 * @param name Nombre que se muestra del certificado.
	 * @return Nombre con el origen, o el nombre sin cambios si ning&uacute;n gestor lo indica. */
	static String displayName(final AOKeyStoreManager ksm, final String alias, final String name) {
		final String origin = origin(ksm, alias);
		return origin != null ? name + " (" + origin + ")" : name; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Busca el origen en el gestor o, si es un agregado, en el primero de sus gestores que
	 * contiene el alias, que es el que resuelve el agregado. */
	private static String origin(final AOKeyStoreManager ksm, final String alias) {
		if (ksm instanceof CertificateOriginProvider) {
			return ((CertificateOriginProvider) ksm).getCertificateOrigin(alias);
		}
		if (ksm instanceof AggregatedKeyStoreManager) {
			for (final AOKeyStoreManager child : ((AggregatedKeyStoreManager) ksm).getKeyStoreManagers()) {
				try {
					if (Arrays.asList(child.getAliases()).contains(alias)) {
						return origin(child, alias);
					}
				}
				catch (final Exception e) {
					LOGGER.warning("No se pudieron obtener los alias de un almacen: " + e); //$NON-NLS-1$
				}
			}
		}
		return null;
	}
}
