package com.fusion.dev.cystol.config.yaml;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SnakeYAML 2.x engine that merges missing keys and writes path patches while
 * keeping comments attached to the node tree ({@code processComments=true}).
 *
 * <p>Rules:
 * <ul>
 *   <li>Existing user values are never overwritten by defaults</li>
 *   <li>Missing keys (and whole missing subtrees) are inserted from the default
 *       document, including their block / inline / end comments</li>
 *   <li>Sequences are atomic: if the key exists, list items are not merged</li>
 *   <li>Insertion order follows the default document (new keys land after the
 *       previous default sibling that already exists in the user file)</li>
 *   <li>Path patches replace only the value node so key-attached comments stay</li>
 * </ul>
 */
public final class CommentPreservingYaml {

    private CommentPreservingYaml() {
    }

    public record MergeResult(int keysAdded, boolean rewritten) {
        public static MergeResult unchanged() {
            return new MergeResult(0, false);
        }
    }

    /** Comment-aware SnakeYAML instance (safe constructor, block style, 2-space indent). */
    public static Yaml createYaml() {
        LoaderOptions loader = new LoaderOptions();
        loader.setProcessComments(true);
        loader.setCodePointLimit(10 * 1024 * 1024);

        DumperOptions dumper = new DumperOptions();
        dumper.setProcessComments(true);
        dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumper.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        dumper.setIndent(2);
        dumper.setIndicatorIndent(0);
        dumper.setPrettyFlow(true);
        dumper.setSplitLines(false);
        dumper.setWidth(4096);
        dumper.setAllowUnicode(true);

        Representer representer = new Representer(dumper);
        representer.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

        return new Yaml(new SafeConstructor(loader), representer, dumper, loader);
    }

    /**
     * Ensure {@code userFile} contains every key present in {@code defaultsYaml}.
     * Creates the file from defaults when missing or empty. Preserves user values
     * and comments; attaches default comments to newly inserted keys.
     */
    public static MergeResult mergeMissing(Path userFile, String defaultsYaml) throws IOException {
        Objects.requireNonNull(userFile, "userFile");
        Objects.requireNonNull(defaultsYaml, "defaultsYaml");

        Yaml yaml = createYaml();
        Node defaultRoot = composeRoot(yaml, defaultsYaml);
        if (!(defaultRoot instanceof MappingNode defaultMap)) {
            throw new IOException("Default YAML root must be a mapping: " + userFile.getFileName());
        }

        if (!Files.isRegularFile(userFile) || Files.size(userFile) == 0L) {
            Files.createDirectories(userFile.getParent() != null ? userFile.getParent() : Path.of("."));
            atomicWrite(userFile, defaultsYaml.endsWith("\n") ? defaultsYaml : defaultsYaml + "\n");
            return new MergeResult(countKeys(defaultMap), true);
        }

        String userText = Files.readString(userFile, StandardCharsets.UTF_8);
        Node userRoot = composeRoot(yaml, userText);
        if (userRoot == null) {
            atomicWrite(userFile, defaultsYaml.endsWith("\n") ? defaultsYaml : defaultsYaml + "\n");
            return new MergeResult(countKeys(defaultMap), true);
        }
        if (!(userRoot instanceof MappingNode userMap)) {
            throw new IOException("User YAML root must be a mapping: " + userFile.getFileName());
        }

        int added = mergeMappings(userMap, defaultMap);
        if (added == 0) {
            return MergeResult.unchanged();
        }

        atomicWrite(userFile, serialize(yaml, userMap));
        return new MergeResult(added, true);
    }

    /**
     * Same as {@link #mergeMissing(Path, String)} but reads defaults from a stream
     * (plugin resource).
     */
    public static MergeResult mergeMissing(Path userFile, InputStream defaultsStream) throws IOException {
        Objects.requireNonNull(defaultsStream, "defaultsStream");
        String defaults = new String(defaultsStream.readAllBytes(), StandardCharsets.UTF_8);
        return mergeMissing(userFile, defaults);
    }

