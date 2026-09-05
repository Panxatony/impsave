package impsave;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

public class Context {
	private String appPath;

	public Context() {
		appPath = getJarOrAppPath();
	}

	public File getJarOrAppFile() {
		return new File(appPath);
	}

	public File getSaveDirectory() {
		String appLocation = getApplicationLocationOrWorkingDir();
		return new File(appLocation, "Save");
	}

	public File getImperialismApp() {
		return new File(getApplicationLocationOrWorkingDir() + "/Imperialism.exe");
	}

	public String getSaveExtension() {
		return  ".imp";
	}

	private static String getJarOrAppPath() {
		return Context.class.getProtectionDomain().getCodeSource().getLocation().getFile();
	}

	private static String getApplicationLocationOrWorkingDir() {
		String location = getJarOrAppPath();
		if (!location.endsWith(".jar") && !location.endsWith(".exe")) {
			return System.getProperty("user.dir");
		}
		location = new File(location).getParent();
		// we stripped off the jar, but it may still be deep inside the bundle, so:
		if (location.endsWith(".app/Contents/Resources/Java")) {
			location = new File(location).getParentFile().getParentFile().getParentFile().getParent();
		}
		// jpackage app-image on Windows: the JAR lives in <game>/ImpSave/app/ next to
		// <game>/ImpSave/runtime/, so the game folder is two levels up.
		File dir = new File(location);
		if (dir.getName().equals("app") && dir.getParentFile() != null
				&& new File(dir.getParentFile(), "runtime").isDirectory()) {
			location = dir.getParentFile().getParent();
		}
		try { // decode %20's and such back into spaces
			location = URLDecoder.decode(location, "UTF-8");
		} catch (UnsupportedEncodingException e) { }
		return location;
	}
}
