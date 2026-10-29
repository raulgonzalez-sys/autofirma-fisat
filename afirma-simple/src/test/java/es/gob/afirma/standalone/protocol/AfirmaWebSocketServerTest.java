package es.gob.afirma.standalone.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;

import org.junit.Test;

import es.gob.afirma.core.misc.protocol.ProtocolVersion;

/** Pruebas del servidor websocket del protocolo de Autofirma. */
public final class AfirmaWebSocketServerTest {

	/** Comprueba que la version minima del protocolo es la V3 y que el lanzador la acepta. */
	@Test
	public void minProtocolVersionIsCompatibleWithLauncher() {
		assertEquals(3, AfirmaWebSocketServer.MIN_PROTOCOL_VERSION.getMajorVersion());
		assertTrue(ProtocolInvocationLauncher.isCompatibleWith(AfirmaWebSocketServer.MIN_PROTOCOL_VERSION));
		assertFalse(ProtocolInvocationLauncher.isCompatibleWith(ProtocolVersion.getInstance(ProtocolVersion.VERSION_0)));
	}

	/** Comprueba que la direccion de escucha es 127.0.0.1 y no todas las interfaces. */
	@Test
	public void bindAddressIsLoopbackIpv4() {
		final InetSocketAddress a = AfirmaWebSocketServer.getBindAddress(63117);
		assertEquals("127.0.0.1", a.getAddress().getHostAddress()); //$NON-NLS-1$
		assertTrue(a.getAddress().isLoopbackAddress());
		assertFalse(a.getAddress().isAnyLocalAddress());
		assertEquals(63117, a.getPort());
	}

	/** Comprueba que el constructor usa la direccion de loopback. */
	@Test
	public void constructorUsesLoopback() {
		final AfirmaWebSocketServer s = new AfirmaWebSocketServer(0, null);   // no abre el puerto hasta start()
		assertTrue(s.getAddress().getAddress().isLoopbackAddress());
	}
}
