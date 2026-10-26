package impsave;

import java.security.*;
import java.util.ArrayList;

import crockford.CrockfordBase32;

public class SaveParser {
	public static final int MAX_OFFSET = 0x2000;
	public static final int SAVE_NAME_OFFSET = 0xC;
	// The name field spans 0x0C-0x2B; game data starts at 0x2C. The game fills
	// all 32 bytes without a terminating NUL when the name is that long, e.g.
	// the German autosave label " - Automatische Dateisicherung -".
	public static final int SAVE_NAME_LENGTH = 32;

	private byte[] data;

	public SaveParser(byte[] data) {
		this.data = data;
	}

	public String getCountryName() {
		return readCString(0x1980, 0x19a4 - 0x1980);
	}

	public String getSavedGameName() {
		return readCString(SAVE_NAME_OFFSET, SAVE_NAME_LENGTH);
	}

	public int getTurnNumber() {
		int value = data[0x19a4];
		return value < 0 ? 256 + value : value;
	}

	public String getGameId() {
		byte[] idBytes = new byte[] { data[8], data[9], data[10], data[11] };
		CrockfordBase32 encoder = new CrockfordBase32();
		String gameId = encoder.encodeToString(idBytes);
		// Single player games don't have an id, instead hash list of countries.
		if (gameId.equals("0000000")) {
			byte[] longId = computeSoloGameId();
			System.arraycopy(longId, 0, idBytes, 0, 4);
			gameId = encoder.encodeToString(idBytes);
		}
		return gameId;
	}

	private byte[] computeSoloGameId() {
		ArrayList<String> countries = new ArrayList<String>();
		// Read list of countries (this only works for solo games as they
		// are stored differently in multiplayer).
		int offset = 0x1a2d;
		while (data[offset + 1] != 0) {
			String country = readPascalString(offset);
			countries.add(country);
			offset += country.length() + 1;
		}
		return computeStringListHash(countries);
	}

	private static byte[] computeStringListHash(ArrayList<String> list) {
		MessageDigest md = null;
		try {
			md = MessageDigest.getInstance("MD5");
			for (String str : list)
				md.update(str.getBytes());
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		return md.digest();
	}

	private String readCString(int start, int maxLength) {
		int limit = Math.min(data.length, start + maxLength);
		int end = start;
		while (end < limit && data[end] != 0) {
			end++;
		}
		return printable(new String(data, start, end - start, java.nio.charset.StandardCharsets.ISO_8859_1));
	}

	/** Drops control characters, which are not allowed in the XML database. */
	static String printable(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= 0x20 && c != 0x7F && !(c >= 0x80 && c < 0xA0)) {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private String readPascalString(int start) {
		int length = data[start];
		return new String(data, start + 1, length);
	}

	public SaveDb.GameFileContents getGameFileContents() {
		SaveDb.GameFileContents contents = new SaveDb.GameFileContents();
		contents.setId(getGameId());
		contents.setCountry(getCountryName());
		contents.setTurn(getTurnNumber());
		contents.setSavedGameName(getSavedGameName());
		return contents;
	}
}
