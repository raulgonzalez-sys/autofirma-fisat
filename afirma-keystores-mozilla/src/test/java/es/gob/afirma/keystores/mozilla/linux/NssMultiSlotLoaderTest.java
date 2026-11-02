package es.gob.afirma.keystores.mozilla.linux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import es.gob.afirma.keystores.mozilla.MozillaKeyStoreUtilities;

/** Pruebas de la carga de varias bases NSS en una sola inicializaci&oacute;n.
 * NSS solo se inicializa una vez por proceso, as&iacute; que cada caso que lo
 * inicializa se ejecuta en una JVM hija ({@link #main(String[])}). */
public class NssMultiSlotLoaderTest {

	private static final File CERTUTIL = new File("/usr/bin/certutil"); //$NON-NLS-1$
	private static final String SOFTOKN = "libsoftokn3.so"; //$NON-NLS-1$
	private static final String RESULT = "RESULTADO|"; //$NON-NLS-1$
	private static final String PROVIDER_1 = "SunPKCS11-NSSCrypto-AFirma-1"; //$NON-NLS-1$
	private static final String PROVIDER_2 = "SunPKCS11-NSSCrypto-AFirma-2"; //$NON-NLS-1$
	private static final String LLAMADAS = "prueba.nss.llamadas"; //$NON-NLS-1$

	/** Directorio temporal para las bases NSS. */
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** Cadena exacta para dos bases. */
	@Test
	public void argsDosBases() {
		assertEquals(
			"flags='readOnly' tokens=<0x02=[configdir='sql:/a/firefox' flags=readOnly] 0x04=[configdir='sql:/b/chrome' flags=readOnly]>", //$NON-NLS-1$
			NssMultiSlotLoader.buildNssArgs(Arrays.asList(new File("/a/firefox"), new File("/b/chrome"))) //$NON-NLS-1$ //$NON-NLS-2$
		);
	}

