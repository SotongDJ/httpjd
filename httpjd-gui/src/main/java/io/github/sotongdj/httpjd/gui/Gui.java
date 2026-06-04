package io.github.sotongdj.httpjd.gui;

import io.github.sotongdj.httpjd.core.FileServer;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Cross-platform Swing control panel for httpjd.
 *
 * <p>Wraps the same {@link FileServer} core used by the CLI, letting the user
 * pick a directory, port, and listing option, then start/stop the server and
 * open it in a browser. Packaged per-OS with {@code jpackage}.
 */
public final class Gui {

    private final JFrame frame = new JFrame("httpjd — HTTP file server");
    private final JTextField dirField = new JTextField(System.getProperty("user.dir"), 24);
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(8080, 0, 65535, 1));
    private final JCheckBox indexBox = new JCheckBox("Show directory listing");
    private final JButton chooseButton = new JButton("Browse…");
    private final JButton toggleButton = new JButton("Start server");
    private final JButton openButton = new JButton("Open in browser");
    private final JLabel statusLabel = new JLabel("Stopped");
    private final JTextArea log = new JTextArea(8, 40);

    private FileServer server;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to default look and feel
        }
        SwingUtilities.invokeLater(() -> new Gui().show());
    }

    private void show() {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                stopServer();
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        form.add(new JLabel("Directory:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        form.add(dirField, c);
        c.gridx = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        form.add(chooseButton, c);

        c.gridx = 0; c.gridy = 1;
        form.add(new JLabel("Port:"), c);
        c.gridx = 1;
        portSpinner.setPreferredSize(new Dimension(90, portSpinner.getPreferredSize().height));
        JPanel portRow = new JPanel(new BorderLayout());
        portRow.add(portSpinner, BorderLayout.WEST);
        form.add(portRow, c);

        c.gridx = 1; c.gridy = 2;
        form.add(indexBox, c);

        c.gridx = 1; c.gridy = 3;
        JPanel buttons = new JPanel(new BorderLayout(8, 0));
        JPanel left = new JPanel();
        left.add(toggleButton);
        left.add(openButton);
        buttons.add(left, BorderLayout.WEST);
        form.add(buttons, c);

        c.gridx = 0; c.gridy = 4; c.gridwidth = 3;
        form.add(statusLabel, c);

        log.setEditable(false);
        JScrollPane logScroll = new JScrollPane(log);

        openButton.setEnabled(false);
        chooseButton.addActionListener(e -> chooseDirectory());
        toggleButton.addActionListener(e -> toggle());
        openButton.addActionListener(e -> openInBrowser());

        frame.setLayout(new BorderLayout());
        frame.add(form, BorderLayout.NORTH);
        frame.add(logScroll, BorderLayout.CENTER);
        frame.pack();
        frame.setMinimumSize(frame.getSize());
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser(dirField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            if (selected != null) {
                dirField.setText(selected.getAbsolutePath());
            }
        }
    }

    private void toggle() {
        if (server == null) {
            startServer();
        } else {
            stopServer();
        }
    }

    private void startServer() {
        String dir = dirField.getText().trim();
        int port = (Integer) portSpinner.getValue();
        boolean showIndex = indexBox.isSelected();
        try {
            server = new FileServer(Path.of(dir), showIndex, "0.0.0.0", port);
            server.start();
            int actual = server.port();
            setRunning(true);
            statusLabel.setText("Running on http://localhost:" + actual + "/  →  " + server.root());
            append("Serving '" + server.root() + "' on port " + actual
                    + (showIndex ? " (directory listing enabled)" : ""));
        } catch (Exception ex) {
            server = null;
            JOptionPane.showMessageDialog(frame,
                    "Could not start server:\n" + ex.getMessage(),
                    "httpjd", JOptionPane.ERROR_MESSAGE);
            append("error: " + ex.getMessage());
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop(0);
            append("Server stopped.");
            server = null;
        }
        setRunning(false);
        statusLabel.setText("Stopped");
    }

    private void setRunning(boolean running) {
        toggleButton.setText(running ? "Stop server" : "Start server");
        openButton.setEnabled(running);
        dirField.setEnabled(!running);
        portSpinner.setEnabled(!running);
        indexBox.setEnabled(!running);
        chooseButton.setEnabled(!running);
    }

    private void openInBrowser() {
        if (server == null) {
            return;
        }
        try {
            URI uri = URI.create("http://localhost:" + server.port() + "/");
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            } else {
                append("Open " + uri + " in your browser.");
            }
        } catch (Exception ex) {
            append("error opening browser: " + ex.getMessage());
        }
    }

    private void append(String line) {
        String stamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        log.append("[" + stamp + "] " + line + System.lineSeparator());
        log.setCaretPosition(log.getDocument().getLength());
    }

    private Gui() {}
}
