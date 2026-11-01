/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.keystores;

/** Gestor de almac&eacute;n que sabe de d&oacute;nde procede cada certificado (por ejemplo,
 * de qu&eacute; navegador), para mostrarlo en el di&aacute;logo de selecci&oacute;n. */
public interface CertificateOriginProvider {

	/** Obtiene el origen del certificado de un alias, tal como se muestra al usuario.
	 * @param alias Alias del certificado en este gestor.
	 * @return Texto del origen (por ejemplo {@code Firefox, Chrome}) o {@code null} si
	 *         no se conoce o el alias no es de este gestor. */
	String getCertificateOrigin(String alias);
}
