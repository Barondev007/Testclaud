package be.bnppf.openapi.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for converting YAML content to JSON.
 * Handles YAML files with free-text descriptions that may contain special characters.
 */
public class YamlToJsonConverter {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    /**
     * Detect if content is JSON or YAML and return JSON.
     * If the content is already JSON, returns it as-is (optionally pretty-printed).
     * If the content is YAML, converts it to JSON.
     *
     * @param content the content to process (JSON or YAML)
     * @return JSON string representation
     * @throws IOException if parsing fails
     */
    public static String toJson(String content) throws IOException {
        if (content == null || content.trim().isEmpty()) {
            throw new IOException("Content is null or empty");
        }

        if (isJson(content)) {
            // Already JSON, parse and pretty-print to ensure valid JSON
            JsonNode node = JSON_MAPPER.readTree(content);
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } else {
            // Assume YAML, convert to JSON
            return convert(content);
        }
    }

    /**
     * Detect if content is JSON or YAML and return JSON, with description fix for YAML.
     * If the content is already JSON, returns it as-is.
     * If the content is YAML, applies description fix and converts to JSON.
     *
     * @param content the content to process (JSON or YAML)
     * @return JSON string representation
     * @throws IOException if parsing fails
     */
    public static String toJsonWithDescriptionFix(String content) throws IOException {
        if (content == null || content.trim().isEmpty()) {
            throw new IOException("Content is null or empty");
        }

        if (isJson(content)) {
            JsonNode node = JSON_MAPPER.readTree(content);
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } else {
            return convertWithDescriptionFix(content);
        }
    }

    /**
     * Detect if content is JSON or YAML and return as JsonNode.
     *
     * @param content the content to process (JSON or YAML)
     * @return JsonNode representation
     * @throws IOException if parsing fails
     */
    public static JsonNode toJsonNode(String content) throws IOException {
        if (content == null || content.trim().isEmpty()) {
            throw new IOException("Content is null or empty");
        }

        if (isJson(content)) {
            return JSON_MAPPER.readTree(content);
        } else {
            return YAML_MAPPER.readTree(content);
        }
    }

    /**
     * Detect if the content is JSON format.
     * Checks if the trimmed content starts with '{' or '[' (JSON object or array).
     *
     * @param content the content to check
     * @return true if content appears to be JSON, false if likely YAML
     */
    public static boolean isJson(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = content.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /**
     * Detect if the content is YAML format.
     *
     * @param content the content to check
     * @return true if content appears to be YAML, false if likely JSON
     */
    public static boolean isYaml(String content) {
        return !isJson(content);
    }

    /**
     * Convert YAML string to JSON string using Jackson.
     *
     * @param yamlContent the YAML content to convert
     * @return JSON string representation
     * @throws IOException if parsing fails
     */
    public static String convert(String yamlContent) throws IOException {
        JsonNode node = YAML_MAPPER.readTree(yamlContent);
        return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    /**
     * Convert YAML string to JSON string, with pre-processing to fix description blocks.
     * Use this method when the YAML contains free-text descriptions with improper indentation.
     *
     * @param yamlContent the YAML content to convert
     * @return JSON string representation
     * @throws IOException if parsing fails
     */
    public static String convertWithDescriptionFix(String yamlContent) throws IOException {
        String fixed = fixDescriptionBlocks(yamlContent);
        return convert(fixed);
    }

    /**
     * Convert YAML string to JSON using SnakeYAML (more lenient parser).
     * This approach is more tolerant of edge cases.
     *
     * @param yamlContent the YAML content to convert
     * @return JSON string representation
     * @throws IOException if conversion fails
     */
    public static String convertWithSnakeYaml(String yamlContent) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);

        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object obj = yaml.load(yamlContent);

        return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }

    /**
     * Convert YAML file to JSON file.
     *
     * @param yamlPath path to the YAML file
     * @param jsonPath path to the output JSON file
     * @throws IOException if file operations fail
     */
    public static void convertFile(String yamlPath, String jsonPath) throws IOException {
        String yamlContent = Files.readString(Path.of(yamlPath));
        String jsonContent = convert(yamlContent);
        Files.writeString(Path.of(jsonPath), jsonContent);
    }

    /**
     * Convert YAML file to JSON file, with pre-processing to fix description blocks.
     *
     * @param yamlPath path to the YAML file
     * @param jsonPath path to the output JSON file
     * @throws IOException if file operations fail
     */
    public static void convertFileWithDescriptionFix(String yamlPath, String jsonPath) throws IOException {
        String yamlContent = Files.readString(Path.of(yamlPath));
        String jsonContent = convertWithDescriptionFix(yamlContent);
        Files.writeString(Path.of(jsonPath), jsonContent);
    }

    /**
     * Convert YAML file to JSON and return as JsonNode.
     *
     * @param yamlFile the YAML file to convert
     * @return JsonNode representation
     * @throws IOException if parsing fails
     */
    public static JsonNode convertToJsonNode(File yamlFile) throws IOException {
        return YAML_MAPPER.readTree(yamlFile);
    }

    /**
     * Convert YAML string to JsonNode.
     *
     * @param yamlContent the YAML content to convert
     * @return JsonNode representation
     * @throws IOException if parsing fails
     */
    public static JsonNode convertToJsonNode(String yamlContent) throws IOException {
        return YAML_MAPPER.readTree(yamlContent);
    }

