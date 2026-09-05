package impsave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Sanity checks over the patch table in {@link Patcher#getPatches()}.
 *
 * These run without the (copyrighted) game binary: they only verify that every
 * patch is well-formed and lands inside the file. The address→offset mapping in
 * {@link PatchSet#apply} is a fixed {@code addr - 0x400C00}, which is only valid
 * for the {@code .text} section, so a patch placed anywhere else silently maps
 * to the wrong bytes or - as happened once in review - past the end of the
 * buffer and throws {@code ArrayIndexOutOfBoundsException} at patch time.
 */
class PatchTableTest {

	/** Virtual address range of the .text section of the GOG Imperialism.exe. */
	private static final int TEXT_VA_LO = 0x401000;
	private static final int TEXT_VA_HI = 0x63DE95;

	private static List<int[]> regions() {
		Map<Integer, String> patches = Patcher.getPatches().entries();
		assertFalse(patches.isEmpty(), "patch table must not be empty");
		List<int[]> regions = new ArrayList<>();
		for (Map.Entry<Integer, String> e : patches.entrySet()) {
			String hex = e.getValue();
			assertEquals(0, hex.length() % 2, "odd hex length at 0x" + Integer.toHexString(e.getKey()));
			assertTrue(hex.matches("[0-9A-Fa-f]+"), "non-hex characters at 0x" + Integer.toHexString(e.getKey()));
			regions.add(new int[] { e.getKey(), hex.length() / 2 });
		}
		regions.sort(Comparator.comparingInt(r -> r[0]));
		return regions;
	}

	@Test
	void everyPatchStaysInsideTheFile() {
		for (int[] r : regions()) {
			int addr = r[0], len = r[1];
			int offset = addr - PatchSet.ADDRESS_TO_OFFSET;
			assertTrue(offset >= 0,
				String.format("patch at 0x%x maps to a negative file offset", addr));
			assertTrue(offset + len <= Patcher.EXPECTED_SIZE,
				String.format("patch at 0x%x (%d bytes) maps to file offset 0x%x, past the end of the %d-byte binary",
					addr, len, offset, Patcher.EXPECTED_SIZE));
		}
	}

	@Test
	void everyPatchIsInsideTheTextSection() {
		for (int[] r : regions()) {
			int addr = r[0], len = r[1];
			assertTrue(addr >= TEXT_VA_LO && addr + len <= TEXT_VA_HI,
				String.format("patch at 0x%x (%d bytes) is outside .text (0x%x-0x%x); the 0x400C00 mapping is only valid there",
					addr, len, TEXT_VA_LO, TEXT_VA_HI));
		}
	}

	@Test
	void patchesDoNotOverlap() {
		List<int[]> regions = regions();
		for (int i = 1; i < regions.size(); i++) {
			int[] prev = regions.get(i - 1), cur = regions.get(i);
			assertTrue(prev[0] + prev[1] <= cur[0],
				String.format("patch at 0x%x (%d bytes) overlaps patch at 0x%x", prev[0], prev[1], cur[0]));
		}
	}

	@Test
	void jumpHelperComputesRelativeOffsets() {
		// e9 + rel32, rel32 = dest - (instr + 5), little-endian.
		assertEquals("e900000000", PatchSet.jmpInstr(0x1000, 0x1005));
		assertEquals("e9fbffffff", PatchSet.jmpInstr(0x1005, 0x1005));
		assertEquals("e9d95e1d00", PatchSet.jmpInstr(0x4099b2, 0x5df890));
	}

	@Test
	void knownVersionListIsSane() {
		assertTrue(Patcher.knownVersionCount() >= 2, "at least the GOG base and one patched version must be registered");
		assertEquals(32, Patcher.latestMd5().length(), "MD5 must be 32 hex characters");
		assertTrue(Patcher.latestMd5().matches("[0-9a-f]{32}"));
	}
}
