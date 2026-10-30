/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.keystores.mozilla.linux;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.callback.PasswordCallback;

import es.gob.afirma.core.AOCancelledOperationException;
import es.gob.afirma.core.misc.AOUtil;
import es.gob.afirma.keystores.AOKeyStore;
import es.gob.afirma.keystores.AggregatedKeyStoreManager;
import es.gob.afirma.keystores.mozilla.MozillaKeyStoreUtilities;

/** Almac&eacute;n NSS de Linux que re&uacute;ne en una sola lista los certificados de la base
 * del perfil activo de Firefox y de la base de Chrome ({@link NssLinuxDatabases#findDatabases(File)}).
 * Cada base es una ranura de una &uacute;nica inicializaci&oacute;n de NSS
 * ({@link NssMultiSlotLoader#load(String, List)}), as&iacute; que los proveedores se crean una
 * vez por proceso y se reutilizan al reiniciar o refrescar el almac&eacute;n.
 * Un certificado presente en las dos bases (misma huella SHA-256) aparece una sola vez, tomado
 * de Firefox. Si dos certificados distintos tienen el mismo alias, el segundo se muestra con
 * un sufijo ({@code alias (2)}) para que cada alias firme con su propia clave. */
public final class NssFirefoxChromeKeyStoreManager extends AggregatedKeyStoreManager {

	/** Gestores de cada ranura, en el orden de las bases (Firefox primero). */
	private final List<NssSlotKeyStoreManager> slots = new ArrayList<>();

	/** Alias visibles y la ranura y alias real a los que corresponden ({@code null} si hay
	 * que volver a calcularlos). */
	private Map<String, SlotEntry> entries = null;

	/** Construye el almac&eacute;n de Firefox y Chrome.
	 * @param parent Elemento padre para la modalidad de los di&aacute;logos. */
	public NssFirefoxChromeKeyStoreManager(final Object parent) {
		setParentComponent(parent);
		setKeyStoreType(AOKeyStore.MOZ_UNI);
	}

	/** Abre las bases de Firefox y Chrome del usuario y agrega un almac&eacute;n por cada una
	 * que se pueda abrir. Una base que no se abre (o cuya contrase&ntilde;a se cancela) se
	 * omite y se contin&uacute;a con las dem&aacute;s. */
	@Override
	public void init(final AOKeyStore type,
			         final InputStream store,
			         final PasswordCallback pssCallBack,
			         final Object[] params,
			         final boolean forceReset) {
		removeAll();
		this.slots.clear();
		this.entries = null;
		setKeyStore(null);
		setKeyStoreType(AOKeyStore.MOZ_UNI);

		for (final Provider provider : loadProviders()) {
			final NssSlotKeyStoreManager slot = new NssSlotKeyStoreManager(provider, getParentComponent());
			try {
				slot.init(AOKeyStore.MOZ_UNI, null, pssCallBack, params, forceReset);
			}
			catch (final AOCancelledOperationException e) {
				LOGGER.info("Se cancelo la apertura de " + provider.getName() + ", se continua con el resto: " + e); //$NON-NLS-1$ //$NON-NLS-2$
				continue;
			}
			if (slot.getKeyStore() == null) {
				continue;
			}
			if (getKeyStore() == null) {
				setKeyStore(slot.getKeyStore());
			}
			this.slots.add(slot);
			addKeyStoreManager(slot);
		}
		if (this.slots.isEmpty()) {
			LOGGER.warning("No se ha podido abrir ninguna base NSS de Firefox o Chrome"); //$NON-NLS-1$
		}
	}

	private static List<Provider> loadProviders() {
		final List<File> dbs = NssLinuxDatabases.findDatabases(new File(System.getProperty("user.home"))); //$NON-NLS-1$
		if (dbs.isEmpty()) {
			LOGGER.info("No se han encontrado bases NSS de Firefox ni de Chrome"); //$NON-NLS-1$
			return Collections.emptyList();
		}
		try {
			return NssMultiSlotLoader.load(MozillaKeyStoreUtilities.getSystemNSSLibDir(), dbs);
		}
		catch (final IOException e) {
			LOGGER.warning("No se ha encontrado NSS en el sistema: " + e); //$NON-NLS-1$
			return Collections.emptyList();
		}
	}

