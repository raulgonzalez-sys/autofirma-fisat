/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.keystores.mozilla.linux;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.Provider;

import javax.security.auth.callback.PasswordCallback;

import es.gob.afirma.core.AOCancelledOperationException;
import es.gob.afirma.keystores.AOKeyStore;
import es.gob.afirma.keystores.AOKeyStoreManager;
import es.gob.afirma.keystores.callbacks.UIPasswordCallback;

/** Almac&eacute;n de claves y certificados de una sola base NSS, abierta como una ranura de
 * un proveedor SunPKCS11 ya cargado. */
final class NssSlotKeyStoreManager extends AOKeyStoreManager {

	private final Provider provider;

	private final String passwordPrompt;

	private PasswordCallback passwordCallback = null;

	/** Construye el gestor del almac&eacute;n de una ranura NSS.
	 * @param provider Proveedor SunPKCS11 de la ranura, ya registrado.
	 * @param passwordPrompt Texto del di&aacute;logo que pide la contrase&ntilde;a de la base.
	 * @param parent Elemento padre para la modalidad de los di&aacute;logos. */
	NssSlotKeyStoreManager(final Provider provider, final String passwordPrompt, final Object parent) {
		this.provider = provider;
		this.passwordPrompt = passwordPrompt;
		setParentComponent(parent);
		setKeyStoreType(AOKeyStore.MOZ_UNI);
	}

	/** Abre el almac&eacute;n PKCS#11 de la ranura: primero sin contrase&ntilde;a y, si falla,
	 * con la que devuelva {@code pssCallBack} o, si es nulo, la que se pida al usuario con el
	 * texto indicado al construir el gestor.
	 * Si no se puede abrir, el gestor queda sin almac&eacute;n ({@link #getKeyStore()} nulo).
	 * @throws AOCancelledOperationException Si el usuario cancela el di&aacute;logo de contrase&ntilde;a. */
	@Override
	public void init(final AOKeyStore type,
			         final InputStream store,
			         final PasswordCallback pssCallBack,
			         final Object[] params,
			         final boolean forceReset) {
		this.passwordCallback = pssCallBack;
		resetCachedAliases();
		setKeyStore(null);

		final KeyStore keyStore;
		try {
			keyStore = KeyStore.getInstance("PKCS11", this.provider); //$NON-NLS-1$
		}
		catch (final Exception e) {
			LOGGER.warning("No se ha podido obtener el KeyStore PKCS#11 de " + this.provider.getName() + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}
		try {
			keyStore.load(null, new char[0]);
		}
		catch (final Exception e) {
			LOGGER.info(
				"No se ha podido abrir " + this.provider.getName() + " sin contrasena, se intentara proporcionando una: " + e //$NON-NLS-1$ //$NON-NLS-2$
			);
			try {
				keyStore.load(null, pssCallBack != null
					? pssCallBack.getPassword()
						: new UIPasswordCallback(this.passwordPrompt, getParentComponent()).getPassword());
			}
			catch (final AOCancelledOperationException e1) {
				throw e1;
			}
			catch (final Exception e2) {
				LOGGER.warning("No se ha podido abrir el almacen PKCS#11 de " + this.provider.getName() + ": " + e2); //$NON-NLS-1$ //$NON-NLS-2$
				return;
			}
		}
		setKeyStore(keyStore);
	}

	@Override
	public void refresh() {
		init(getType(), null, this.passwordCallback, null, true);
	}
}
