// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.ai;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loads the module in a layer of its own, as the daemon does. */
class LoadedModuleTest {
  @TempDir Path dir;

  private static void pack(Path classes, Path jar) throws IOException {
    try (OutputStream out = Files.newOutputStream(jar);
        JarOutputStream jos = new JarOutputStream(out);
        Stream<Path> files = Files.walk(classes)) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        jos.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
        jos.write(Files.readAllBytes(f));
        jos.closeEntry();
      }
    }
  }

  @Test
  void theModuleLoadsInItsOwnLayerAndGetsItsDataDirectory() throws Exception {
    Path modules = Files.createDirectories(dir.resolve("modules"));
    Path moduleDir = Files.createDirectories(modules.resolve("ai"));
    Files.copy(Path.of("module.toml"), moduleDir.resolve("module.toml"));
    pack(Path.of("target/classes"), moduleDir.resolve("praksis-module-ai.jar"));
    Path lock = dir.resolve("modules.lock");
    ModuleLoader.lock(modules, lock);

    try (LoadedModules loaded = ModuleLoader.load(modules, lock, Map.of())) {
      assertThat(loaded.capabilities())
          .containsOnlyKeys(
              "ai.calls", "ai.classify", "ai.complete", "ai.embed", "ai.evaluate", "ai.extract");
      assertThat(loaded.catalog().moduleNames()).containsExactly("ai");
      assertThat(loaded.catalog().capability("ai.classify").orElseThrow().effect().id())
          .isEqualTo("external");
      assertThat(loaded.catalog().capability("ai.calls").orElseThrow().effect().id())
          .isEqualTo("read-only");
      loaded.useDataDirectory(dir.resolve("data"));
      assertThat(dir.resolve("data/ai")).isDirectory();
    }
  }
}