	/** Cada proveedor ve solo su base y firma con su clave. @throws Exception En cualquier error. */
	@Test
	public void dosBasesFirman() throws Exception {
		final String libDir = nssLibDir();
		final File a = crearBase("certA", "A"); //$NON-NLS-1$ //$NON-NLS-2$
		final File b = crearBase("certB", "B"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(
			Arrays.asList(
				RESULT + PROVIDER_1 + "|true|[certA]|256", //$NON-NLS-1$
				RESULT + PROVIDER_2 + "|true|[certB]|256" //$NON-NLS-1$
			),
			ejecutarEnJvmHija(libDir, a, b)
		);
	}

	/** Solo la base de Firefox. @throws Exception En cualquier error. */
	@Test
	public void unaBase() throws Exception {
		final String libDir = nssLibDir();
		final File a = crearBase("certA", "A"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(
			Collections.singletonList(RESULT + PROVIDER_1 + "|true|[certA]|256"), //$NON-NLS-1$
			ejecutarEnJvmHija(libDir, a)
		);
	}

	/** Una base rota hace fallar la inicializaci&oacute;n completa: se reintenta en el
	 * mismo proceso sin ella, se registra y la otra se carga. @throws Exception En cualquier error. */
	@Test
	public void baseRota() throws Exception {
		final String libDir = nssLibDir();
		final File a = crearBase("certA", "A"); //$NON-NLS-1$ //$NON-NLS-2$
		final File b = this.tmp.newFolder("B"); //$NON-NLS-1$
		Files.write(new File(b, "cert9.db").toPath(), "no es una base".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(
			Collections.singletonList(RESULT + PROVIDER_1 + "|true|[certA]|256"), //$NON-NLS-1$
			ejecutarEnJvmHija(libDir, a, b)
		);
		final String log = logHija();
		assertTrue(log, log.contains(b.getAbsolutePath()));
	}

	/** Una segunda llamada en el mismo proceso, aunque sea con otra lista, devuelve los mismos
	 * proveedores sin registrarlos otra vez. @throws Exception En cualquier error. */
	@Test
	public void segundaLlamadaReutiliza() throws Exception {
		final String libDir = nssLibDir();
		final File a = crearBase("certA", "A"); //$NON-NLS-1$ //$NON-NLS-2$
		final File b = crearBase("certB", "B"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(
			Arrays.asList(
				RESULT + PROVIDER_1 + "|true|[certA]|256", //$NON-NLS-1$
				RESULT + PROVIDER_2 + "|true|[certB]|256", //$NON-NLS-1$
				RESULT + "reutiliza|true|2" //$NON-NLS-1$
			),
			ejecutarEnJvmHija(Collections.singletonList("-D" + LLAMADAS + "=2"), libDir, a, b) //$NON-NLS-1$ //$NON-NLS-2$
		);
		final String log = logHija();
		assertTrue(log, log.contains("se ignora la nueva lista de bases")); //$NON-NLS-1$
	}

	/** Una base en un directorio con espacios, e&ntilde;es, tildes y arroba se abre.
	 * @throws Exception En cualquier error. */
	@Test
	public void rutaConEspaciosYTildes() throws Exception {
		final String libDir = nssLibDir();
		assumeTrue(
			"El sistema de ficheros de la JVM no usa UTF-8", //$NON-NLS-1$
			"UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding")) //$NON-NLS-1$ //$NON-NLS-2$
		);
		final File a = crearBase("certA", "Usuaria \u00d1\u00fa\u00f1ez", "usuario1@example.org", "nssdb"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		assertEquals(
			Collections.singletonList(RESULT + PROVIDER_1 + "|true|[certA]|256"), //$NON-NLS-1$
			ejecutarEnJvmHija(libDir, a)
		);
	}

	/** Las bases cuya ruta romperia la configuracion de NSS se omiten con un aviso, sin
	 * intentar iniciar NSS con ellas. @throws Exception En cualquier error. */
	@Test
	public void rutaInseguraSeOmite() throws Exception {
		final String libDir = nssLibDir();
		final File a = crearBase("certA", "A"); //$NON-NLS-1$ //$NON-NLS-2$
		final File comilla = crearBase("certB", "comilla'"); //$NON-NLS-1$ //$NON-NLS-2$
		final File corchete = crearBase("certC", "corchete]"); //$NON-NLS-1$ //$NON-NLS-2$
		final File menor = crearBase("certD", "menor<"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(
			Collections.singletonList(RESULT + PROVIDER_1 + "|true|[certA]|256"), //$NON-NLS-1$
			ejecutarEnJvmHija(libDir, comilla, a, corchete, menor)
		);
		final String log = logHija();
		for (final File db : new File[] { comilla, corchete, menor }) {
			assertTrue(log, log.contains("Se omite la base NSS por contener caracteres no admitidos en su ruta: " + db.getAbsolutePath())); //$NON-NLS-1$
		}
		assertFalse(log, log.contains("No se ha podido iniciar NSS")); //$NON-NLS-1$
	}

	private String logHija() throws IOException {
		return new String(Files.readAllBytes(new File(this.tmp.getRoot(), "hija.log").toPath()), StandardCharsets.UTF_8); //$NON-NLS-1$
	}

	private static String nssLibDir() {
		assumeTrue("Falta " + CERTUTIL, CERTUTIL.canExecute()); //$NON-NLS-1$
		String libDir;
		try {
			libDir = MozillaKeyStoreUtilities.getSystemNSSLibDir();
		}
		catch (final IOException e) {
			libDir = null;
		}
		assumeTrue("No se encuentra NSS en el sistema", libDir != null && new File(libDir, SOFTOKN).isFile()); //$NON-NLS-1$
		return libDir;
	}

	/** Crea una base NSS sin contrase&ntilde;a con un certificado autofirmado y su clave. */
	private File crearBase(final String nick, final String... path) throws IOException, InterruptedException {
		final File db = this.tmp.newFolder(path);
		final File noise = this.tmp.newFile(nick + ".noise"); //$NON-NLS-1$
		final byte[] bytes = new byte[64];
		new SecureRandom().nextBytes(bytes);
		Files.write(noise.toPath(), bytes);
		final String sql = "sql:" + db.getAbsolutePath(); //$NON-NLS-1$
		ejecutar(CERTUTIL.getPath(), "-N", "--empty-password", "-d", sql); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		ejecutar(
			CERTUTIL.getPath(), "-S", "-x", "-t", "u,u,u", "-k", "rsa", "-g", "2048", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
			"-z", noise.getAbsolutePath(), "-v", "12", "-n", nick, "-s", "CN=" + nick, "-d", sql //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
		);
		return db;
	}

	private static void ejecutar(final String... cmd) throws IOException, InterruptedException {
		final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start(); //$NON-NLS-1$
		final String out = leer(p);
		assertTrue("Tiempo agotado: " + Arrays.toString(cmd), p.waitFor(60, TimeUnit.SECONDS)); //$NON-NLS-1$
		assertEquals(Arrays.toString(cmd) + "\n" + out, 0, p.exitValue()); //$NON-NLS-1$
	}

	private static String leer(final Process p) throws IOException {
		final StringBuilder sb = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	/** Ejecuta {@link #main(String[])} en otra JVM y devuelve sus l&iacute;neas de resultado.
	 * El registro de la JVM hija queda en {@code hija.log} del directorio temporal. */
	private List<String> ejecutarEnJvmHija(final String libDir, final File... dbs) throws IOException, InterruptedException {
		return ejecutarEnJvmHija(Collections.<String>emptyList(), libDir, dbs);
	}

	private List<String> ejecutarEnJvmHija(final List<String> jvmArgs, final String libDir, final File... dbs) throws IOException, InterruptedException {
		final List<String> cmd = new ArrayList<>();
		cmd.add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
		cmd.addAll(jvmArgs);
		cmd.add("-cp"); //$NON-NLS-1$
		cmd.add(System.getProperty("java.class.path")); //$NON-NLS-1$
		cmd.add(NssMultiSlotLoaderTest.class.getName());
		cmd.add(libDir);
		for (final File db : dbs) {
			cmd.add(db.getAbsolutePath());
		}
		final File log = new File(this.tmp.getRoot(), "hija.log"); //$NON-NLS-1$
		final Process p = new ProcessBuilder(cmd).redirectError(log).start();
		final String out = leer(p);
		assertTrue("Tiempo agotado en la JVM hija", p.waitFor(120, TimeUnit.SECONDS)); //$NON-NLS-1$
		final String err = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
		assertEquals(out + err, 0, p.exitValue());
		final List<String> results = new ArrayList<>();
		for (final String line : out.split("\n")) { //$NON-NLS-1$
			if (line.startsWith(RESULT)) {
				results.add(line);
			}
		}
		return results;
	}

	/** Punto de entrada de la JVM hija: carga las bases y, por cada proveedor, imprime su
	 * nombre, si est&aacute; registrado, sus alias y la longitud de la firma con cada clave.
	 * Con {@code -Dprueba.nss.llamadas=2} vuelve a cargar con la lista invertida e imprime si
	 * obtuvo los mismos proveedores y cu&aacute;ntos hay registrados.
	 * @param args Directorio de NSS seguido de los directorios de las bases.
	 * @throws Exception En cualquier error. */
	public static void main(final String[] args) throws Exception {
		final List<File> dbs = new ArrayList<>();
		for (int i = 1; i < args.length; i++) {
			dbs.add(new File(args[i]));
		}
		final List<Provider> providers = NssMultiSlotLoader.load(args[0], dbs);
		String reutiliza = null;
		if (Integer.getInteger(LLAMADAS, 1).intValue() > 1) {
			final List<File> otras = new ArrayList<>(dbs);
			Collections.reverse(otras);
			final List<Provider> segunda = NssMultiSlotLoader.load(args[0], otras);
			boolean mismos = segunda.size() == providers.size();
			for (int i = 0; mismos && i < providers.size(); i++) {
				mismos = segunda.get(i) == providers.get(i);
			}
			int registrados = 0;
			for (final Provider p : Security.getProviders()) {
				if (p.getName().startsWith("SunPKCS11-NSSCrypto-AFirma-")) { //$NON-NLS-1$
					registrados++;
				}
			}
			reutiliza = RESULT + "reutiliza|" + mismos + "|" + registrados; //$NON-NLS-1$ //$NON-NLS-2$
		}
		for (final Provider p : providers) {
			final KeyStore ks = KeyStore.getInstance("PKCS11", p); //$NON-NLS-1$
			ks.load(null, new char[0]);
			final List<String> aliases = Collections.list(ks.aliases());
			final List<Integer> firmas = new ArrayList<>();
			for (final String alias : aliases) {
				if (ks.isKeyEntry(alias)) {
					final Signature s = Signature.getInstance("SHA256withRSA", p); //$NON-NLS-1$
					s.initSign((PrivateKey) ks.getKey(alias, null));
					s.update("datos".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
					firmas.add(Integer.valueOf(s.sign().length));
				}
			}
			System.out.println(
				RESULT + p.getName() + "|" + (Security.getProvider(p.getName()) == p) //$NON-NLS-1$
					+ "|" + aliases + "|" + String.join(",", toStrings(firmas)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			);
		}
		if (reutiliza != null) {
			System.out.println(reutiliza);
		}
	}

	private static List<String> toStrings(final List<Integer> values) {
		final List<String> ret = new ArrayList<>();
		for (final Integer v : values) {
			ret.add(v.toString());
		}
		return ret;
	}
}
