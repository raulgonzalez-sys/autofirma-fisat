/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.ui.restoreconfig.linux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Informe de una restauraci&oacute;n en Linux: cada paso deja una l&iacute;nea
 * {@code OK}, {@code AVISO} o {@code ERROR} que se guarda y se pasa a un consumidor. */
public final class RestoreReport {

	/** Prefijo de las l&iacute;neas de pasos correctos. */
	public static final String OK = "OK: "; //$NON-NLS-1$

	/** Prefijo de las l&iacute;neas de pasos saltados o pendientes. */
	public static final String WARNING = "AVISO: "; //$NON-NLS-1$

	/** Prefijo de las l&iacute;neas de pasos fallidos. */
	public static final String ERROR = "ERROR: "; //$NON-NLS-1$

	private final Consumer<String> out;
	private final List<String> lines = new ArrayList<>();
	private boolean errors = false;

	/** Crea un informe.
	 * @param out Consumidor que recibe cada l&iacute;nea en cuanto se a&ntilde;ade (puede ser nulo). */
	public RestoreReport(final Consumer<String> out) {
		this.out = out;
	}

	/** A&ntilde;ade una l&iacute;nea {@code OK}.
	 * @param message Mensaje en castellano. */
	public void ok(final String message) {
		add(OK + message);
	}

	/** A&ntilde;ade una l&iacute;nea {@code AVISO}.
	 * @param message Mensaje en castellano. */
	public void warning(final String message) {
		add(WARNING + message);
	}

	/** A&ntilde;ade una l&iacute;nea {@code ERROR}.
	 * @param message Mensaje en castellano. */
	public void error(final String message) {
		this.errors = true;
		add(ERROR + message);
	}

	private synchronized void add(final String line) {
		this.lines.add(line);
		if (this.out != null) {
			this.out.accept(line);
		}
	}

	/** Indica si alg&uacute;n paso ha dejado una l&iacute;nea {@code ERROR}.
	 * @return {@code true} si hay errores. */
	public synchronized boolean hasErrors() {
		return this.errors;
	}

	/** Obtiene las l&iacute;neas a&ntilde;adidas hasta ahora.
	 * @return Copia inmutable de las l&iacute;neas. */
	public synchronized List<String> getLines() {
		return Collections.unmodifiableList(new ArrayList<>(this.lines));
	}
}
