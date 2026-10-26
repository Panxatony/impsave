package impsave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextTest {
	@TempDir
	Path tmp;

	private File bundleJar(Path game) throws IOException {
		Files.createDirectories(game.resolve("ImpSave/runtime"));
		Path app = Files.createDirectories(game.resolve("ImpSave/app"));
		return Files.createFile(app.resolve("ImpSave-0.25.jar")).toFile();
	}

	@Test
	void jarInGameFolder() throws IOException {
		Path game = Files.createDirectories(tmp.resolve("Imperialism"));
		File jar = Files.createFile(game.resolve("ImpSave-0.25.jar")).toFile();
		assertEquals(game.toFile(), Context.resolveGameDirectory(jar));
	}

	@Test
	void windowsBundleInGameFolder() throws IOException {
		Path game = tmp.resolve("Imperialism");
		assertEquals(game.toFile(), Context.resolveGameDirectory(bundleJar(game)));
	}

	@Test
	void windowsBundleInPathWithSpaces() throws IOException {
		// Regression: the GOG Galaxy default path contains spaces.
		Path game = tmp.resolve("GOG Galaxy/Games/Imperialism (1997)");
		assertEquals(game.toFile(), Context.resolveGameDirectory(bundleJar(game)));
	}

	@Test
	void appFolderWithoutRuntimeIsNotTreatedAsBundle() throws IOException {
		Path app = Files.createDirectories(tmp.resolve("Imperialism/app"));
		File jar = Files.createFile(app.resolve("ImpSave.jar")).toFile();
		assertEquals(app.toFile(), Context.resolveGameDirectory(jar));
	}

	@Test
	void classDirectoryFallsBackToWorkingDir() {
		assertNull(Context.resolveGameDirectory(tmp.resolve("classes").toFile()));
	}
}
