package primegap.sieve.simd;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.util.IncreasingBigIntegers;

/**
 * This classes mark all prime multiples in parallel thread. Atomicity is
 * obtained via the use of a {@link VarHandle} to update the sieve table.
 * Parallelism is only started when the primes are bigger enough so that there
 * is only one bit to update per long int.
 */
class Parallel2SIMDSlidingWindowSieve extends SIMDSlidingWindowSieve {
	public Parallel2SIMDSlidingWindowSieve(SieveGap sieve, int size, boolean doubleBuffer) {
		super(sieve, size, doubleBuffer);
	}

	@Override
	protected String name() {
		if (name == null)
			name = super.name() + "/Parallel2";
		return name;
	}

	private String name;

	static final VarHandle VH = MethodHandles.arrayElementVarHandle(long[].class);

	@Override
	protected void updateTab(long[] tab, int i, long mask) {
		VH.getAndBitwiseOr(tab, i, mask);
	}

	@Override
	protected void doMarkAllMultiples(BigInteger start, long[] tab, IncreasingBigIntegers primes, BigInteger limit) {
		final int thr = 16;
		if (primes.size() > thr) {
			fillTab(tab, 0);
			var list = primes.stream().takeWhile(p -> p.compareTo(limit) <= 0).toList();
			list.subList(0, thr).forEach(p -> markMultiplesOf(start, tab, p));
			list.subList(thr, list.size()).parallelStream().forEach(p -> markMultiplesOf(start, tab, p));
		} else {
			super.doMarkAllMultiples(start, tab, primes, limit);
		}
	}

	public static class Parallel2SIMDSlidingWindowSieveGap extends SieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
			return new Parallel2SIMDSlidingWindowSieve(this, size, doubleBuffer);
		}

		public static void main(String[] args) {
			new Parallel2SIMDSlidingWindowSieveGap().run();
		}
	}

	public static class DoubleBufferedParallel2SIMDSlidingWindowSieveGap extends Parallel2SIMDSlidingWindowSieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DoubleBufferedParallel2SIMDSlidingWindowSieveGap().run();
		}
	}
}
