package impsave;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Sanity checks over the patch table in {@link Patcher#getPatches()}.
 *
 * These run without the (copyrighted) game binary: they only verify that every
 * patch is well-formed and lands inside the file. The address-to-offset mapping
 * in {@link PatchSet#apply} is a fixed {@code addr - 0x400C00}, which is only
 * valid for the {@code .text} section, so a patch placed anywhere else silently
 * maps to the wrong bytes or - as happened once in review - past the end of the
 * buffer and throws {@code ArrayIndexOutOfBoundsException} at patch time.
 *
 * Written against JUnit 4 and Java 7 language level to match the project's
 * compiler target.
 */
public class PatchTableTest {

	/** Virtual address range of the .text section of the GOG Imperialism.exe. */
	private static final int TEXT_VA_LO = 0x401000;
	private static final int TEXT_VA_HI = 0x63DE95;

	/** Every patch as {address, length}, sorted by address. */
	private static List<int[]> regions() {
		Map<Integer, String> patches = Patcher.getPatches().entries();
		assertFalse("patch table must not be empty", patches.isEmpty());
		List<int[]> regions = new ArrayList<int[]>();
		for (Map.Entry<Integer, String> e : patches.entrySet()) {
			String hex = e.getValue();
			String at = "0x" + Integer.toHexString(e.getKey());
			assertEquals("odd hex length at " + at, 0, hex.length() % 2);
			assertTrue("non-hex characters at " + at, hex.matches("[0-9A-Fa-f]+"));
			regions.add(new int[] { e.getKey(), hex.length() / 2 });
		}
		Collections.sort(regions, new Comparator<int[]>() {
			public int compare(int[] a, int[] b) {
				return Integer.compare(a[0], b[0]);
			}
		});
		return regions;
	}

	@Test
	public void everyPatchStaysInsideTheFile() {
		for (int[] r : regions()) {
			int addr = r[0], len = r[1];
			int offset = addr - PatchSet.ADDRESS_TO_OFFSET;
			assertTrue(String.format("patch at 0x%x maps to a negative file offset", addr),
				offset >= 0);
			assertTrue(String.format("patch at 0x%x (%d bytes) maps to file offset 0x%x, past the end of the %d-byte binary",
					addr, len, offset, Patcher.EXPECTED_SIZE),
				offset + len <= Patcher.EXPECTED_SIZE);
		}
	}

	@Test
	public void everyPatchIsInsideTheTextSection() {
		for (int[] r : regions()) {
			int addr = r[0], len = r[1];
			assertTrue(String.format("patch at 0x%x (%d bytes) is outside .text (0x%x-0x%x); the 0x400C00 mapping is only valid there",
					addr, len, TEXT_VA_LO, TEXT_VA_HI),
				addr >= TEXT_VA_LO && addr + len <= TEXT_VA_HI);
		}
	}

	@Test
	public void patchesDoNotOverlap() {
		List<int[]> regions = regions();
		for (int i = 1; i < regions.size(); i++) {
			int[] prev = regions.get(i - 1), cur = regions.get(i);
			assertTrue(String.format("patch at 0x%x (%d bytes) overlaps patch at 0x%x", prev[0], prev[1], cur[0]),
				prev[0] + prev[1] <= cur[0]);
		}
	}

	@Test
	public void jumpHelperComputesRelativeOffsets() {
		// e9 + rel32, rel32 = dest - (instr + 5), little-endian, always 4 bytes.
		assertEquals("e900000000", PatchSet.jmpInstr(0x1000, 0x1005));
		assertEquals("e9fbffffff", PatchSet.jmpInstr(0x1005, 0x1005));
		assertEquals("e9d95e1d00", PatchSet.jmpInstr(0x4099b2, 0x5df890));
		// Low displacement byte 0x00 - the case that used to lose its leading zeros.
		assertEquals("e900010000", PatchSet.jmpInstr(0x1000, 0x1105));
	}

	@Test
	public void knownVersionListIsSane() {
		assertTrue("at least the GOG base and one patched version must be registered",
			Patcher.knownVersionCount() >= 2);
		assertTrue("MD5 must be 32 lowercase hex characters", Patcher.latestMd5().matches("[0-9a-f]{32}"));
	}
}
