package primegap.sieve.parallel;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.util.stream.IntStream;

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
public class ParallelPrimesSieve extends SlidingWindowSieve {
	public ParallelPrimesSieve(SieveGap sieve, int size, boolean doubleBuffer) {
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
		fillTab(tab, 0);
		if (primes.isEmpty())
			return;

		assert Java.dbgTic();

		// known = primes.stream().takeWhile(p -> p.compareTo(limit) <=
		// 0).toArray(BigInteger[]::new);
		// var b = known.length;

		var col = primes.upTo(limit);
		var array = col.toArray(IncreasingBigIntegers.EMPTY);

		@SuppressWarnings("unused")
		var stream = false ? IntStream.range(0, col.size()) : Java.shuffledRange(0, col.size());
		stream.parallel().forEach(i -> markMultiplesOf(start, tab, array[i]));
		assert Java.dbg("all(parallel, ",col.size(),")=", Java.dbgToc(), "ms                  ");
	}

}
