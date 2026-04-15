package primegap.naive;

import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import primegap.IterativePrimeGap;

/**
 * This implementation uses a parallelized version of the Miller-Rabin primality
 * test to check for primality. The Miller-Rabin test is a probabilistic test that
 * can quickly determine if a number is composite or probably prime. By running
 * multiple iterations of the test in parallel, we can increase the confidence
 * level of our primality checks while still maintaining good performance.
 */
public class ParallelMillerRabinGap extends IterativePrimeGap {
	@Override
	protected BigInteger nextPrimeImpl(BigInteger N) {
		BigInteger P = N.add(N.testBit(0) ? TWO : ONE);
		while (!isPrime(P))
			P = P.add(TWO);
		return P;
	}

	public boolean isPrime(BigInteger N) {
		if (N.testBit(0) == false)
			return N.equals(TWO);

		return IntStream.range(0, 1).parallel().allMatch(i -> i == 0 //
				? N.isProbablePrime(1) // <= also contains Miller-Rabin.
				: passesParallelMillerRabin(N, MILLER_RABIN_PASSES - 1));
	}

	/**
	 * Returns true iff this BigInteger passes the specified number of Miller-Rabin
	 * tests. This test is taken from the DSA spec (NIST FIPS 186-2).
	 *
	 * The following assumptions are made: This BigInteger is a positive, odd number
	 * greater than 2. iterations<=50.
	 */
	static protected boolean passesParallelMillerRabin(BigInteger N, int iterations) {
		// Find a and m such that m is odd and this == 1 + 2**a * m
		BigInteger thisMinusOne = N.subtract(ONE);
		BigInteger m_ = thisMinusOne;
		int a = m_.getLowestSetBit();
		BigInteger m = m_.shiftRight(a);

		return IntStream.range(0, iterations).parallel().allMatch(ignored -> {
			Random rnd = ThreadLocalRandom.current();
			// Generate a uniform random on (1, this)
			BigInteger b;
			do {
				b = new BigInteger(N.bitLength(), rnd);
			} while (b.compareTo(ONE) <= 0 || b.compareTo(N) >= 0);

			int j = 0;
			BigInteger z = b.modPow(m, N);
			while (!((j == 0 && z.equals(ONE)) || z.equals(thisMinusOne))) {
				if (j > 0 && z.equals(ONE) || ++j == a)
					return false;
				z = z.modPow(TWO, N);
			}

			return true;
		});
	}

	public static void main(String[] args) throws Exception {
		new ParallelMillerRabinGap().run();
	}
}
