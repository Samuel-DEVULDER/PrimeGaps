package primegap.sieve.simd;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import primegap.sieve.SlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.util.Java;

public class SIMDSlidingWindowSieve extends SlidingWindowSieve {
	static boolean isSIMDEnabled = Java.SIMD.enable();

	protected static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;

	public SIMDSlidingWindowSieve(SieveGap sieve, int size, boolean doubleBuffer) {
		super(sieve, size, doubleBuffer);
	}

	private final String name = super.name() + "/SIMD_" + SPECIES.length() * Long.SIZE;

	@Override
	protected String name() {
		return name;
	}

	@Override
	protected long[] newTab(int size) {
		// align size to vector length for better SIMD performance (no tail handling)
		int ajusted = (size + SPECIES.length() - 1) & -SPECIES.length();
		return super.newTab(ajusted);
	}

	protected void updateSeq64(long[] tab, int from, long to, long step) {
		for (long pos = from; pos < to; pos += step) {
			int idx = (int) (pos >>> 6);
			tab[idx] |= 1L << (pos & 63);
		}
	}

	@Override
	protected void updateSeq(long[] tab, int from, long to, long step) {
		if (step >= 64) {
			// No SIMD benefit for steps >= 64: only one bit set per long, so no
			// vectorization possible.
			updateSeq64(tab, from, to, step);
		} else {
			// SIMD dispatch: fast hard-coded 256-bit path, generic fallback otherwise
			switch (SPECIES.length()) {
			case 4:
				updateSeqSimd256(tab, from, to, (int) step);
				break;
			case 2:
				updateSeqSimd128(tab, from, to, (int) step);
				break;
			default:
				updateSeqSimdGen(tab, from, to, (int) step);
				break;
			}
		}
	}

	private void updateSeqSimd128(long[] tab, int bitPos, long windowSize, int pLong) {
		var SPECIES = LongVector.SPECIES_128;

		// Hard-coded for SPECIES_128 (2 lanes): pos0..pos1 live in registers.
		// vMasks encodes one bit per lane; shifted left by pLong each inner step.
		// pos0 (smallest anchor) drives the outer loop — no window overflow.
		// Inner loop exits when vMasks == 0: all bits shifted out, tail included.

		long[] buf = new long[2];

		long viEnd = (bitPos + 128L) & -128L;
		long lane1Start = viEnd - 64L;

		long pos0 = bitPos;

		LongVector zero = LongVector.zero(SPECIES);
		do {
			int vi = (int) (viEnd >>> 6) - 2;

			long pos1 = pos0;
			while (pos1 < lane1Start)
				pos1 += pLong;

			LongVector vMasks = buildMask128(buf, pos0, pos1);

			var vTab = LongVector.fromArray(SPECIES, tab, vi);
			do {
				pos0 += pLong;
				vTab = vTab.or(vMasks);
				vMasks = vMasks.lanewise(VectorOperators.LSHL, pLong);
			} while (!vMasks.eq(zero).allTrue());
			// } while (vMasks.reduceLanesToLong(VectorOperators.OR) != 0L);
			vTab.intoArray(tab, vi);

			if (pos0 >= windowSize)
				break;

			// Advance block boundaries
			viEnd += 128L;
			lane1Start += 128L;

			// pos0 exited its lane but may still be behind the new block —
			// advance it to the first multiple of p in lane 0 of the new block
			while (pos0 < viEnd - 128L)
				pos0 += pLong;
		} while (pos0 < windowSize);
	}

