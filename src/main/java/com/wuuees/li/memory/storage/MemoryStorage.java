package com.wuuees.li.memory.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.wuuees.li.memory.model.MemoryEntry;
import com.wuuees.li.memory.model.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MemoryStorage {

    private static final Logger log = LoggerFactory.getLogger(MemoryStorage.class);
    private static final String ENV_OVERRIDE = "AGENT_MEMORY_PATH_OVERRIDE";
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);

    private final Path basePath;

    public MemoryStorage(Path basePath) {
        this.basePath = basePath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.basePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory base directory: " + this.basePath, e);
        }
    }

    public Path getBasePath() {
        return basePath;
    }

    /**
     * 解析指定项目根目录对应的记忆目录。
     * 优先级：环境变量覆盖 > projectName > 项目哈希推导。
     */
    public Path resolveMemoryDir(String projectRoot, String projectName) {
        String override = System.getenv(ENV_OVERRIDE);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        String subDir;
        if (projectName != null && !projectName.isBlank()) {
            subDir = projectName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_\\-]", "_");
        } else {
            subDir = hashProjectRoot(projectRoot);
        }
        return basePath.resolve("projects").resolve(subDir).resolve("memory");
    }

    /**
     * 安全校验：确保路径在基路径范围内，禁止目录穿越。
     */
    public Path sanitize(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(basePath)) {
            throw new SecurityException(
                    "Path escape detected: " + path + " is outside base path " + basePath);
        }
        return normalized;
    }

    /**
     * 原子写入：先写 .tmp 临时文件，校验完整性，再 rename 覆盖目标。
     */
    public void atomicWrite(Path file, String content) throws IOException {
        sanitize(file);
        Files.createDirectories(file.getParent());

        Path tmpFile = file.resolveSibling(file.getFileName() + ".tmp");

        Files.writeString(tmpFile, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        String written = Files.readString(tmpFile, StandardCharsets.UTF_8);
        if (!written.equals(content)) {
            throw new IOException("Atomic write verification failed for: " + file);
        }

        Files.move(tmpFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Atomic write complete: {}", file);
    }

    /**
     * 读取并解析带 YAML frontmatter 的记忆文件。
     */
    public MemoryEntry readMemoryFile(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        MemoryEntry entry = new MemoryEntry();
        entry.setFilePath(path);

        Matcher m = FRONTMATTER_PATTERN.matcher(raw);
        String body;
        if (m.matches()) {
            parseFrontmatter(m.group(1), entry);
            body = m.group(2);
        } else {
            // 无 frontmatter —— 整个文件作为正文
            body = raw;
            entry.setName(path.getFileName().toString().replace(".md", ""));
            entry.setType(MemoryType.REFERENCE);
        }

        entry.setContent(body.trim());

        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        if (entry.getCreatedAt() == null) {
            entry.setCreatedAt(attrs.creationTime().toInstant());
        }
        entry.setUpdatedAt(attrs.lastModifiedTime().toInstant());

        return entry;
    }

    private void parseFrontmatter(String yaml, MemoryEntry entry) {
        for (String line : yaml.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;

            String key = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();

            switch (key) {
                case "name" -> entry.setName(stripYamlQuotes(value));
                case "description" -> entry.setDescription(stripYamlQuotes(value));
                case "type" -> {
                    try {
                        entry.setType(MemoryType.valueOf(stripYamlQuotes(value).toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        entry.setType(MemoryType.REFERENCE);
                    }
                }
                case "createdAt" -> {
                    try { entry.setCreatedAt(Instant.parse(stripYamlQuotes(value))); }
                    catch (Exception ignored) {}
                }
                case "updatedAt" -> {
                    try { entry.setUpdatedAt(Instant.parse(stripYamlQuotes(value))); }
                    catch (Exception ignored) {}
                }
                case "related" -> {
                    String cleaned = stripYamlQuotes(value).replaceAll("[\\[\\],]", " ").trim();
                    if (!cleaned.isEmpty()) {
                        entry.setRelated(new ArrayList<>(List.of(cleaned.split("\\s+"))));
                    }
                }
                case "weight" -> {
                    try { entry.setWeight(Double.parseDouble(stripYamlQuotes(value))); }
                    catch (NumberFormatException ignored) {}
                }
                case "reinforced" -> {
                    try { entry.setReinforced(Integer.parseInt(stripYamlQuotes(value))); }
                    catch (NumberFormatException ignored) {}
                }
                case "lastRecalled" -> {
                    try { entry.setLastRecalled(Instant.parse(stripYamlQuotes(value))); }
                    catch (Exception ignored) {}
                }
                case "decayRate" -> {
                    try { entry.setDecayRate(Double.parseDouble(stripYamlQuotes(value))); }
                    catch (NumberFormatException ignored) {}
                }
                case "importance" -> {
                    try { entry.setImportance(MemoryEntry.Importance.valueOf(stripYamlQuotes(value).toUpperCase())); }
                    catch (IllegalArgumentException ignored) {}
                }
                case "tags" -> {
                    String cleaned = stripYamlQuotes(value).replaceAll("[\\[\\],]", " ").trim();
                    if (!cleaned.isEmpty()) {
                        entry.setTags(new ArrayList<>(List.of(cleaned.split("\\s+"))));
                    }
                }
                case "evolvedFrom" -> entry.setEvolvedFrom(stripYamlQuotes(value));
            }
        }
    }

    private String stripYamlQuotes(String value) {
        if (value == null) return "";
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 列出记忆目录中所有 .md 文件，排除 MEMORY.md 和 logs/ 目录。
     */
    public List<Path> listMemoryFiles(Path memoryDir) throws IOException {
        List<Path> files = new ArrayList<>();
        Path entrypointPath = memoryDir.resolve("MEMORY.md");
        Path logsDir = memoryDir.resolve("logs");

        if (!Files.exists(memoryDir)) {
            return files;
        }

        try (Stream<Path> stream = Files.list(memoryDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.equals(entrypointPath))
                    .filter(p -> !p.startsWith(logsDir))
                    .forEach(files::add);
        }
        return files;
    }

    /**
     * 确保记忆目录及其父目录存在。
     */
    public Path ensureMemoryDir(Path memoryDir) throws IOException {
        sanitize(memoryDir);
        Files.createDirectories(memoryDir);
        return memoryDir;
    }

    /**
     * 删除一条记忆文件。
     */
    public boolean deleteMemoryFile(Path path) throws IOException {
        sanitize(path);
        return Files.deleteIfExists(path);
    }

    /**
     * 检查文件是否存在。
     */
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    /**
     * 获取文件的最后修改时间（毫秒时间戳）。
     */
    public long getLastModifiedMs(Path path) throws IOException {
        if (!Files.exists(path)) return 0;
        return Files.getLastModifiedTime(path).toMillis();
    }

    /**
     * 对项目根路径生成文件系统安全的哈希值。
     */
    public static String hashProjectRoot(String projectRoot) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(projectRoot.getBytes(StandardCharsets.UTF_8));
            // Use first 16 chars of hex encoding
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(projectRoot.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 返回当前工作目录作为项目根目录。
     */
    public static String detectProjectRoot() {
        return Path.of("").toAbsolutePath().normalize().toString();
    }
}
