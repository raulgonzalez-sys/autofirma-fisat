package es.gob.afirma.ui.core.jse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.swing.UIManager;

import es.gob.afirma.core.misc.Platform;
import es.gob.afirma.core.ui.GenericFileFilter;

/** Di&aacute;logos de selecci&oacute;n de ficheros nativos de Linux (kdialog o zenity).
 * Devuelve un resultado tri-estado para que quien llama pueda recurrir al
 * <code>JFileChooser</code> cuando no hay herramienta nativa o esta falla. */
final class NativeFileDialogs {

	/** Variable de entorno que, con valor <code>java</code>, fuerza el <code>JFileChooser</code>. */
	static final String ENV_SWITCH = "AUTOFIRMA_FISAT_DIALOGOS"; //$NON-NLS-1$

	/** Propiedad del sistema que, con valor <code>java</code>, fuerza el <code>JFileChooser</code>. */
	static final String PROPERTY_SWITCH = "es.gob.afirma.fisat.dialogos"; //$NON-NLS-1$

	private static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

	private static final String FORCE_JAVA = "java"; //$NON-NLS-1$

	/** Variables que alteran los c&oacute;digos de salida de zenity. */
	private static final String[] ZENITY_VARIABLES = {
		"ZENITY_OK", "ZENITY_CANCEL", "ZENITY_ESC", "ZENITY_ERROR", "ZENITY_TIMEOUT" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
	};

	private static final int EXIT_ACCEPTED = 0;
	private static final int EXIT_CANCELLED = 1;

	/** Herramienta nativa de selecci&oacute;n de ficheros. */
	enum Tool {
		/** kdialog (KDE). */
		KDIALOG("kdialog"), //$NON-NLS-1$
		/** zenity (GTK). */
		ZENITY("zenity"); //$NON-NLS-1$

		private final String executable;

		Tool(final String executable) {
			this.executable = executable;
		}

		/** Nombre del ejecutable en el <code>PATH</code>.
		 * @return Nombre del ejecutable. */
		String getExecutable() {
			return this.executable;
		}
	}

	/** Tipo de di&aacute;logo. */
	enum Mode {
		/** Abrir uno o varios ficheros. */
		OPEN,
		/** Guardar un fichero. */
		SAVE,
		/** Seleccionar una carpeta. */
		DIRECTORY
	}

	/** Estado del resultado de un di&aacute;logo. */
	enum Status {
		/** El usuario acept&oacute; y hay al menos un fichero. */
		ACCEPTED,
		/** El usuario cancel&oacute;. */
		CANCELLED,
		/** No hay di&aacute;logo nativo o fall&oacute;: hay que usar el de Java. */
		UNAVAILABLE
	}

	/** Resultado de un di&aacute;logo nativo. */
	static final class Result {

		/** Estado del di&aacute;logo. */
		final Status status;

		/** Ficheros seleccionados; nunca <code>null</code> y vac&iacute;o salvo en {@link Status#ACCEPTED}. */
		final File[] files;

		Result(final Status status, final File[] files) {
			this.status = status;
			this.files = files != null ? files : new File[0];
		}
	}

	private static final Result CANCELLED = new Result(Status.CANCELLED, null);
	private static final Result UNAVAILABLE = new Result(Status.UNAVAILABLE, null);

	private final Platform.OS os;
	private final Map<String, String> env;
	private final String property;

	/** Crea el gestor de di&aacute;logos nativos con el estado indicado.
	 * @param os Sistema operativo.
	 * @param env Variables de entorno.
	 * @param property Valor de la propiedad {@link #PROPERTY_SWITCH} o <code>null</code>. */
	NativeFileDialogs(final Platform.OS os, final Map<String, String> env, final String property) {
		this.os = os;
		this.env = env != null ? env : Collections.<String, String>emptyMap();
		this.property = property;
	}

	/** Crea el gestor de di&aacute;logos nativos con el estado del sistema.
	 * @return Gestor de di&aacute;logos nativos. */
	static NativeFileDialogs fromSystem() {
		return new NativeFileDialogs(Platform.getOS(), System.getenv(), System.getProperty(PROPERTY_SWITCH));
	}

