package primegap.sieve.wheel;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.util.stream.IntStream;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.util.IncreasingBigIntegers;
import primegap.util.Java;

public class ParallelPrimesWheelSieveGap extends AbstractWheelSieveGap {

	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doubleBuffer) {
		return new WheelSieve(size, doubleBuffer, 2, 3, 5, 7) {
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
			protected void doMarkAllMultiples(BigInteger start, long[] tab, IncreasingBigIntegers primes,
					BigInteger limit) {
				fillTab(tab, 0);
				if (primes.isEmpty())
					return;

				assert Java.dbgTic();

				var col = primes.upTo(limit);
				var array = col.toArray(IncreasingBigIntegers.EMPTY);

				@SuppressWarnings("unused")
				var stream = false ? IntStream.range(0, col.size()) : Java.shuffledRange(0, col.size());
				stream.parallel().forEach(i -> markMultiplesOf(start, tab, array[i]));
				assert Java.dbg("all(parallel)=", Java.dbgToc(), "ms                  ");
			}
		};
	}

	public static void main(String[] args) {
		new ParallelPrimesWheelSieveGap().run();
	}

	static public class DB extends ParallelPrimesWheelSieveGap {
		@Override
		protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
			return newSlidingWindowSieve(size, true);
		}

		public static void main(String[] args) {
			new DB().run();
		}
	}

	public static class FF extends ParallelPrimesWheelSieveGap {
		public FF() {
			gapCounts = null;
		}

		@Override
		protected BigInteger fastForward(BigInteger P, int gap) {
			return supplier.fastForward(P, gap, this::addPrimeCount);
		}

		public static void main(String[] args) {
			new FF().run();
		}

		static public class DB extends FF {
			@Override
			protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
				return newSlidingWindowSieve(size, true);
			}

			public static void main(String[] args) {
				new DB().run();
			}
		}
	}
}