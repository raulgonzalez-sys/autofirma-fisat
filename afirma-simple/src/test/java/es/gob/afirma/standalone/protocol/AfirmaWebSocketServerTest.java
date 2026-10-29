package es.gob.afirma.standalone.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.junit.Test;

import es.gob.afirma.core.misc.protocol.ProtocolVersion;
import es.gob.afirma.standalone.SimpleErrorCode;

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

	private static InetSocketAddress loopback() throws UnknownHostException {
		return new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 5555);
	}

	/** Comprueba que se acepta una peticion local sin identificador de sesion esperado. */
	@Test
	public void checkRequestAcceptsLoopbackWithoutSession() throws Exception {
		assertNull(AfirmaWebSocketServer.checkRequest(loopback(), null, "echo=-")); //$NON-NLS-1$
	}

	/** Comprueba que se rechaza una peticion sin direccion remota. */
	@Test
	public void checkRequestRejectsNullRemote() {
		assertEquals(SimpleErrorCode.Communication.EXTERNAL_REQUEST, AfirmaWebSocketServer.checkRequest(null, null, "x")); //$NON-NLS-1$
	}

	/** Comprueba que se rechaza una direccion remota sin resolver. */
	@Test
	public void checkRequestRejectsUnresolvedAddress() {
		final InetSocketAddress remote = InetSocketAddress.createUnresolved("evil.example", 1); //$NON-NLS-1$
		assertEquals(SimpleErrorCode.Communication.EXTERNAL_REQUEST, AfirmaWebSocketServer.checkRequest(remote, null, "x")); //$NON-NLS-1$
	}

	/** Comprueba que se rechaza una peticion desde otro equipo de la red. */
	@Test
	public void checkRequestRejectsLanAddress() throws Exception {
		final InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 1, 10}), 5555);
		assertEquals(SimpleErrorCode.Communication.EXTERNAL_REQUEST, AfirmaWebSocketServer.checkRequest(remote, null, "x")); //$NON-NLS-1$
	}

	/** Comprueba que se rechaza un identificador de sesion distinto del esperado. */
	@Test
	public void checkRequestRejectsWrongSession() throws Exception {
		assertEquals(SimpleErrorCode.Request.INVALID_SESSION_ID,
				AfirmaWebSocketServer.checkRequest(loopback(), "abc", "afirma://sign?x=1&idsession=zzz")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Comprueba que se rechaza una peticion sin identificador de sesion cuando se espera uno. */
	@Test
	public void checkRequestRejectsMissingSession() throws Exception {
		assertEquals(SimpleErrorCode.Request.INVALID_SESSION_ID,
				AfirmaWebSocketServer.checkRequest(loopback(), "abc", "afirma://sign?x=1")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Comprueba que se acepta una peticion local con el identificador de sesion esperado. */
	@Test
	public void checkRequestAcceptsMatchingSession() throws Exception {
		assertNull(AfirmaWebSocketServer.checkRequest(loopback(), "abc", "afirma://sign?x=1&idsession=abc&y=2")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Comprueba la extraccion del identificador de sesion de una peticion de eco. */
	@Test
	public void getSessionIdParsesEcho() {
		assertEquals("abc", AfirmaWebSocketServer.getSessionId("echo=-idsession=abc@EOF")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Comprueba la extraccion del identificador de sesion seguido de otro parametro y su ausencia. */
	@Test
	public void getSessionIdParsesAmpersand() {
		assertEquals("abc", AfirmaWebSocketServer.getSessionId("a=1&idsession=abc&b=2")); //$NON-NLS-1$ //$NON-NLS-2$
		assertNull(AfirmaWebSocketServer.getSessionId("a=1")); //$NON-NLS-1$
		assertNull(AfirmaWebSocketServer.getSessionId(null));
	}

	/** Comprueba la extraccion del identificador de sesion al final del mensaje. */
	@Test
	public void getSessionIdParsesTail() {
		assertEquals("abc", AfirmaWebSocketServer.getSessionId("a=1&idsession=abc")); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