	/** Muestra un di&aacute;logo nativo. Nunca lanza excepciones: ante cualquier problema
	 * devuelve {@link Status#UNAVAILABLE}.
	 * @param mode Tipo de di&aacute;logo.
	 * @param multi Si se permite seleccionar varios ficheros (solo al abrir).
	 * @param title T&iacute;tulo; si es <code>null</code> o vac&iacute;o se usa uno por defecto.
	 * @param start Fichero o carpeta inicial.
	 * @param filters Filtros de fichero o <code>null</code>.
	 * @param defaultFilter Filtro por defecto o <code>null</code>.
	 * @return Resultado del di&aacute;logo. */
	Result show(final Mode mode,
			    final boolean multi,
			    final String title,
			    final File start,
			    final List<GenericFileFilter> filters,
			    final GenericFileFilter defaultFilter) {
		try {
			final Tool tool = chooseTool(this.os, this.env, this.property);
			if (tool == null) {
				return UNAVAILABLE;
			}
			final File executable = findInPath(tool.getExecutable(), this.env.get("PATH")); //$NON-NLS-1$
			if (executable == null) {
				return UNAVAILABLE;
			}

			String dialogTitle = title;
			if (dialogTitle == null || dialogTitle.trim().isEmpty()) {
				dialogTitle = JSEUIMessages.getString(mode == Mode.SAVE ? "JSEUIManager.85" : "JSEUIManager.96"); //$NON-NLS-1$ //$NON-NLS-2$
			}

			String allFilesText = UIManager.getString("FileChooser.acceptAllFileFilterText"); //$NON-NLS-1$
			if (allFilesText == null) {
				allFilesText = "*"; //$NON-NLS-1$
			}

			final List<String> command = buildCommand(
					tool,
					executable.getPath(),
					mode,
					multi,
					dialogTitle,
					start,
					orderFilters(filters, defaultFilter, allFilesText));

			LOGGER.info("Se usa el dialogo de ficheros nativo: " + executable.getPath()); //$NON-NLS-1$
			return run(command, multi && mode == Mode.OPEN);
		}
		catch (final RuntimeException e) {
			LOGGER.warning("Error en el dialogo de ficheros nativo, se usara el de Java: " + e); //$NON-NLS-1$
			return UNAVAILABLE;
		}
	}

	/** Elige la herramienta nativa disponible.
	 * @param os Sistema operativo.
	 * @param env Variables de entorno.
	 * @param property Valor de la propiedad {@link #PROPERTY_SWITCH} o <code>null</code>.
	 * @return Herramienta o <code>null</code> si hay que usar el <code>JFileChooser</code>. */
	static Tool chooseTool(final Platform.OS os, final Map<String, String> env, final String property) {
		if (os != Platform.OS.LINUX) {
			return null;
		}
		final Map<String, String> e = env != null ? env : Collections.<String, String>emptyMap();
		if (isForceJava(e.get(ENV_SWITCH)) || isForceJava(property)) {
			return null;
		}
		final String path = e.get("PATH"); //$NON-NLS-1$
		if (isKde(e.get("XDG_CURRENT_DESKTOP")) && findInPath(Tool.KDIALOG.getExecutable(), path) != null) { //$NON-NLS-1$
			return Tool.KDIALOG;
		}
		if (findInPath(Tool.ZENITY.getExecutable(), path) != null) {
			return Tool.ZENITY;
		}
		return null;
	}

	private static boolean isForceJava(final String value) {
		return value != null && FORCE_JAVA.equalsIgnoreCase(value.trim());
	}

	private static boolean isKde(final String desktop) {
		if (desktop == null) {
			return false;
		}
		for (final String d : desktop.split(":")) { //$NON-NLS-1$
			if ("KDE".equalsIgnoreCase(d)) { //$NON-NLS-1$
				return true;
			}
		}
		return false;
	}

	/** Busca un ejecutable en las carpetas de un <code>PATH</code>.
	 * @param name Nombre del ejecutable.
	 * @param path Valor del <code>PATH</code> o <code>null</code>.
	 * @return Fichero ejecutable o <code>null</code> si no se encuentra. */
	static File findInPath(final String name, final String path) {
		if (path == null) {
			return null;
		}
		for (final String dir : path.split(File.pathSeparator)) {
			if (dir.isEmpty()) {
				continue;
			}
			final File f = new File(dir, name);
			if (f.isFile() && f.canExecute()) {
				return f;
			}
		}
		return null;
	}

