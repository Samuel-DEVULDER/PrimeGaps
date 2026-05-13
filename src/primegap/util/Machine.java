package primegap.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility class to gather and print machine information, including CPU model
 * and cache sizes. It also includes a method to probe the cache hierarchy by
 * measuring memory access latencies for different buffer sizes.
 */
public class Machine {
	public static void printMachineInfo(PrintStream out) {
		Runtime rt = Runtime.getRuntime();
		out.printf("\r%-80s%n", "=== Machine Info ===");
		out.printf("OS      : %s %s (%s)%n", System.getProperty("os.name"), System.getProperty("os.version"),
				System.getProperty("os.arch"));
		out.printf("JVM     : %s %s (%s)%n", System.getProperty("java.vm.name"), System.getProperty("java.version"),
				System.getProperty("java.vm.vendor"));
		out.printf("CPUs    : %d%n", rt.availableProcessors());
		out.printf("RAM     : %.1f GB total%n", rt.maxMemory() / 1e9);
		printCpuModel(out);
		out.printf("====================%n");
	}

	public static void printCpuModel(PrintStream out) {
		String os = System.getProperty("os.name").toLowerCase();
		String[] cmd;
		if (os.contains("linux"))
			cmd = new String[] { "sh", "-c", "lscpu | grep -E 'Model name|L1|L2|L3'" };
		else if (os.contains("mac"))
			cmd = new String[] { "sh", "-c", "sysctl -n machdep.cpu.brand_string" };
		else // Windows
			cmd = new String[] { "cmd", "/c", "wmic cpu get name,L2CacheSize,L3CacheSize" };

		try {
			Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			new String(proc.getInputStream().readAllBytes()).lines().filter(l -> !l.isBlank()).forEach(out::println);
		} catch (IOException ignore) {
		}
	}

	@SuppressWarnings("resource")
	public static int probeCache(PrintStream out) {
		out = out == null ? NullStream.instance : out;
		out.println("=== Cache probing (pointer chasing) ===");

		class ChainBuilder {
			int[] buildChain(int len) {
				int[] arr = new int[len];
				for (int i = 0; i < len; i++)
					arr[i] = i;
				java.util.Random rng = new java.util.Random(42);
				for (int i = len - 1; i > 0; i--) {
					int j = rng.nextInt(i + 1);
					int tmp = arr[i];
					arr[i] = arr[j];
					arr[j] = tmp;
				}
				int[] chain = new int[len];
				for (int i = 0; i < len - 1; i++)
					chain[arr[i]] = arr[i + 1];
				chain[arr[len - 1]] = arr[0];
				return chain;
			}
		}
		var chainBuilder = new ChainBuilder();

		// JIT warm-up
		int[] w = chainBuilder.buildChain(256 * 1024 / 4);
		long wEnd = System.nanoTime() + 3_000_000_000L;
		int idx = 0;
		while (System.nanoTime() < wEnd)
			idx = w[idx];
		if (idx < 0)
			out.print("");

		// Sizes: one per octave (x2), from 8 KB to 64 MB
		List<Integer> sizeList = new ArrayList<>();
		for (int sz = 8 * 1024; sz <= 64 * 1024 * 1024; sz *= 2)
			sizeList.add(sz);
		int steps = sizeList.size();
		int[] sizes = sizeList.stream().mapToInt(Integer::intValue).toArray();
		double[] latencies = new double[steps];

		int REPEATS = 7;
		int HOPS = 1 << 23;

		for (int s = 0; s < steps; s++) {
			int len = sizes[s] / 4;
			int[] chain = chainBuilder.buildChain(len);

			double[] samples = new double[REPEATS];
			for (int r = 0; r < REPEATS; r++) {
				int cur = 0;
				long t0 = System.nanoTime();
				for (int i = 0; i < HOPS; i++)
					cur = chain[cur];
				long dt = System.nanoTime() - t0;
				if (cur < 0)
					out.print("");
				samples[r] = (double) dt / HOPS;
			}
			java.util.Arrays.sort(samples);
			latencies[s] = samples[REPEATS / 2];
			out.printf("size=%7d KB  latency=%6.1f ns%n", sizes[s] / 1024, latencies[s]);
		}

		// JIT stabilization: 3 consecutive points within +/-30% of their average
		int startDetect = 0;
		for (int s = 0; s < steps - 3; s++) {
			double a = latencies[s], b = latencies[s + 1], c = latencies[s + 2];
			double avg = (a + b + c) / 3.0;
			if (avg > 0 && Math.abs(a - avg) / avg < 0.30 && Math.abs(b - avg) / avg < 0.30
					&& Math.abs(c - avg) / avg < 0.30) {
				startDetect = s;
				break;
			}
		}
		out.printf("  (JIT stable from %d KB)%n", sizes[startDetect] / 1024);

		// Threshold detection via sliding window 2+2
		List<Integer> thresholds = new ArrayList<>();
		for (int s = startDetect + 2; s < steps - 2; s++) {
			double before = (latencies[s - 2] + latencies[s - 1]) / 2.0;
			double after = (latencies[s] + latencies[s + 1]) / 2.0;
			boolean bigJump = after / before > 1.6;
			boolean farEnough = thresholds.isEmpty() || sizes[s] > thresholds.get(thresholds.size() - 1) * 4;
			if (bigJump && farEnough)
				thresholds.add(sizes[s - 1]);
		}

		// Print raw thresholds
		out.println("\n=== Cache boundaries ===");
		for (int i = 0; i < thresholds.size() && i < 3; i++)
			out.printf("  boundary #%d : ~%d KB%n", i + 1, thresholds.get(i) / 1024);

		// Heuristic: if boundary#1 < 128 KB -> it is L1 end, use boundary#2 for L2
		int targetIdx = 0;
		if (thresholds.size() >= 2 && thresholds.get(0) < 128 * 1024)
			targetIdx = 1;

		int optimal;
		if (targetIdx < thresholds.size()) {
			int l2end = thresholds.get(targetIdx);
			optimal = Integer.highestOneBit(l2end);
			if (l2end > optimal + optimal / 2)
				optimal <<= 1;
			out.printf("  => estimated L2 end : ~%d KB%n", l2end / 1024);
		} else {
			optimal = 256 * 1024;
			out.println("  (L2 boundary not detected, fallback 256 KB)");
		}

		out.printf("%n=> Optimal sieve size : %d KB%n%n", optimal / 1024);
		return optimal;
	}

