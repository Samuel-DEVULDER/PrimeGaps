package primegap;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.stream.IntStream;

public class WheelSieveGap extends SieveGap {
	// =========================================================================
	// Wheel record
	// =========================================================================

	/**
	 * Immutable wheel factorization data, computed once from a set of small primes.
	 *
	 * <p>
	 * A wheel of order k is built from the first k primes ("small primes"). Its
	 * modulus is their product. Within each period of {@code modulus} consecutive
	 * integers, only the {@code phi(modulus)} values coprime to all small primes
	 * can possibly be prime. These are the "residues".
	 * </p>
	 *
	 * <p>
	 * Comparative candidate density:
	 * 
	 * <pre>
	 *   wheel2   : 50.0%  (1 bit / 2 integers)
	 *   wheel30  : 26.7%  (8 bits / 30 integers)   <- good trade-off
	 *   wheel210 : 22.9% (48 bits / 210 integers)  <- best practical choice
	 *   wheel2310: 20.8% (480 bits / 2310 integers) <- diminishing returns
	 * </pre>
	 * </p>
	 *
	 * @param smallPrimes  primes whose product is the modulus
	 * @param residues     values in [1, modulus) coprime to all small primes, in
	 *                     order
	 * @param gaps         gaps[i] = number-space distance from residues[i] to
	 *                     residues[(i+1) % len]; multiply by prime p to get the
	 *                     O(1) step to the next candidate multiple of p
	 * @param residueIndex reverse lookup: residueIndex[r] = index of r in
	 *                     residues[], or -1 if r is not a wheel candidate
	 */
	record Wheel(int[] smallPrimes, int[] residues, int[] residueIndex, int[][] jumpFromAny) {

		/**
		 * Builds a wheel from the given small primes. The modulus is computed as their
		 * product.
		 *
		 * @param smallPrimes e.g. {@code 2,3,5} for wheel30
		 */
		static Wheel of(int... smallPrimes) {
			int mod = 1;
			for (int p : smallPrimes)
				mod *= p;

			// Residues: integers in [1, mod) coprime to all small primes
			int count = 0;
			for (int r = 1; r < mod; r++)
				if (isCoprime(r, smallPrimes))
					count++;
			int[] residues = new int[count];
			int i = 0;
			for (int r = 1; r < mod; r++)
				if (isCoprime(r, smallPrimes))
					residues[i++] = r;

			// Reverse lookup
			int[] residueIndex = new int[mod];
			Arrays.fill(residueIndex, -1);
			for (i = 0; i < count; i++)
				residueIndex[residues[i]] = i;

			int[][] jumpFromAny = new int[mod][mod];
			for (int rem = 0; rem < mod; rem++) {
				jumpFromAny[rem][0] = -1;
				for (int pMod = 1; pMod < mod; pMod++) {
					int cycleLen = mod / gcd(pMod, mod); // max steps before repeat
					int k = 1;
					while (k <= cycleLen && residueIndex[(rem + k * pMod) % mod] < 0)
						k++;
					// k > cycleLen -> unreachable (e.g. rem even + pMod even)
					jumpFromAny[rem][pMod] = (k <= cycleLen) ? k : -1;
				}
			}

			return new Wheel(smallPrimes, residues, residueIndex, jumpFromAny);
		}

		/** Wheel modulus = product of small primes. Called once at bootstrap. */
		int modulus() {
			int mod = 1;
			for (int p : smallPrimes)
				mod *= p;
			return mod;
		}

		/** Number of candidate bits per wheel period = phi(modulus). */
		int bitsPerAlignment() {
			return residues.length;
		}

		/**
		 * Whether bitsPerAlignment is a power of two. If true, division/modulo by bpa
		 * can be replaced by faster shift/mask ops. wheel30: bpa=8=2^3 -> true.
		 * wheel210: bpa=48 -> false.
		 */
		boolean bpaIsPowerOfTwo() {
			int bpa = residues.length;
			return (bpa & (bpa - 1)) == 0;
		}

		/**
		 * log2(bitsPerAlignment). Valid only when {@link #bpaIsPowerOfTwo()} is true.
		 */
		int bpaShift() {
			return Integer.numberOfTrailingZeros(residues.length);
		}

		/**
		 * bitsPerAlignment - 1, usable as bitmask when {@link #bpaIsPowerOfTwo()} is
		 * true.
		 */
		int bpaMask() {
			return residues.length - 1;
		}

