package es.gob.afirma.standalone.ui.restoreconfig;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

	/** La deteccion de procesos mira el nombre del proceso, no su ruta. */
	@Test
	public void detectaProcesosPorNombre() throws Exception {
		final File proc = java.nio.file.Files.createTempDirectory("proc").toFile(); //$NON-NLS-1$
		try {
			for (final String[] p : new String[][] { { "1", "systemd" }, { "42", "chrome" }, { "abc", "firefox" } }) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				final File dir = new File(proc, p[0]);
				assertTrue(dir.mkdir());
				Files.write(new File(dir, "comm").toPath(), (p[1] + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
			}
			assertTrue(RestoreConfigLinux.isProcessRunningLinux(proc, "chrome")); //$NON-NLS-1$
			assertTrue(RestoreConfigLinux.isProcessRunningLinux(proc, "firefox-esr", "chrome")); //$NON-NLS-1$ //$NON-NLS-2$
			// "abc" no es un PID: se ignora
			assertFalse(RestoreConfigLinux.isProcessRunningLinux(proc, "firefox")); //$NON-NLS-1$
			assertFalse(RestoreConfigLinux.isProcessRunningLinux(proc, "chromium")); //$NON-NLS-1$
			assertFalse(RestoreConfigLinux.isProcessRunningLinux(new File(proc, "no-existe"), "chrome")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		finally {
			for (final File d : proc.listFiles()) {
				for (final File f : d.listFiles()) {
					f.delete();
				}
				d.delete();
			}
			proc.delete();
		}
	}
}
