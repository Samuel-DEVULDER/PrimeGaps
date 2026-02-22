package attic;
import java.util.concurrent.atomic.AtomicLongArray;

/*
 * "and" bitmasks..
 */
public class ParallelSieveGap2 extends SieveGap2 {
	static class ParallelWindowedSieve2 extends SlidingWindowSieve2 {
		AtomicLongArray tab;
		boolean concurrent;

		public ParallelWindowedSieve2(int size) {
			super(size);
		}

		@Override
		long[] newTab(int size) {
			tab = new AtomicLongArray(size);
			return null;
		}

		@Override
		long getTab(int i) {
			return tab.get(i);
		}

		@Override
		void updateTab(int i, long mask) {
			long current = tab.get(i);
			long updated = current & ~mask;
			if(current==updated) return;
			if (concurrent) {
				while (!tab.compareAndSet(i, current, updated)) {
					current = tab.get(i);
					updated = current & ~mask;
				}
			} else {
				tab.set(i, updated);
			}
		}

		@Override
		void fillTab(long val) {
			for (int i = 0, m = tab.length(); i < m; ++i) {
				tab.set(i, val);
			}
		}

		@Override
		void markAllMultiples() {
			final int small_thr = 127;

			concurrent = false;
			primes.stream().takeWhile(p -> p.intValue() <= small_thr).forEach(this::markMultiplesOf);

			concurrent = true;
			var big = primes.stream().dropWhile(p -> p.intValue() <= small_thr).takeWhile(p -> p.compareTo(limit) <= 0)
					.toList();
			big.parallelStream().forEach(this::markMultiplesOf);

			concurrent = false;
		}
	}
	
	@Override
	SlidingWindowSieve2 newSlidingWindowSieve(int size) {
		return new ParallelWindowedSieve2(size);
	}

	public static void main(String[] args) {
		new ParallelSieveGap2().run();
	}

}
