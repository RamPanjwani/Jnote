import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

public class App extends JFrame {
    private Connection conn;
    private Preferences prefs;
    private final String SESSION_KEY = "logged_in_user";
    private File notesFolder;
    private JList<String> noteList;
    private JTextArea noteContentArea;
    private JButton saveButton;
    private JButton editButton;
    private JButton createFileButton;
    private JButton deleteFileButton;
    private JButton deleteSelectedButton;
    private JButton selectAllButton;
    private DefaultListModel<String> noteListModel;
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    public App() {
        try {
            socket = new Socket("localhost", 12345);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
        prefs = Preferences.userNodeForPackage(App.class);
        connectToDatabase();
        checkSession();
    }

    private void connectToDatabase() {
        try {
            conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/jnote", "root", "root");
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error connecting to the database!", "Error",
                    JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void checkSession() {
        String loggedInUser = prefs.get(SESSION_KEY, null);
        if (loggedInUser != null) {
            showDashboard(loggedInUser);
        } else {
            showLoginScreen();
        }
    }

    private void showLoginScreen() {
        setTitle("Login and Registration Form");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(400, 700);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        Login loginPanel = new Login(conn, this::showDashboard);
        Register registerPanel = new Register(conn, this::showDashboard);

        JPanel containerPanel = new JPanel();
        containerPanel.setLayout(new BoxLayout(containerPanel, BoxLayout.Y_AXIS));
        containerPanel.add(loginPanel);
        containerPanel.add(Box.createRigidArea(new Dimension(0, 20)));
        containerPanel.add(registerPanel);

        add(containerPanel, BorderLayout.CENTER);
        setVisible(true);
    }

    private void showDashboard(String username) {
        prefs.put(SESSION_KEY, username);

        getContentPane().removeAll();
        setTitle("Dashboard - Notes");

        notesFolder = new File("vault_" + username);
        if (!notesFolder.exists()) {
            notesFolder.mkdir();
        }

        setLayout(new BorderLayout());

        // Initialize list model and note list FIRST
        noteListModel = new DefaultListModel<>();
        noteList = new JList<>(noteListModel);
        noteList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        noteList.addListSelectionListener(e -> loadNoteContent());

        // Create file management buttons
        JPanel fileManagementPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        createFileButton = new JButton("New File");
        createFileButton.addActionListener(e -> createNewFile());

        deleteFileButton = new JButton("Delete File");
        deleteFileButton.addActionListener(e -> deleteFile());

        selectAllButton = new JButton("Select All");
        selectAllButton.addActionListener(e -> noteList.setSelectionInterval(0, noteListModel.size() - 1));

        deleteSelectedButton = new JButton("Delete Selected");
        deleteSelectedButton.addActionListener(e -> deleteSelectedFiles());

        fileManagementPanel.add(createFileButton);
        fileManagementPanel.add(deleteFileButton);
        fileManagementPanel.add(selectAllButton);
        fileManagementPanel.add(deleteSelectedButton);

        // Create left panel with file management and note list
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(fileManagementPanel, BorderLayout.NORTH);

        JScrollPane noteListScrollPane = new JScrollPane(noteList);
        noteListScrollPane.setPreferredSize(new Dimension(200, 0));
        leftPanel.add(noteListScrollPane, BorderLayout.CENTER);

        // Create right panel with note content and buttons
        noteContentArea = new JTextArea();
        noteContentArea.setEditable(false);
        JScrollPane noteContentScrollPane = new JScrollPane(noteContentArea);

        saveButton = new JButton("Save");
        saveButton.setEnabled(false);
        saveButton.addActionListener(e -> saveNoteContent());

        editButton = new JButton("Edit");
        editButton.addActionListener(e -> enableEditing());

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(editButton);
        buttonPanel.add(saveButton);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(noteContentScrollPane, BorderLayout.CENTER);
        rightPanel.add(buttonPanel, BorderLayout.SOUTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        splitPane.setDividerLocation(200);

        add(splitPane, BorderLayout.CENTER);

        JButton logoutButton = new JButton("Logout");
        logoutButton.addActionListener(e -> logout(username));
        add(logoutButton, BorderLayout.SOUTH);

        // Now that everything is initialized, sync with server
        syncWithServer(username);

        revalidate();
        repaint();
        setVisible(true);
    }
    
    private void syncWithServer(String username) {
        try {
            out.writeObject(new FileOperation(OperationType.SYNC_REQUEST, username, "", null));
            out.flush();

            FileOperation response = (FileOperation) in.readObject();
            if (response.getType() == OperationType.SYNC_RESPONSE) {
                List<FileData> serverFiles = (List<FileData>) response.getData();
                if (serverFiles != null) { // Add null check
                    for (FileData fileData : serverFiles) {
                        File localFile = new File(notesFolder, fileData.getFileName());
                        Files.writeString(localFile.toPath(), fileData.getContent());
                    }
                }
                updateNoteList(); // Will work now because noteListModel is initialized
            }
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Error syncing with server: " + e.getMessage(),
                    "Sync Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateNoteList() {
        noteListModel.clear();
        if (notesFolder != null && notesFolder.isDirectory()) {
            File[] txtFiles = notesFolder.listFiles((dir, name) -> name.endsWith(".txt"));
            if (txtFiles != null) {
                Arrays.stream(txtFiles)
                        .map(File::getName)
                        .sorted()
                        .forEach(noteListModel::addElement);
            }
        }
    }

    private void loadNoteContent() {
        String selectedNote = noteList.getSelectedValue();
        if (selectedNote != null) {
            File noteFile = new File(notesFolder, selectedNote);
            try {
                String content = Files.readString(noteFile.toPath());
                noteContentArea.setText(content);
                noteContentArea.setEditable(false);
                saveButton.setEnabled(false);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Error loading note", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void enableEditing() {
        noteContentArea.setEditable(true);
        saveButton.setEnabled(true);
    }

    private void saveNoteContent() {
        String selectedNote = noteList.getSelectedValue();
        if (selectedNote != null) {
            try {
                String content = noteContentArea.getText();

                // Save locally
                File noteFile = new File(notesFolder, selectedNote);
                Files.writeString(noteFile.toPath(), content);

                // Sync with server
                out.writeObject(new FileOperation(OperationType.UPDATE_FILE,
                        prefs.get(SESSION_KEY, ""), selectedNote, content));
                out.flush();

                FileOperation response = (FileOperation) in.readObject();
                if ("SUCCESS".equals(response.getContent())) {
                    JOptionPane.showMessageDialog(this, "Note saved successfully!", "Success",
                            JOptionPane.INFORMATION_MESSAGE);
                    saveButton.setEnabled(false);
                    noteContentArea.setEditable(false);
                }
            } catch (IOException | ClassNotFoundException e) {
                JOptionPane.showMessageDialog(this, "Error saving note", "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void createNewFile() {
        String fileName = JOptionPane.showInputDialog(this, "Enter file name:");
        if (fileName != null && !fileName.trim().isEmpty()) {
            if (!fileName.endsWith(".txt")) {
                fileName += ".txt";
            }

            File newFile = new File(notesFolder, fileName);
            try {
                if (newFile.createNewFile()) {
                    // Sync with server
                    out.writeObject(new FileOperation(OperationType.CREATE_FILE,
                            prefs.get(SESSION_KEY, ""), fileName, ""));
                    out.flush();

                    FileOperation response = (FileOperation) in.readObject();
                    if ("SUCCESS".equals(response.getContent())) {
                        updateNoteList();
                        noteList.setSelectedValue(fileName, true);
                        noteContentArea.setText("");
                        enableEditing();
                    }
                } else {
                    JOptionPane.showMessageDialog(this, "File already exists!", "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    private void deleteFile() {
        String selectedNote = noteList.getSelectedValue();
        if (selectedNote != null) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Are you sure you want to delete " + selectedNote + "?",
                    "Confirm Delete",
                    JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                try {
                    // Delete locally
                    File fileToDelete = new File(notesFolder, selectedNote);
                    fileToDelete.delete();

                    // Sync with server
                    out.writeObject(new FileOperation(OperationType.DELETE_FILE,
                            prefs.get(SESSION_KEY, ""), selectedNote, null));
                    out.flush();

                    FileOperation response = (FileOperation) in.readObject();
                    if ("SUCCESS".equals(response.getContent())) {
                        updateNoteList();
                        noteContentArea.setText("");
                    }
                } catch (IOException | ClassNotFoundException e) {
                    JOptionPane.showMessageDialog(this, "Error deleting file", "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void deleteSelectedFiles() {
        List<String> selectedNotes = noteList.getSelectedValuesList();
        if (!selectedNotes.isEmpty()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Are you sure you want to delete " + selectedNotes.size() + " files?",
                    "Confirm Delete",
                    JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                try {
                    // Delete locally
                    for (String fileName : selectedNotes) {
                        File fileToDelete = new File(notesFolder, fileName);
                        fileToDelete.delete();
                    }

                    // Sync with server
                    FileOperation deleteOp = new FileOperation(OperationType.DELETE_MULTIPLE,
                            prefs.get(SESSION_KEY, ""), "", null);
                    deleteOp.setFileNames(selectedNotes);
                    out.writeObject(deleteOp);
                    out.flush();

                    FileOperation response = (FileOperation) in.readObject();
                    updateNoteList();
                    noteContentArea.setText("");
                } catch (IOException | ClassNotFoundException e) {
                    JOptionPane.showMessageDialog(this, "Error deleting files", "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }
    private void logout(String username) {
        prefs.remove(SESSION_KEY);
        deleteFolder(notesFolder);
        getContentPane().removeAll();
        showLoginScreen();
        revalidate();
        repaint();
    }

    private void deleteFolder(File folder) {
        if (folder != null && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteFolder(file);
                    } else {
                        file.delete();
                    }
                }
            }
            folder.delete();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(App::new);
    }
}

class Login extends JPanel {
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JCheckBox showPasswordCheckBox;

    public Login(Connection conn, LoginSuccessCallback callback) {
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);

        JLabel loginTitle = new JLabel("Login");
        loginTitle.setFont(new Font("Arial", Font.BOLD, 16));

        JLabel usernameLabel = new JLabel("Username:");
        usernameField = new JTextField(15);

        JLabel passwordLabel = new JLabel("Password:");
        passwordField = new JPasswordField(15);

        showPasswordCheckBox = new JCheckBox("Show Password");

        JButton loginButton = new JButton("Login");

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        add(loginTitle, gbc);

        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        add(usernameLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        add(usernameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.anchor = GridBagConstraints.EAST;
        add(passwordLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        add(passwordField, gbc);

        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.anchor = GridBagConstraints.WEST;
        add(showPasswordCheckBox, gbc);

        gbc.gridx = 1;
        gbc.gridy = 4;
        gbc.anchor = GridBagConstraints.CENTER;
        add(loginButton, gbc);

        showPasswordCheckBox.addActionListener(e -> {
            if (showPasswordCheckBox.isSelected()) {
                passwordField.setEchoChar((char) 0);
            } else {
                passwordField.setEchoChar('\u2022');
            }
        });

        loginButton.addActionListener(e -> {
            String username = usernameField.getText();
            String password = new String(passwordField.getPassword());

            if (username.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please fill in both fields", "Error", JOptionPane.ERROR_MESSAGE);
            } else {
                try {
                    PreparedStatement stmt = conn
                            .prepareStatement("SELECT * FROM users WHERE username = ? AND password = ?");
                    stmt.setString(1, username);
                    stmt.setString(2, password);
                    ResultSet rs = stmt.executeQuery();

                    if (rs.next()) {
                        JOptionPane.showMessageDialog(this, "Login successful!", "Success",
                                JOptionPane.INFORMATION_MESSAGE);
                        callback.onLoginSuccess(username);
                    } else {
                        JOptionPane.showMessageDialog(this, "Invalid username or password", "Error",
                                JOptionPane.ERROR_MESSAGE);
                    }

                } catch (SQLException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(this, "Database error", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }

    interface LoginSuccessCallback {
        void onLoginSuccess(String username);
    }
}

class Register extends JPanel {
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JPasswordField confirmPasswordField;

    private void notifyServer(String username) {
        try (Socket socket = new Socket("localhost", 12345);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(username);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

public Register(Connection conn, Login.LoginSuccessCallback callback) {
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);

        JLabel registerTitle = new JLabel("Register");
        registerTitle.setFont(new Font("Arial", Font.BOLD, 16));

        JLabel usernameLabel = new JLabel("Username:");
        usernameField = new JTextField(15);

        JLabel passwordLabel = new JLabel("Password:");
        passwordField = new JPasswordField(15);

        JLabel confirmPasswordLabel = new JLabel("Confirm Password:");
        confirmPasswordField = new JPasswordField(15);

        JButton registerButton = new JButton("Register");

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        add(registerTitle, gbc);

        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        add(usernameLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        add(usernameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.anchor = GridBagConstraints.EAST;
        add(passwordLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        add(passwordField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.anchor = GridBagConstraints.EAST;
        add(confirmPasswordLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        add(confirmPasswordField, gbc);

        gbc.gridx = 1;
        gbc.gridy = 4;
        gbc.anchor = GridBagConstraints.CENTER;
        add(registerButton, gbc);

        registerButton.addActionListener(e -> {
            String username = usernameField.getText();
            String password = new String(passwordField.getPassword());
            String confirmPassword = new String(confirmPasswordField.getPassword());

            if (username.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please fill in all fields", "Error", JOptionPane.ERROR_MESSAGE);
            } else if (!password.equals(confirmPassword)) {
                JOptionPane.showMessageDialog(this, "Passwords do not match", "Error", JOptionPane.ERROR_MESSAGE);
            } else {
                try {
                    PreparedStatement checkUserStmt = conn.prepareStatement("SELECT * FROM users WHERE username = ?");
                    checkUserStmt.setString(1, username);
                    ResultSet rs = checkUserStmt.executeQuery();

                    if (rs.next()) {
                        JOptionPane.showMessageDialog(this, "Username already exists", "Error",
                                JOptionPane.ERROR_MESSAGE);
                    } else {
                        PreparedStatement stmt = conn
                                .prepareStatement("INSERT INTO users (username, password) VALUES (?, ?)");
                        stmt.setString(1, username);
                        stmt.setString(2, password);
                        stmt.executeUpdate();

                        // Notify the server about the new user
                        notifyServer(username);

                        JOptionPane.showMessageDialog(this, "Registration successful!", "Success",
                                JOptionPane.INFORMATION_MESSAGE);
                        callback.onLoginSuccess(username);
                    }

                } catch (SQLException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(this, "Database error", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }
}