		/**
		 * Required alignment for tabLen (number of longs in the sieve window).
		 * tabLen*64 must be a multiple of bitsPerAlignment so the window covers a whole
		 * number of wheel periods, keeping bitposToNum/numToBitPos consistent.
		 *
		 * <p>
		 * = bitsPerAlignment / gcd(64, bitsPerAlignment). Examples:
		 * 
		 * <pre>
		 *   wheel30  : gcd(64, 8)=8  -> multiple=1  (no constraint)
		 *   wheel210 : gcd(64,48)=16 -> multiple=3
		 *   wheel2310: gcd(64,480)=32 -> multiple=15
		 * </pre>
		 * </p>
		 */
		int tabLenMultiple() {
			int bpa = residues.length;
			return bpa / gcd(64, bpa);
		}

		/**
		 * Computes a safe, wheel-aligned tabLen from a requested size. Rounds up to the
		 * next multiple of {@link #tabLenMultiple()} and clamps to prevent overflow in
		 * windowRange (= tabLen * 64 * modulus / bpa).
		 */
		int adaptSize(int requestedSize) {
			int m = tabLenMultiple();
			int max = (int) ((long) (Integer.MAX_VALUE / 64) * residues.length / modulus() / m) * m;
			int adjusted = ((requestedSize + m - 1) / m) * m;
			return Math.min(adjusted, max);
		}

		/**
		 * Computes the real numeric range covered by a window of totalBits candidate
		 * bits. = totalBits * modulus / bitsPerAlignment This is always an integer when
		 * totalBits is a multiple of tabLenMultiple()*64.
		 */
		long adaptRange(int totalBits) {
			return (long) totalBits * modulus() / residues.length;
		}

		/**
		 * Returns an iterator over the small primes as negative values. In
		 * {@code get()}, k&lt;0 means bootstrap prime with value {@code -k}. These are
		 * NOT added to the primes list: the wheel already excludes their multiples from
		 * the bit array, so no marking is needed.
		 */
		int[] pendingPrimes() {
			// Tous les premiers < modulus : smallPrimes + résidus premiers < modulus
			IntStream all = Arrays.stream(smallPrimes); // 2, 3, 5
			IntStream wheelPrimes = Arrays.stream(residues).filter(r -> r < modulus() && isPrime(r)); // 7, 11, 13, 17,
																										// 19, 23, 29
			return IntStream.concat(all, wheelPrimes).map(p -> -p).toArray();
		}

		private static boolean isPrime(int n) {
			if (n < 2)
				return false;
			for (int i = 2; i * i <= n; i++)
				if (n % i == 0)
					return false;
			return true;
		}

		private static boolean isCoprime(int r, int[] primes) {
			for (int p : primes)
				if (r % p == 0)
					return false;
			return true;
		}

		private static int gcd(int a, int b) {
			return b == 0 ? a : gcd(b, a % b);
		}

		public void bootstrap(SlidingWindowSieve wheelSieve) {
			int[] pending = pendingPrimes();
			for (int i = smallPrimes.length; i < pending.length; ++i)
				wheelSieve.primes.add(v(-pending[i]));
			wheelSieve.setStart(v(modulus())); // first window starts just after small primes
			wheelSieve.pending = Arrays.stream(pending).iterator();
		}
	}

	abstract class AbstracWheelSieve extends SlidingWindowSieve {
		public AbstracWheelSieve(int size) {
			super(size);
		}

		abstract protected int adaptSize(int size);

		abstract protected long adaptRange(int totalBits);

		abstract protected void bootstrap();

		abstract protected long bitposToNum(int bitpos);

		abstract protected int numToBitpos(int group, int index);

		abstract protected int residue_index(int rem);

		abstract protected int jumps(int rem, int pMod);

		abstract protected int MOD();

		protected void markMultiplesOf(BigInteger start, long tab[], BigInteger p) {
			// Find offset to first multiple of p >= start
			BigInteger n = start.remainder(p);
			if (n.signum() > 0)
				n = p.subtract(n);
			// n = 0..p-1

			if (n.compareTo(windowRange_) >= 0)
				return;

			// Convert to bit position
			long num = n.longValue();
			long pLong = p.longValue();

			final int MOD = MOD();

			// Constants pre-computed once outside loop
			final int pMod = (int) (pLong % MOD);
			final long pDiv = pLong / MOD;

			// Single division to init rem/group
			int rem = (int) (num % MOD);
			int group = (int) (num / MOD);

			// Jump to first wheel candidate (works even if num not in wheel)
			int resIdx = residue_index(rem);
			if (resIdx < 0) {
				int k = jumps(rem, pMod);
				if (k < 0)
					return;
				rem += k * pMod;
				group += k * pDiv;
				while (rem >= MOD) {
					rem -= MOD;
					++group;
				}
				num += k * pLong;
				if (num >= windowRange)
					return;
				resIdx = residue_index(rem); // guaranteed >= 0
			}

			// First bitPos
			int bitPos;
			int i = 0;
			long mask = 0;

			// Main loop: incremental rem/group, zero division, zero modulo
			while ((bitPos = numToBitpos(group, resIdx)) < windowSize) {
				int nextI = bitPos >>> 6;
				int nextB = bitPos & 63;
				if (nextI != i) {
					updateTab(tab, i, mask);
					i = nextI;
					mask = 0;
				}
				mask |= (1L << nextB);

				// Advance
				int k = jumps(rem, pMod);
				if (k < 0)
					break;
				rem += k * pMod;
				group += k * pDiv;
				while (rem >= MOD) {
					rem -= MOD;
					++group;
				}
				resIdx = residue_index(rem);
			}

			updateTab(tab, i, mask);
		}
	}

