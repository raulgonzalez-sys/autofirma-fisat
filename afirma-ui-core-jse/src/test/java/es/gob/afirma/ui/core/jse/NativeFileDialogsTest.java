package es.gob.afirma.ui.core.jse;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import es.gob.afirma.core.misc.Platform;
import es.gob.afirma.core.ui.GenericFileFilter;
import es.gob.afirma.ui.core.jse.NativeFileDialogs.Mode;
import es.gob.afirma.ui.core.jse.NativeFileDialogs.Result;
import es.gob.afirma.ui.core.jse.NativeFileDialogs.Status;
import es.gob.afirma.ui.core.jse.NativeFileDialogs.Tool;

/** Pruebas de los dialogos de ficheros nativos (kdialog y zenity) sin abrir ventanas.
 * Las pruebas de ejecucion usan scripts de shell falsos, por lo que solo se ejecutan en Linux. */
public final class NativeFileDialogsTest {

	private static final GenericFileFilter CADES = new GenericFileFilter(new String[] { "csig", "p7s" }, "Firmas CAdES"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	private static final GenericFileFilter XADES = new GenericFileFilter(new String[] { "xsig" }, "Firmas XAdES"); //$NON-NLS-1$ //$NON-NLS-2$
	private static final String TODOS = "Todos los archivos"; //$NON-NLS-1$
	private static final String KD = "/usr/bin/kdialog"; //$NON-NLS-1$
	private static final String ZE = "/usr/bin/zenity"; //$NON-NLS-1$

	/** Fuerza el modo headless antes de tocar AWT para no abrir ventanas en el escritorio. */
	@BeforeClass
	public static void headless() {
		System.setProperty("java.awt.headless", "true"); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(GraphicsEnvironment.isHeadless());
	}

	/** Directorio temporal para los ejecutables falsos. */
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** Crea un ejecutable falso con el cuerpo indicado.
	 * @param dir Directorio donde se crea.
	 * @param name Nombre del ejecutable.
	 * @param body Cuerpo del script.
	 * @return Ruta del script.
	 * @throws Exception En cualquier error. */
	private File fake(final File dir, final String name, final String body) throws Exception {
		Assume.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux")); //$NON-NLS-1$ //$NON-NLS-2$
		final File script = new File(dir, name);
		Files.write(script.toPath(), ("#!/bin/sh\n" + body + "\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(script.setExecutable(true));
		// Por si el directorio temporal esta montado noexec
		Assume.assumeTrue(script.canExecute());
		assumeExecutable();
		return script;
	}

	/** Comprueba que el directorio temporal permite ejecutar scripts (no esta montado noexec).
	 * @throws Exception En cualquier error. */
	private void assumeExecutable() throws Exception {
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

	/** Crea un directorio con los ejecutables falsos indicados (cuerpo "exit 0").
	 * @param names Nombres de los ejecutables.
	 * @return Directorio creado.
	 * @throws Exception En cualquier error. */
	private File bin(final String... names) throws Exception {
		final File dir = this.tmp.newFolder("bin-" + System.nanoTime()); //$NON-NLS-1$
		for (final String n : names) {
			fake(dir, n, "exit 0"); //$NON-NLS-1$
		}
		return dir;
	}

	private static Map<String, String> env(final String... claveValor) {
		final Map<String, String> m = new HashMap<>();
		for (int i = 0; i < claveValor.length; i += 2) {
			m.put(claveValor[i], claveValor[i + 1]);
		}
		return m;
	}

	private static List<String> descripciones(final List<GenericFileFilter> filters) {
		final List<String> d = new ArrayList<>();
		for (final GenericFileFilter f : filters) {
			d.add(f.getDescription());
		}
		return d;
	}

	private static String last(final List<String> l) {
		return l.get(l.size() - 1);
	}

	// chooseTool

	/** En KDE con ambas herramientas se elige kdialog.
	 * @throws Exception En cualquier error. */
	@Test
	public void testKdeConAmbasEligeKdialog() throws Exception {
		final File b = bin("kdialog", "zenity"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(Tool.KDIALOG, NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("PATH", b + ":/no-existe", "XDG_CURRENT_DESKTOP", "KDE"), null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** Un XDG_CURRENT_DESKTOP compuesto con kde tambien es KDE.
	 * @throws Exception En cualquier error. */
	@Test
	public void testKdeCompuestoEligeKdialog() throws Exception {
		final File b = bin("kdialog", "zenity"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(Tool.KDIALOG, NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("PATH", b.getPath(), "XDG_CURRENT_DESKTOP", "ubuntu:kde"), null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** En KDE sin kdialog se elige zenity.
	 * @throws Exception En cualquier error. */
	@Test
	public void testKdeSinKdialogEligeZenity() throws Exception {
		final File b = bin("zenity"); //$NON-NLS-1$
		assertEquals(Tool.ZENITY, NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("PATH", b.getPath(), "XDG_CURRENT_DESKTOP", "KDE"), null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** Fuera de KDE se elige zenity aunque haya kdialog.
	 * @throws Exception En cualquier error. */
	@Test
	public void testNoKdeEligeZenityAunqueHayaKdialog() throws Exception {
		final File b = bin("kdialog", "zenity"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(Tool.ZENITY, NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("PATH", b.getPath(), "XDG_CURRENT_DESKTOP", "GNOME"), null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** Fuera de KDE y solo con kdialog no hay herramienta.
	 * @throws Exception En cualquier error. */
	@Test
	public void testNoKdeSoloKdialogDevuelveNull() throws Exception {
		final File b = bin("kdialog"); //$NON-NLS-1$
		assertNull(NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("PATH", b.getPath(), "XDG_CURRENT_DESKTOP", "GNOME"), null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** Sin ejecutables o sin PATH no hay herramienta.
	 * @throws Exception En cualquier error. */
	@Test
	public void testSinEjecutablesDevuelveNull() throws Exception {
		final File vacio = this.tmp.newFolder("vacio"); //$NON-NLS-1$
		assertNull(NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("PATH", vacio.getPath(), "XDG_CURRENT_DESKTOP", "KDE"), null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertNull(NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("XDG_CURRENT_DESKTOP", "KDE"), null)); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Un kdialog sin permiso de ejecucion se ignora.
	 * @throws Exception En cualquier error. */
	@Test
	public void testKdialogNoEjecutableSeIgnora() throws Exception {
		final File b = bin("kdialog", "zenity"); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(new File(b, "kdialog").setExecutable(false)); //$NON-NLS-1$
		assertEquals(Tool.ZENITY, NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("PATH", b.getPath(), "XDG_CURRENT_DESKTOP", "KDE"), null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** La variable de entorno con valor java fuerza JFileChooser.
	 * @throws Exception En cualquier error. */
	@Test
	public void testVariableJavaFuerzaJFileChooser() throws Exception {
		final File b = bin("kdialog", "zenity"); //$NON-NLS-1$ //$NON-NLS-2$
		assertNull(NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("PATH", b.getPath(), "XDG_CURRENT_DESKTOP", "KDE", NativeFileDialogs.ENV_SWITCH, " Java "), null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** La propiedad del sistema con valor java fuerza JFileChooser.
	 * @throws Exception En cualquier error. */
	@Test
	public void testPropiedadJavaFuerzaJFileChooser() throws Exception {
		final File b = bin("kdialog", "zenity"); //$NON-NLS-1$ //$NON-NLS-2$
		assertNull(NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("PATH", b.getPath(), "XDG_CURRENT_DESKTOP", "KDE"), "JAVA")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** El valor sistema no fuerza JFileChooser.
	 * @throws Exception En cualquier error. */
	@Test
	public void testValorSistemaNoFuerza() throws Exception {
		final File b = bin("kdialog", "zenity"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(Tool.KDIALOG, NativeFileDialogs.chooseTool(Platform.OS.LINUX,
				env("PATH", b.getPath(), "XDG_CURRENT_DESKTOP", "KDE", NativeFileDialogs.ENV_SWITCH, "sistema"), "sistema")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
	}

	/** Fuera de Linux no hay herramienta nativa.
	 * @throws Exception En cualquier error. */
	@Test
	public void testFueraDeLinuxDevuelveNull() throws Exception {
		final File b = bin("kdialog", "zenity"); //$NON-NLS-1$ //$NON-NLS-2$
		final Map<String, String> e = env("PATH", b.getPath(), "XDG_CURRENT_DESKTOP", "KDE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertNull(NativeFileDialogs.chooseTool(Platform.OS.WINDOWS, e, null));
		assertNull(NativeFileDialogs.chooseTool(Platform.OS.MACOSX, e, null));
	}

	// orderFilters

	/** El filtro por defecto va primero y el de todos los archivos al final. */
	@Test
	public void testOrdenPorDefectoPrimeroYTodosAlFinal() {
		final List<GenericFileFilter> o = NativeFileDialogs.orderFilters(asList(CADES, XADES), XADES, TODOS);
		assertEquals(asList("Firmas XAdES", "Firmas CAdES", TODOS), descripciones(o)); //$NON-NLS-1$ //$NON-NLS-2$
		assertNull(o.get(o.size() - 1).getExtensions());
	}

	/** Sin filtro por defecto, el de todos los archivos va primero. */
	@Test
	public void testOrdenSinPorDefectoTodosPrimero() {
		assertEquals(asList(TODOS, "Firmas CAdES", "Firmas XAdES"), //$NON-NLS-1$ //$NON-NLS-2$
				descripciones(NativeFileDialogs.orderFilters(asList(CADES, XADES), null, TODOS)));
	}

	/** Los filtros sin extensiones se descartan. */
	@Test
	public void testOrdenDescartaSinExtensiones() {
		assertTrue(NativeFileDialogs.orderFilters(
				asList(new GenericFileFilter(null, "x"), new GenericFileFilter(new String[0], "y")), null, TODOS).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(asList("Firmas CAdES", TODOS), descripciones(NativeFileDialogs.orderFilters( //$NON-NLS-1$
				asList(new GenericFileFilter(null, "x"), CADES), CADES, TODOS))); //$NON-NLS-1$
	}

	/** Sin filtros, lista vacia. */
	@Test
	public void testOrdenNullDevuelveVacia() {
		assertTrue(NativeFileDialogs.orderFilters(null, null, TODOS).isEmpty());
	}

	// buildCommand kdialog

	/** kdialog: abrir un fichero con filtro. */
	@Test
	public void testKdialogAbrirUnoConFiltro() {
		assertEquals(asList(KD, "--title", "Abrir firma", "--getopenfilename", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				"/home/ana/Mis firmas/contrato \u00e1.csig", //$NON-NLS-1$
				"Firmas CAdES (*.csig *.CSIG *.p7s *.P7S)|Todos los archivos (*)"), //$NON-NLS-1$
				NativeFileDialogs.buildCommand(Tool.KDIALOG, KD, Mode.OPEN, false, "Abrir firma", //$NON-NLS-1$
						new File("/home/ana/Mis firmas/contrato \u00e1.csig"), //$NON-NLS-1$
						NativeFileDialogs.orderFilters(singletonList(CADES), CADES, TODOS)));
	}

	/** kdialog: abrir varios ficheros sin filtros. */
	@Test
	public void testKdialogAbrirVarios() {
		assertEquals(asList(KD, "--title", "Abrir", "--multiple", "--separate-output", "--getopenfilename", "/home/ana"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				NativeFileDialogs.buildCommand(Tool.KDIALOG, KD, Mode.OPEN, true, "Abrir", new File("/home/ana"), //$NON-NLS-1$ //$NON-NLS-2$
						Collections.<GenericFileFilter>emptyList()));
	}

	/** kdialog: guardar (la multiseleccion no cuenta). */
	@Test
	public void testKdialogGuardar() {
		assertEquals(asList(KD, "--title", "Guardar como", "--getsavefilename", "/home/ana/firma.xsig", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				"Firmas XAdES (*.xsig *.XSIG)|Firmas CAdES (*.csig *.CSIG *.p7s *.P7S)|Todos los archivos (*)"), //$NON-NLS-1$
				NativeFileDialogs.buildCommand(Tool.KDIALOG, KD, Mode.SAVE, true, "Guardar como", new File("/home/ana/firma.xsig"), //$NON-NLS-1$ //$NON-NLS-2$
						NativeFileDialogs.orderFilters(asList(CADES, XADES), XADES, TODOS)));
	}

	/** kdialog: seleccionar carpeta ignora los filtros. */
	@Test
	public void testKdialogCarpeta() {
		assertEquals(asList(KD, "--title", "Abrir", "--getexistingdirectory", "/home/ana"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				NativeFileDialogs.buildCommand(Tool.KDIALOG, KD, Mode.DIRECTORY, false, "Abrir", new File("/home/ana"), //$NON-NLS-1$ //$NON-NLS-2$
						NativeFileDialogs.orderFilters(singletonList(CADES), CADES, TODOS)));
	}

	/** kdialog: la descripcion se sanea y las extensiones se normalizan. */
	@Test
	public void testKdialogDescripcionSaneada() {
		final GenericFileFilter f = new GenericFileFilter(new String[] { ".TXT", "*.log" }, "Texto|log \\ raro"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		final List<String> c = NativeFileDialogs.buildCommand(Tool.KDIALOG, KD, Mode.OPEN, false, "Abrir", new File("/home/ana"), //$NON-NLS-1$ //$NON-NLS-2$
				NativeFileDialogs.orderFilters(singletonList(f), f, TODOS));
		assertTrue(last(c), last(c).startsWith("Texto/log \\\\ raro (*.txt *.TXT *.log *.LOG)|")); //$NON-NLS-1$
	}

	/** kdialog: ni ';;' ni saltos de linea en la descripcion, que Qt tomaria como separadores de filtros. */
	@Test
	public void testKdialogDescripcionSinSeparadoresDeQt() {
		final GenericFileFilter f = new GenericFileFilter(new String[] { "txt" }, "Texto;;;log\nraro\r"); //$NON-NLS-1$ //$NON-NLS-2$
		final List<String> c = NativeFileDialogs.buildCommand(Tool.KDIALOG, KD, Mode.OPEN, false, "Abrir", new File("/home/ana"), //$NON-NLS-1$ //$NON-NLS-2$
				NativeFileDialogs.orderFilters(singletonList(f), f, TODOS));
		assertEquals("Texto;log raro (*.txt *.TXT)|Todos los archivos (*)", last(c)); //$NON-NLS-1$
	}

	/** kdialog: sin descripcion se usan los patrones. */
	@Test
	public void testKdialogDescripcionNula() {
		final GenericFileFilter f = new GenericFileFilter(new String[] { "pdf" }, null); //$NON-NLS-1$
		final List<String> c = NativeFileDialogs.buildCommand(Tool.KDIALOG, KD, Mode.OPEN, false, "Abrir", new File("/home/ana"), //$NON-NLS-1$ //$NON-NLS-2$
				NativeFileDialogs.orderFilters(singletonList(f), f, TODOS));
		assertTrue(last(c), last(c).startsWith("*.pdf (*.pdf *.PDF)|")); //$NON-NLS-1$
	}

	// buildCommand zenity

	/** zenity: abrir varios con filtro en una carpeta existente. */
	@Test
	public void testZenityAbrirVariosConFiltro() {
		final File raiz = this.tmp.getRoot();
		assertEquals(asList(ZE, "--file-selection", "--title=Abrir", "--multiple", "--separator=\n", "--filename=" + raiz.getPath() + "/", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				"--file-filter=Firmas CAdES | *.csig *.CSIG *.p7s *.P7S", "--file-filter=Todos los archivos | *"), //$NON-NLS-1$ //$NON-NLS-2$
				NativeFileDialogs.buildCommand(Tool.ZENITY, ZE, Mode.OPEN, true, "Abrir", raiz, //$NON-NLS-1$
						NativeFileDialogs.orderFilters(singletonList(CADES), CADES, TODOS)));
	}

	/** zenity: guardar. */
	@Test
	public void testZenityGuardar() {
		assertEquals(asList(ZE, "--file-selection", "--title=Guardar como", "--save", "--filename=/home/ana/firma.xsig", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				"--file-filter=Firmas XAdES | *.xsig *.XSIG", "--file-filter=Firmas CAdES | *.csig *.CSIG *.p7s *.P7S", //$NON-NLS-1$ //$NON-NLS-2$
				"--file-filter=Todos los archivos | *"), //$NON-NLS-1$
				NativeFileDialogs.buildCommand(Tool.ZENITY, ZE, Mode.SAVE, false, "Guardar como", new File("/home/ana/firma.xsig"), //$NON-NLS-1$ //$NON-NLS-2$
						NativeFileDialogs.orderFilters(asList(CADES, XADES), XADES, TODOS)));
	}

	/** zenity: carpeta (sin filtros ni multiseleccion, con barra final). */
	@Test
	public void testZenityCarpeta() {
		assertEquals(asList(ZE, "--file-selection", "--title=Abrir", "--directory", "--filename=/home/ana/no-existe/"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				NativeFileDialogs.buildCommand(Tool.ZENITY, ZE, Mode.DIRECTORY, true, "Abrir", new File("/home/ana/no-existe"), //$NON-NLS-1$ //$NON-NLS-2$
						singletonList(CADES)));
	}

	/** zenity: la barra vertical de la descripcion se sustituye. */
	@Test
	public void testZenityDescripcionConBarraVertical() {
		final GenericFileFilter f = new GenericFileFilter(new String[] { "txt" }, "Texto|log"); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(NativeFileDialogs.buildCommand(Tool.ZENITY, ZE, Mode.OPEN, false, "Abrir", new File("/home/ana"), //$NON-NLS-1$ //$NON-NLS-2$
				NativeFileDialogs.orderFilters(singletonList(f), f, TODOS)).contains("--file-filter=Texto/log | *.txt *.TXT")); //$NON-NLS-1$
	}

	/** zenity: sin saltos de linea en la descripcion. */
	@Test
	public void testZenityDescripcionSinSaltosDeLinea() {
		final GenericFileFilter f = new GenericFileFilter(new String[] { "txt" }, "Texto\nlog"); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue(NativeFileDialogs.buildCommand(Tool.ZENITY, ZE, Mode.OPEN, false, "Abrir", new File("/home/ana"), //$NON-NLS-1$ //$NON-NLS-2$
				NativeFileDialogs.orderFilters(singletonList(f), f, TODOS)).contains("--file-filter=Texto log | *.txt *.TXT")); //$NON-NLS-1$
	}

	/** Nunca se usa --attach ni --confirm-overwrite. */
	@Test
	public void testNuncaAttachNiConfirmOverwrite() {
		final List<GenericFileFilter> filtros = NativeFileDialogs.orderFilters(asList(CADES, XADES), CADES, TODOS);
		for (final Tool t : Tool.values()) {
			for (final Mode m : Mode.values()) {
				final List<String> c = NativeFileDialogs.buildCommand(t, "/x/" + t, m, true, "T", new File("/home/ana"), filtros); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				assertFalse(c.toString(), c.contains("--attach")); //$NON-NLS-1$
				assertFalse(c.toString(), c.contains("--confirm-overwrite")); //$NON-NLS-1$
			}
		}
	}

	// parseOutput

	/** Una ruta con espacios y tildes se conserva entera, incluido el espacio final. */
	@Test
	public void testParseUnoConEspaciosYTildes() {
		final Result r = NativeFileDialogs.parseOutput(0, "/home/ana/Mis firmas/\u00f1and\u00fa .pdf\n", false); //$NON-NLS-1$
		assertEquals(Status.ACCEPTED, r.status);
		assertArrayEquals(new File[] { new File("/home/ana/Mis firmas/\u00f1and\u00fa .pdf") }, r.files); //$NON-NLS-1$
	}

	/** Varias rutas, una por linea. */
	@Test
	public void testParseVarios() {
		final Result r = NativeFileDialogs.parseOutput(0, "/a/x.pdf\n/a/y z.pdf\n", true); //$NON-NLS-1$
		assertEquals(Status.ACCEPTED, r.status);
		assertArrayEquals(new File[] { new File("/a/x.pdf"), new File("/a/y z.pdf") }, r.files); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Sin multiseleccion solo cuenta la primera linea. */
	@Test
	public void testParseUnoIgnoraLineasDeMas() {
		final Result r = NativeFileDialogs.parseOutput(0, "/a/x\n/a/y\n", false); //$NON-NLS-1$
		assertEquals(Status.ACCEPTED, r.status);
		assertArrayEquals(new File[] { new File("/a/x") }, r.files); //$NON-NLS-1$
	}

	/** El codigo 1 es cancelar. */
	@Test
	public void testParseCancelado() {
		final Result r = NativeFileDialogs.parseOutput(1, "", false); //$NON-NLS-1$
		assertEquals(Status.CANCELLED, r.status);
		assertEquals(0, r.files.length);
	}

	/** Otros codigos o salidas no validas son no disponible. */
	@Test
	public void testParseNoDisponible() {
		for (final int code : new int[] { 2, 5, 134, 255, -1 }) {
			final Result r = NativeFileDialogs.parseOutput(code, "/a/x\n", false); //$NON-NLS-1$
			assertEquals("codigo " + code, Status.UNAVAILABLE, r.status); //$NON-NLS-1$
			assertEquals(0, r.files.length);
		}
		assertEquals(Status.UNAVAILABLE, NativeFileDialogs.parseOutput(0, "", false).status); //$NON-NLS-1$
		assertEquals(Status.UNAVAILABLE, NativeFileDialogs.parseOutput(0, "\n", true).status); //$NON-NLS-1$
		assertEquals(Status.UNAVAILABLE, NativeFileDialogs.parseOutput(0, "relativa/x.pdf\n", false).status); //$NON-NLS-1$
		assertEquals(Status.UNAVAILABLE, NativeFileDialogs.parseOutput(0, "smb://srv/x.pdf\n", false).status); //$NON-NLS-1$
	}

	// cleanEnvironment

	/** Se quitan las variables que cambian los codigos de salida de zenity. */
	@Test
	public void testLimpiaVariablesZenity() {
		final Map<String, String> m = env(
				"ZENITY_OK", "9", "ZENITY_CANCEL", "9", "ZENITY_ESC", "9", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				"ZENITY_ERROR", "9", "ZENITY_TIMEOUT", "9", "PATH", "/usr/bin"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
		NativeFileDialogs.cleanEnvironment(m);
		assertEquals(env("PATH", "/usr/bin"), m); //$NON-NLS-1$ //$NON-NLS-2$
	}

	// startPath

	/** Carpeta inicial con las mismas reglas que el JFileChooser. */
	@Test
	public void testCarpetaInicial() {
		final String h = "/home/ana"; //$NON-NLS-1$
		assertEquals(new File("/d/f.pdf"), NativeFileDialogs.startPath("/d", "f.pdf", "/pref", h)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		assertEquals(new File("/pref/f.pdf"), NativeFileDialogs.startPath(null, "f.pdf", "/pref", h)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertEquals(new File("/home/ana/f.pdf"), NativeFileDialogs.startPath(null, "f.pdf", null, h)); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(new File("/abs/f.pdf"), NativeFileDialogs.startPath(null, "/abs/f.pdf", null, h)); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(new File("/d"), NativeFileDialogs.startPath("/d", null, "/pref", h)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		assertEquals(new File("/pref"), NativeFileDialogs.startPath(null, null, "/pref", h)); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(new File("/home/ana"), NativeFileDialogs.startPath(null, null, null, h)); //$NON-NLS-1$
	}

	// addDefaultExtension

	/** Extension por defecto al guardar con dialogo nativo. */
	@Test
	public void testExtensionPorDefecto() {
		final List<GenericFileFilter> fs = asList(CADES, XADES);
		assertEquals(new File("/t/firma.csig"), NativeFileDialogs.addDefaultExtension(new File("/t/firma"), fs, CADES)); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(new File("/t/firma.XSIG"), NativeFileDialogs.addDefaultExtension(new File("/t/firma.XSIG"), fs, CADES)); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(new File("/t/firma.p7s"), NativeFileDialogs.addDefaultExtension(new File("/t/firma.p7s"), fs, CADES)); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(new File("/t/informe.v2.csig"), NativeFileDialogs.addDefaultExtension(new File("/t/informe.v2"), fs, CADES)); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(new File("/t/firma"), NativeFileDialogs.addDefaultExtension(new File("/t/firma"), fs, null)); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(new File("/t/firma"), NativeFileDialogs.addDefaultExtension(new File("/t/firma"), null, CADES)); //$NON-NLS-1$ //$NON-NLS-2$
		final GenericFileFilter t = new GenericFileFilter(new String[] { ".TXT" }, "Texto"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(new File("/t/a.txt"), NativeFileDialogs.addDefaultExtension(new File("/t/a"), singletonList(t), t)); //$NON-NLS-1$ //$NON-NLS-2$
	}

	// run con ejecutables falsos

	/** La salida se lee como UTF-8 y stderr se descarta.
	 * @throws Exception En cualquier error. */
	@Test
	public void testRunAceptadoUtf8() throws Exception {
		final File s = fake(this.tmp.getRoot(), "ok", "printf '%s\\n' '/tmp/A\u00f1o 2026/contrato \u00e1.pdf'\necho ruido >&2\nexit 0"); //$NON-NLS-1$ //$NON-NLS-2$
		final Result r = NativeFileDialogs.run(singletonList(s.getPath()), false);
		assertEquals(Status.ACCEPTED, r.status);
		assertArrayEquals(new File[] { new File("/tmp/A\u00f1o 2026/contrato \u00e1.pdf") }, r.files); //$NON-NLS-1$
	}

	/** Salida 1: cancelado.
	 * @throws Exception En cualquier error. */
	@Test
	public void testRunCancelado() throws Exception {
		final File s = fake(this.tmp.getRoot(), "cancela", "exit 1"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(Status.CANCELLED, NativeFileDialogs.run(singletonList(s.getPath()), false).status);
	}

	/** Proceso abortado: no disponible.
	 * @throws Exception En cualquier error. */
	@Test
	public void testRunAbortado() throws Exception {
		final File s = fake(this.tmp.getRoot(), "aborta", "exit 134"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(Status.UNAVAILABLE, NativeFileDialogs.run(singletonList(s.getPath()), false).status);
	}

	/** Ejecutable inexistente: no disponible, sin excepcion. */
	@Test
	public void testRunEjecutableInexistente() {
		assertEquals(Status.UNAVAILABLE,
				NativeFileDialogs.run(singletonList(this.tmp.getRoot() + "/no-existe"), false).status); //$NON-NLS-1$
	}

	/** Los argumentos con tildes y espacios llegan intactos al proceso.
	 * @throws Exception En cualquier error. */
	@Test
	public void testRunConservaArgumentosConTildesYEspacios() throws Exception {
		Assume.assumeTrue("UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding"))); //$NON-NLS-1$ //$NON-NLS-2$
		final File s = fake(this.tmp.getRoot(), "args", "printf '%s\\n' \"$2\""); //$NON-NLS-1$ //$NON-NLS-2$
		final Result r = NativeFileDialogs.run(asList(s.getPath(), "--x", "/tmp/A\u00f1o 2026/\u00e1 b.pdf"), false); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(Status.ACCEPTED, r.status);
		assertArrayEquals(new File[] { new File("/tmp/A\u00f1o 2026/\u00e1 b.pdf") }, r.files); //$NON-NLS-1$
	}

	/** Una salida mayor que el bufer de la tuberia no bloquea.
	 * @throws Exception En cualquier error. */
	@Test(timeout = 30000)
	public void testRunSalidaGrandeNoSeBloquea() throws Exception {
		final File s = fake(this.tmp.getRoot(), "grande", //$NON-NLS-1$
				"i=0; while [ $i -lt 3000 ]; do printf '/tmp/directorio-con-nombre-largo/fichero-%05d.pdf\\n' $i; i=$((i+1)); done"); //$NON-NLS-1$
		final Result r = NativeFileDialogs.run(singletonList(s.getPath()), true);
		assertEquals(Status.ACCEPTED, r.status);
		assertEquals(3000, r.files.length);
		assertEquals(new File("/tmp/directorio-con-nombre-largo/fichero-02999.pdf"), r.files[2999]); //$NON-NLS-1$
	}

	// Espera desde el hilo de eventos de AWT

	/** Con el dialogo nativo abierto el hilo de eventos sigue atendiendo eventos (repintado).
	 * @throws Exception En cualquier error. */
	@Test(timeout = 60000)
	public void testEnHiloDeEventosNoSeBloquea() throws Exception {
		final File iniciado = new File(this.tmp.getRoot(), "iniciado"); //$NON-NLS-1$
		final File libera = new File(this.tmp.getRoot(), "libera"); //$NON-NLS-1$
		final File s = fake(this.tmp.getRoot(), "lento", //$NON-NLS-1$
				"touch '" + iniciado + "'\n" //$NON-NLS-1$ //$NON-NLS-2$
				+ "i=0; while [ ! -e '" + libera + "' ] && [ $i -lt 300 ]; do sleep 0.1; i=$((i+1)); done\n" //$NON-NLS-1$ //$NON-NLS-2$
				+ "printf '%s\\n' '/tmp/A\u00f1o 2026/lento.pdf'\nexit 0"); //$NON-NLS-1$
		final AtomicReference<Result> resultado = new AtomicReference<>();
		final CountDownLatch terminado = new CountDownLatch(1);
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				resultado.set(NativeFileDialogs.runWithoutBlockingEdt(singletonList(s.getPath()), false, null));
				terminado.countDown();
			}
		});
		final long limite = System.currentTimeMillis() + 10000;
		while (!iniciado.exists() && System.currentTimeMillis() < limite) {
			Thread.sleep(20);
		}
		assertTrue("El dialogo falso no llego a arrancar", iniciado.exists()); //$NON-NLS-1$

		final CountDownLatch atendido = new CountDownLatch(1);
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				atendido.countDown();
			}
		});
		final boolean hiloDeEventosLibre = atendido.await(5, TimeUnit.SECONDS);
		final boolean seguiaAbierto = terminado.getCount() == 1;
		Files.write(libera.toPath(), new byte[0]);

		assertTrue("El dialogo falso no termino", terminado.await(40, TimeUnit.SECONDS)); //$NON-NLS-1$
		assertTrue("El hilo de eventos quedo bloqueado con el dialogo abierto", hiloDeEventosLibre); //$NON-NLS-1$
		assertTrue("La llamada volvio antes de cerrar el dialogo", seguiaAbierto); //$NON-NLS-1$
		assertEquals(Status.ACCEPTED, resultado.get().status);
		assertArrayEquals(new File[] { new File("/tmp/A\u00f1o 2026/lento.pdf") }, resultado.get().files); //$NON-NLS-1$
	}

	/** Desde el hilo de eventos, un ejecutable inexistente sigue dando no disponible.
	 * @throws Exception En cualquier error. */
	@Test(timeout = 30000)
	public void testEnHiloDeEventosEjecutableInexistente() throws Exception {
		final AtomicReference<Result> resultado = new AtomicReference<>();
		EventQueue.invokeAndWait(new Runnable() {
			@Override
			public void run() {
				resultado.set(NativeFileDialogs.runWithoutBlockingEdt(
						singletonList(NativeFileDialogsTest.this.tmp.getRoot() + "/no-existe"), false, null)); //$NON-NLS-1$
			}
		});
		assertEquals(Status.UNAVAILABLE, resultado.get().status);
	}
}
