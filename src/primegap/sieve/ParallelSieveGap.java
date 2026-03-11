package primegap.sieve;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class ParallelSieveGap extends SieveGap {
	class ParallelWindowedSieve extends SlidingWindowSieve {
		static final VarHandle VH = MethodHandles.arrayElementVarHandle(long[].class);
		volatile boolean concurrent;

		public ParallelWindowedSieve(int size) {
			super(ParallelSieveGap.this, size);
		}

		@Override
		protected void updateTab(long[] tab, int i, long mask) {
			if (concurrent) {
				VH.getAndBitwiseOr(tab, i, mask);
			} else {
				super.updateTab(tab, i, mask);
			}
		}

		final int nProc = Runtime.getRuntime().availableProcessors();
		final int CACHE_LINE_THRESHOLD = 512; // 8 longs × 64 bits
		final int small_thr = nProc <= 1 ? Integer.MAX_VALUE : CACHE_LINE_THRESHOLD;

		protected void markAllMultiples() {
			concurrent = false;
			fillTab(getTab(), 0);

			long now = timer.getAsLong();
			primes.stream().takeWhile(p -> p.intValue() <= small_thr).forEach(this::markMultiplesOf);
			dbg("small=", (timer.getAsLong() - now) / 1e6, "ms                                          ");

			concurrent = true;
			var big = primes.stream().dropWhile(p -> p.intValue() <= small_thr)//
					.takeWhile(p -> p.compareTo(limit) <= 0).toList();
			big.parallelStream().unordered().forEach(this::markMultiplesOf);
			concurrent = false;
			dbg("large=", (timer.getAsLong() - now) / 1e6, "ms");
			// super.markAllMultiples();
		}
	}

	@Override
	protected SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new ParallelWindowedSieve(size);
	}

	public static void main(String[] args) {
		new ParallelSieveGap().run();
	}

}