	final class Wheel2Sieve extends AbstracWheelSieve {
		public Wheel2Sieve(int size) {
			super(size);
		}

		@Override
		protected int adaptSize(int size) {
			return Math.min(size, Integer.MAX_VALUE / 64);
		}

		@Override
		protected long adaptRange(int totalBits) {
			return totalBits * 2L;
		}

		@Override
		protected void bootstrap() {
			setStart(TWO);
			pending = IntStream.of(-2).iterator();
		}

		@Override
		protected long bitposToNum(int bitpos) {
			return bitpos * 2L + 1;
		}

		@Override
		protected int numToBitpos(int group, int index) {
			return group + index;
		}

		@Override
		protected int residue_index(int rem) {
			return (rem & 1) - 1;
		}

		@Override
		protected int jumps(int rem, int pMod) {
			return pMod == 0 ? -1 : rem + 1;
		}

		@Override
		protected int MOD() {
			return 2;
		}
	}

	final class Wheel6Sieve extends AbstracWheelSieve {
		private static final int[] JUMPS = { -1, 1, -1, -1, -1, 1, -1, 4, 2, 2, 1, 2, -1, 3, -1, 1, -1, 1, -1, 2, 1, -1,
				1, 2, -1, 1, -1, 1, -1, 3, -1, 2, 1, 2, 2, 4 };

		public Wheel6Sieve(int size) {
			super(size);
		}

		@Override
		protected int adaptSize(int size) {
			return Math.min(size, Integer.MAX_VALUE / 64);
		}

		@Override
		protected long adaptRange(int totalBits) {
			return 3L * totalBits;
		}

		@Override
		protected void bootstrap() {
			primes.add(v(5));
			setStart(v(6)); // first window starts just after small primes
			pending = IntStream.of(-2, -3, -5).iterator();
		}

		@Override
		protected long bitposToNum(int bitpos) {
			return (bitpos >>> 1) * 6L + ((bitpos & 1) == 0 ? 1 : 5);
		}

		@Override
		protected int numToBitpos(int group, int index) {
			return group * 2 + index;
		}

		@Override
		protected int residue_index(int rem) {
			return switch (rem) {
//			 [-1, 0, -1, -1, -1, 1]
			case 1 -> 0;
			case 5 -> 1;
			default -> -1;
			};
		}

		@Override
		protected int jumps(int rem, int pMod) {
			return JUMPS[rem * 6 + pMod];
		}

		@Override
		protected int MOD() {
			return 6;
		}
	}

	// =========================================================================
	// Wheel30Sieve — 8 candidates per 30 integers (~73% fewer than odd-only)
	// =========================================================================

	/**
	 * Wheel-30 sieve. Skips all multiples of 2, 3, 5. Only the wheel-specific
	 * methods are overridden; everything else is inherited.
	 */
	final class Wheel30Sieve extends AbstracWheelSieve {

		private static final Wheel WHEEL = Wheel.of(2, 3, 5);

		// Copy into static finals for zero-indirection access at runtime
		private static final int[] RESIDUES = WHEEL.residues(); // {1,7,11,13,17,19,23,29}
		private static final int[] RESIDUE_INDEX = WHEEL.residueIndex(); // size 30
		private static final int[][] JUMPS = WHEEL.jumpFromAny();

		// bpa=8=2^3: replace /8 and %8 by >>3 and &7
		private static final int MOD = WHEEL.modulus(); // 30
//		private static final int BPA_SHIFT = WHEEL.bpaShift(); // 3
//		private static final int BPA_MASK = WHEEL.bpaMask(); // 7

		public Wheel30Sieve(int size) {
			super(size);
		}

		@Override
		protected int adaptSize(int size) {
			return WHEEL.adaptSize(size);
		}

		@Override
		protected long adaptRange(int totalBits) {
			return WHEEL.adaptRange(totalBits);
		}

		@Override
		protected void bootstrap() {
			WHEEL.bootstrap(this);
		}

