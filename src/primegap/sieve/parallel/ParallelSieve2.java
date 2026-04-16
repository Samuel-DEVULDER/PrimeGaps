package primegap.sieve.parallel;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.util.stream.IntStream;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;
import primegap.sieve.SlidingWindowSieve;
import primegap.util.IncreasingBigIntegers;
import primegap.util.Java;

/**
 * This classes mark all prime multiples in parallel thread. Atomicity is
 * obtained via the use of a {@link VarHandle} to update the sieve table.
 * Parallelism is only started when the primes are bigger enough so that there
 * is only one bit to update per long int.
 */
public class ParallelSieve2 extends SlidingWindowSieve {
	public ParallelSieve2(SieveGap sieve, int size, boolean doubleBuffer) {
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
		fillTab(tab, 0);
		Java.dbgTime(true);
		var array = primes.stream().takeWhile(p -> p.compareTo(limit) <= 0).toArray(BigInteger[]::new);
		IntStream.range(0, array.length).parallel().forEach(i -> markMultiplesOf(start, tab, array[i]));
		if(Java.dbg) Java.dbg("all(parellel)=", Java.dbgTime(false), "ms                  ");
	}

	public static class Parallel2SieveGap extends SieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
			return new ParallelSieve2(this, size, doubleBuffer);
		}

		public static void main(String[] args) {
			new Parallel2SieveGap().run();
		}
	}

	public static class DoubleBufferedParallel2SieveGap extends Parallel2SieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DoubleBufferedParallel2SieveGap().run();
		}
	}
}