	/** Ordena los filtros como el <code>JFileChooser</code>: el filtro por defecto
	 * primero y el de todos los archivos al final, o este primero si no hay filtro
	 * por defecto. Se descartan los filtros sin extensiones.
	 * @param filters Filtros o <code>null</code>.
	 * @param defaultFilter Filtro por defecto o <code>null</code>.
	 * @param allFilesText Descripci&oacute;n del filtro de todos los archivos.
	 * @return Filtros ordenados; el de todos los archivos tiene extensiones <code>null</code>. */
	static List<GenericFileFilter> orderFilters(final List<GenericFileFilter> filters,
			                                    final GenericFileFilter defaultFilter,
			                                    final String allFilesText) {
		final List<GenericFileFilter> ordered = new ArrayList<>();
		if (filters == null) {
			return ordered;
		}
		boolean defaultFound = false;
		for (final GenericFileFilter f : filters) {
			if (f == null || normalizeExtensions(f.getExtensions()).isEmpty()) {
				continue;
			}
			if (!defaultFound && defaultFilter != null && defaultFilter.equals(f)) {
				ordered.add(0, f);
				defaultFound = true;
			}
			else {
				ordered.add(f);
			}
		}
		if (!ordered.isEmpty()) {
			final GenericFileFilter all = new GenericFileFilter(null, allFilesText);
			if (defaultFound) {
				ordered.add(all);
			}
			else {
				ordered.add(0, all);
			}
		}
		return ordered;
	}

