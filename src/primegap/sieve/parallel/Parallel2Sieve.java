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
public class Parallel2Sieve extends SlidingWindowSieve {
	public Parallel2Sieve(SieveGap sieve, int size, boolean doubleBuffer) {
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
		VH.getAndBitwiseOr(tab, i, mask);
	}

	BigInteger[] known;
	int last_known = 0;

	@Override
	protected void doMarkAllMultiples(BigInteger start, long[] tab, IncreasingBigIntegers primes, BigInteger limit) {
		fillTab(tab, 0);
		if(primes.isEmpty()) return;
		
		assert Java.dbgTic();

		var array = known;
		if (array == null || array[array.length - 1].compareTo(limit) < 0) {
			known = array = primes.toArray(BigInteger[]::new);
			assert array[array.length - 1].compareTo(limit) >= 0;
		}
		int a = last_known, b = known.length;
		while (b-a>1) {
			int c = a + (b - a) / 2;
			int d = array[c].compareTo(limit);
			if (d <= 0)
				a = c;
			else
				b = c;
		}
		last_known = b;

		// var array = primes.stream().takeWhile(p -> p.compareTo(limit) <=
		// 0).toArray(BigInteger[]::new);
		var  final_array = array;
		@SuppressWarnings("unused")
		var stream = false ? IntStream.range(0, b) : Java.shuffledRange(0, b);
		stream.parallel().forEach(i -> markMultiplesOf(start, tab, final_array[i]));
		assert Java.dbg("all(parallel)=", Java.dbgToc(), "ms                  ");
	}

	
}
