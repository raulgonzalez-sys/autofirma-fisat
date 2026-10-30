/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.ui.restoreconfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;

import es.gob.afirma.standalone.configurator.common.ConfiguratorUtil;

/** Enganches p&uacute;blicos de la restauraci&oacute;n de Linux del upstream para
 * {@code restoreconfig.linux}: la misma generaci&oacute;n de la CA local, los mismos nombres
 * de fichero y el mismo {@code AutoFirma.js} que {@link RestoreConfigLinux}. */
public final class RestoreConfigLinuxHooks {

	/** Nombre del almac&eacute;n PKCS#12 con el certificado SSL del websocket. */
	public static final String KS_FILENAME = RestoreConfigLinux.KS_FILENAME;

	/** Nombre del certificado de la CA local en DER. */
	public static final String CA_FILENAME = RestoreConfigLinux.FILE_AUTOFIRMA_CERTIFICATE;

	/** Nombre del fichero de preferencias de Firefox que registra {@code afirma://}. */
	public static final String PROTOCOL_HANDLER_FILENAME = RestoreConfigLinux.PROTOCOL_HANDLER_TARGET_FILE;

	private RestoreConfigLinuxHooks() {
		// No se permite la instanciacion
	}

	/** Genera una CA local nueva y el certificado SSL de {@code 127.0.0.1} firmado por ella.
	 * @return {@code {PKCS#12 con el certificado SSL, certificado de la CA en DER}}.
	 * @throws IOException Si falla la codificaci&oacute;n.
	 * @throws GeneralSecurityException Si falla la generaci&oacute;n. */
	public static byte[][] generateSslCertificates() throws IOException, GeneralSecurityException {
		final CertUtil.CertPack pack = CertUtil.getCertPackForLocalhostSsl(ConfiguratorUtil.CERT_ALIAS, RestoreConfigLinux.KS_PASSWORD);
		return new byte[][] { pack.getPkcs12(), pack.getCaCertificate().getEncoded() };
	}

	/** Extrae la CA (&uacute;ltimo certificado de la cadena) de un almac&eacute;n SSL ya generado.
	 * @param pkcs12 Almac&eacute;n {@code autofirma.pfx}.
	 * @return Certificado de la CA en DER.
	 * @throws IOException Si no se puede leer el almac&eacute;n.
	 * @throws GeneralSecurityException Si el almac&eacute;n no tiene una cadena v&aacute;lida. */
	public static byte[] caFromPkcs12(final File pkcs12) throws IOException, GeneralSecurityException {
		final KeyStore ks = KeyStore.getInstance("PKCS12"); //$NON-NLS-1$
		try (InputStream is = Files.newInputStream(pkcs12.toPath())) {
			ks.load(is, RestoreConfigLinux.KS_PASSWORD.toCharArray());
		}
		final Certificate[] chain = ks.getCertificateChain(ks.aliases().nextElement());
		if (chain == null || chain.length == 0) {
			throw new GeneralSecurityException("El almacen " + pkcs12 + " no tiene cadena de certificados"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return chain[chain.length - 1].getEncoded();
	}

	/** Contenido del {@code AutoFirma.js} interno del JAR.
	 * @return Contenido, nunca vac&iacute;o.
	 * @throws IOException Si el recurso no existe, est&aacute; vac&iacute;o o no se puede leer. */
	public static byte[] protocolHandlerConfig() throws IOException {
		try (InputStream is = RestoreConfigLinux.class.getResourceAsStream(RestoreConfigLinux.PROTOCOL_HANDLER_RESOURCE)) {
			return RestoreConfigLinux.loadProtocolHandlerConfig(is);
		}
	}
}
