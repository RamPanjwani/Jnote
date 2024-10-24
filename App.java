import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
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

    private String removeExtension(String fileName) {
        if (fileName != null && fileName.endsWith(".txt")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private String addExtension(String fileName) {
        if (fileName != null && !fileName.endsWith(".txt")) {
            return fileName + ".txt";
        }
        return fileName;
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
            showLoginScreen(); // Show only login screen by default
        }
    }

    private void showLoginScreen() {
        setTitle("Login");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(400, 500);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        Login loginPanel = new Login(conn, this::showDashboard);

        // Add navigation label
        JLabel registerLabel = new JLabel("Don't have an account? Register here", SwingConstants.CENTER);
        registerLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        registerLabel.setForeground(Color.BLUE);
        registerLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                getContentPane().removeAll();
                showRegisterScreen();
                revalidate();
                repaint();
            }
        });

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(loginPanel, BorderLayout.CENTER);
        mainPanel.add(registerLabel, BorderLayout.SOUTH);

        add(mainPanel);
        setVisible(true);
    }

    private void showRegisterScreen() {
        setTitle("Register");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(400, 500);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        Register registerPanel = new Register(conn, this::showDashboard);

        // Add navigation label
        JLabel loginLabel = new JLabel("Already have an account? Login here", SwingConstants.CENTER);
        loginLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        loginLabel.setForeground(Color.BLUE);
        loginLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                getContentPane().removeAll();
                showLoginScreen();
                revalidate();
                repaint();
            }
        });

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(registerPanel, BorderLayout.CENTER);
        mainPanel.add(loginLabel, BorderLayout.SOUTH);

        add(mainPanel);
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

        selectAllButton = new JButton("Select All");
        selectAllButton.addActionListener(e -> noteList.setSelectionInterval(0, noteListModel.size() - 1));

        deleteSelectedButton = new JButton("Delete Files"); // Changed button text
        deleteSelectedButton.addActionListener(e -> deleteSelectedFiles());

        fileManagementPanel.add(createFileButton);
        fileManagementPanel.add(selectAllButton);
        fileManagementPanel.add(deleteSelectedButton); // No deleteFileButton anymore

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

        // Add header with username in the middle and logout button on the side
        JPanel headerPanel = new JPanel(new BorderLayout());

        JLabel userLabel = new JLabel("Logged in as: " + username, SwingConstants.CENTER); // Centered username label
        headerPanel.add(userLabel, BorderLayout.CENTER);

        JButton logoutButton = new JButton("Logout");
        logoutButton.addActionListener(e -> logout(username));
        headerPanel.add(logoutButton, BorderLayout.EAST); // Logout button on the right

        add(headerPanel, BorderLayout.NORTH); // Add header to the top
        add(splitPane, BorderLayout.CENTER); // Add the split pane for notes

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
                if (serverFiles != null) {
                    for (FileData fileData : serverFiles) {
                        File localFile = new File(notesFolder, fileData.getFileName());
                        Files.writeString(localFile.toPath(), fileData.getContent());
                    }
                }
                updateNoteList();
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
                        .map(this::removeExtension) // Remove .txt extension for display
                        .sorted()
                        .forEach(noteListModel::addElement);
            }
        }
    }

    private void loadNoteContent() {
        String selectedNote = noteList.getSelectedValue();
        if (selectedNote != null) {
            File noteFile = new File(notesFolder, addExtension(selectedNote)); // Add .txt for file operations
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
                File noteFile = new File(notesFolder, addExtension(selectedNote)); // Add .txt for file operations
                Files.writeString(noteFile.toPath(), content);

                // Sync with server
                out.writeObject(new FileOperation(OperationType.UPDATE_FILE,
                        prefs.get(SESSION_KEY, ""), addExtension(selectedNote), content));
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
            // Remove .txt if user added it
            fileName = removeExtension(fileName);

            // Add .txt for file creation
            String fileNameWithExt = addExtension(fileName);

            File newFile = new File(notesFolder, fileNameWithExt);
            try {
                if (newFile.createNewFile()) {
                    // Sync with server
                    out.writeObject(new FileOperation(OperationType.CREATE_FILE,
                            prefs.get(SESSION_KEY, ""), fileNameWithExt, ""));
                    out.flush();

                    FileOperation response = (FileOperation) in.readObject();
                    if ("SUCCESS".equals(response.getContent())) {
                        updateNoteList();
                        noteList.setSelectedValue(fileName, true); // Select without extension
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
                        File fileToDelete = new File(notesFolder, addExtension(fileName)); // Add .txt for file
                                                                                           // operations
                        fileToDelete.delete();
                    }

                    // Sync with server - convert to list with extensions
                    List<String> fileNamesWithExt = selectedNotes.stream()
                            .map(this::addExtension)
                            .collect(java.util.stream.Collectors.toList());

                    FileOperation deleteOp = new FileOperation(OperationType.DELETE_MULTIPLE,
                            prefs.get(SESSION_KEY, ""), "", null);
                    deleteOp.setFileNames(fileNamesWithExt);
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

        // Add some padding at the top
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        add(Box.createVerticalStrut(30), gbc);

        JLabel loginTitle = new JLabel("Login");
        loginTitle.setFont(new Font("Arial", Font.BOLD, 24)); // Made font bigger

        JLabel usernameLabel = new JLabel("Username:");
        usernameField = new JTextField(20); // Made text fields wider

        JLabel passwordLabel = new JLabel("Password:");
        passwordField = new JPasswordField(20);

        showPasswordCheckBox = new JCheckBox("Show Password");

        JButton loginButton = new JButton("Login");
        loginButton.setPreferredSize(new Dimension(200, 35)); // Made button bigger

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        add(loginTitle, gbc);

        // Add some spacing after title
        gbc.gridy = 2;
        add(Box.createVerticalStrut(20), gbc);

        gbc.gridy = 3;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        add(usernameLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        add(usernameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.anchor = GridBagConstraints.EAST;
        add(passwordLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        add(passwordField, gbc);

        gbc.gridx = 1;
        gbc.gridy = 5;
        gbc.anchor = GridBagConstraints.WEST;
        add(showPasswordCheckBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 2;
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
    private JCheckBox showPasswordCheckBox;

    public Register(Connection conn, Login.LoginSuccessCallback callback) {
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);

        // Add some padding at the top
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        add(Box.createVerticalStrut(30), gbc);

        JLabel registerTitle = new JLabel("Register");
        registerTitle.setFont(new Font("Arial", Font.BOLD, 24));

        JLabel usernameLabel = new JLabel("Username:");
        usernameField = new JTextField(20);

        JLabel passwordLabel = new JLabel("Password:");
        passwordField = new JPasswordField(20);

        JLabel confirmPasswordLabel = new JLabel("Confirm Password:");
        confirmPasswordField = new JPasswordField(20);

        // Add show password checkbox
        showPasswordCheckBox = new JCheckBox("Show Password");
        showPasswordCheckBox.addActionListener(e -> {
            if (showPasswordCheckBox.isSelected()) {
                passwordField.setEchoChar((char) 0);
                confirmPasswordField.setEchoChar((char) 0);
            } else {
                passwordField.setEchoChar('\u2022');
                confirmPasswordField.setEchoChar('\u2022');
            }
        });

        JButton registerButton = new JButton("Register");
        registerButton.setPreferredSize(new Dimension(200, 35));

        // Layout components
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        add(registerTitle, gbc);

        // Add some spacing after title
        gbc.gridy = 2;
        add(Box.createVerticalStrut(20), gbc);

        // Username field
        gbc.gridy = 3;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        add(usernameLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        add(usernameField, gbc);

        // Password field
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.anchor = GridBagConstraints.EAST;
        add(passwordLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        add(passwordField, gbc);

        // Confirm password field
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.anchor = GridBagConstraints.EAST;
        add(confirmPasswordLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        add(confirmPasswordField, gbc);

        // Show password checkbox
        gbc.gridx = 1;
        gbc.gridy = 6;
        gbc.anchor = GridBagConstraints.WEST;
        add(showPasswordCheckBox, gbc);

        // Register button
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        add(registerButton, gbc);

        // Add action listener for register button
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

    private void notifyServer(String username) {
        try (Socket socket = new Socket("localhost", 12345);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(username);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}