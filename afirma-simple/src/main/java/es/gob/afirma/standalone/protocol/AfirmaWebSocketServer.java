/* Copyright (C) 2022 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.protocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import es.gob.afirma.core.ErrorCode;
import es.gob.afirma.core.misc.protocol.ProtocolVersion;
import es.gob.afirma.standalone.SimpleErrorCode;
import es.gob.afirma.standalone.protocol.AfirmaWebSocketServerManager.BindingErrorListener;

/**
 * Servidor para la comunicaci&oacute;n por <i>WebSocket</i> acorde a la versi&oacute;n
 * inicial del protocolo.
 */
public class AfirmaWebSocketServer extends WebSocketServer {

	static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

	/** Prefijo de las peticiones de eco. */
	private static final String ECHO_REQUEST_PREFIX = "echo="; //$NON-NLS-1$

	/** Sufijo de las peticiones de eco. */
	private static final String ECHO_REQUEST_SUFFIX = "@EOF"; //$NON-NLS-1$

	/** Prefijo del parametro con el identificador de sesion. */
	private static final String IDSESSION_PARAM_PREFIX = "idsession="; //$NON-NLS-1$

	/** Respuesta que se debe enviar ante las peticiones de echo correctas. */
	private static final String ECHO_OK_RESPONSE = "OK"; //$NON-NLS-1$

	/** Uno de los prefijos que puede presentar el mensaje de invocaci&oacute;n de una firma de lote. Versi&oacute;n 1. */
	private static final String HEADER_BATCH_1 = "afirma://batch?"; //$NON-NLS-1$

	/** Uno de los prefijos que puede presentar el mensaje de invocaci&oacute;n de una firma de lote. Versi&oacute;n 2. */
	private static final String HEADER_BATCH_2 = "afirma://batch/?"; //$NON-NLS-1$

	static final ProtocolVersion MIN_PROTOCOL_VERSION = ProtocolVersion.getInstance(ProtocolVersion.VERSION_3);

	/**
	 * M&aacute;ximo n&uacute;mero de milisegundos que puede estar el websocket abierto
	 * antes de recibir la primera petici&oacute;n.
	 */
	private static final long INITIAL_INACTIVITY_TIMEOUT = 30000;

	private AfirmaWebSocketBindingErrorListener bindingErrorListener = null;

	private Thread innactivityWatcher = null;

	private WebSocket wsClient = null;

	protected String sessionId;