	private void updateSeqSimd256(long[] tab, int bitPos, long windowSize, int pLong) {
		var SPECIES = LongVector.SPECIES_256;

		// Hard-coded for SPECIES_256 (4 lanes): pos0..pos3 live in registers.
		// vMasks encodes one bit per lane; shifted left by pLong each inner step.
		// pos0 (smallest anchor) drives the outer loop — no window overflow.
		// Inner loop exits when vMasks == 0: all bits shifted out, tail included.

		long[] buf = new long[4];

		long viEnd = (bitPos + 256L) & -256L;
		long lane1Start = viEnd - 192L;
		long lane2Start = viEnd - 128L;
		long lane3Start = viEnd - 64L;

		long pos0 = bitPos;
		long pos1 = pos0;
		while (pos1 < lane1Start)
			pos1 += pLong;
		long pos2 = pos1;
		while (pos2 < lane2Start)
			pos2 += pLong;
		long pos3 = pos2;
		while (pos3 < lane3Start)
			pos3 += pLong;

		LongVector zero = LongVector.zero(SPECIES);

		do {
			int vi = (int) (viEnd >>> 6) - 4;

			LongVector vMasks = buildMask256(buf, pos0, pos1, pos2, pos3);

			var vTab = LongVector.fromArray(SPECIES, tab, vi);
			do {
				pos0 += pLong;
				vTab = vTab.or(vMasks);
				vMasks = vMasks.lanewise(VectorOperators.LSHL, pLong);
			} while (!vMasks.eq(zero).allTrue());
			vTab.intoArray(tab, vi);

			if (pos0 >= windowSize)
				break;

			// Advance block boundaries
			viEnd += 256L;
			lane1Start += 256L;
			lane2Start += 256L;
			lane3Start += 256L;

			// pos0 exited its lane but may still be behind the new block —
			// advance it to the first multiple of p in lane 0 of the new block
			while (pos0 < viEnd - 256L)
				pos0 += pLong;

			// Recompute pos1/pos2/pos3 from updated pos0
			pos1 = pos0;
			while (pos1 < lane1Start)
				pos1 += pLong;
			pos2 = pos1;
			while (pos2 < lane2Start)
				pos2 += pLong;
			pos3 = pos2;
			while (pos3 < lane3Start)
				pos3 += pLong;
		} while (pos0 < windowSize);
	}

	private void updateSeqSimdGen(long[] tab, int bitPos, long windowSize, int pLong) {
		var SPECIES = SIMDSlidingWindowSieve.SPECIES;

		// Generic version: works for any SPECIES (256, 512, or more).
		// pos[] and buf[] on heap — less JIT-friendly but fully parameterized.

		int nLanes = SPECIES.length();
		int blockBits = nLanes * Long.SIZE;
		long winSize = tab.length * 64L;
		long[] buf = new long[nLanes];
		long[] pos = new long[nLanes];
		long[] laneStart = new long[nLanes];

		long viEnd = (bitPos + blockBits) & -blockBits;
		for (int i = 0; i < nLanes; i++)
			laneStart[i] = viEnd - (long) (nLanes - i) * Long.SIZE;

		pos[0] = bitPos;
		for (int i = 1; i < nLanes; i++) {
			pos[i] = pos[i - 1];
			while (pos[i] < laneStart[i])
				pos[i] += pLong;
		}

		for (int i = 0; i < nLanes; i++)
			buf[i] = 1L << (int) (pos[i] & 63);
		LongVector vMasks = LongVector.fromArray(SPECIES, buf, 0);
		LongVector zero = LongVector.zero(SPECIES);

		do {
			int vi = (int) (viEnd >>> 6) - nLanes;

			var vTab = LongVector.fromArray(SPECIES, tab, vi);
			do {
				pos[0] += pLong;
				vTab = vTab.or(vMasks);
				vMasks = vMasks.lanewise(VectorOperators.LSHL, pLong);
			} while (!vMasks.eq(zero).allTrue());
			vTab.intoArray(tab, vi);

			if (pos[0] >= winSize)
				break;

			viEnd += blockBits;
			for (int i = 0; i < nLanes; i++)
				laneStart[i] += blockBits;

			// After inner loop, advance pos[0] into lane 0 of new block
			while (pos[0] < viEnd - blockBits)
				pos[0] += pLong;

			// Recompute pos[1..n] from updated pos[0]
			for (int i = 1; i < nLanes; i++) {
				pos[i] = pos[i - 1];
				while (pos[i] < laneStart[i])
					pos[i] += pLong;
			}

			for (int i = 0; i < nLanes; i++)
				buf[i] = 1L << (int) (pos[i] & 63);
			vMasks = LongVector.fromArray(SPECIES, buf, 0);

		} while (pos[0] < winSize);
	}

	// Mask building

	private static LongVector buildMask128(long[] buf, long p0, long p1) {
		buf[0] = 1L << (int) (p0 & 63);
		buf[1] = 1L << (int) (p1 & 63);
		return LongVector.fromArray(LongVector.SPECIES_128, buf, 0);
	}

	private static LongVector buildMask256(long[] buf, long p0, long p1, long p2, long p3) {
		buf[0] = 1L << (int) (p0 & 63);
		buf[1] = 1L << (int) (p1 & 63);
		buf[2] = 1L << (int) (p2 & 63);
		buf[3] = 1L << (int) (p3 & 63);
		return LongVector.fromArray(LongVector.SPECIES_256, buf, 0);
	}
};