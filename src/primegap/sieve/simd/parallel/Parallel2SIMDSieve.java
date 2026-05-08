package primegap.sieve.simd.parallel;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import primegap.sieve.SieveGap;
import primegap.sieve.simd.SIMDSlidingWindowSieve;
import primegap.util.IncreasingBigIntegers;

/**
 * This classes mark all prime multiples in parallel thread. Atomicity is
 * obtained via the use of a {@link VarHandle} to update the sieve table.
 * Parallelism is only started when the primes are bigger enough so that there
 * is only one bit to update per long int.
 */
class Parallel2SIMDSieve extends SIMDSlidingWindowSieve {
	public Parallel2SIMDSieve(SieveGap sieve, int size, boolean doubleBuffer) {
		super(sieve, size, doubleBuffer);
	}

	@Override
	protected String name() {
		if (name == null)
			name = super.name() + "/ParallelPrimes";
		return name;
	}

	private String name;

	static final VarHandle VH = MethodHandles.arrayElementVarHandle(long[].class);

	@Override
	protected void updateTab(long[] tab, int i, long mask) {
		if ((tab[i] & mask) != mask)
			VH.getAndBitwiseOr(tab, i, mask);
	}

	@Override
	protected void doMarkAllMultiples(BigInteger start, long[] tab, IncreasingBigIntegers primes, BigInteger limit) {
		final int thr = 96; // steps>512 --> no benefit of SIMD
		if (primes.size() > thr) {
			fillTab(tab, 0);
			var col = primes.upTo(limit);

			@SuppressWarnings("unchecked")
			List<BigInteger> list = col instanceof List tmp ? tmp : new ArrayList<>(col);


			// sequential for low steps
			list.subList(0, thr).forEach(p -> markMultiplesOf(start, tab, p));

			// parallel for big steps
			list.subList(thr, list.size()).parallelStream().forEach(p -> markMultiplesOf(start, tab, p));
		} else {
			// sequential: SIMD might help.
			super.doMarkAllMultiples(start, tab, primes, limit);
		}
	}
}
