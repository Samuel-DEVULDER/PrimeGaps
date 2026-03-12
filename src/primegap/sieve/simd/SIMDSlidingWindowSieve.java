package primegap.sieve.simd;

import java.math.BigInteger;

import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import primegap.sieve.SieveGap;
import primegap.sieve.SlidingWindowSieve;
import primegap.util.Machine;

public class SIMDSlidingWindowSieve extends SlidingWindowSieve {
	public SIMDSlidingWindowSieve(SieveGap sieve, int size) {
		super(sieve, size);
	}

	protected SIMDSlidingWindowSieve(SieveGap sieve, SlidingWindowSieve delegate) {
		super(sieve, delegate);
	}

	protected SIMDSlidingWindowSieve(SieveGap sieve, int size, long range) {
		super(sieve, size, range);
	}

	static boolean isSIMDEnabled = Machine.enableSIMD();

	static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;

	private static LongVector buildMask256(long[] buf, long p0, long p1, long p2, long p3) {
		buf[0] = 1L << (int) (p0 & 63);
		buf[1] = 1L << (int) (p1 & 63);
		buf[2] = 1L << (int) (p2 & 63);
		buf[3] = 1L << (int) (p3 & 63);
		return LongVector.fromArray(LongVector.SPECIES_256, buf, 0);
	}

	private void markMultiplesOfSimd256(long bitPos, long[] tab, long pLong) {
		// Hard-coded for SPECIES_256 (4 lanes): pos0..pos3 live in registers.
		// vMasks encodes one bit per lane; shifted left by pLong each inner step.
		// pos0 (smallest anchor) drives the outer loop — no window overflow.
		// Inner loop exits when vMasks == 0: all bits shifted out, tail included.

		int shift = (int) (pLong & 63);
		long windowSize = tab.length * 64L;
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

		LongVector vMasks = buildMask256(buf, pos0, pos1, pos2, pos3);
		LongVector zero = LongVector.zero(LongVector.SPECIES_256);

		do {
			int vi = (int) (viEnd >>> 6) - 4;

			while (!vMasks.eq(zero).allTrue()) {
				LongVector.fromArray(LongVector.SPECIES_256, tab, vi).or(vMasks).intoArray(tab, vi);
				vMasks = vMasks.lanewise(VectorOperators.LSHL, shift);
				pos0 += pLong;
			}

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

			vMasks = buildMask256(buf, pos0, pos1, pos2, pos3);

		} while (pos0 < windowSize);
	}

	private void markMultiplesOfSimdGeneric(long bitPos, long[] tab, long pLong) {
		// Generic version: works for any SPECIES (128, 256, 512).
		// pos[] and buf[] on heap — less JIT-friendly but fully parameterized.

		int shift = (int) (pLong & 63);
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

			while (!vMasks.eq(zero).allTrue()) {
				LongVector.fromArray(SPECIES, tab, vi).or(vMasks).intoArray(tab, vi);
				vMasks = vMasks.lanewise(VectorOperators.LSHL, shift);
				pos[0] += pLong;
			}

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

	@Override
	protected void markMultiplesOf(BigInteger start, long[] tab, BigInteger p) {
		// First odd multiple of p >= start inside the window
		BigInteger n = start.remainder(p);
		if (n.signum() > 0)
			n = p.subtract(n);
		if (n.testBit(0))
			n = n.add(p);
		if (n.compareTo(windowRange_) >= 0)
			return;

		long bitPos = n.longValue() >>> 1;
		long windowSize = tab.length * 64L;

		// Single multiple: scalar write
		if (p.compareTo(windowRange_) >= 0) {
			tab[(int) (bitPos >>> 6)] |= 1L << (int) (bitPos & 63);
			return;
		}

		long pLong = p.longValue();

		// Scalar path: p >= 64, at most one multiple per long
		if (pLong >= 64) {
			long pos = bitPos;
			while (pos < windowSize) {
				tab[(int) (pos >>> 6)] |= 1L << (int) (pos & 63);
				pos += pLong;
			}
			return;
		}

		// SIMD dispatch: fast hard-coded 256-bit path, generic fallback otherwise
		if (SPECIES == LongVector.SPECIES_256) {
			markMultiplesOfSimd256(bitPos, tab, pLong);
		} else {
			markMultiplesOfSimdGeneric(bitPos, tab, pLong);
		}
	}
};