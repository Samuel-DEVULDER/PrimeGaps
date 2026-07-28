package primegap.util;
import java.util.Random;

public class CacheProbe {

    // Configuration de la mesure
    static final int MIN_SIZE = 4 * 1024;          // 4 KB
    static final int MAX_SIZE = 256 * 1024 * 1024; // 256 MB
    static final int STEPS    = 40;               // nombre de tailles testées
    static final int HOPS     = 1 << 20;          // nombre de sauts par taille
    static final int REPEATS  = 3;                // répétitions par taille
    static final double JUMP_RATIO = 1.5;         // seuil de saut de latence

    public static void main(String[] args) {
        int[] sizes = buildSizes(MIN_SIZE, MAX_SIZE, STEPS);
        double[] lat = measureLatencies(sizes);

        System.out.println("=== Cache probing (pointer chasing) ===");
        for (int i = 0; i < sizes.length; i++) {
            System.out.printf("size=%8d KB  latency=%6.2f ns%n", sizes[i] / 1024, lat[i]);
        }

        findBoundaries(sizes, lat);
    }

    // Génère une suite de tailles (progression géométrique)
    static int[] buildSizes(int min, int max, int steps) {
        int[] sizes = new int[steps];
        double r = Math.pow((double) max / min, 1.0 / (steps - 1));
        double sz = min;
        for (int i = 0; i < steps; i++) {
            sizes[i] = (int) Math.round(sz);
            sz *= r;
        }
        return sizes;
    }

    // Construction d'une liste chaînée en permutation circulaire
    static int[] buildChain(int len) {
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) arr[i] = i;

        Random rng = new Random(42);
        for (int i = len - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = arr[i];
            arr[i] = arr[j];
            arr[j] = t;
        }

        int[] chain = new int[len];
        for (int i = 0; i < len - 1; i++) {
            chain[arr[i]] = arr[i + 1];
        }
        chain[arr[len - 1]] = arr[0];
        return chain;
    }

    // Mesure la latence (ns par saut) pour chaque taille
    static double[] measureLatencies(int[] sizes) {
        double[] lat = new double[sizes.length];

        // petit échauffement
        int[] warm = buildChain(64 * 1024 / 4);
        int w = 0;
        long end = Machine.getCpuTimeNano() + 500_000_000L;
        while (Machine.getCpuTimeNano()  < end) {
            w = warm[w];
        }

        for (int i = 0; i < sizes.length; i++) {
            int bytes = sizes[i];
            int len = Math.max(1, bytes / 4);
            int[] chain = buildChain(len);

            double best = Double.POSITIVE_INFINITY;
            for (int r = 0; r < REPEATS; r++) {
                int idx = 0;
                long t0 = Machine.getCpuTimeNano();
                for (int h = 0; h < HOPS; h++) {
                    idx = chain[idx];
                }
                long dt = Machine.getCpuTimeNano() - t0;
                double ns = (double) dt / HOPS;
                if (ns < best) best = ns;
            }
            lat[i] = best;
        }

        return lat;
    }

    // Détection empirique des fins de cache par sauts de latence
    static void findBoundaries(int[] sizes, double[] lat) {
        System.out.println();
        System.out.println("=== Detected cache boundaries (heuristic) ===");

        for (int i = 1; i < sizes.length - 2; i++) {
            double prev = lat[i - 1];
            double cur  = lat[i];
            double next = lat[i + 1];
            double after = lat[i + 2];

            if (!Double.isFinite(prev) || !Double.isFinite(cur) ||
                !Double.isFinite(next) || !Double.isFinite(after)) {
                continue;
            }

            boolean jump = cur / prev >= JUMP_RATIO;
            boolean durable = next / cur >= 0.9 && after / cur >= 0.9;

            if (jump && durable) {
                int boundaryBytes = sizes[i - 1];
                System.out.printf("possible cache end around %d KB (latency ~%.2f ns)%n",
                                  boundaryBytes / 1024, lat[i - 1]);
            }
        }
    }
}