	public static long getCpuTimeNano() {
		return timer.getAsLong();
	}

	private static final LongSupplier timer = initTimer();

	private static LongSupplier initTimer() {
		ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
		if (tmx.isThreadCpuTimeSupported()) {
			tmx.setThreadCpuTimeEnabled(true);
			assert Java.dbg("[timer] using thread CPU time");
			return tmx::getCurrentThreadCpuTime;
		}
		assert Java.dbg("[timer] fallback to nanoTime");
		return System::nanoTime;
	}

	private static class StayAwake {
		boolean installed = false;
		int noSleep = 0;

		private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

		private static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase().contains("linux");

		// --- Windows (FFM) ---
		private MethodHandle winHandle;

		private static final int ES_CONTINUOUS = 0x80000000;
		private static final int ES_SYSTEM_REQUIRED = 0x00000001;

		@SuppressWarnings("preview")
		private void initWindows() throws Exception {
			if (winHandle != null)
				return;

			Linker linker = Linker.nativeLinker();
			SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

			winHandle = linker.downcallHandle(kernel32.find("SetThreadExecutionState").get(),
					FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
		}

		// --- Linux ---
		private Process inhibitor;

		// --- API publique ---
		public void preventSleep() {
			if (noSleep++ > 0)
				return;
			try {
				if (IS_WINDOWS) {
					initWindows();
					winHandle.invokeExact(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
				} else if (IS_LINUX) {
					if (inhibitor == null) {
						inhibitor = new ProcessBuilder("systemd-inhibit", "--why=Java running", "sleep", "infinity")
								.start();
					}
				}
			} catch (Throwable e) {
				assert Java.dbg(e);
			}
			if (!installed) {
				installed = true;
				Java.atexit(atExitCallback); // add
			}
		}

		Runnable atExitCallback = () -> allowSleep();

		public void allowSleep() {
			if (0 == --noSleep) {
				try {
					if (IS_WINDOWS && winHandle != null) {
						winHandle.invokeExact(ES_CONTINUOUS);
					} else if (IS_LINUX && inhibitor != null) {
						inhibitor.destroy();
						inhibitor = null;
					}
					Java.atexit(atExitCallback); // remove
				} catch (Throwable e) {
					assert Java.dbg(e);
				}
			}
		}
	}

	static StayAwake awake = new StayAwake();

	static public void preventSleep() {
		awake.preventSleep();
	}

	static public void allowSleep() {
		awake.allowSleep();
	}

	/**
	 * Detects the L2 cache size PER PHYSICAL CORE, in a cross-platform manner.
	 *
	 * Problem: on Windows, WMIC/PowerShell returns the *total* L2 (sum of all
	 * cores), whereas on Linux/macOS, APIs usually return the per-core value. => We
	 * always normalize to "per physical core".
	 *
	 * IMPORTANT: never use Runtime.getRuntime().availableProcessors() as a
	 * substitute for physical core count — it returns logical threads (e.g. 28 on a
	 * 20-core i7-13850HX with Hyper-Threading), which would produce a wrong
	 * per-core value.
	 */
	public static class Cache {

		// Per-core floor/ceiling (realistic values 2005-2026)
		private static final long L2_PER_CORE_MIN_BYTES = 128L * 1024; // 128 KB
		private static final long L2_PER_CORE_MAX_BYTES = 4L * 1024 * 1024; // 4 MB

		// -------------------------------------------------------------------------
		// Main API
		// -------------------------------------------------------------------------

		/**
		 * Returns the L2 cache size per physical core, in bytes. Uses the first
		 * strategy that succeeds, in order: 1. Linux :
		 * /sys/devices/system/cpu/cpu0/cache/ 2. macOS : sysctl hw.l2cachesize 3.
		 * Windows: PowerShell Get-CimInstance Win32_Processor (preferred) 4. Windows:
		 * wmic cpu get L2CacheSize,NumberOfCores (fallback) 5. Default: 512 KB
		 *
		 * The fallback NEVER uses availableProcessors() as a core count substitute,
		 * because that returns logical threads, not physical cores.
		 */
		public static int detectL2CachePerCoreBytes() {
			String os = System.getProperty("os.name", "").toLowerCase();
			try {
				if (os.contains("linux"))
					return clamp(linuxL2PerCore());
				if (os.contains("mac"))
					return clamp(macL2PerCore());
				if (os.contains("win"))
					return clamp(windowsL2PerCore());
			} catch (Exception ignored) {
			}
			return 512 * 1024; // ultimate default
		}

		/**
		 * Recommended sieve window size (number of longs). ws = L2PerCore / Long.BYTES
		 */
		public static long recommendedWindowSize() {
			return detectL2CachePerCoreBytes() / Long.BYTES;
		}

		// -------------------------------------------------------------------------
		// Linux: /sys/devices/system/cpu/cpu0/cache/index*/
		// -------------------------------------------------------------------------
		private static long linuxL2PerCore() throws Exception {
			// Look for level 2 among the cpu0 cache entries
			for (int idx = 0; idx <= 4; idx++) {
				String levelFile = "/sys/devices/system/cpu/cpu0/cache/index" + idx + "/level";
				String sizeFile = "/sys/devices/system/cpu/cpu0/cache/index" + idx + "/size";
				String level = readFile(levelFile).trim();
				if ("2".equals(level)) {
					String sizeStr = readFile(sizeFile).trim(); // e.g. "512K" or "2048K"
					return parseSizeKB(sizeStr) * 1024L;
				}
			}
			throw new Exception("L2 cache not found in /sys");
		}

		// -------------------------------------------------------------------------
		// macOS: sysctl -n hw.l2cachesize (returns directly per core, in bytes)
		// -------------------------------------------------------------------------
		private static long macL2PerCore() throws Exception {
			String out = runCommand("sysctl", "-n", "hw.l2cachesize");
			return Long.parseLong(out.trim());
		}

		// -------------------------------------------------------------------------
		// Windows: PowerShell (preferred) then WMIC (fallback)
		//
		// Both commands return NumberOfCores = physical cores only (e.g. 20 on an
		// i7-13850HX: 14 P-cores + 6 E-cores), NOT logical threads (28 with HT).
		// We must use that value — never availableProcessors() — to divide L2 total.
		// -------------------------------------------------------------------------
		private static long windowsL2PerCore() throws Exception {
			// WMIC is absent on recent Windows 11 builds => try PowerShell first
			try {
				return windowsViaPowerShell();
			} catch (Exception e) {
				return windowsViaWmic();
			}
		}

		private static long windowsViaPowerShell() throws Exception {
			// PowerShell: Get-CimInstance Win32_Processor
			// L2CacheSize = total KB across all physical cores
			// NumberOfCores = physical core count (P-cores + E-cores, no HT)
			String script = "$c=Get-CimInstance Win32_Processor;"
					+ "Write-Output ($c.L2CacheSize.ToString() + ' ' + $c.NumberOfCores.ToString())";
			String out = runCommand("powershell", "-NoProfile", "-Command", script).trim();
			String[] parts = out.split("\\s+");
			if (parts.length < 2)
				throw new Exception("Unexpected PowerShell output: " + out);
			long l2KB = Long.parseLong(parts[0].trim());
			long cores = Long.parseLong(parts[1].trim());
			if (cores <= 0)
				throw new Exception("Invalid physical core count: " + cores);
			return (l2KB * 1024L) / cores; // per physical core
		}

		private static long windowsViaWmic() throws Exception {
			// wmic cpu get L2CacheSize,NumberOfCores /value
			// L2CacheSize = total KB, NumberOfCores = physical cores (same as PowerShell)
			String out = runCommand("wmic", "cpu", "get", "L2CacheSize,NumberOfCores", "/value");
			long l2KB = parseWmicLong(out, "L2CacheSize");
			long cores = parseWmicLong(out, "NumberOfCores");
			if (cores <= 0)
				throw new Exception("Invalid physical core count from WMIC: " + cores);
			// Do NOT fall back to availableProcessors() here: it returns logical threads,
			// not physical cores, and would silently produce a wrong per-core value.
			return (l2KB * 1024L) / cores; // per physical core
		}

		// -------------------------------------------------------------------------
		// Fallback JVM: sun.cpu.l2.cache.size system property
		// (set by some embedded/mobile JVMs; absent on desktop HotSpot)
		// -------------------------------------------------------------------------
		private static long jvmL2PerCore() throws Exception {
			// Some JVMs (J9, Zing, embedded) expose the L2 cache size via this property.
			// It is NOT present on standard HotSpot desktop/server builds.
			String prop = System.getProperty("sun.cpu.l2.cache.size");
			if (prop != null && !prop.isBlank()) {
				return Long.parseLong(prop.trim()); // value is already per-core, in bytes
			}
			// com.sun.management.OperatingSystemMXBean only exposes RAM sizes, not cache.
			// There is no standard JVM API for L2 cache size — give up and use the default.
			throw new Exception("No JVM L2 cache size property available (sun.cpu.l2.cache.size)");
		}

		// -------------------------------------------------------------------------
		// Utilities
		// -------------------------------------------------------------------------

		private static int clamp(long value) {
			return (int)Math.max(L2_PER_CORE_MIN_BYTES, Math.min(L2_PER_CORE_MAX_BYTES, value));
		}

		private static String readFile(String path) throws Exception {
			return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
		}

		private static String runCommand(String... cmd) throws Exception {
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			StringBuilder sb = new StringBuilder();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				String line;
				while ((line = r.readLine()) != null)
					sb.append(line).append('\n');
			}
			p.waitFor();
			return sb.toString();
		}

		/** Parse "512K" or "2048K" into KB */
		private static long parseSizeKB(String s) {
			s = s.toUpperCase().trim();
			if (s.endsWith("K"))
				return Long.parseLong(s.replace("K", "").trim());
			if (s.endsWith("M"))
				return Long.parseLong(s.replace("M", "").trim()) * 1024L;
			return Long.parseLong(s); // assumed to already be in KB
		}

		/** Extract a long value from a WMIC output of the form "Key=Value\r\n" */
		private static long parseWmicLong(String output, String key) throws Exception {
			Pattern p = Pattern.compile(key + "=(\\d+)", Pattern.CASE_INSENSITIVE);
			Matcher m = p.matcher(output);
			if (m.find())
				return Long.parseLong(m.group(1));
			throw new Exception("Key not found in WMIC output: " + key);
		}

		// -------------------------------------------------------------------------
		// Quick test entry point
		// -------------------------------------------------------------------------
		public static void main(String[] args) {
			long l2 = detectL2CachePerCoreBytes();
			long ws = recommendedWindowSize();
			System.out.printf("OS               : %s%n", System.getProperty("os.name"));
			System.out.printf("Logical CPUs     : %d%n", Runtime.getRuntime().availableProcessors());
			System.out.printf("L2 per phys.core : %,d bytes  (%,d KB)%n", l2, l2 / 1024);
			System.out.printf("Window size      : %,d longs%n", ws);
		}
	}
}
