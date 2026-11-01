package es.gob.afirma.keystores.mozilla.linux;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import es.gob.afirma.core.misc.Platform;
import es.gob.afirma.keystores.AOKeyStore;
import es.gob.afirma.keystores.AOKeyStoreManager;
import es.gob.afirma.keystores.mozilla.MozillaKeyStoreUtilities;
import es.gob.afirma.keystores.mozilla.MozillaUnifiedKeyStoreManager;
import es.gob.afirma.keystores.mozilla.shared.SharedNssKeyStoreManager;

/** Pruebas del almac&eacute;n que agrega las bases NSS de Firefox y de Chrome en Linux.
 * NSS solo se inicializa una vez por proceso, as&iacute; que cada caso se ejecuta en una
 * JVM hija ({@link #main(String[])}) con un directorio de usuario falso. */
public class NssFirefoxChromeKeyStoreManagerTest {

	private static final File CERTUTIL = new File("/usr/bin/certutil"); //$NON-NLS-1$
	private static final File PK12UTIL = new File("/usr/bin/pk12util"); //$NON-NLS-1$
	private static final String SOFTOKN = "libsoftokn3.so"; //$NON-NLS-1$
	private static final String RESULT = "RESULTADO|"; //$NON-NLS-1$
	private static final String PROFILE = "abcd1234.default-release"; //$NON-NLS-1$
	private static final String FIREFOX_PROFILE = ".mozilla/firefox/" + PROFILE; //$NON-NLS-1$
	private static final String CHROME_DB = ".pki/nssdb"; //$NON-NLS-1$
	private static final String P12_PASSWORD = "clave-de-prueba"; //$NON-NLS-1$

