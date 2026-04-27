package primegap.naive;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import primegap.util.IncreasingBigIntegers;
import primegap.util.Java;

public class PreviousPrimesGap extends SIMDWheelGap {
	static boolean enabled = Java.SIMD.enable();
	final IncreasingBigIntegers primes;

	public PreviousPrimesGap() {
		primes = new IncreasingBigIntegers(1 << 24);
		primes.add(TWO);
	}

	public boolean isDivisibleBy(BigInteger P, BigInteger Q) {
		if (P.bitLength() < 64 && Q.bitLength() < 64) {
			return (P.longValue() % Q.longValue()) == 0L;
		} else {
			return P.remainder(Q).signum() == 0;
		}
	}

	List<BigInteger> testList = new ArrayList<>();
	{
		testList.add(TWO);
	}

	@Override
	public boolean isPrime(BigInteger N) {
		if (N.compareTo(primes.getLast()) <= 0) {
			return primes.stream().anyMatch(p -> p.equals(N));
		} else {
			var last = testList.getLast();
			if (last.multiply(last).compareTo(N) < 0) {
				for (var p : primes) {
					if (p.compareTo(last) <= 0) {
						// skip
					} else {
						testList.add(p);
						if (p.multiply(p).compareTo(N) > 0)
							break;
					}
				}
			}
			var stream = testList.stream();
			if (testList.size() > 2048)
				stream = stream.parallel();
			var ok = !stream.anyMatch(p -> isDivisibleBy(N, p));
			if (ok && N.compareTo(primes.getLast()) > 0)
				primes.add(N);
			return ok;
		}
	}

	public static void main(String[] args) {
		new PreviousPrimesGap().run();
	}
}
