package es.gob.afirma.standalone.ui.restoreconfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;

import org.junit.Test;

import es.gob.afirma.core.misc.Platform;

/** Pruebas del panel de &laquo;Restaurar instalaci&oacute;n&raquo; con sus dos botones en Linux. */
public final class RestoreConfigPanelLinuxTest {

	private static void buttons(final Container c, final List<JButton> found) {
		for (final Component child : c.getComponents()) {
			if (child instanceof JButton) {
				found.add((JButton) child);
			}
			if (child instanceof Container) {
				buttons((Container) child, found);
			}
		}
	}

	/** En Linux el panel tiene los botones de las dos restauraciones y el de cerrar, sin el bot&oacute;n &uacute;nico. */
	@Test
	public void panelDosBotones() throws Exception {
		assumeFalse("Sin entorno grafico", GraphicsEnvironment.isHeadless()); //$NON-NLS-1$
		assumeTrue(Platform.OS.LINUX.equals(Platform.getOS()));
		final List<String> texts = new ArrayList<>();
		javax.swing.SwingUtilities.invokeAndWait(() -> {
			final JDialog dialog = new JDialog();
			final RestoreConfigPanel panel = new RestoreConfigPanel(dialog);
			final List<JButton> found = new ArrayList<>();
			buttons(panel, found);
			for (final JButton b : found) {
				// Los botones sin texto son las flechas de la barra de desplazamiento
				if (b.getText() != null && !b.getText().isEmpty()) {
					texts.add(b.getText());
					assertTrue(b.isEnabled());
				}
			}
			dialog.dispose();
		});
		assertEquals(texts.toString(), 3, texts.size());
		assertTrue(texts.toString(), texts.contains("Todo el sistema")); //$NON-NLS-1$
		assertTrue(texts.toString(), texts.contains("Solo este usuario")); //$NON-NLS-1$
		assertFalse(texts.toString(), texts.contains("Iniciar restauración") || texts.contains("Start restoration")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** El comando de &laquo;Todo el sistema&raquo; pide permisos con pkexec y lanza el modo de sistema. */
	@Test
	public void comandoDeSistema() {
		assertEquals(Arrays.asList("/usr/bin/pkexec", "/usr/bin/autofirma", "restaurar", "--sistema"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				RestoreConfigPanelLinux.systemCommand());
	}

	/** Cancelar pkexec (126 o 127) da un mensaje claro y no el gen&eacute;rico. */
	@Test
	public void pkexecCancelado() {
		final String cancelado = RestoreConfigPanelLinux.messageForExit(126);
		assertEquals(cancelado, RestoreConfigPanelLinux.messageForExit(127));
		assertTrue(cancelado, cancelado.contains("administrador")); //$NON-NLS-1$
		assertFalse(cancelado, cancelado.startsWith("!")); //$NON-NLS-1$
		final String generico = RestoreConfigPanelLinux.messageForExit(3);
		assertTrue(generico, generico.contains("3")); //$NON-NLS-1$
		assertFalse(RestoreConfigPanelLinux.messageForExit(0).equals(RestoreConfigPanelLinux.messageForExit(1)));
	}
}
