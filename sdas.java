import java.io.Serializable;
import java.util.List;

enum OperationType {
    CREATE_FILE,
    DELETE_FILE,
    UPDATE_FILE,
    SYNC_REQUEST,
    SYNC_RESPONSE,
    DELETE_MULTIPLE
}

class FileOperation implements Serializable {
    private static final long serialVersionUID = 1L;

    private OperationType type;
    private String username;
    private String fileName;
    private String content;
    private Object data;
    private List<String> fileNames;

    public FileOperation(OperationType type, String username, String fileName, Object data) {
        this.type = type;
        this.username = username;
        this.fileName = fileName;
        this.data = data;
    }

    public FileOperation(OperationType type, String username, String fileName,
            String content) {
        this.type = type;
        this.username = username;
        this.fileName = fileName;
        this.content = content;
    }

    // Getters
    public OperationType getType() {
        return type;
    }

    public String getUsername() {
        return username;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContent() {
        return content;
    }

    public Object getData() {
        return data;
    }

    public List<String> getFileNames() {
        return fileNames;
    }

    // Setter for file names (used in bulk delete)
    public void setFileNames(List<String> fileNames) {
        this.fileNames = fileNames;
    }
}

// FileData.java (Shared between client and server)
class FileData implements Serializable {
    private static final long serialVersionUID = 1L;

    private String fileName;
    private String content;

    public FileData(String fileName, String content) {
        this.fileName = fileName;
        this.content = content;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContent() {
        return content;
    }
}