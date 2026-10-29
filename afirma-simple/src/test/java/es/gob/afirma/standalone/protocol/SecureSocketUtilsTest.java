package es.gob.afirma.standalone.protocol;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.junit.Test;

/** Pruebas de las utilidades del socket seguro. */
public final class SecureSocketUtilsTest {

	/** Comprueba que el contexto TLS del socket admite TLS 1.2 y 1.3. */
	@Test
	public void sslContextAllowsTls12And13() throws Exception {
		final SSLContext c = SSLContext.getInstance(SecureSocketUtils.SSLCONTEXT);
		c.init(null, null, null);
		final List<String> p = Arrays.asList(c.getDefaultSSLParameters().getProtocols());
		assertTrue(p.toString(), p.contains("TLSv1.2")); //$NON-NLS-1$
		assertTrue(p.toString(), p.contains("TLSv1.3")); //$NON-NLS-1$
	}
}
