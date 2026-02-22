import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class PrimeGap2 {

	int sqrt(int n) {
		return BigInteger.valueOf(n).sqrt().intValueExact();
	}

	// todo Stream ?
	List<Integer> primesUpto(int n) {
		List<Integer> list = new ArrayList<>();

		BitSet bs = new BitSet(n);
		for (int p = 1; (p = bs.nextClearBit(p + 1)) >= 0 && p <= n;) {
			list.add(p);
			for (int k = p * p; k <= n; k += p) {
				bs.set(k);
			}
		}

		return list;
	}

	void findCovering(int n, List<Integer> pi, List<Integer> ai) {
		BitSet bs = new BitSet(n + 1);
		pi.clear();
		ai.clear();

		for (BigInteger p_ = BigInteger.valueOf(2); true; p_ = p_.nextProbablePrime()) {
			int p = p_.intValueExact();
			int bestA, bestC;

			for(int i = 3; i<4;++i) {
				bestA = bestC = -1;
				for (int a = 0; a < p; ++a) {
					int c = 0;
					for (int j = a == 0 ? p : a; j <= n; j += p) {
						if (!bs.get(j))
							++c;
					}
					// greedy
					if (c > bestC) {
						bestC = c;
						bestA = a;
					}
				}

				if (bestC > 0) {
					pi.add(p);
					ai.add(bestA == 0 ? 0 : p - bestA);
					for (int j = bestA == 0 ? p : bestA; j <= n; j += p) {
						bs.set(j);
					}
					if (bs.cardinality() == n)
						return;
				} else break;
			}
		}
	}

	BigInteger crt(List<Integer> pi, List<Integer> ai) {
		int n = pi.size();
		BigInteger P = BigInteger.ONE;
		
		for (Integer p : pi)
			P = P.multiply(BigInteger.valueOf(p));

		BigInteger x = BigInteger.ZERO;
		for (int i = 0; i < n; i++) {
			BigInteger p_i = BigInteger.valueOf(pi.get(i));
			BigInteger a_i = BigInteger.valueOf(ai.get(i));
			BigInteger P_i = P.divide(p_i);
			BigInteger y_i = P_i.modInverse(p_i);
			x = x.add(a_i.multiply(P_i).multiply(y_i));

		}
		x = x.mod(P);
		if (BigInteger.ZERO.equals(x))
			x = P;
		return x;
	}

	int prime(int rank) {
		int k = 2;
		List<Integer> l;
		do {
			l = primesUpto(k += k);
		} while (l.size() <= rank - 1);
		return l.get(rank - 1);
	}

	BigInteger factorial(int n) {
		return IntStream.range(2, n).mapToObj(BigInteger::valueOf).reduce(BigInteger.ONE, BigInteger::multiply);
	}

	BigInteger primorial(int n) {
		return primesUpto(n).stream().map(BigInteger::valueOf).reduce(BigInteger.ONE, BigInteger::multiply);
	}

	BigInteger findAround(int gap, BigInteger N) {
		List<Integer> list = primesUpto(gap).stream().filter(i -> i > 99)
				.collect(Collectors.toCollection(ArrayList::new));
		Collections.shuffle(list);

		if (list.isEmpty())
			list.add(2);

		Set<BigInteger> tested = Collections.newSetFromMap(new ConcurrentHashMap<>());

		BigInteger p = list.parallelStream().map(step -> {
			BigInteger t;
			int k = 0;

			do {
				k += step;
				t = N.subtract(BigInteger.valueOf(k));
				if (tested.add(t) == false)
					continue;
				t = t.nextProbablePrime();
			} while (t.compareTo(N) > 0);
			return t;
		}).findAny().get(), q;

		do {
			q = p;
			p = p.nextProbablePrime();
		} while (p.compareTo(N) <= 0);

		return q;
	}

	BigInteger find1(int gap) {
		return findAround(gap, factorial(gap).add(BigInteger.TWO));
	}

	BigInteger find2(int gap) {
		return findAround(gap, primorial(gap).add(BigInteger.TWO));
	}

	BigInteger find3(int gap) {
		List<Integer> pi = new ArrayList<>(), ai = new ArrayList<>();

		findCovering(gap, pi, ai);
		BigInteger N = crt(pi, ai);
		System.out.println("#primes = " + pi.size());
		System.out.println("biggest = " + pi.getLast());
		// System.out.println("#bits = " + N.bitLength());

		return findAround(gap, N);
	}

	void run() {
		// BigInteger p1 = new BigInteger("1693182318746371");
		double total = 0;

		int last = 0;
		for (int gap = 3; gap < 1 << 20; gap += 2)
			if (gap > last) {
				System.out.println("Searching gap >= " + gap);

				double time = System.currentTimeMillis();
				BigInteger p1 = find3(gap + 1);
				time = System.currentTimeMillis() - time;
				total += time;

				BigInteger p2 = p1.nextProbablePrime();

				float prev = last;
				last = p2.subtract(p1).intValueExact();

				System.out.println(">> " + p2.subtract(p1) + "\n + " + p1 + "\n = " + p2);
				System.out.println(String.format("%.3fs (tot=%.3fs), %d bits, %d digits, x%.2f prev.\n", time / 1000,
						total / 1000, p2.bitLength(), p2.toString().length(), last / prev));
			}
	}

	public static void main(String[] args) {
		new PrimeGap2().run();
	}
}