	@Override
	public void refresh() throws IOException {
		super.refresh();
		this.entries = null;
	}

	@Override
	public void deactivateEntry(final String certificateThumbprint) {
		super.deactivateEntry(certificateThumbprint);
		this.entries = null;
	}

	/** Devuelve los alias de todas las bases, sin certificados repetidos por huella SHA-256. */
	@Override
	public String[] getAliases() {
		this.entries = buildEntries();
		return this.entries.keySet().toArray(new String[0]);
	}

	private Map<String, SlotEntry> buildEntries() {
		final Map<String, SlotEntry> ret = new LinkedHashMap<>();
		final Set<String> thumbprints = new HashSet<>();
		for (final NssSlotKeyStoreManager slot : this.slots) {
			final String[] aliases;
			try {
				aliases = slot.getAliases();
			}
			catch (final Exception e) {
				LOGGER.warning("No se pudieron obtener los alias de una base NSS: " + e); //$NON-NLS-1$
				continue;
			}
			for (final String alias : aliases) {
				final String thumbprint = sha256(slot.getCertificate(alias));
				if (thumbprint != null && !thumbprints.add(thumbprint)) {
					continue;
				}
				String visible = alias;
				for (int n = 2; ret.containsKey(visible); n++) {
					visible = alias + " (" + n + ")"; //$NON-NLS-1$ //$NON-NLS-2$
				}
				ret.put(visible, new SlotEntry(slot, alias));
			}
		}
		return ret;
	}

	private static String sha256(final X509Certificate cert) {
		if (cert == null) {
			return null;
		}
		try {
			return AOUtil.hexify(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()), false); //$NON-NLS-1$
		}
		catch (final NoSuchAlgorithmException | CertificateEncodingException e) {
			LOGGER.warning("No se ha podido calcular la huella de un certificado, puede aparecer repetido: " + e); //$NON-NLS-1$
			return null;
		}
	}

	private SlotEntry resolve(final String alias) {
		if (this.entries == null) {
			this.entries = buildEntries();
		}
		return alias != null ? this.entries.get(alias) : null;
	}

	@Override
	public X509Certificate getCertificate(final String alias) {
		final SlotEntry e = resolve(alias);
		if (e == null) {
			LOGGER.warning("El almacen no contiene ningun certificado con el alias especificado, se devolvera null"); //$NON-NLS-1$
			return null;
		}
		return e.slot.getCertificate(e.alias);
	}

	@Override
	public X509Certificate[] getCertificateChain(final String alias) {
		final SlotEntry e = resolve(alias);
		if (e == null) {
			LOGGER.warning("El almacen no contiene ninguna cadena con el alias especificado, se devolvera una vacia"); //$NON-NLS-1$
			return new X509Certificate[0];
		}
		return e.slot.getCertificateChain(e.alias);
	}

	@Override
	public KeyStore.PrivateKeyEntry getKeyEntry(final String alias) throws KeyStoreException,
	                                                                       NoSuchAlgorithmException,
	                                                                       UnrecoverableEntryException {
		final SlotEntry e = resolve(alias);
		if (e == null) {
			LOGGER.warning("El almacen no contiene ninguna clave con el alias especificado, se devolvera null"); //$NON-NLS-1$
			return null;
		}
		return e.slot.getKeyEntry(e.alias);
	}

	@Override
	public boolean isKeyEntry(final String alias) throws KeyStoreException {
		final SlotEntry e = resolve(alias);
		if (e == null) {
			throw new KeyStoreException(
				"Se ha pedido comprobar la clave privada de un certificado no contenido en este gestor" //$NON-NLS-1$
			);
		}
		return e.slot.isKeyEntry(e.alias);
	}

	@Override
	protected AOKeyStore getType(final String alias) {
		return getType();
	}

	/** Ranura y alias real que corresponden a un alias visible. */
	private static final class SlotEntry {
		final NssSlotKeyStoreManager slot;
		final String alias;

		SlotEntry(final NssSlotKeyStoreManager slot, final String alias) {
			this.slot = slot;
			this.alias = alias;
		}
	}
}
