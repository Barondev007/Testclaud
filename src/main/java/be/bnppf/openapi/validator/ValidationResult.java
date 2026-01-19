package be.bnppf.openapi.validator;

import com.atlassian.oai.validator.report.ValidationReport;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the result of an OpenAPI validation operation.
 */
public class ValidationResult {

    private boolean valid;
    private boolean blocked;
    private String validationType; // "request" or "response"
    private ValidationLevel validationLevel;
    private List<String> errors;
    private List<String> warnings;
    private List<String> allMessages;
    private String debugInfo;
    private ValidationReport originalReport;

    public ValidationResult() {
        this.valid = true;
        this.blocked = false;
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
        this.allMessages = new ArrayList<>();
    }

    /**
     * Create a ValidationResult from a ValidationReport.
     */
    public static ValidationResult fromReport(ValidationReport report, ValidationLevel level, String validationType) {
        ValidationResult result = new ValidationResult();
        result.originalReport = report;
        result.validationLevel = level;
        result.validationType = validationType;

        // Collect all messages
        for (ValidationReport.Message msg : report.getMessages()) {
            String formattedMsg = "[" + msg.getKey() + "] " + msg.getMessage();

            switch (msg.getLevel()) {
                case ERROR:
                    result.errors.add(formattedMsg);
                    result.allMessages.add(formattedMsg);
                    break;
                case WARN:
                    result.warnings.add(formattedMsg);
                    result.allMessages.add(formattedMsg);
                    break;
                case INFO:
                case IGNORE:
                    result.allMessages.add(formattedMsg);
                    break;
            }
        }

        // Determine validity based on errors
        result.valid = result.errors.isEmpty();

        // Determine if flow should be blocked based on validation level
        switch (level) {
            case LIGHT:
                // Never block in light mode
                result.blocked = false;
                break;
            case LENIENT:
            case STRICT:
            default:
                // Block if there are errors
                result.blocked = report.hasErrors();
                break;
        }

        return result;
    }

    // Getters and setters

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public String getValidationType() {
        return validationType;
    }

    public void setValidationType(String validationType) {
        this.validationType = validationType;
    }

    public ValidationLevel getValidationLevel() {
        return validationLevel;
    }

    public void setValidationLevel(ValidationLevel validationLevel) {
        this.validationLevel = validationLevel;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public List<String> getAllMessages() {
        return allMessages;
    }

    public void setAllMessages(List<String> allMessages) {
        this.allMessages = allMessages;
    }

    public String getDebugInfo() {
        return debugInfo;
    }

    public void setDebugInfo(String debugInfo) {
        this.debugInfo = debugInfo;
    }

    public ValidationReport getOriginalReport() {
        return originalReport;
    }

    public void setOriginalReport(ValidationReport originalReport) {
        this.originalReport = originalReport;
    }

    public int getErrorCount() {
        return errors.size();
    }

    public String getErrorsAsString() {
        return String.join("; ", errors);
    }

    public String getAllMessagesAsString() {
        return String.join("; ", allMessages);
    }

    @Override
    public String toString() {
        return "ValidationResult{" +
                "valid=" + valid +
                ", blocked=" + blocked +
                ", validationType='" + validationType + '\'' +
                ", level=" + validationLevel +
                ", errorCount=" + errors.size() +
                '}';
    }
}
