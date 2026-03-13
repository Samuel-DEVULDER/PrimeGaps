package primegap.util;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

import jdk.incubator.vector.VectorOperators;

public class Java {
	public static <E> Class<? extends E>[] findSubclasses(Class<E> parent) {
		String cp = System.getProperty("java.class.path");
		List<Class<? extends E>> result = new ArrayList<>();
		try {
			for (String entry : cp.split(File.pathSeparator)) {
				Path path = Path.of(entry);
				if (Files.isDirectory(path)) {
					try (var stream = Files.walk(path)) {
						stream.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
							String className = path.relativize(p).toString().replace(File.separatorChar, '.')
									.replace(".class", "");
							tryLoad(className, parent, result);
						});
					}
				} else if (entry.endsWith(".jar")) {
					try (JarFile jar = new JarFile(path.toFile())) {
						jar.entries().asIterator().forEachRemaining(entry2 -> {
							String name = entry2.getName();
							if (name.endsWith(".class")) {
								tryLoad(name.replace('/', '.').replace(".class", ""), parent, result);
							}
						});
					}
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		@SuppressWarnings({ "unchecked", "rawtypes" })
		Class<E>[] classes = result.stream().map(c -> ((Class) c).asSubclass(parent)).toArray(Class[]::new);

		return classes;
	}

	static <E> void tryLoad(String className, Class<E> parent, List<Class<? extends E>> result) {
		if (!className.startsWith("attic.")) {
			try {
				Class<?> cls = Class.forName(className);
				if (parent.isAssignableFrom(cls) && !Modifier.isAbstract(cls.getModifiers())) {
					result.add(cls.asSubclass(parent));
				}
			} catch (Throwable ignored) {
				// ignore any error loading the class (NoClassDefFoundError, etc.)
				// as we only want to find classes that can be loaded successfully
				//ignored.printStackTrace();
			}
		}
	}

	/**
	 * Relaunches the current JVM process with --add-modules=jdk.incubator.vector
	 * appended, forwarding all existing JVM arguments and the main class args.
	 * Inherits stdin/stdout/stderr so output appears normally.
	 */
	public static boolean enableSIMD() {
		boolean enabled = ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();
		// Check if the Vector API incubator module is already loaded
		if (!enabled) {
			// Resolve the current java executable path
			String javaExe = ProcessHandle.current().info().command().orElse("java");

			RuntimeMXBean jvmMeta = ManagementFactory.getRuntimeMXBean();

			List<String> cmd = new ArrayList<>();
			cmd.add(javaExe);

			// Inject the missing module flag first
			cmd.add("--add-modules=jdk.incubator.vector");

			// Forward all existing JVM flags (-Xmx, -Xms, -D... etc.)
			cmd.addAll(jvmMeta.getInputArguments());

			// Forward classpath
			cmd.add("-cp");
			cmd.add(jvmMeta.getClassPath());

			// Main class and its arguments
			String[] parts = Optional.ofNullable(System.getProperty("sun.java.command"))
					.orElseGet(() -> Arrays.asList(new Exception().getStackTrace()).getLast().getClassName())
					.split(" ");
			cmd.addAll(Arrays.asList(parts));

			// Launch child process, sharing all I/O with current process
			try {
				int exitCode = new ProcessBuilder(cmd).inheritIO().start().waitFor();
				System.exit(exitCode);
			} catch (InterruptedException | IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			// Mirror the child exit code
		}
		return enabled;
	}

}
