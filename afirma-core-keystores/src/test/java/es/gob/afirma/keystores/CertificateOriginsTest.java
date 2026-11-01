package es.gob.afirma.keystores;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** Pruebas del origen que se a&ntilde;ade al nombre mostrado de cada certificado. */
public final class CertificateOriginsTest {

	/** Almac&eacute;n en memoria que cuenta las veces que se piden sus alias. */
	private static class Almacen extends AOKeyStoreManager {
		private final String[] aliases;
		int llamadas = 0;

		Almacen(final String... aliases) {
			this.aliases = aliases;
			setKeyStoreType(AOKeyStore.PKCS12);
		}

		@Override
		public String[] getAliases() {
			this.llamadas++;
			return this.aliases.clone();
		}
	}

	/** Almac&eacute;n que indica el origen de sus certificados. */
	private static final class AlmacenConOrigen extends Almacen implements CertificateOriginProvider {
		private final Map<String, String> origenes;

		AlmacenConOrigen(final Map<String, String> origenes, final String... aliases) {
			super(aliases);
			this.origenes = origenes;
		}

		@Override
		public String getCertificateOrigin(final String alias) {
			return this.origenes.get(alias);
		}
	}

	/** Con los agregados anidados que monta el di&aacute;logo, cada nombre lleva el origen que
	 * da el almac&eacute;n que resuelve su alias (el primero que lo contiene), los que no tienen
	 * origen quedan igual y los alias de cada almac&eacute;n se piden una vez por cada nivel de
	 * agregado que lo contiene, no una vez por certificado. */
	@Test
	public void origenesEnUnaPasada() {
		final Map<String, String> origenes = new HashMap<>();
		origenes.put("a", "Firefox"); //$NON-NLS-1$ //$NON-NLS-2$
		origenes.put("b", "Firefox, Chrome"); //$NON-NLS-1$ //$NON-NLS-2$
		origenes.put("comun", "Chrome"); //$NON-NLS-1$ //$NON-NLS-2$
		final Almacen otro = new Almacen("p1", "comun"); //$NON-NLS-1$ //$NON-NLS-2$
		final String[] aliasNss = new String[24];
		final Map<String, String> nombres = new HashMap<>();
		final Map<String, String> esperado = new HashMap<>();
		for (int i = 0; i < 20; i++) {
			aliasNss[i] = "x" + i; //$NON-NLS-1$
			nombres.put(aliasNss[i], "X" + i); //$NON-NLS-1$
			esperado.put(aliasNss[i], "X" + i); //$NON-NLS-1$
		}
		aliasNss[20] = "a"; //$NON-NLS-1$
		aliasNss[21] = "b"; //$NON-NLS-1$
		aliasNss[22] = "comun"; //$NON-NLS-1$
		aliasNss[23] = "sin"; //$NON-NLS-1$
		final AlmacenConOrigen nss = new AlmacenConOrigen(origenes, aliasNss);
		final AggregatedKeyStoreManager mozilla = new AggregatedKeyStoreManager(otro);
		mozilla.addKeyStoreManager(nss);
		final AggregatedKeyStoreManager dialogo = new AggregatedKeyStoreManager(mozilla);

		nombres.put("p1", "P1"); //$NON-NLS-1$ //$NON-NLS-2$
		nombres.put("a", "A"); //$NON-NLS-1$ //$NON-NLS-2$
		nombres.put("b", "B"); //$NON-NLS-1$ //$NON-NLS-2$
		nombres.put("comun", "C"); //$NON-NLS-1$ //$NON-NLS-2$
		nombres.put("sin", "S"); //$NON-NLS-1$ //$NON-NLS-2$

		esperado.put("p1", "P1"); //$NON-NLS-1$ //$NON-NLS-2$
		esperado.put("a", "A (Firefox)"); //$NON-NLS-1$ //$NON-NLS-2$
		esperado.put("b", "B (Firefox, Chrome)"); //$NON-NLS-1$ //$NON-NLS-2$
		esperado.put("comun", "C"); //$NON-NLS-1$ //$NON-NLS-2$
		esperado.put("sin", "S"); //$NON-NLS-1$ //$NON-NLS-2$

		otro.llamadas = 0;
		nss.llamadas = 0;
		assertEquals(esperado, CertificateOrigins.withOrigins(dialogo, nombres));
		// Dos niveles de agregado (dialogo y mozilla) sobre cada almacen
		assertTrue("Alias de NSS pedidos " + nss.llamadas + " veces", nss.llamadas <= 2); //$NON-NLS-1$ //$NON-NLS-2$
		assertTrue("Alias del otro almacen pedidos " + otro.llamadas + " veces", otro.llamadas <= 2); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