	/** Los textos de la interfaz se comprueban en castellano. */
	@BeforeClass
	public static void castellano() {
		Locale.setDefault(new Locale("es", "ES")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Directorio temporal con el directorio de usuario falso. */
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** Firefox y Chrome con un certificado distinto cada uno: aparecen los dos y se firma
	 * con ambos; el refresco reutiliza los proveedores. @throws Exception En cualquier error. */
	@Test
	public void agregaLasDos() throws Exception {
		nssLibDir();
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		crearPerfilFirefox(home);
		crearBase(new File(home, FIREFOX_PROFILE), "certA", "CN=certA"); //$NON-NLS-1$ //$NON-NLS-2$
		crearBase(new File(home, CHROME_DB), "certB", "CN=certB"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(
			Arrays.asList(
				RESULT + "certA|CN=certA|true", //$NON-NLS-1$
				RESULT + "certB|CN=certB|true", //$NON-NLS-1$
				RESULT + "refresco|[certA, certB]|2" //$NON-NLS-1$
			),
			ejecutarEnJvmHija(home)
		);
	}

	/** El mismo certificado importado en las dos bases aparece una sola vez.
	 * @throws Exception En cualquier error. */
	@Test
	public void mismoCertEnAmbas() throws Exception {
		nssLibDir();
		assumeTrue("Falta " + PK12UTIL, PK12UTIL.canExecute()); //$NON-NLS-1$
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		crearPerfilFirefox(home);
		final File firefox = new File(home, FIREFOX_PROFILE);
		final File chrome = new File(home, CHROME_DB);
		crearBase(firefox, "certA", "CN=certA"); //$NON-NLS-1$ //$NON-NLS-2$
		crearBase(chrome, "certB", "CN=certB"); //$NON-NLS-1$ //$NON-NLS-2$
		final File p12 = new File(this.tmp.getRoot(), "certA.p12"); //$NON-NLS-1$
		ejecutar(PK12UTIL.getPath(), "-o", p12.getAbsolutePath(), "-n", "certA", "-W", P12_PASSWORD, "-d", "sql:" + firefox.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
		ejecutar(PK12UTIL.getPath(), "-i", p12.getAbsolutePath(), "-W", P12_PASSWORD, "-d", "sql:" + chrome.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		assertEquals(
			Arrays.asList(
				RESULT + "certA|CN=certA|true", //$NON-NLS-1$
				RESULT + "certB|CN=certB|true", //$NON-NLS-1$
				RESULT + "refresco|[certA, certB]|2" //$NON-NLS-1$
			),
			ejecutarEnJvmHija(home)
		);
	}

	/** Usuario que nunca abri&oacute; Chrome: solo los certificados de Firefox, sin errores
	 * ni di&aacute;logos (la JVM hija es <i>headless</i>). @throws Exception En cualquier error. */
	@Test
	public void soloFirefox() throws Exception {
		nssLibDir();
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		crearPerfilFirefox(home);
		crearBase(new File(home, FIREFOX_PROFILE), "certA", "CN=certA"); //$NON-NLS-1$ //$NON-NLS-2$
		assertFalse(new File(home, CHROME_DB).exists());
		assertEquals(
			Arrays.asList(
				RESULT + "certA|CN=certA|true", //$NON-NLS-1$
				RESULT + "refresco|[certA]|1" //$NON-NLS-1$
			),
			ejecutarEnJvmHija(home)
		);
		final String log = logHija();
		assertFalse(log, log.contains("WARNING")); //$NON-NLS-1$
		assertFalse(log, log.contains("SEVERE")); //$NON-NLS-1$
	}

	/** Certificados distintos con el mismo alias en las dos bases: aparecen los dos y cada
	 * alias firma con la clave de su propio certificado. @throws Exception En cualquier error. */
	@Test
	public void mismoAliasDistintoCert() throws Exception {
		nssLibDir();
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		crearPerfilFirefox(home);
		crearBase(new File(home, FIREFOX_PROFILE), "certA", "CN=certA Firefox"); //$NON-NLS-1$ //$NON-NLS-2$
		crearBase(new File(home, CHROME_DB), "certA", "CN=certA Chrome"); //$NON-NLS-1$ //$NON-NLS-2$
		assertEquals(
			Arrays.asList(
				RESULT + "certA|CN=certA Firefox|true", //$NON-NLS-1$
				RESULT + "certA (2)|CN=certA Chrome|true", //$NON-NLS-1$
				RESULT + "refresco|[certA, certA (2)]|2" //$NON-NLS-1$
			),
			ejecutarEnJvmHija(home)
		);
	}

	/** El certificado est&aacute; sin clave en Firefox (solo el {@code .cer}) y con clave en
	 * Chrome: se conserva la copia con clave, que aparece y firma. SunPKCS11 no lista los
	 * certificados sin clave de NSS, as&iacute; que la preferencia por la copia con clave se
	 * comprueba sin NSS en {@link #dedupPrefiereConClaveSinNss()}. @throws Exception En cualquier error. */
	@Test
	public void dedupPrefiereConClave() throws Exception {
		nssLibDir();
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		crearPerfilFirefox(home);
		final File firefox = new File(home, FIREFOX_PROFILE);
		final File chrome = new File(home, CHROME_DB);
		crearBase(chrome, "certA", "CN=certA"); //$NON-NLS-1$ //$NON-NLS-2$
		final File cer = new File(this.tmp.getRoot(), "certA.cer"); //$NON-NLS-1$
		ejecutar(CERTUTIL.getPath(), "-L", "-n", "certA", "-r", "-o", cer.getAbsolutePath(), "-d", "sql:" + chrome.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
		Files.createDirectories(firefox.toPath());
		ejecutar(CERTUTIL.getPath(), "-N", "--empty-password", "-d", "sql:" + firefox.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		ejecutar(CERTUTIL.getPath(), "-A", "-n", "certA", "-t", "P,P,P", "-i", cer.getAbsolutePath(), "-d", "sql:" + firefox.getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
		assertEquals(
			Arrays.asList(
				RESULT + "certA|CN=certA|true", //$NON-NLS-1$
				RESULT + "refresco|[certA]|2" //$NON-NLS-1$
			),
			ejecutarEnJvmHija(home)
		);
	}

	/** Sin NSS: un certificado sin clave en el primer almac&eacute;n (Firefox) y con clave en el
	 * segundo (Chrome) queda una sola vez y apunta al almac&eacute;n con clave.
	 * @throws Exception En cualquier error. */
	@Test
	public void dedupPrefiereConClaveSinNss() throws Exception {
		final File p12 = new File(this.tmp.getRoot(), "certA.p12"); //$NON-NLS-1$
		ejecutar(
			new File(System.getProperty("java.home"), "bin/keytool").getAbsolutePath(), //$NON-NLS-1$ //$NON-NLS-2$
			"-genkeypair", "-keystore", p12.getAbsolutePath(), "-storetype", "PKCS12", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"-storepass", P12_PASSWORD, "-alias", "certa", "-dname", "CN=certA", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			"-keyalg", "RSA", "-keysize", "2048", "-validity", "30" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
		);
		// PKCS#12 guarda los alias en minusculas
		final KeyStore conClave = KeyStore.getInstance("PKCS12"); //$NON-NLS-1$
		try (InputStream is = new FileInputStream(p12)) {
			conClave.load(is, P12_PASSWORD.toCharArray());
		}
		final KeyStore sinClave = KeyStore.getInstance("PKCS12"); //$NON-NLS-1$
		sinClave.load(null, null);
		sinClave.setCertificateEntry("certa", conClave.getCertificate("certa")); //$NON-NLS-1$ //$NON-NLS-2$
		final AOKeyStoreManager firefox = new AlmacenFijo(sinClave);
		final AOKeyStoreManager chrome = new AlmacenFijo(conClave);

		final Map<String, NssFirefoxChromeKeyStoreManager.SlotEntry> entries =
			NssFirefoxChromeKeyStoreManager.buildEntries(Arrays.asList(firefox, chrome));
		assertEquals(Collections.singletonList("certa"), new ArrayList<>(entries.keySet())); //$NON-NLS-1$
		assertSame(chrome, entries.get("certa").slot); //$NON-NLS-1$

		// Con la clave en el primero, se queda el primero
		final Map<String, NssFirefoxChromeKeyStoreManager.SlotEntry> inverso =
			NssFirefoxChromeKeyStoreManager.buildEntries(Arrays.asList(chrome, firefox));
		assertEquals(Collections.singletonList("certa"), new ArrayList<>(inverso.keySet())); //$NON-NLS-1$
		assertSame(chrome, inverso.get("certa").slot); //$NON-NLS-1$
	}

	/** El di&aacute;logo de contrase&ntilde;a dice si la base es la de Firefox o la de Chrome.
	 * @throws Exception En cualquier error. */
	@Test
	public void textoContrasenaPorBase() throws Exception {
		final File home = this.tmp.newFolder("home"); //$NON-NLS-1$
		Files.createDirectories(new File(home, CHROME_DB).toPath());
		assertEquals(
			"Contrase\u00f1a del almac\u00e9n de Chrome", //$NON-NLS-1$
			NssFirefoxChromeKeyStoreManager.passwordPrompt(new File(home, CHROME_DB), home)
		);
		assertEquals(
			"Contrase\u00f1a del almac\u00e9n de Firefox", //$NON-NLS-1$
			NssFirefoxChromeKeyStoreManager.passwordPrompt(new File(home, FIREFOX_PROFILE), home)
		);
	}

	/** Almac&eacute;n de prueba sobre un {@code KeyStore} ya cargado. */
	private static final class AlmacenFijo extends AOKeyStoreManager {
		AlmacenFijo(final KeyStore ks) {
			setKeyStore(ks);
			setKeyStoreType(AOKeyStore.PKCS12);
		}
	}

	/** En Linux los dos gestores de Mozilla usan el almac&eacute;n de Firefox y Chrome.
	 * @throws Exception En cualquier error. */
	@Test
	public void enganches() throws Exception {
		assumeTrue("Solo en Linux", Platform.OS.LINUX.equals(Platform.getOS())); //$NON-NLS-1$
		final Method m = MozillaUnifiedKeyStoreManager.class.getDeclaredMethod("getNssKeyStoreManager"); //$NON-NLS-1$
		m.setAccessible(true);
		assertTrue(m.invoke(new MozillaUnifiedKeyStoreManager()) instanceof NssFirefoxChromeKeyStoreManager);
		final Method s = SharedNssKeyStoreManager.class.getDeclaredMethod("getNssKeyStoreManager"); //$NON-NLS-1$
		s.setAccessible(true);
		assertTrue(s.invoke(new SharedNssKeyStoreManager()) instanceof NssFirefoxChromeKeyStoreManager);
	}

	private String logHija() throws IOException {
		return new String(Files.readAllBytes(new File(this.tmp.getRoot(), "hija.log").toPath()), StandardCharsets.UTF_8); //$NON-NLS-1$
	}

	private static void nssLibDir() {
		assumeTrue("Falta " + CERTUTIL, CERTUTIL.canExecute()); //$NON-NLS-1$
		String libDir;
		try {
			libDir = MozillaKeyStoreUtilities.getSystemNSSLibDir();
		}
		catch (final IOException e) {
			libDir = null;
		}
		assumeTrue("No se encuentra NSS en el sistema", libDir != null && new File(libDir, SOFTOKN).isFile()); //$NON-NLS-1$
	}

	/** Crea un {@code profiles.ini} tradicional con un perfil por defecto que Firefox
	 * dar&iacute;a por v&aacute;lido (al menos diez ficheros). */
	private static void crearPerfilFirefox(final File home) throws IOException {
		final File root = new File(home, ".mozilla/firefox"); //$NON-NLS-1$
		final File profile = new File(root, PROFILE);
		Files.createDirectories(profile.toPath());
		Files.write(
			new File(root, "profiles.ini").toPath(), //$NON-NLS-1$
			("[Profile0]\nName=default-release\nIsRelative=1\nPath=" + PROFILE + "\nDefault=1\n").getBytes(StandardCharsets.UTF_8) //$NON-NLS-1$ //$NON-NLS-2$
		);
		for (int i = 0; i < 10; i++) {
			Files.write(new File(profile, "relleno" + i).toPath(), new byte[0]); //$NON-NLS-1$
		}
	}

	/** Crea (o ampl&iacute;a) una base NSS sin contrase&ntilde;a con un certificado autofirmado y su clave. */
	private void crearBase(final File db, final String nick, final String subject) throws IOException, InterruptedException {
		Files.createDirectories(db.toPath());
		final String sql = "sql:" + db.getAbsolutePath(); //$NON-NLS-1$
		if (!new File(db, "cert9.db").isFile()) { //$NON-NLS-1$
			ejecutar(CERTUTIL.getPath(), "-N", "--empty-password", "-d", sql); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		final File noise = File.createTempFile("ruido", ".bin", this.tmp.getRoot()); //$NON-NLS-1$ //$NON-NLS-2$
		final byte[] bytes = new byte[64];
		new SecureRandom().nextBytes(bytes);
		Files.write(noise.toPath(), bytes);
		ejecutar(
			CERTUTIL.getPath(), "-S", "-x", "-t", "u,u,u", "-k", "rsa", "-g", "2048", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
			"-z", noise.getAbsolutePath(), "-v", "12", "-n", nick, "-s", subject, "-d", sql //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
		);
	}

	private static void ejecutar(final String... cmd) throws IOException, InterruptedException {
		final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start(); //$NON-NLS-1$
		final String out = esperar(p, 60, Arrays.toString(cmd));
		assertEquals(Arrays.toString(cmd) + "\n" + out, 0, p.exitValue()); //$NON-NLS-1$
	}

	/** Lee la salida est&aacute;ndar en un hilo aparte y espera al proceso como mucho
	 * {@code segundos}: si no termina, lo mata y la prueba falla con lo le&iacute;do hasta entonces. */
	private static String esperar(final Process p, final long segundos, final String que) throws InterruptedException {
		final StringBuilder sb = new StringBuilder();
		final Thread lector = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					synchronized (sb) {
						sb.append(line).append('\n');
					}
				}
			}
			catch (final IOException e) {
				// El proceso ha terminado o se ha matado
			}
		});
		lector.setDaemon(true);
		lector.start();
		final boolean terminado = p.waitFor(segundos, TimeUnit.SECONDS);
		if (!terminado) {
			p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
		}
		lector.join(TimeUnit.SECONDS.toMillis(10));
		final String out;
		synchronized (sb) {
			out = sb.toString();
		}
		assertTrue("Tiempo agotado (" + segundos + " s): " + que + "\n" + out, terminado); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		return out;
	}

	/** Ejecuta {@link #main(String[])} en otra JVM <i>headless</i> con {@code user.home}
	 * apuntando al directorio indicado y devuelve sus l&iacute;neas de resultado. El registro
	 * de la JVM hija queda en {@code hija.log} del directorio temporal. */
	private List<String> ejecutarEnJvmHija(final File home) throws IOException, InterruptedException {
		final List<String> cmd = new ArrayList<>();
		cmd.add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath()); //$NON-NLS-1$ //$NON-NLS-2$
		cmd.add("-Djava.awt.headless=true"); //$NON-NLS-1$
		// Configuracion regional con traducciones, para que el registro solo recoja avisos del almacen
		cmd.add("-Duser.language=es"); //$NON-NLS-1$
		cmd.add("-Duser.country=ES"); //$NON-NLS-1$
		cmd.add("-Duser.home=" + home.getAbsolutePath()); //$NON-NLS-1$
		cmd.add("-cp"); //$NON-NLS-1$
		cmd.add(System.getProperty("java.class.path")); //$NON-NLS-1$
		cmd.add(NssFirefoxChromeKeyStoreManagerTest.class.getName());
		final File log = new File(this.tmp.getRoot(), "hija.log"); //$NON-NLS-1$
		final Process p = new ProcessBuilder(cmd).redirectError(log).start();
		final String out = esperar(p, 120, "JVM hija"); //$NON-NLS-1$
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

	/** Punto de entrada de la JVM hija: inicia el almac&eacute;n y, por cada alias, imprime
	 * el sujeto de su certificado y si la firma con su clave se verifica con ese certificado
	 * ({@code sinclave} si no tiene clave privada).
	 * Despu&eacute;s refresca el almac&eacute;n e imprime sus alias y cu&aacute;ntos proveedores
	 * NSS hay registrados.
	 * @param args No se usan.
	 * @throws Exception En cualquier error. */
	public static void main(final String[] args) throws Exception {
		final AOKeyStoreManager ksm = new NssFirefoxChromeKeyStoreManager(null);
		ksm.init(AOKeyStore.MOZ_UNI, null, null, null, false);
		for (final String alias : ksm.getAliases()) {
			final X509Certificate cert = ksm.getCertificate(alias);
			if (!ksm.isKeyEntry(alias)) {
				System.out.println(RESULT + alias + "|" + cert.getSubjectX500Principal().getName() + "|sinclave"); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			final KeyStore.PrivateKeyEntry entry = ksm.getKeyEntry(alias);
			final byte[] data = "datos".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
			final Signature s = Signature.getInstance("SHA256withRSA"); //$NON-NLS-1$
			s.initSign(entry.getPrivateKey());
			s.update(data);
			final byte[] firma = s.sign();
			final Signature v = Signature.getInstance("SHA256withRSA"); //$NON-NLS-1$
			v.initVerify(cert.getPublicKey());
			v.update(data);
			System.out.println(
				RESULT + alias + "|" + cert.getSubjectX500Principal().getName() + "|" + v.verify(firma) //$NON-NLS-1$ //$NON-NLS-2$
			);
		}
		ksm.refresh();
		int registrados = 0;
		for (final Provider p : Security.getProviders()) {
			if (p.getName().startsWith("SunPKCS11-NSSCrypto-AFirma-")) { //$NON-NLS-1$
				registrados++;
			}
		}
		System.out.println(RESULT + "refresco|" + Arrays.toString(ksm.getAliases()) + "|" + registrados); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