    /**
     * Load user YAML (comments on), apply dotted-path value patches, write back.
     * Key-node comments are preserved; only value nodes change.
     *
     * @return true if the file was written
     */
    public static boolean patch(Path userFile, Map<String, ?> pathValues) throws IOException {
        Objects.requireNonNull(userFile, "userFile");
        Objects.requireNonNull(pathValues, "pathValues");
        if (pathValues.isEmpty()) {
            return false;
        }
        if (!Files.isRegularFile(userFile)) {
            throw new IOException("Cannot patch missing YAML: " + userFile);
        }

        Yaml yaml = createYaml();
        String userText = Files.readString(userFile, StandardCharsets.UTF_8);
        Node root = composeRoot(yaml, userText);
        if (!(root instanceof MappingNode map)) {
            throw new IOException("User YAML root must be a mapping: " + userFile.getFileName());
        }

        for (Map.Entry<String, ?> e : pathValues.entrySet()) {
            setPath(yaml, map, e.getKey(), e.getValue());
        }
        atomicWrite(userFile, serialize(yaml, map));
        return true;
    }

    // --- merge ---

    /**
     * Deep-merge {@code defaults} into {@code user}. Returns number of mapping keys added.
     */
    static int mergeMappings(MappingNode user, MappingNode defaults) {
        int added = 0;
        // key -> index in user.getValue()
        Map<String, Integer> userIndex = indexKeys(user);

        // Last user index that matches a default key we have already walked (for ordered insert).
        int insertAfter = -1;

        for (NodeTuple defTuple : defaults.getValue()) {
            String key = scalarKey(defTuple.getKeyNode());
            if (key == null) {
                continue;
            }
            Integer existing = userIndex.get(key);
            if (existing != null) {
                insertAfter = existing;
                Node userVal = user.getValue().get(existing).getValueNode();
                Node defVal = defTuple.getValueNode();
                if (userVal instanceof MappingNode um && defVal instanceof MappingNode dm) {
                    added += mergeMappings(um, dm);
                    // child inserts may grow user.getValue() only under um, not this list
                }
                // Sequence / scalar / type mismatch: user owns the value completely
            } else {
                int at = insertAfter + 1;
                // Take ownership of default nodes (fresh compose per merge call).
                user.getValue().add(at, defTuple);
                added += 1 + countNestedKeys(defTuple.getValueNode());
                // Shift indices for keys after insertion point
                userIndex = indexKeys(user);
                insertAfter = at;
            }
        }
        return added;
    }

    // --- path set ---

