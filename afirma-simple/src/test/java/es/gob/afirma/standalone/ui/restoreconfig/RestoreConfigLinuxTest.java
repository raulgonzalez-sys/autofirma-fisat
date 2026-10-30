package es.gob.afirma.standalone.ui.restoreconfig;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/** Pruebas del recurso de configuracion del protocolo afirma que usa "Restaurar instalacion" en Linux. */
public final class RestoreConfigLinuxTest {

	private static final String PACKAGE_FILE = "../afirma-simple-installer/linux/instalador_deb/src/etc/firefox/pref/AutoFirma.js"; //$NON-NLS-1$

	private static byte[] readResource() throws Exception {
		try (InputStream is = RestoreConfigLinux.class.getResourceAsStream(RestoreConfigLinux.PROTOCOL_HANDLER_RESOURCE)) {
			assertNotNull("Falta el recurso " + RestoreConfigLinux.PROTOCOL_HANDLER_RESOURCE, is); //$NON-NLS-1$
			final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			final byte[] buf = new byte[4096];
			int n;
			while ((n = is.read(buf)) != -1) {
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	/** El recurso existe, no esta vacio y declara el protocolo externo. */
	@Test
	public void recursoAutofirmaJsPresenteYNoVacio() throws Exception {
		final byte[] data = readResource();
		assertTrue("El recurso esta vacio", data.length > 0); //$NON-NLS-1$
		assertTrue(new String(data, StandardCharsets.UTF_8)
				.contains("pref(\"network.protocol-handler.external.afirma\",true);")); //$NON-NLS-1$
	}

	/** El recurso es idempotente con el AutoFirma.js del paquete. */
	@Test
	public void recursoIgualAlDelPaquete() throws Exception {
		final File f = new File(PACKAGE_FILE);
		assertTrue("No existe " + f.getAbsolutePath(), f.isFile()); //$NON-NLS-1$
		assertArrayEquals(Files.readAllBytes(f.toPath()), readResource());
	}

	/** Una entrada nula (recurso ausente) debe fallar, no devolver un fichero vacio. */
	@Test(expected = IOException.class)
	public void cargaNulaFalla() throws Exception {
		RestoreConfigLinux.loadProtocolHandlerConfig(null);
	}

	/** Un recurso de 0 bytes debe fallar. */
	@Test(expected = IOException.class)
	public void cargaVaciaFalla() throws Exception {
		RestoreConfigLinux.loadProtocolHandlerConfig(new ByteArrayInputStream(new byte[0]));
	}

	/** Un recurso con contenido se devuelve integro. */
	@Test
	public void cargaDevuelveContenido() throws Exception {
		assertArrayEquals(new byte[] { 'x' },
				RestoreConfigLinux.loadProtocolHandlerConfig(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)))); //$NON-NLS-1$
	}

	/** El fichero de destino sustituye al del paquete (mismo nombre, con mayusculas). */
	@Test
	public void destinoEsAutoFirmaJs() {
		assertEquals("AutoFirma.js", RestoreConfigLinux.PROTOCOL_HANDLER_TARGET_FILE); //$NON-NLS-1$
	}
}