	/**
	 * Genera un servidor websocket que atiende las peticiones de Autofirma.
	 * @param port Puerto a trav&eacute;s del que realizar la comunicaci&oacute;n.
	 * @param sessionId Identificador con el que se deber&aacute; autenticar la web
	 * para usar el servicio.
	 */
	public AfirmaWebSocketServer(final int port, final String sessionId) {
		super(getBindAddress(port));
		setReuseAddr(true);

		this.sessionId = sessionId;

		// Nos aseguramos de que al cierre de la aplicacion, se dejara de escuchar en el puerto
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					AfirmaWebSocketServer.this.stop();
				} catch (final Exception e) {
					LOGGER.log(Level.SEVERE, "No se pudo detener la escucha en el puerto", e); //$NON-NLS-1$
				}
			}
		});
	}

	/**
	 * Obtiene la direcci&oacute;n de escucha del servidor: solo la interfaz de loopback IPv4
	 * (127.0.0.1), para que ning&uacute;n otro equipo de la red pueda conectar.
	 * @param port Puerto de escucha.
	 * @return Direcci&oacute;n 127.0.0.1 con el puerto indicado.
	 */
	static InetSocketAddress getBindAddress(final int port) {
		try {
			// Se fija la IPv4 porque getLoopbackAddress() puede devolver ::1
			return new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port);
		} catch (final UnknownHostException e) {
			throw new IllegalStateException("No se pudo construir la direccion de loopback", e); //$NON-NLS-1$
		}
	}

	/**
	 * Comprueba que una direcci&oacute;n de red sea de loopback (127.0.0.0/8 o ::1).
	 * @param a Direcci&oacute;n de red.
	 * @return {@code true} si la direcci&oacute;n es de loopback, {@code false} en caso contrario.
	 */
	static boolean isLocalAddress(final InetAddress a) {
		return a != null && a.isLoopbackAddress();
	}

	/**
	 * Devuelve el valor del par&aacute;metro "idsession" del mensaje.
	 * @param message Mensaje.
	 * @return Par&aacute;metro "idsession" o {@code null} si no se encontr&oacute; o
	 * no hay mensaje.
	 */
	static String getSessionId(final String message) {
		if (message == null) {
			return null;
		}
		String id = null;
		final int idx = message.indexOf(IDSESSION_PARAM_PREFIX);
		if (idx > -1) {
			final int startIdx = idx + IDSESSION_PARAM_PREFIX.length();
			final int endIdx = message.indexOf("&", startIdx); //$NON-NLS-1$
			if (endIdx > -1) {
				id = message.substring(startIdx, endIdx);
			}
			else {
				id = message.substring(startIdx);
				if (id.endsWith(ECHO_REQUEST_SUFFIX)) {
					id = id.substring(0, id.length() - ECHO_REQUEST_SUFFIX.length());
				}
			}
		}

		return id;
	}

	/**
	 * Comprueba que una petici&oacute;n llegue del propio equipo y, si se espera un
	 * identificador de sesi&oacute;n, que lo incluya.
	 * @param remote Direcci&oacute;n remota de la conexi&oacute;n.
	 * @param expectedSessionId Identificador de sesi&oacute;n esperado o {@code null} si no se exige.
	 * @param message Mensaje recibido.
	 * @return {@code null} si la petici&oacute;n es v&aacute;lida o el c&oacute;digo de error en caso contrario.
	 */
	static ErrorCode checkRequest(final InetSocketAddress remote, final String expectedSessionId, final String message) {
		// Comprobamos que la peticion haya llegado del mismo equipo
		final InetAddress remoteAddress = remote != null ? remote.getAddress() : null;
		if (!isLocalAddress(remoteAddress)) {
			LOGGER.warning("Peticion al socket desde IP externa o sin identificar: " + remote); //$NON-NLS-1$
			return SimpleErrorCode.Communication.EXTERNAL_REQUEST;
		}

		// Si se proporciono un ID de sesion al crear el socket y el ID del
		// mensaje enviado no coincide con el mismo, ignoraremos la peticion
		if (expectedSessionId != null && !expectedSessionId.equals(getSessionId(message))) {
			LOGGER.warning("La peticion no incluia el id de sesion correcto"); //$NON-NLS-1$
			return SimpleErrorCode.Request.INVALID_SESSION_ID;
		}

		return null;
	}

	@Override
	public void onOpen(final WebSocket ws, final ClientHandshake handshake) {
		LOGGER.info("Apertura del socket del puerto " + getAddress().getPort()); //$NON-NLS-1$

		if (this.wsClient == null) {
			this.wsClient = ws;
		}
	}

	/**
	 * Inicia la cuenta atr&aacute;s de inactividad, para que se cierre el socket
	 * (y la aplicaci&oacute;n) si no se recibe ninguna petici&oacute;n en un
	 * determinado tiempo.
	 */
	public void initInactivityCountdown() {
		this.innactivityWatcher = new InnactivityWatcherThread(this.wsClient, INITIAL_INACTIVITY_TIMEOUT);
		this.innactivityWatcher.start();
	}

	/**
	 * Se&ntilde;ala que el websocket est&aacute; operativo y recibiendo peticiones.
	 * Si no se llama a este m&eacute;todo despues de haber iniciado el websocket,
	 * se cerrara el websocket pasado un cierto tiempo. Se hace asi para evitar que
	 * la aplicaci&oacute;Mn se quede abierta indefinidamente sin recibir jam&aacute;s
	 * una petici&oacute;n desde el navegador.
	 */
	protected void markAsWorking() {

		if (this.innactivityWatcher != null) {
			this.innactivityWatcher.interrupt();
			this.innactivityWatcher = null;
		}
	}

	@Override
	public void onClose(final WebSocket ws, final int code, final String reason, final boolean remote) {
		// Si se cierra el socket es que se ha terminado de operar con la aplicacion y puede cerrarse.
		// Sin embargo, comprobamos que sea este el primer cliente que lo abrio y no otra instancia
		// de la aplicacion que luego ha intentado acceder a el
		LOGGER.info("Se ha cerrado la comunicacion con el socket del puerto " + getAddress().getPort() + " . Codigo: " + code + ": " + reason); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (this.wsClient == null || this.wsClient.equals(ws)) {
			LOGGER.info("Cerramos la aplicacion"); //$NON-NLS-1$
			Runtime.getRuntime().halt(0);
		}
	}

	@Override
	public void onStart() {
		LOGGER.fine("Inicio del socket"); //$NON-NLS-1$
	}

	@Override
	public void onMessage(final WebSocket ws, final String message) {
		LOGGER.info("Recibimos una peticion en el socket del puerto: " + getAddress().getPort()); //$NON-NLS-1$

		// Comprobamos el origen y el id de sesion de la peticion
		final ErrorCode requestError = checkRequest(ws.getRemoteSocketAddress(), this.sessionId, message);
		if (requestError != null) {
			broadcast(ProtocolInvocationLauncherErrorManager.getErrorMessage(MIN_PROTOCOL_VERSION, requestError), Collections.singletonList(ws));
			return;
		}

		// Indicamos que el socket esta operativo y recibiendo peticiones
		// para evitar el cierre de seguridad
		markAsWorking();

		// Si recibimos en el socket un eco, lo respondemos con un OK
		if (message.startsWith(ECHO_REQUEST_PREFIX)) {
			broadcast(ECHO_OK_RESPONSE, Collections.singletonList(ws));
		}
		// Si recibimos cualquier cosa distinta de un eco, consideraremos que es una peticion de
		// operacion y la procesaremos como tal
		else {
			// Si se trata de una operacion de firma de lote, incrementamos el tiempo de timeout
			final boolean batchOperation = message.startsWith(HEADER_BATCH_1) || message.startsWith(HEADER_BATCH_2);
			setConnectionLostTimeout(batchOperation ? 240 : 60);
			// Ejecutamos la peticion y devolvemos el resultado
			broadcast(ProtocolInvocationLauncher.launch(message, MIN_PROTOCOL_VERSION, true), Collections.singletonList(ws));
		}
	}

	@Override
	public void onError(final WebSocket ws, final Exception ex) {
		LOGGER.log(Level.SEVERE, "Error en el socket del puerto " + getAddress().getPort(), ex); //$NON-NLS-1$
		if (ex instanceof java.net.BindException && this.bindingErrorListener != null) {
			LOGGER.severe("Se identifica como un error en la apertura del puerto " + getAddress().getPort() //$NON-NLS-1$
					+ ". Se reintenta con el siguiente puerto disponible"); //$NON-NLS-1$

			if (this.innactivityWatcher != null && this.innactivityWatcher.isAlive()) {
				this.innactivityWatcher.interrupt();
			}
			this.bindingErrorListener.onErrorBinding();
		}
	}

	public void setBindingErrorListener(final BindingErrorListener bindingErrorListener) {
		this.bindingErrorListener = bindingErrorListener;
	}


	/**
	 * Hilo para el cierre del websocket pasado un cierto tiempo.
	 */
	private class InnactivityWatcherThread extends Thread {

		private final WebSocket websocket;
		private final long timeoutMilis;

		/**
		 * Inicia el hilo que cerrar&aacute; el websocket.
		 * @param websocket Websocket que debe cerrarse.
		 * @param timeoutMilis N&uacute;nero de milisegundos que se debe esperar antes del cierre.
		 */
		public InnactivityWatcherThread(final WebSocket websocket, final long timeoutMilis) {
			this.websocket = websocket;
			this.timeoutMilis = timeoutMilis;
		}

		@Override
		public void run() {
			// Esperamos la cantidad de milisegundos indicada
			try {
				Thread.sleep(this.timeoutMilis);
				//wait(this.timeoutMilis);
			} catch (final InterruptedException e) {
				// Si se interrumpe el hilo, se termina la ejecucion
				// sin llegar a cerrar el socket
				return;
			}

			// Si no se ha interrumpido este hilo es porque no se ha recibido ninguna
			// peticion en el socket, asi que se cerrara el Websocket para que se cierre
			// a su vez la aplicacion y no se quede en segundo plano indefinidamente
			if (!isInterrupted()) {
				onClose(
						this.websocket,
						CloseFrame.NEVER_CONNECTED,
						"Se ha cumplido el tiempo maximo sin recibir la primera peticion en el socket", //$NON-NLS-1$
						false);
			}
		}
	}
}