	/** Construye la l&iacute;nea de &oacute;rdenes de la herramienta nativa.
	 * @param tool Herramienta.
	 * @param executable Ruta del ejecutable.
	 * @param mode Tipo de di&aacute;logo.
	 * @param multi Si se permite seleccionar varios ficheros (solo cuenta al abrir).
	 * @param title T&iacute;tulo del di&aacute;logo.
	 * @param start Fichero o carpeta inicial o <code>null</code>.
	 * @param orderedFilters Filtros ya ordenados con {@link #orderFilters(List, GenericFileFilter, String)}.
	 * @return L&iacute;nea de &oacute;rdenes. */
	static List<String> buildCommand(final Tool tool,
			                         final String executable,
			                         final Mode mode,
			                         final boolean multi,
			                         final String title,
			                         final File start,
			                         final List<GenericFileFilter> orderedFilters) {
		final boolean multiple = multi && mode == Mode.OPEN;
		final List<GenericFileFilter> filters = mode == Mode.DIRECTORY || orderedFilters == null
				? Collections.<GenericFileFilter>emptyList()
				: orderedFilters;
		final List<String> command = new ArrayList<>();
		command.add(executable);
		if (tool == Tool.KDIALOG) {
			if (title != null) {
				command.add("--title"); //$NON-NLS-1$
				command.add(title);
			}
			if (multiple) {
				command.add("--multiple"); //$NON-NLS-1$
				command.add("--separate-output"); //$NON-NLS-1$
			}
			if (mode == Mode.SAVE) {
				command.add("--getsavefilename"); //$NON-NLS-1$
			}
			else if (mode == Mode.DIRECTORY) {
				command.add("--getexistingdirectory"); //$NON-NLS-1$
			}
			else {
				command.add("--getopenfilename"); //$NON-NLS-1$
			}
			if (start != null) {
				command.add(start.getPath());
				if (!filters.isEmpty()) {
					final StringBuilder sb = new StringBuilder();
					for (final GenericFileFilter f : filters) {
						if (sb.length() > 0) {
							sb.append('|');
						}
						final String patterns = patterns(f);
						sb.append(description(f, patterns).replace("\\", "\\\\")) //$NON-NLS-1$ //$NON-NLS-2$
						  .append(" (").append(patterns).append(')'); //$NON-NLS-1$
					}
					command.add(sb.toString());
				}
			}
		}
		else {
			command.add("--file-selection"); //$NON-NLS-1$
			if (title != null) {
				command.add("--title=" + title); //$NON-NLS-1$
			}
			if (multiple) {
				command.add("--multiple"); //$NON-NLS-1$
				command.add("--separator=\n"); //$NON-NLS-1$
			}
			if (mode == Mode.SAVE) {
				command.add("--save"); //$NON-NLS-1$
			}
			else if (mode == Mode.DIRECTORY) {
				command.add("--directory"); //$NON-NLS-1$
			}
			if (start != null) {
				String startPath = start.getPath();
				// Con barra final zenity abre la carpeta en lugar de proponerla como nombre
				if ((mode == Mode.DIRECTORY || start.isDirectory()) && !startPath.endsWith("/")) { //$NON-NLS-1$
					startPath = startPath + "/"; //$NON-NLS-1$
				}
				command.add("--filename=" + startPath); //$NON-NLS-1$
			}
			for (final GenericFileFilter f : filters) {
				final String patterns = patterns(f);
				command.add("--file-filter=" + description(f, patterns) + " | " + patterns); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return command;
	}

	/** Patrones de un filtro separados por espacios; <code>*</code> si no tiene extensiones. */
	private static String patterns(final GenericFileFilter filter) {
		final List<String> exts = normalizeExtensions(filter.getExtensions());
		if (exts.isEmpty()) {
			return "*"; //$NON-NLS-1$
		}
		final Set<String> patterns = new LinkedHashSet<>();
		for (final String ext : exts) {
			patterns.add("*." + ext.toLowerCase(Locale.ROOT)); //$NON-NLS-1$
			patterns.add("*." + ext.toUpperCase(Locale.ROOT)); //$NON-NLS-1$
		}
		return join(patterns);
	}

	/** Descripci&oacute;n saneada de un filtro; sin descripci&oacute;n, los patrones en min&uacute;sculas. */
	private static String description(final GenericFileFilter filter, final String patterns) {
		String desc = filter.getDescription();
		if (desc == null || desc.trim().isEmpty()) {
			final List<String> exts = normalizeExtensions(filter.getExtensions());
			final Set<String> lower = new LinkedHashSet<>();
			for (final String ext : exts) {
				lower.add("*." + ext.toLowerCase(Locale.ROOT)); //$NON-NLS-1$
			}
			desc = lower.isEmpty() ? patterns : join(lower);
		}
		return desc.replace('|', '/');
	}

	private static String join(final Iterable<String> parts) {
		final StringBuilder sb = new StringBuilder();
		for (final String p : parts) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(p);
		}
		return sb.toString();
	}

	/** Extensiones sin <code>*</code> ni <code>.</code> iniciales, descartando las vac&iacute;as. */
	private static List<String> normalizeExtensions(final String[] extensions) {
		final List<String> exts = new ArrayList<>();
		if (extensions == null) {
			return exts;
		}
		for (final String ext : extensions) {
			if (ext == null) {
				continue;
			}
			int i = 0;
			while (i < ext.length() && (ext.charAt(i) == '*' || ext.charAt(i) == '.')) {
				i++;
			}
			if (i < ext.length()) {
				exts.add(ext.substring(i));
			}
		}
		return exts;
	}

	/** Elimina del entorno las variables que alteran los c&oacute;digos de salida de zenity.
	 * @param env Entorno del proceso. */
	static void cleanEnvironment(final Map<String, String> env) {
		for (final String v : ZENITY_VARIABLES) {
			env.remove(v);
		}
	}

	/** Ejecuta la herramienta nativa y espera a que termine.
	 * @param command L&iacute;nea de &oacute;rdenes.
	 * @param multi Si se admiten varios ficheros en la salida.
	 * @return Resultado del di&aacute;logo. */
	static Result run(final List<String> command, final boolean multi) {
		final ProcessBuilder pb = new ProcessBuilder(command);
		cleanEnvironment(pb.environment());
		pb.redirectError(ProcessBuilder.Redirect.to(new File("/dev/null"))); //$NON-NLS-1$
		Process process = null;
		try {
			process = pb.start();
			process.getOutputStream().close();
			final String stdout;
			try (InputStream is = process.getInputStream()) {
				stdout = new String(readAll(is), StandardCharsets.UTF_8);
			}
			final int exitCode = process.waitFor();
			final Result result = parseOutput(exitCode, stdout, multi);
			if (result.status == Status.UNAVAILABLE) {
				LOGGER.warning("El dialogo de ficheros nativo termino con codigo " + exitCode //$NON-NLS-1$
						+ " o sin rutas locales validas, se usara el de Java"); //$NON-NLS-1$
			}
			return result;
		}
		catch (final IOException e) {
			LOGGER.warning("No se pudo ejecutar el dialogo de ficheros nativo, se usara el de Java: " + e); //$NON-NLS-1$
			if (process != null) {
				process.destroy();
			}
			return UNAVAILABLE;
		}
		catch (final InterruptedException e) {
			LOGGER.warning("Se interrumpio la espera del dialogo de ficheros nativo: " + e); //$NON-NLS-1$
			process.destroy();
			Thread.currentThread().interrupt();
			return CANCELLED;
		}
	}

	private static byte[] readAll(final InputStream is) throws IOException {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		final byte[] buffer = new byte[8192];
		int n;
		while ((n = is.read(buffer)) != -1) {
			baos.write(buffer, 0, n);
		}
		return baos.toByteArray();
	}

	/** Interpreta la salida de la herramienta nativa.
	 * @param exitCode C&oacute;digo de salida.
	 * @param stdout Salida est&aacute;ndar.
	 * @param multi Si se admiten varios ficheros (uno por l&iacute;nea).
	 * @return Resultado del di&aacute;logo. */
	static Result parseOutput(final int exitCode, final String stdout, final boolean multi) {
		if (exitCode == EXIT_CANCELLED) {
			return CANCELLED;
		}
		if (exitCode != EXIT_ACCEPTED || stdout == null) {
			return UNAVAILABLE;
		}
		final List<File> files = new ArrayList<>();
		for (final String line : stdout.split("\n", -1)) { //$NON-NLS-1$
			if (line.isEmpty()) {
				continue;
			}
			// Solo rutas locales absolutas (KIO puede devolver URL como smb://)
			if (!line.startsWith("/")) { //$NON-NLS-1$
				return UNAVAILABLE;
			}
			files.add(new File(line));
			if (!multi) {
				break;
			}
		}
		if (files.isEmpty()) {
			return UNAVAILABLE;
		}
		return new Result(Status.ACCEPTED, files.toArray(new File[0]));
	}

	/** Calcula el fichero o carpeta inicial con las mismas reglas que el <code>JFileChooser</code>
	 * de {@link JSEUIManager}.
	 * @param currentDir Carpeta indicada por quien llama o <code>null</code>.
	 * @param filename Nombre de fichero propuesto o <code>null</code>.
	 * @param preferenceDir Carpeta guardada en preferencias o <code>null</code>.
	 * @param userHome Carpeta del usuario.
	 * @return Fichero o carpeta inicial absoluta. */
	static File startPath(final String currentDir,
			              final String filename,
			              final String preferenceDir,
			              final String userHome) {
		final File result;
		if (filename != null && currentDir != null) {
			result = new File(currentDir, filename);
		}
		else {
			final String dir = currentDir != null ? currentDir : preferenceDir;
			if (filename != null) {
				result = dir != null ? new File(dir, filename) : new File(filename);
			}
			else if (dir != null) {
				result = new File(dir);
			}
			else {
				return userHome != null ? new File(userHome) : null;
			}
		}
		if (!result.isAbsolute() && userHome != null) {
			return new File(userHome, result.getPath());
		}
		return result;
	}

	/** A&ntilde;ade la extensi&oacute;n del filtro por defecto si el nombre no termina en
	 * ninguna extensi&oacute;n de los filtros ofrecidos.
	 * @param file Fichero elegido.
	 * @param filters Filtros ofrecidos o <code>null</code>.
	 * @param defaultFilter Filtro por defecto o <code>null</code>.
	 * @return Fichero con la extensi&oacute;n por defecto, o el mismo si no procede. */
	static File addDefaultExtension(final File file,
			                        final List<GenericFileFilter> filters,
			                        final GenericFileFilter defaultFilter) {
		if (file == null || filters == null || defaultFilter == null || !filters.contains(defaultFilter)) {
			return file;
		}
		final List<String> defaultExts = normalizeExtensions(defaultFilter.getExtensions());
		if (defaultExts.isEmpty()) {
			return file;
		}
		final String name = file.getName().toLowerCase(Locale.ROOT);
		for (final GenericFileFilter f : filters) {
			if (f == null) {
				continue;
			}
			for (final String ext : normalizeExtensions(f.getExtensions())) {
				if (name.endsWith("." + ext.toLowerCase(Locale.ROOT))) { //$NON-NLS-1$
					return file;
				}
			}
		}
		return new File(file.getPath() + '.' + defaultExts.get(0).toLowerCase(Locale.ROOT));
	}
}
