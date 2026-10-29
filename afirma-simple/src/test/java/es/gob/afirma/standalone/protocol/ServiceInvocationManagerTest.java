package es.gob.afirma.standalone.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import org.junit.Test;

/** Pruebas del servicio de invocacion por socket del protocolo de Autofirma. */
public final class ServiceInvocationManagerTest {

	/** Comprueba que el socket del protocolo heredado escucha solo en 127.0.0.1. */
	@Test
	public void tryPortsBindsLoopback() throws Exception {
		final SSLServerSocketFactory f = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
		try (final SSLServerSocket s = ServiceInvocationManager.tryPorts(new int[] { 0 }, f)) {
			assertTrue(s.getInetAddress().isLoopbackAddress());
			assertEquals("127.0.0.1", s.getInetAddress().getHostAddress()); //$NON-NLS-1$
		}
	}
}