		/** bit k -> (k >> 3) * 30 + RESIDUES[k & 7] (shift/mask since bpa=8=2^3) */
		@Override
		protected long bitposToNum(int bitpos) {
			//return (bitpos >>> BPA_SHIFT) * MOD + RESIDUES[bitpos & BPA_MASK];
			return (bitpos >>> 3) * 30L + RESIDUES[bitpos & 7];
		}

		protected int numToBitpos(int group, int index) {
			//return (group << BPA_SHIFT) + index;
			return (group << 3) + index;
		}

		@Override
		protected int residue_index(int rem) {
			return RESIDUE_INDEX[rem];
		}

		@Override
		protected int jumps(int rem, int pMod) {
			return JUMPS[rem][pMod];
		}

		@Override
		protected int MOD() {
			return MOD;
		}
	}

	// =========================================================================
	// Wheel210Sieve — 48 candidates per 210 integers (~77% fewer than odd-only)
	// =========================================================================

	/**
	 * Wheel-210 sieve. Skips all multiples of 2, 3, 5, 7. Only the wheel-specific
	 * methods are overridden; everything else is inherited.
	 */
	final class Wheel210Sieve extends AbstracWheelSieve {

		private static final Wheel WHEEL = Wheel.of(2, 3, 5, 7);

		private static final int[] RESIDUES = WHEEL.residues(); // 48 values
		private static final int[] RESIDUE_INDEX = WHEEL.residueIndex(); // size 210
		private static final int[][] JUMPS = WHEEL.jumpFromAny(); // size 210

		// bpa=48 is NOT a power of 2: plain / and % required
		private static final int MOD = WHEEL.modulus(); // 210
		private static final int BPA = WHEEL.bitsPerAlignment(); // 48

		public Wheel210Sieve(int size) {
			super(size); // (size+MOD/6-1)/(MOD/6));
		}

		@Override
		protected int adaptSize(int size) {
			return WHEEL.adaptSize(size);
		}

		@Override
		protected long adaptRange(int totalBits) {
			return WHEEL.adaptRange(totalBits);
		}

		@Override
		protected void bootstrap() {
			WHEEL.bootstrap(this);
		}

		/** bit k -> (k/48) * 210 + RESIDUES[k%48] (no power-of-2 shortcut for 48) */
		@Override
		protected long bitposToNum(int bitpos) {
			return (bitpos / BPA) * MOD + RESIDUES[bitpos % BPA];
		}

		/** n -> (n/210)*48 + RESIDUE_INDEX[n%210] */
		@Override
		protected int numToBitpos(int group, int index) {
			return group * BPA + index;
		}

		@Override
		protected int residue_index(int rem) {
			return RESIDUE_INDEX[rem];
		}

		@Override
		protected int jumps(int rem, int pMod) {
			return JUMPS[rem][pMod];
		}

		@Override
		protected int MOD() {
			return MOD;
		}
	}

	final class Wheel2310Sieve extends AbstracWheelSieve {

		private static final Wheel WHEEL = Wheel.of(2, 3, 5, 7, 11);

		private static final int[] RESIDUES = WHEEL.residues(); // 48 values
		private static final int[] RESIDUE_INDEX = WHEEL.residueIndex(); // size 210
		private static final int[][] JUMPS = WHEEL.jumpFromAny(); // size 210

		// bpa=48 is NOT a power of 2: plain / and % required
		private static final int MOD = WHEEL.modulus(); // 210
		private static final int BPA = WHEEL.bitsPerAlignment(); // 48

		public Wheel2310Sieve(int size) {
			super(size); // (size+MOD/6-1)/(MOD/6));
		}

		@Override
		protected int adaptSize(int size) {
			return WHEEL.adaptSize(size);
		}

		@Override
		protected long adaptRange(int totalBits) {
			return WHEEL.adaptRange(totalBits);
		}

		@Override
		protected void bootstrap() {
			WHEEL.bootstrap(this);
		}

		/** bit k -> (k/48) * 210 + RESIDUES[k%48] (no power-of-2 shortcut for 48) */
		@Override
		protected long bitposToNum(int bitpos) {
			return (bitpos / BPA) * MOD + RESIDUES[bitpos % BPA];
		}

		/** n -> (n/210)*48 + RESIDUE_INDEX[n%210] */
		@Override
		protected int numToBitpos(int group, int index) {
			return group * BPA + index;
		}

		@Override
		protected int residue_index(int rem) {
			return RESIDUE_INDEX[rem];
		}

		@Override
		protected int jumps(int rem, int pMod) {
			return JUMPS[rem][pMod];
		}

		@Override
		protected int MOD() {
			return MOD;
		}
	}

	@Override
	SieveGap.SlidingWindowSieve newSlidingWindowSieve(int size) {
		return new Wheel210Sieve(size);
	}

	public static void main(String[] args) {
		new WheelSieveGap().run();
	}

}
