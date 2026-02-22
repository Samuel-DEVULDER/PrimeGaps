import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class ParallelSieveGap extends SieveGap {
	class ParallelWindowedSieve extends SlidingWindowSieve {
		static final VarHandle VH = MethodHandles.arrayElementVarHandle(long[].class);
		volatile boolean concurrent;

		public ParallelWindowedSieve(int size) {
			super(size);
		}

		@Override
		void updateTab(int i, long mask) {
			if (concurrent) {
				VH.getAndBitwiseOr(tab, i, mask);
			} else {
				tab[i] |= mask;
			}
		}

		void markAllMultiples_() {
			concurrent = true;
			var big = primes.stream().takeWhile(p -> p.compareTo(limit) <= 0).toList();
			big.parallelStream().forEach(this::markMultiplesOf);
			concurrent = false;
		}

		final int nProc = Runtime.getRuntime().availableProcessors();
		final int CACHE_LINE_THRESHOLD = 512; // 8 longs × 64 bits
		final int small_thr = nProc <= 1 ? Integer.MAX_VALUE : CACHE_LINE_THRESHOLD;
		
		void markAllMultiples() {
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
	SieveGap.SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new ParallelWindowedSieve(size);
	}

	public static void main(String[] args) {
		new ParallelSieveGap().run();
	}

}