    static void setPath(Yaml yaml, MappingNode root, String dottedPath, Object value) {
        if (dottedPath == null || dottedPath.isBlank()) {
            throw new IllegalArgumentException("path must be non-blank");
        }
        String[] parts = dottedPath.split("\\.");
        MappingNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            current = getOrCreateMap(current, parts[i]);
        }
        String leaf = parts[parts.length - 1];
        Node newValue = representValue(yaml, value);
        int idx = indexOfKey(current, leaf);
        if (idx >= 0) {
            NodeTuple old = current.getValue().get(idx);
            // Keep key node (block/inline comments live here).
            current.getValue().set(idx, new NodeTuple(old.getKeyNode(), newValue));
        } else {
            current.getValue().add(new NodeTuple(plainKey(leaf), newValue));
        }
    }

    private static MappingNode getOrCreateMap(MappingNode parent, String key) {
        int idx = indexOfKey(parent, key);
        if (idx >= 0) {
            Node val = parent.getValue().get(idx).getValueNode();
            if (val instanceof MappingNode m) {
                return m;
            }
            MappingNode created = emptyMap();
            NodeTuple old = parent.getValue().get(idx);
            parent.getValue().set(idx, new NodeTuple(old.getKeyNode(), created));
            return created;
        }
        MappingNode created = emptyMap();
        parent.getValue().add(new NodeTuple(plainKey(key), created));
        return created;
    }

    private static Node representValue(Yaml yaml, Object value) {
        if (value == null) {
            return new ScalarNode(Tag.NULL, "null", null, null, DumperOptions.ScalarStyle.PLAIN);
        }
        // Prefer native represent for collections / numbers / booleans / strings
        Node node = yaml.represent(value);
        forceBlockCollections(node);
        return node;
    }

    private static void forceBlockCollections(Node node) {
        if (node instanceof MappingNode m) {
            m.setFlowStyle(DumperOptions.FlowStyle.BLOCK);
            for (NodeTuple t : m.getValue()) {
                forceBlockCollections(t.getValueNode());
            }
        } else if (node instanceof SequenceNode s) {
            s.setFlowStyle(DumperOptions.FlowStyle.BLOCK);
            for (Node n : s.getValue()) {
                forceBlockCollections(n);
            }
        }
    }

    // --- compose / serialize ---

    static Node composeRoot(Yaml yaml, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try (Reader reader = new StringReader(text)) {
            return yaml.compose(reader);
        } catch (IOException e) {
            // StringReader never throws
            throw new IllegalStateException(e);
        }
    }

    static String serialize(Yaml yaml, Node root) {
        StringWriter sw = new StringWriter(Math.max(256, 64));
        yaml.serialize(root, sw);
        String out = sw.toString();
        if (!out.isEmpty() && !out.endsWith("\n")) {
            out = out + "\n";
        }
        return out;
    }

    static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = parent != null
                ? Files.createTempFile(parent, target.getFileName().toString(), ".tmp")
                : Files.createTempFile(target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // --- node helpers ---

    private static Map<String, Integer> indexKeys(MappingNode map) {
        Map<String, Integer> index = new LinkedHashMap<>();
        List<NodeTuple> values = map.getValue();
        for (int i = 0; i < values.size(); i++) {
            String key = scalarKey(values.get(i).getKeyNode());
            if (key != null) {
                index.put(key, i);
            }
        }
        return index;
    }

    private static int indexOfKey(MappingNode map, String key) {
        List<NodeTuple> values = map.getValue();
        for (int i = 0; i < values.size(); i++) {
            if (key.equals(scalarKey(values.get(i).getKeyNode()))) {
                return i;
            }
        }
        return -1;
    }

    static String scalarKey(Node keyNode) {
        if (keyNode instanceof ScalarNode sn) {
            return sn.getValue();
        }
        return null;
    }

    private static ScalarNode plainKey(String key) {
        return new ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN);
    }

    private static MappingNode emptyMap() {
        return new MappingNode(Tag.MAP, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
    }

    static int countKeys(MappingNode map) {
        int n = 0;
        for (NodeTuple t : map.getValue()) {
            n += 1 + countNestedKeys(t.getValueNode());
        }
        return n;
    }

    private static int countNestedKeys(Node value) {
        if (value instanceof MappingNode m) {
            return countKeys(m);
        }
        return 0;
    }

    /**
     * Diagnostic: dump node tree with comment counts (tests / debug).
     */
    static int countComments(Node node) {
        if (node == null) {
            return 0;
        }
        int n = size(node.getBlockComments()) + size(node.getInLineComments()) + size(node.getEndComments());
        if (node instanceof MappingNode m) {
            for (NodeTuple t : m.getValue()) {
                n += countComments(t.getKeyNode());
                n += countComments(t.getValueNode());
            }
        } else if (node instanceof SequenceNode s) {
            for (Node child : s.getValue()) {
                n += countComments(child);
            }
        }
        return n;
    }

    private static int size(Collection<?> c) {
        return c == null ? 0 : c.size();
    }

    /** True when {@code path} exists as a mapping/scalar/sequence key chain. */
    static boolean hasPath(MappingNode root, String dottedPath) {
        String[] parts = dottedPath.split("\\.");
        Node current = root;
        for (String part : parts) {
            if (!(current instanceof MappingNode map)) {
                return false;
            }
            int idx = indexOfKey(map, part);
            if (idx < 0) {
                return false;
            }
            current = map.getValue().get(idx).getValueNode();
        }
        return true;
    }

    static Object loadPlain(String yamlText) {
        Yaml yaml = createYaml();
        return yaml.load(yamlText);
    }

    // Avoid unused import warnings for NodeId if javac is strict on some setups
    @SuppressWarnings("unused")
    private static boolean isScalar(Node n) {
        return n != null && n.getNodeId() == NodeId.scalar;
    }
}
