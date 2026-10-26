package impsave;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;

public class Context {
	private final File appFile;

	public Context() {
		appFile = getJarOrAppPath();
	}

	public File getJarOrAppFile() {
		return appFile;
	}

	public File getSaveDirectory() {
		return new File(getApplicationLocationOrWorkingDir(), "Save");
	}

	public File getImperialismApp() {
		return new File(getApplicationLocationOrWorkingDir(), "Imperialism.exe");
	}

	public String getSaveExtension() {
		return  ".imp";
	}

	/**
	 * Returns the decoded file system location of the running JAR (or class
	 * directory). Converting via URI decodes %20 and friends, so paths with
	 * spaces (e.g. "Program Files (x86)" or "GOG Galaxy") work.
	 */
	private static File getJarOrAppPath() {
		URL url = Context.class.getProtectionDomain().getCodeSource().getLocation();
		try {
			return new File(url.toURI());
		} catch (URISyntaxException | IllegalArgumentException e) {
			return new File(url.getPath());
		}
	}

	private File getApplicationLocationOrWorkingDir() {
		File dir = resolveGameDirectory(appFile);
		return dir != null ? dir : new File(System.getProperty("user.dir"));
	}

	/**
	 * Determines the game folder from the location of the ImpSave JAR, or
	 * returns null if ImpSave does not run from a JAR (e.g. from an IDE).
	 */
	static File resolveGameDirectory(File jarOrApp) {
		String name = jarOrApp.getName();
		if (!name.endsWith(".jar") && !name.endsWith(".exe")) {
			return null;
		}
		File dir = jarOrApp.getAbsoluteFile().getParentFile();
		if (dir == null) {
			return null;
		}
		// Mac app bundle: <game>/ImpSave.app/Contents/Resources/Java/<jar>
		if (dir.getPath().replace('\\', '/').endsWith(".app/Contents/Resources/Java")) {
			dir = dir.getParentFile().getParentFile().getParentFile().getParentFile();
		}
		// jpackage app-image on Windows: the JAR lives in <game>/ImpSave/app/ next to
		// <game>/ImpSave/runtime/, so the game folder is two levels up.
		if (dir.getName().equals("app") && dir.getParentFile() != null
				&& new File(dir.getParentFile(), "runtime").isDirectory()
				&& dir.getParentFile().getParentFile() != null) {
			dir = dir.getParentFile().getParentFile();
		}
		return dir;
	}
}
