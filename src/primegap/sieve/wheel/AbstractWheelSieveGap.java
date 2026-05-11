package primegap.sieve.wheel;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import primegap.sieve.AbstractSlidingWindowSieve;
import primegap.sieve.SieveGap;

/**
 * SieveGap implementation using wheel factorization to skip candidates
 * divisible by small primes.
 * <p>
 * This class defines an abstract wheel sieve gap that utilizes wheel
 * factorization to optimize the sieving process. It includes a nested Wheel
 * record that encapsulates the wheel factorization data and provides methods
 * for bootstrapping the sieve and marking multiples of primes. The WheelSieve
 * class extends the AbstractSlidingWindowSieve and implements the necessary
 * methods for mapping between bit positions and candidate numbers based on the
 * wheel's properties.
 */
public abstract class AbstractWheelSieveGap extends SieveGap {

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
	protected record Wheel(int[] smallPrimes, int[] residues, int[] residueIndex, int[][] jumpFromAny) {

		/**
		 * Builds a wheel from the given small primes. The modulus is computed as their
		 * product.
		 *
		 * @param smallPrimes e.g. {@code 2,3,5} for wheel30
		 */
		static Wheel of(int... smallPrimes_) {
			int[] smallPrimes = new int[smallPrimes_.length];
			int mod = 1;
			for (int i = 0; i < smallPrimes_.length; i++) {
				int p = smallPrimes_[i];
				if (p < 2 || p > 127 || !isPrime(p))
					throw new IllegalArgumentException("Invalid prime: " + p);
				smallPrimes[i] = p;
				mod *= p;
			}

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
			List<Integer> list = new ArrayList<>();
			for (int b : smallPrimes)
				list.add(-b);
			// Tous les premiers < modulus : smallPrimes + re sidus premiers < modulus
			for (int r : residues)
				if (r < modulus() && isPrime(r))
					list.add(-r);
			int[] arr = new int[list.size()];
			for (int i = 0; i < arr.length; i++)
				arr[i] = list.get(i);
			return arr;
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

		public void bootstrap(AbstractSlidingWindowSieve wheelSieve) {
			int[] pending = pendingPrimes();
			for (int i = smallPrimes.length; i < pending.length; ++i)
				wheelSieve.primes.add(v(-pending[i]));
			wheelSieve.setStart(v(modulus())); // first window starts just after small primes
			wheelSieve.pending = Arrays.stream(pending).iterator();
		}
	}

	protected abstract class AbstracWheelSieve extends AbstractSlidingWindowSieve {
		protected AbstracWheelSieve(int size, long range, boolean doubleBuffer) {
			super(AbstractWheelSieveGap.this, size, range, doubleBuffer);
		}

		abstract protected void bootstrap();

		abstract protected int numToBitpos(int group, int index);

		abstract protected int residue_index(int rem);

		abstract protected int jumps(int rem, int pMod);

		abstract protected int MOD();

		@Override
		protected String name() {
			if (name == null)
				name = "/WHEEL_" + MOD() + super.name();
			return name;
		}

		private String name;

		protected void markMultiplesOf(BigInteger start, long tab[], BigInteger p) {
			long num;
			long pLong;

			if (windowEndIsLong) {
				// Find offset to first multiple of p >= start
				pLong = p.longValue();
				num = start.longValue() % pLong;
				if (num != 0)
					num = pLong - num;
				if (num >= windowRange)
					return;
			} else {
				// Find offset to first multiple of p >= start
				BigInteger n = start.remainder(p);
				if (n.signum() > 0)
					n = p.subtract(n);
				// n = 0..p-1

				if (n.compareTo(windowRange_bigint) >= 0)
					return;

				// Convert to bit position
				num = n.longValue();
				pLong = p.longValue();
			}

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

	protected class WheelSieve extends AbstracWheelSieve {
		final Wheel WHEEL;

		final int[] RESIDUES;
		final int[] RESIDUE_INDEX;
		final int[][] JUMPS;

		final int MOD;
		final int BPA;

		protected WheelSieve(int size, boolean doubleBuffer, int... primes) {
			this(size, Wheel.of(primes), doubleBuffer);
			bootstrap();
		}

		protected WheelSieve(int size, Wheel w, boolean doubleBuffer) {
			super(w.adaptSize(size), w.adaptRange(w.adaptSize(size) * 64), doubleBuffer);

			WHEEL = w;

			RESIDUES = WHEEL.residues();
			RESIDUE_INDEX = WHEEL.residueIndex();
			JUMPS = WHEEL.jumpFromAny();

			MOD = WHEEL.modulus();
			BPA = WHEEL.bitsPerAlignment();

			bootstrap();
		}

		protected void bootstrap() {
			WHEEL.bootstrap(this);
		}

		/** bit k -> (k/48) * 210 + RESIDUES[k%48] (no power-of-2 shortcut for 48) */
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

	protected WheelSieve newWheelSieve(int size, boolean doubleBuffer, int... primes) {
		return new WheelSieve(size, doubleBuffer, primes);
	}
}
