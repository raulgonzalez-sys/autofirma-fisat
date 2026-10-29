package es.gob.afirma.ui.core.jse;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import es.gob.afirma.core.AOCancelledOperationException;
import es.gob.afirma.core.misc.Platform;
import es.gob.afirma.core.ui.GenericFileFilter;
import es.gob.afirma.ui.core.jse.NativeFileDialogs.Tool;

/** Pruebas de integracion de los dialogos nativos en {@link JSEUIManager} con un kdialog falso.
 * Se ejecutan en modo headless para que nunca se abra un JFileChooser en el escritorio. */
public final class JSEUIManagerNativeDialogsTest {

	private static final GenericFileFilter CADES = new GenericFileFilter(new String[] { "csig", "p7s" }, "Firmas CAdES"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	private static final GenericFileFilter XADES = new GenericFileFilter(new String[] { "xsig" }, "Firmas XAdES"); //$NON-NLS-1$ //$NON-NLS-2$

	/** Directorio temporal para el kdialog falso y los ficheros de salida. */
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private String savedDirectory;
	private File out;

	/** Fuerza el modo headless antes de tocar AWT. */
	@BeforeClass
	public static void headless() {
		System.setProperty("java.awt.headless", "true"); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(GraphicsEnvironment.isHeadless());
	}

	/** Guarda la carpeta de las preferencias y crea la carpeta de salida.
	 * @throws IOException En cualquier error. */
	@Before
	public void setUp() throws IOException {
		this.savedDirectory = JSEUIManager.get(JSEUIManager.PREFERENCE_DIRECTORY, null);
		this.out = this.tmp.newFolder("salida"); //$NON-NLS-1$
	}

	/** Restaura la carpeta de las preferencias. */
	@After
	public void tearDown() {
		if (this.savedDirectory == null) {
			Preferences.userNodeForPackage(JSEUIManager.class).remove(JSEUIManager.PREFERENCE_DIRECTORY);
		}
		else {
			JSEUIManager.put(JSEUIManager.PREFERENCE_DIRECTORY, this.savedDirectory);
		}
	}

	private static Map<String, String> env(final String... claveValor) {
		final Map<String, String> m = new HashMap<>();
		for (int i = 0; i < claveValor.length; i += 2) {
			m.put(claveValor[i], claveValor[i + 1]);
		}
		return m;
	}

	/** Crea un ejecutable falso con el cuerpo indicado.
	 * @param dir Directorio donde se crea.
	 * @param name Nombre del ejecutable.
	 * @param body Cuerpo del script.
	 * @throws Exception En cualquier error. */
	private void fake(final File dir, final String name, final String body) throws Exception {
		Assume.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux")); //$NON-NLS-1$ //$NON-NLS-2$
		final File script = new File(dir, name);
		Files.write(script.toPath(), ("#!/bin/sh\n" + body + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(script.setExecutable(true));
		// Por si el directorio temporal esta montado noexec
		Assume.assumeTrue(script.canExecute());
		final File probe = this.tmp.newFile("sonda-" + System.nanoTime()); //$NON-NLS-1$
		Files.write(probe.toPath(), "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		assertTrue(probe.setExecutable(true));
		try {
			Assume.assumeTrue(new ProcessBuilder(probe.getAbsolutePath()).start().waitFor() == 0);
		}
		catch (final IOException e) {
			Assume.assumeNoException(e);
		}
	}

	/** Crea un JSEUIManager que usa un kdialog falso con el cuerpo indicado.
	 * @param kdialogBody Cuerpo del kdialog falso.
	 * @return Gestor de interfaz.
	 * @throws Exception En cualquier error. */
	private JSEUIManager ui(final String kdialogBody) throws Exception {
		final File bin = this.tmp.newFolder("bin"); //$NON-NLS-1$
		fake(bin, "kdialog", kdialogBody); //$NON-NLS-1$
		final Map<String, String> e = env("PATH", bin.getPath(), "XDG_CURRENT_DESKTOP", "KDE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertEquals(Tool.KDIALOG, NativeFileDialogs.chooseTool(Platform.OS.LINUX, e, null));
		final JSEUIManager ui = new JSEUIManager();
		ui.nativeDialogs = new NativeFileDialogs(Platform.OS.LINUX, e, null);
		return ui;
	}

	private String printPath(final String name) {
		return "printf '%s\\n' '" + new File(this.out, name).getPath() + "'"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Al guardar sin extension se anade la del filtro por defecto y se escriben los datos.
	 * @throws Exception En cualquier error. */
	@Test
	public void testGuardarAnadeExtensionYEscribe() throws Exception {
		final JSEUIManager ui = ui(printPath("salida firma")); //$NON-NLS-1$
		final File r = ui.saveDataToFile("hola".getBytes(StandardCharsets.UTF_8), "Guardar", this.out.getPath(), //$NON-NLS-1$ //$NON-NLS-2$
				"propuesta.csig", asList(CADES, XADES), CADES, null); //$NON-NLS-1$
		assertEquals(new File(this.out, "salida firma.csig").getCanonicalFile(), r); //$NON-NLS-1$
		assertEquals("hola", new String(Files.readAllBytes(r.toPath()), StandardCharsets.UTF_8)); //$NON-NLS-1$
		assertEquals(this.out.getCanonicalPath(), JSEUIManager.get(JSEUIManager.PREFERENCE_DIRECTORY, null));
	}

	/** Sin datos solo se devuelve la ruta elegida.
	 * @throws Exception En cualquier error. */
	@Test
	public void testGuardarSinDatosSoloDevuelveRuta() throws Exception {
		final JSEUIManager ui = ui(printPath("salida firma")); //$NON-NLS-1$
		final File r = ui.saveDataToFile(null, "Guardar", this.out.getPath(), //$NON-NLS-1$
				"propuesta.csig", asList(CADES, XADES), CADES, null); //$NON-NLS-1$
		assertEquals(new File(this.out, "salida firma.csig").getCanonicalFile(), r); //$NON-NLS-1$
		assertFalse(r.exists());
	}

	/** kdialog recibe titulo, modo, nombre propuesto y filtros.
	 * @throws Exception En cualquier error. */
	@Test
	public void testArgumentosQueRecibeKdialog() throws Exception {
		final File args = new File(this.tmp.getRoot(), "args.txt"); //$NON-NLS-1$
		final JSEUIManager ui = ui("printf '%s\\n' \"$@\" > '" + args.getPath() + "'\n" + printPath("x.csig")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		ui.saveDataToFile(null, "Guardar", this.out.getPath(), "propuesta.csig", asList(CADES, XADES), CADES, null); //$NON-NLS-1$ //$NON-NLS-2$
		final List<String> lines = Files.readAllLines(args.toPath(), StandardCharsets.UTF_8);
		assertEquals(asList("--title", "Guardar", "--getsavefilename", this.out.getPath() + "/propuesta.csig"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				lines.subList(0, 4));
		assertTrue(lines.get(4), lines.get(4).startsWith("Firmas CAdES (*.csig *.CSIG *.p7s *.P7S)|Firmas XAdES (*.xsig *.XSIG)|")); //$NON-NLS-1$
	}

	/** Cancelar en el dialogo nativo de guardado cancela la operacion.
	 * @throws Exception En cualquier error. */
	@Test(expected = AOCancelledOperationException.class)
	public void testGuardarCanceladoEnNativo() throws Exception {
		ui("exit 1").saveDataToFile(null, "Guardar", this.out.getPath(), "propuesta.csig", asList(CADES, XADES), CADES, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** Cancelar en el dialogo nativo de apertura cancela la operacion.
	 * @throws Exception En cualquier error. */
	@Test(expected = AOCancelledOperationException.class)
	public void testCargarCanceladoEnNativo() throws Exception {
		ui("exit 1").getLoadFiles("Abrir", this.out.getPath(), null, new String[] { "pdf" }, "PDF", false, false, null, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** Se cargan varios ficheros en el orden devuelto y se recuerda su carpeta.
	 * @throws Exception En cualquier error. */
	@Test
	public void testCargarVarios() throws Exception {
		final JSEUIManager ui = ui(printPath("a b.pdf") + "\n" + printPath("\u00f1.pdf")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final File[] r = ui.getLoadFiles("Abrir", this.out.getPath(), null, null, null, false, true, null, null); //$NON-NLS-1$
		assertArrayEquals(new File[] { new File(this.out, "a b.pdf"), new File(this.out, "\u00f1.pdf") }, r); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(this.out.getPath(), JSEUIManager.get(JSEUIManager.PREFERENCE_DIRECTORY, null));
	}

	/** Se selecciona una carpeta.
	 * @throws Exception En cualquier error. */
	@Test
	public void testCargarCarpeta() throws Exception {
		final JSEUIManager ui = ui("printf '%s\\n' '" + this.out.getPath() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
		final File[] r = ui.getLoadFiles("Abrir", this.out.getPath(), null, null, null, true, true, null, null); //$NON-NLS-1$
		assertArrayEquals(new File[] { this.out }, r);
	}

	/** Si el dialogo nativo falla se usa el JFileChooser (que en headless lanza HeadlessException).
	 * @throws Exception En cualquier error. */
	@Test(expected = HeadlessException.class)
	public void testFalloNativoCaeAJFileChooser() throws Exception {
		Assume.assumeTrue(GraphicsEnvironment.isHeadless());
		ui("exit 134").getLoadFiles("Abrir", this.out.getPath(), null, null, null, false, false, null, null); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
