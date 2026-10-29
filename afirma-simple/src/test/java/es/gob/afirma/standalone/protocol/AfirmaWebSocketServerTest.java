package es.gob.afirma.standalone.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
