package mcopt.metal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;

/** The portable Rust library; loaded only when an explicit native experiment needs it. */
public final class RustNative {
	private RustNative() { }

	private static final class Library {
		static final SymbolLookup SYMBOLS = SymbolLookup.libraryLookup(extract(), Arena.global());
	}

	public static SymbolLookup lookup() { return Library.SYMBOLS; }

	private static Path extract() {
		String arch = switch (System.getProperty("os.arch", "")) {
			case "amd64", "x86_64" -> "x86_64";
			case "aarch64", "arm64" -> "aarch64";
			default -> throw new IllegalStateException("Unsupported Rust library architecture");
		};
		String resource = "/natives/linux-" + arch + "/libmcopt_native.so";
		try (var input = RustNative.class.getResourceAsStream(resource)) {
			if (input == null) throw new IllegalStateException("Missing " + resource + "; build the mod on the target Linux architecture");
			Path directory = Files.createTempDirectory("mcopt-rust-");
			directory.toFile().deleteOnExit();
			Path library = directory.resolve("libmcopt_native.so");
			library.toFile().deleteOnExit();
			Files.copy(input, library);
			return library;
		} catch (IOException e) {
			throw new IllegalStateException("Cannot extract Rust CPU kernels", e);
		}
	}
}
