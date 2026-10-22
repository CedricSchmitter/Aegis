package dev.aegis.planning.domain.history;

/** The task fields the history records, with the stable code used in the API and the stored change. */
public enum TaskField {
    TITLE("title"),
    NOTE("note"),
    OWNER("owner"),
    STATUS("status"),
    DUE_DATE("dueDate"),
    PLANNED_DATE("plannedDate");

    private final String code;

    TaskField(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static TaskField ofCode(String code) {
        for (TaskField field : values()) {
            if (field.code.equals(code)) {
                return field;
            }
        }
        throw new IllegalArgumentException("unknown task field " + code);
    }
}