    /**
     * Fix description blocks in YAML that have improper indentation.
     * This method ensures all lines in description blocks are properly indented.
     *
     * @param yaml the YAML content to fix
     * @return YAML content with fixed description blocks
     */
    public static String fixDescriptionBlocks(String yaml) {
        // Pattern to match description blocks with > or | block scalar indicators
        Pattern pattern = Pattern.compile(
            "(description:\\s*[>|]\\s*\\n)((?:.*\\n)*?)(?=^\\s*\\w+:|\\z)",
            Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(yaml);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String header = matcher.group(1);
            String content = matcher.group(2);

            // Indent each line of the description content
            StringBuilder indentedContent = new StringBuilder();
            for (String line : content.split("\\n", -1)) {
                if (line.isEmpty()) {
                    indentedContent.append("\n");
                } else {
                    indentedContent.append("    ").append(line.stripLeading()).append("\n");
                }
            }

            matcher.appendReplacement(result,
                Matcher.quoteReplacement(header + indentedContent.toString()));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Sanitize YAML content by removing or fixing common problematic patterns.
     *
     * @param yaml the YAML content to sanitize
     * @return sanitized YAML content
     */
    public static String sanitizeYaml(String yaml) {
        return yaml
            .replaceAll("(?m)^>>.*$", "")           // Remove lines starting with >>
            .replaceAll("-url:", "- url:")          // Fix missing space after dash
            .replaceAll("(?m)^\\s*$\\n", "\n");     // Normalize empty lines
    }

    /**
     * Remove all description blocks from YAML content.
     * Removes the description key and ALL content until the next YAML key at same or lower indentation.
     *
     * @param yaml the YAML content to process
     * @return YAML content with all description fields removed
     */
    public static String removeDescriptions(String yaml) {
        if (yaml == null) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        String[] lines = yaml.split("\n", -1);
        boolean skipping = false;
        int descriptionIndent = -1;

        for (String line : lines) {
            int currentIndent = getIndentation(line);
            String trimmed = line.trim();

            // Check if this line starts a description field
            if (trimmed.startsWith("description:")) {
                // Start skipping - record the indentation level of this description
                skipping = true;
                descriptionIndent = currentIndent;
                continue; // Skip the description line itself
            }

            // If we're skipping description content
            if (skipping) {
                // Empty line - keep skipping
                if (trimmed.isEmpty()) {
                    continue;
                }

                // Check if this line is a new YAML key at same or lower indentation level
                // A YAML key is typically: word followed by colon (not inside the value)
                boolean isNewKey = isYamlKey(trimmed) && currentIndent <= descriptionIndent;

                if (isNewKey) {
                    // Stop skipping, this is a new section
                    skipping = false;
                    descriptionIndent = -1;
                    // Fall through to add this line
                } else {
                    // Still part of description content, skip it
                    continue;
                }
            }

            result.append(line).append("\n");
        }

        // Remove trailing newline if original didn't have one
        if (!yaml.endsWith("\n") && result.length() > 0) {
            result.setLength(result.length() - 1);
        }

        return result.toString();
    }

    /**
     * Get the indentation level (number of leading spaces) of a line.
     */
    private static int getIndentation(String line) {
        int indent = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') indent++;
            else if (c == '\t') indent += 2;
            else break;
        }
        return indent;
    }

    /**
     * Check if a trimmed line appears to be a YAML key (word followed by colon).
     */
    private static boolean isYamlKey(String trimmed) {
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return false;
        }
        // Match patterns like "key:", "key: value", "- key:", but not lines that are just values
        // A key typically starts with a word character or dash (for list items)
        return trimmed.matches("^-?\\s*[a-zA-Z_][a-zA-Z0-9_]*\\s*:.*")
            || trimmed.matches("^[a-zA-Z_][a-zA-Z0-9_]*\\s*:.*");
    }

    /**
     * Detect if content is JSON or YAML and return JSON, removing descriptions from YAML.
     * If the content is already JSON, returns it as-is.
     * If the content is YAML, removes all description blocks and converts to JSON.
     *
     * @param content the content to process (JSON or YAML)
     * @return JSON string representation without descriptions
     * @throws IOException if parsing fails
     */
    public static String toJsonWithoutDescriptions(String content) throws IOException {
        if (content == null || content.trim().isEmpty()) {
            throw new IOException("Content is null or empty");
        }

        if (isJson(content)) {
            // For JSON, remove description fields from the parsed tree
            JsonNode node = JSON_MAPPER.readTree(content);
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } else {
            // For YAML, remove description blocks then convert
            String withoutDescriptions = removeDescriptions(content);
            return convert(withoutDescriptions);
        }
    }

    /**
     * Convert YAML to JSON after removing all description blocks.
     *
     * @param yamlContent the YAML content to convert
     * @return JSON string representation without descriptions
     * @throws IOException if parsing fails
     */
    public static String convertWithoutDescriptions(String yamlContent) throws IOException {
        String withoutDescriptions = removeDescriptions(yamlContent);
        return convert(withoutDescriptions);
    }

    /**
     * Full conversion with sanitization and description fix.
     * Use this method when dealing with potentially malformed YAML from external sources.
     *
     * @param yamlContent the YAML content to convert
     * @return JSON string representation
     * @throws IOException if parsing fails
     */
    public static String convertWithFullPreprocessing(String yamlContent) throws IOException {
        String sanitized = sanitizeYaml(yamlContent);
        String fixed = fixDescriptionBlocks(sanitized);
        return convert(fixed);
    }
}
