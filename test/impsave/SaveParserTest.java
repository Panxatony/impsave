package impsave;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SaveParserTest {
	private static byte[] header(String name, byte[] following) {
		byte[] data = new byte[0x2000];
		byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(n, 0, data, SaveParser.SAVE_NAME_OFFSET, n.length);
		System.arraycopy(following, 0, data, 0x2C, following.length);
		return data;
	}

	@Test
	void germanAutosaveNameFillsWholeFieldWithoutNul() {
		// Real header of a German slotA.imp: 32 name bytes, map data right after.
		byte[] data = header(" - Automatische Dateisicherung -",
				new byte[] { ';', ';', 0x17, 0x17, 0x17, 0x1c, '!', '!' });
		assertEquals(" - Automatische Dateisicherung -", new SaveParser(data).getSavedGameName());
	}

	@Test
	void shortNameStopsAtNul() {
		byte[] data = header("Spiel 7\0atische Dateisicherung -", new byte[] { 'O', 'O', 0x17 });
		assertEquals("Spiel 7", new SaveParser(data).getSavedGameName());
	}

	@Test
	void controlCharactersAreDropped() {
		assertEquals("ab", SaveParser.printable("a\u0017b\u001c"));
	}

	@Test
	void xmlSerializerDropsInvalidCharacters() throws Exception {
		SaveDb.SavedGames games = new SaveDb.SavedGames();
		SaveDb.GameFileContents game = new SaveDb.GameFileContents();
		game.setFile("slotA.imp");
		game.setId("X");
		game.setCountry("Schwarzien");
		game.setSavedGameName("bad\u0017name");
		games.getGames().add(game);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new XmlSerializer().writeSavedGames(games, out);
		String xml = out.toString("UTF-8");
		assertEquals(true, xml.contains("<savedGameName>badname</savedGameName>"), xml);
	}
}
