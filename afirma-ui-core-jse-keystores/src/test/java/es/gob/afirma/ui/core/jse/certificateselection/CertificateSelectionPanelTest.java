package es.gob.afirma.ui.core.jse.certificateselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import es.gob.afirma.core.keystores.NameCertificateBean;

/** Pruebas de la alineacion entre las lineas mostradas y los certificados del selector. */
public final class CertificateSelectionPanelTest {

	private static final String CERT_PATH = "multi_almacen.p12"; //$NON-NLS-1$
	private static final String CERT_PASS = "1111"; //$NON-NLS-1$

	private static NameCertificateBean a;
	private static NameCertificateBean b;
	// Sin cadena de certificacion: su linea falla al construirse
	private static NameCertificateBean x;

	/** Prepara el entorno grafico y los certificados de prueba.
	 * @throws Exception En cualquier error. */
	@BeforeClass
	public static void setUp() throws Exception {
		System.setProperty("java.awt.headless", "true"); //$NON-NLS-1$ //$NON-NLS-2$

		final KeyStore ks = KeyStore.getInstance("PKCS12"); //$NON-NLS-1$
		try (final InputStream is = CertificateSelectionPanelTest.class.getClassLoader().getResourceAsStream(CERT_PATH)) {
			ks.load(is, CERT_PASS.toCharArray());
		}

		final List<NameCertificateBean> beans = new ArrayList<>();
		final Enumeration<String> aliases = ks.aliases();
		while (aliases.hasMoreElements() && beans.size() < 2) {
			final String alias = aliases.nextElement();
			final Certificate[] chain = ks.getCertificateChain(alias);
			if (chain == null) {
				continue;
			}
			final X509Certificate[] x509Chain = new X509Certificate[chain.length];
			for (int i = 0; i < chain.length; i++) {
				x509Chain[i] = (X509Certificate) chain[i];
			}
			beans.add(new NameCertificateBean(alias, beans.isEmpty() ? "nombreA" : "nombreB", x509Chain)); //$NON-NLS-1$ //$NON-NLS-2$
		}
		a = beans.get(0);
		b = beans.get(1);
		x = new NameCertificateBean("aliasX", "nombreX", null); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private static CertificateSelectionPanel newPanel(final NameCertificateBean... beans) {
		return new CertificateSelectionPanel(beans, null, null, null, false, false, new int[0]);
	}

	/** Comprueba que el panel solo expone los certificados cuya linea se mostro. */
	@Test
	public void panelExposesOnlyShownBeans() {
		final CertificateSelectionPanel panel = newPanel(a, x, b);
		assertEquals(2, panel.getShowedCertsCount());
		assertEquals(Arrays.asList(a, b), Arrays.asList(panel.getCertificateBeans()));
		assertEquals(a.getAlias(), panel.getSelectedCertificateAlias());
	}

	/** Comprueba que al refrescar se realinean los certificados con las lineas. */
	@Test
	public void refreshRealignsBeans() {
		final CertificateSelectionPanel panel = newPanel(a, x, b);
		panel.refresh(new NameCertificateBean[] { x, b });
		assertEquals(1, panel.getShowedCertsCount());
		assertEquals(b, panel.getCertificateBeans()[0]);
		assertEquals(b.getAlias(), panel.getSelectedCertificateAlias());
	}

	/** Comprueba que si todas las lineas fallan no se muestra ni se selecciona nada. */
	@Test
	public void allLinesFailYieldsZeroShown() {
		final CertificateSelectionPanel panel = newPanel(x);
		assertEquals(0, panel.getShowedCertsCount());
		assertNull(panel.getSelectedCertificateAlias());
	}

	/** Comprueba que las lineas creadas y los certificados devueltos mantienen orden y alineacion. */
	@Test
	public void createCertLinesKeepsLinesAndBeansAligned() {
		final List<NameCertificateBean> out = new ArrayList<>();
		final List<CertificateLine> lines = CertificateSelectionPanel.createCertLines(
				new NameCertificateBean[] { a, x, b }, CertificateLineView.PERSONAL, out);
		assertEquals(2, lines.size());
		assertEquals(Arrays.asList(a, b), out);
		for (int i = 0; i < lines.size(); i++) {
			assertEquals(out.get(i).getName(), lines.get(i).toString());
		}
	}
}
