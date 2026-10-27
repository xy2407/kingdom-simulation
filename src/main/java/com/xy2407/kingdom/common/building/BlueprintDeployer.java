package com.xy2407.kingdom.common.building;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import com.xy2407.kingdom.ModMain;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;

/** 首次启动把模组内置的建筑文件整目录释放到实例目录。 */
public final class BlueprintDeployer {

    private static final String NAMESPACE = "xy2407_kingdom";

    private static final String[] BUNDLED_PATH = {"data", NAMESPACE, "kingdom_simulation", "structures"};

    private static final String BUNDLED_PREFIX = "data/" + NAMESPACE + "/kingdom_simulation/structures/";

    private BlueprintDeployer() {
    }

    public static void deploy() {
        Path dir = BuildingBlueprints.structuresDir();
        Path root = bundledRoot();
        int copied = root != null && Files.isDirectory(root)
                ? copyTree(root, dir)
                : copyFromClasspath(dir);
        if (copied > 0) {
            ModMain.LOGGER.info("已部署 {} 个内置建筑文件到 {}", copied, dir);
        }
    }

    private static Path bundledRoot() {
        IModFileInfo info = ModList.get().getModFileById(ModMain.MOD_ID);
        IModFile file = info == null ? null : info.getFile();
        if (file == null) {
            return null;
        }
        try {
            return file.findResource(BUNDLED_PATH);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static int copyTree(Path root, Path dir) {
        int copied = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = new ArrayList<>(walk.filter(Files::isRegularFile)
                    .filter(BlueprintDeployer::isDeployable).toList());
            for (Path file : files) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                Path target = dir.resolve(relative);
                if (Files.isRegularFile(target)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream stream = Files.newInputStream(file)) {
                    if (write(stream, target)) {
                        copied++;
                    }
                }
            }
        } catch (IOException exception) {
            ModMain.LOGGER.error("扫描内置建筑目录失败: {}", root, exception);
        }
        return copied;
    }

    private static int copyFromClasspath(Path dir) {
        URL url = BlueprintDeployer.class.getResource("/" + BUNDLED_PREFIX);
        if (url == null) {
            ModMain.LOGGER.warn("模组内找不到内置建筑目录: {}", BUNDLED_PREFIX);
            return 0;
        }
        if ("file".equals(url.getProtocol())) {
            try {
                return copyTree(Paths.get(url.toURI()), dir);
            } catch (Exception exception) {
                ModMain.LOGGER.error("读取内置建筑目录失败: {}", url, exception);
                return 0;
            }
        }
        if (!"jar".equals(url.getProtocol())) {
            ModMain.LOGGER.warn("无法识别的内置建筑目录协议: {}", url.getProtocol());
            return 0;
        }
        return copyFromJar(url, dir);
    }

    private static int copyFromJar(URL url, Path dir) {
        int copied = 0;
        try (JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(BUNDLED_PREFIX)
                        || !isDeployable(entry.getName())) {
                    continue;
                }
                Path target = dir.resolve(entry.getName().substring(BUNDLED_PREFIX.length()));
                if (Files.isRegularFile(target)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream stream = jar.getInputStream(entry)) {
                    if (write(stream, target)) {
                        copied++;
                    }
                }
            }
        } catch (IOException exception) {
            ModMain.LOGGER.error("从模组包读取内置建筑失败: {}", url, exception);
        }
        return copied;
    }

    private static boolean write(InputStream stream, Path target) {
        try {
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException exception) {
            ModMain.LOGGER.error("部署内置建筑文件失败: {}", target, exception);
            return false;
        }
    }

    private static boolean isDeployable(Path file) {
        return isDeployable(file.getFileName().toString());
    }

    private static boolean isDeployable(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".nbt") || lower.endsWith(".json");
    }
}
