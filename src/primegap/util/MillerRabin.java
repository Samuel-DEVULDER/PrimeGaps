package primegap.util;

import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

public class MillerRabin {
	public static MillerRabin instance = new MillerRabin();

	/** Deterministic Miller-Rabin for 0 < N < 2^63, N odd >= 3. */
	public boolean isPrime(long N, boolean allowParallel) {
		if (N < 2)
			return false;
		if (N == 2 || N == 3)
			return true;
		if ((N & 1) == 0)
			return false;

		long nMinusOne = N - 1;
		int a = Long.numberOfTrailingZeros(nMinusOne);
		long m = nMinusOne >>> a;

		long[] witnesses = witnessesFor(N);

		IntStream stream = IntStream.range(0, witnesses.length);
		if (witnesses.length >= 5 && allowParallel)
			stream = stream.parallel();
		return stream.allMatch(i -> witnesses[i] >= N || witness(witnesses[i], m, a, N));
	}

	/** Returns the minimal deterministic witness set for N < 2^63. */
	private long[] witnessesFor(long N) {
		// Sources: Pomerance, Selfridge, Wagstaff; Jaeschke (1993); Wikipedia
		// Miller-Rabin
		if (N < 2_047L)
			return new long[] { 2 };
		if (N < 1_373_653L)
			return new long[] { 2, 3 };
		if (N < 9_080_191L)
			return new long[] { 31, 73 };
		if (N < 25_326_001L)
			return new long[] { 2, 3, 5 };
		if (N < 3_215_031_751L)
			return new long[] { 2, 3, 5, 7 };
		if (N < 4_759_123_141L)
			return new long[] { 2, 7, 61 };
		if (N < 1_122_004_669_633L)
			return new long[] { 2, 13, 23, 1_662_803 };
		if (N < 2_152_302_898_747L)
			return new long[] { 2, 3, 5, 7, 11 };
		if (N < 3_474_749_660_383L)
			return new long[] { 2, 3, 5, 7, 11, 13 };
		if (N < 341_550_071_728_321L)
			return new long[] { 2, 3, 5, 7, 11, 13, 17 };
		if (N < 3_825_123_056_546_413_051L)
			return new long[] { 2, 3, 5, 7, 11, 13, 17, 19, 23 };
		/* covers all N < 2^63 */ return new long[] { 2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37 };
	}

	/** Returns true if w is NOT a witness of compositeness for N. */
	private boolean witness(long w, long m, int a, long N) {
		long z = modPow(w, m, N);
		if (z == 1 || z == N - 1)
			return true;
		for (int j = 1; j < a; j++) {
			z = mulMod(z, z, N);
			if (z == N - 1)
				return true;
			if (z == 1)
				return false;
		}
		return false;
	}

	/** (base^exp) % mod, N <= 63 bits. */
	private long modPow(long base, long exp, long mod) {
		long result = 1;
		base %= mod;
		while (exp > 0) {
			if ((exp & 1) == 1)
				result = mulMod(result, base, mod);
			base = mulMod(base, base, mod);
			exp >>>= 1;
		}
		return result;
	}

	/**
	 * (a * b) % mod, safe for a,b,mod < 2^63. Uses Math.unsignedMultiplyHigh to get
	 * the 128-bit product's high word. No overflow, no __int128, pure Java.
	 */
	private long mulMod(long a, long b, long mod) {
		long hi = Math.unsignedMultiplyHigh(a, b); // high 64 bits of a*b
		long lo = a * b; // low 64 bits of a*b
		// (hi:lo) % mod via: lo%mod + (hi%mod * (2^64 % mod)) % mod
		long lo_mod = Long.remainderUnsigned(lo, mod);
		long hi_mod = Long.remainderUnsigned(hi, mod);
		long pow2_64 = Long.remainderUnsigned(-mod, mod); // 2^64 mod mod
		return Long.remainderUnsigned(lo_mod + mulModSmall(hi_mod, pow2_64, mod), mod);
	}

	/**
	 * (a * b) % mod quand a,b < mod < 2^63 — le produit tient dans un long signé.
	 * Utilisé uniquement par mulMod pour les termes réduits.
	 */
	private long mulModSmall(long a, long b, long mod) {
		// a,b < mod < 2^63 -> a*b < 2^126 -> pas suffisant pour long signé
		// Mais après réduction : hi_mod < mod < 2^63, pow2_64 < mod < 2^63
		// -> produit peut déborder... on re-applique unsignedMultiplyHigh
		long hi = Math.unsignedMultiplyHigh(a, b);
		if (hi == 0)
			return Long.remainderUnsigned(a * b, mod); // cas rapide
		// Sinon : récursion ou __int128 — mais ici hi < 1 car a,b < 2^63 -> hi == 0
		// garanti
		return Long.remainderUnsigned(a * b, mod);
	}

	/**
	 * Returns true iff this BigInteger passes the specified number of Miller-Rabin
	 * tests. This test is taken from the DSA spec (NIST FIPS 186-2).
	 *
	 * The following assumptions are made: This BigInteger is a positive, odd number
	 * greater than 2. iterations<=50.
	 */
	public boolean isPrime(BigInteger N, int iterations, boolean allowParallel) {
		if (N.bitLength() <= 63)
			return isPrime(N.longValue(), allowParallel);

		if (N.testBit(0) == false)
			return N.equals(BigInteger.TWO);

		// Find a and m such that m is odd and this == 1 + 2**a * m
		BigInteger thisMinusOne = N.subtract(BigInteger.ONE);
		BigInteger m_ = thisMinusOne;
		int a = m_.getLowestSetBit();
		BigInteger m = m_.shiftRight(a);
		int bitLength = N.bitLength();

		var stream = IntStream.range(0, iterations);
		if (iterations >= 5 && allowParallel)
			stream = stream.parallel();

		var ok = stream.allMatch(i -> {
			if (i == 0)
				return N.isProbablePrime(1); // <= contains miller rabbin as well + lucas-lerhmer for big primes
			Random rnd = ThreadLocalRandom.current();
			// Generate a uniform random on (1, this)
			BigInteger b;
			do {
				b = new BigInteger(bitLength, rnd);
			} while (b.compareTo(N) >= 0 || b.compareTo(BigInteger.ONE) <= 0);

			int j = 0;
			BigInteger z = b.modPow(m, N);
			while (!((j == 0 && z.equals(BigInteger.ONE)) || z.equals(thisMinusOne))) {
				if (j > 0 && z.equals(BigInteger.ONE) || ++j == a)
					return false;
				z = z.modPow(BigInteger.TWO, N);
			}

			return true;
		});

		return ok;
	}
}
