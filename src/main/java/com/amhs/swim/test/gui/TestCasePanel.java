package com.amhs.swim.test.gui;

import com.amhs.swim.test.testcase.BaseTestCase;
import com.amhs.swim.test.testcase.BaseTestCase.TestMessage;
import com.amhs.swim.test.util.Logger;
import com.amhs.swim.test.util.ResultManager;
import com.amhs.swim.test.util.TestResult;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.plaf.basic.BasicTextFieldUI;
import java.awt.*;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dynamic execution environment for individual AMHS/SWIM test cases.
 * 
 * This component provides the interactive interface for:
 * 1. Test Injection: Single-message or batch execution of ICAO-compliant AMQP messages.
 * 2. Configuration: Per-message custom data inputs and file-based address picker (CTSW112).
 * 3. Live Monitoring: Real-time, color-coded AMQP 1.0 log output with "Deep Inspection" metadata.
 * 
 * Implements an event-driven lifecycle that integrates directly with the {@link Logger} 
 * and {@link com.amhs.swim.test.util.ResultManager} for session persistence.
 */
public class TestCasePanel extends JPanel {

    private Color bgPanel;
    private Color bgRowEven;
    private Color bgRowOdd;
    private Color bgLog;
    private Color bgHeader;
    private Color clrAccent;
    private Color clrSuccess;
    private Color clrWarn;
    private Color clrFg;
    private Color clrFgDim;
    private Color clrSeparator;
    private Color clrRunBtn;
    private Color clrCancel;
    private Color clrBatch;
    private Color clrBtnText;

    // ── State ──────────────────────────────────────────────────────────────────
    private BaseTestCase currentCase;
    private final JPanel headerPanel;
    private final JPanel messagesPanel;
    private final JTextArea logArea;
    private final JLabel caseTitle;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread runThread;
    private Runnable onCancel; // callback when Cancel is confirmed

    /** per-message UI elements */
    private final Map<Integer, JCheckBox>  checkboxes  = new HashMap<>();
    private final Map<Integer, JTextField> customFields = new HashMap<>();
    private final Map<Integer, JLabel>     counters     = new HashMap<>();
    private final Map<Integer, AtomicInteger> attempts  = new HashMap<>();
    /** for file-picker messages (CTSW112-style) */
    private final Map<Integer, JTextField> fileFields   = new HashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public TestCasePanel() {
        setupTheme();
        setLayout(new BorderLayout());
        setBackground(bgPanel);

        // ── Header ──
        headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(bgHeader);
        headerPanel.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, clrSeparator),
            new EmptyBorder(10, 16, 10, 16)
        ));
        caseTitle = new JLabel("Select a test case");
        caseTitle.setFont(new Font("Monospaced", Font.BOLD, 15));
        caseTitle.setForeground(clrAccent);
        headerPanel.add(caseTitle, BorderLayout.CENTER);

        // ── Message rows container ──
        messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(bgRowEven);
        JScrollPane msgScroll = new JScrollPane(messagesPanel);
        msgScroll.setBackground(bgRowEven);
        msgScroll.getViewport().setBackground(bgRowEven);
        msgScroll.setBorder(null);

        // ── Bottom action bar ──
        JPanel actionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        actionBar.setBackground(bgHeader);
        actionBar.setBorder(new MatteBorder(1, 0, 1, 0, clrSeparator));

        JButton batchBtn  = styledButton("▶  Batch Execute", clrBatch);
        JButton cancelBtn = styledButton("✕  Cancel / Close", clrCancel);
        actionBar.add(batchBtn);
        actionBar.add(cancelBtn);

        batchBtn.addActionListener(e -> doBatchExecute());
        cancelBtn.addActionListener(e -> doCancel());

        // ── Log area ──
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(bgLog);
        logArea.setForeground(new Color(0xE2, 0xE8, 0xF0)); // Slate 200 for text in dark log
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setMargin(new Insets(8, 12, 8, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(new MatteBorder(1, 0, 0, 0, clrSeparator));
        logScroll.setPreferredSize(new Dimension(0, 260));

        // ── Split: top (messages) + bottom (log) ──
        JSplitPane innerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, msgScroll, logScroll);
        innerSplit.setResizeWeight(0.45);
        innerSplit.setDividerSize(4);
        innerSplit.setBackground(bgPanel);

        JPanel topWrapper = new JPanel(new BorderLayout());
        topWrapper.setBackground(bgPanel);
        topWrapper.add(innerSplit, BorderLayout.CENTER);
        topWrapper.add(actionBar, BorderLayout.SOUTH);

        add(headerPanel, BorderLayout.NORTH);
        add(topWrapper, BorderLayout.CENTER);

        showPlaceholder();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads and initializes a test scenario into the execution view.
     * @param tc The test case instance to activate.
     */
    public void loadTestCase(BaseTestCase tc) {
        currentCase = tc;
        clearState();

        caseTitle.setText("Case  " + tc.getTestCaseId() + "  —  " + tc.getTestCaseName());

        messagesPanel.removeAll();

        List<TestMessage> messages = tc.getMessages();
        if (messages == null || messages.isEmpty()) {
            JLabel empty = new JLabel("  No messages defined for this test case.");
            empty.setForeground(clrFgDim);
            messagesPanel.add(empty);
        } else {
            boolean even = true;
            for (TestMessage msg : messages) {
                messagesPanel.add(buildMessageRow(msg, tc, even));
                even = !even;
            }
        }

        // Register per-case log listener
        String cid = tc.getTestCaseId();
        Logger.setCaseLogListener(cid, message ->
            SwingUtilities.invokeLater(() -> appendLog(message)));

        // Print log header
        appendLogBanner(tc);

        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    /** Set callback invoked after Cancel is confirmed. */
    public void setOnCancel(Runnable r) { this.onCancel = r; }

    // ─────────────────────────────────────────────────────────────────────────
    // Row builder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs a single-message row featuring control buttons and metadata inputs.
     */
    private JPanel buildMessageRow(TestMessage msg, BaseTestCase tc, boolean even) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(even ? bgRowEven : bgRowOdd);
        row.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, clrSeparator),
            new EmptyBorder(6, 12, 6, 12)
        ));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        int col = 0;

        // ── MSG label ──
        g.gridx = col++; g.weightx = 0;
        JLabel msgLbl = new JLabel("Message " + msg.getIndex());
        msgLbl.setFont(new Font("Monospaced", Font.BOLD, 12));
        msgLbl.setForeground(clrAccent);
        msgLbl.setPreferredSize(new Dimension(88, 22));
        row.add(msgLbl, g);

        // ── Min text (read-only tooltip label) ──
        g.gridx = col++; g.weightx = 0.30;
        // Display first line only; full text in tooltip
        String displayText = msg.getMinText().replace("\n", " │ ");
        if (displayText.length() > 60) displayText = displayText.substring(0, 57) + "…";
        JLabel minLbl = new JLabel(displayText);
        minLbl.setFont(new Font("Monospaced", Font.PLAIN, 11));
        minLbl.setForeground(msg.isOptional() ? clrFgDim : clrFg);
        minLbl.setToolTipText("<html><pre>" + msg.getMinText().replace("<","&lt;") + "</pre></html>");
        if (msg.isOptional()) {
            minLbl.setText("[OPT] " + displayText);
        }
        row.add(minLbl, g);

        // ── Custom data field ──
        boolean isFileMsg = isFileMessage(msg, tc);
        if (isFileMsg) {
            // File picker: text field + browse button (+ optional sync button for msg ≥ 2)
            g.gridx = col++; g.weightx = 0.35;
            JPanel filePicker = buildFilePicker(msg, tc);
            row.add(filePicker, g);
        } else {
            g.gridx = col++; g.weightx = 0.35;
            JTextField customField = new JTextField(msg.getDefaultData(), 18);
            styleTextField(customField);
            customFields.put(msg.getIndex(), customField);
            row.add(customField, g);
        }

        // ── Enable checkbox ──
        g.gridx = col++; g.weightx = 0;
        JCheckBox cb = new JCheckBox();
        cb.setSelected(msg.isMandatory());
        cb.setBackground(even ? bgRowEven : bgRowOdd);
        cb.setForeground(clrFg);
        cb.setToolTipText("Enable/Disable this message in batch execution");
        checkboxes.put(msg.getIndex(), cb);
        row.add(cb, g);

        // ── Single run button ──
        g.gridx = col++; g.weightx = 0;
        JButton runBtn = styledButton("▶", clrRunBtn);
        runBtn.setToolTipText("Execute Message " + msg.getIndex() + " once");
        runBtn.setPreferredSize(new Dimension(36, 26));
        AtomicInteger cnt = new AtomicInteger(0);
        attempts.put(msg.getIndex(), cnt);
        JLabel cntLbl = new JLabel("/0/");
        cntLbl.setFont(new Font("Monospaced", Font.ITALIC, 11));
        cntLbl.setForeground(clrFgDim);
        cntLbl.setPreferredSize(new Dimension(44, 22));
        counters.put(msg.getIndex(), cntLbl);
        runBtn.addActionListener(e -> doSingleExecute(msg.getIndex(), cnt, cntLbl));
        row.add(runBtn, g);

        // ── Counter ──
        g.gridx = col++; g.weightx = 0;
        row.add(cntLbl, g);

        return row;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File picker builder (for CTSW112 and similar)
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isFileMessage(TestMessage msg, BaseTestCase tc) {
        // Now using the explicit flag in TestMessage
        return msg.isFile();
    }

    private JPanel buildFilePicker(TestMessage msg, BaseTestCase tc) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);

        JTextField fileField = new JTextField(22);
        fileField.setUI(new BasicTextFieldUI());
        fileField.setFont(new Font("Monospaced", Font.PLAIN, 11));
        fileField.setOpaque(true);
        fileField.setBackground(Color.WHITE);
        fileField.setForeground(Color.BLACK);
        fileField.setCaretColor(Color.BLACK);
        fileField.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        fileField.setToolTipText("Path to address file (one AMHS address per line)");
        fileFields.put(msg.getIndex(), fileField);

        JButton browseBtn = styledButton("Browse…", new Color(0x94, 0xA3, 0xB8)); // Slate 400
        browseBtn.setPreferredSize(new Dimension(80, 24));
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Select AMHS Address File (one address per line)");
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text files", "txt"));
            int result = fc.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                fileField.setText(f.getAbsolutePath());
                // Update counter label with loaded count
                try {
                    List<String> lines = java.nio.file.Files.readAllLines(f.toPath());
                    long count = lines.stream().filter(l -> !l.isBlank()).count();
                    JLabel cntLbl = counters.get(msg.getIndex());
                    if (cntLbl != null) {
                        cntLbl.setText("/" + count + "/");
                        int required = msg.getIndex() == 1 ? 512 : 513;
                        cntLbl.setForeground(count == required ? clrSuccess : clrWarn);
                    }
                } catch (Exception ex) { /* ignore */ }
            }
        });

        panel.add(fileField);
        panel.add(browseBtn);

        // ── Sync button for messages beyond the first ──
        if (msg.getIndex() > 1) {
            JButton syncBtn = styledButton("⇄ Sync Msg 1", new Color(0x94, 0xA3, 0xB8));
            syncBtn.setPreferredSize(new Dimension(90, 24));
            syncBtn.setToolTipText("Use file from Message 1 for this row");
            syncBtn.addActionListener(e -> {
                JTextField srcField = fileFields.get(1);
                if (srcField != null && !srcField.getText().isBlank()) {
                    fileField.setText(srcField.getText());
                    // Re-count
                    try {
                        List<String> lines = java.nio.file.Files.readAllLines(
                            java.nio.file.Paths.get(srcField.getText()));
                        long count = lines.stream().filter(l -> !l.isBlank()).count();
                        JLabel cntLbl = counters.get(msg.getIndex());
                        if (cntLbl != null) {
                            cntLbl.setText("/" + count + "/");
                            int required = msg.getIndex() == 1 ? 512 : 513;
                            cntLbl.setForeground(count == required ? clrSuccess : clrWarn);
                        }
                    } catch (Exception ex) { /* ignore */ }
                } else {
                    JOptionPane.showMessageDialog(this,
                        "Message 1 has no address file selected yet.",
                        "Sync Error", JOptionPane.WARNING_MESSAGE);
                }
            });
            panel.add(syncBtn);
        }
        return panel;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Execution logic
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, String> collectInputs() {
        Map<String, String> inputs = new HashMap<>();
        // Custom data fields
        customFields.forEach((idx, field) -> {
            List<TestMessage> msgs = currentCase.getMessages();
            for (TestMessage m : msgs) {
                if (m.getIndex() == idx) {
                    inputs.put(m.getCustomKey(), field.getText());
                    break;
                }
            }
        });
        // File fields
        fileFields.forEach((idx, field) -> {
            List<TestMessage> msgs = currentCase.getMessages();
            for (TestMessage m : msgs) {
                if (m.getIndex() == idx) {
                    inputs.put(m.getCustomKey(), field.getText());
                    break;
                }
            }
        });
        return inputs;
    }

    private void doSingleExecute(int msgIndex, AtomicInteger cnt, JLabel cntLbl) {
        if (currentCase == null) return;
        Map<String, String> inputs = collectInputs();
        int attempt = cnt.incrementAndGet();
        SwingUtilities.invokeLater(() -> cntLbl.setText("/" + attempt + "/"));

        new Thread(() -> {
            try {
                boolean sent = currentCase.executeSingle(msgIndex, attempt, inputs);
                // Record result for Excel (Requirement 4)
                ResultManager.getInstance().addResult(new TestResult(
                    currentCase.getTestCaseId(),
                    attempt,
                    msgIndex,
                    inputs.getOrDefault(currentCase.getMessages().get(msgIndex-1).getCustomKey(), ""),
                    sent ? "SENT" : "FAILED",
                    sent ? "SUCCESS" : "ERROR"
                ));
            } catch (Exception ex) {
                Logger.logCase(currentCase.getTestCaseId(), "ERROR",
                    "[MSG-" + msgIndex + "] Exception: " + ex.getMessage());
                // Record exception result
                ResultManager.getInstance().addResult(new TestResult(
                    currentCase.getTestCaseId(),
                    attempt,
                    msgIndex,
                    "EXCEPTION",
                    "FAILED",
                    "ERROR"
                ));
            }
        }, "SingleExec-" + msgIndex).start();
    }

    private void doBatchExecute() {
        if (currentCase == null) return;
        if (running.get()) {
            JOptionPane.showMessageDialog(this,
                "Execution already in progress.", "Busy", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Map<String, String> inputs = collectInputs();
        List<TestMessage> messages = currentCase.getMessages();
        running.set(true);

        runThread = new Thread(() -> {
            Logger.logCaseStart(currentCase.getTestCaseId(), currentCase.getCriteria(), null);
            boolean allOk = true;
            for (TestMessage msg : messages) {
                if (!running.get()) break;
                JCheckBox cb = checkboxes.get(msg.getIndex());
                if (cb != null && !cb.isSelected()) continue; // skipped

                AtomicInteger cnt = attempts.getOrDefault(msg.getIndex(), new AtomicInteger(0));
                int attempt = cnt.incrementAndGet();
                final int idx = msg.getIndex();
                SwingUtilities.invokeLater(() -> {
                    JLabel lbl = counters.get(idx);
                    if (lbl != null) lbl.setText("/" + cnt.get() + "/");
                });

                try {
                    boolean ok = currentCase.executeSingle(msg.getIndex(), attempt, inputs);
                    allOk &= ok;
                    // Record result for Excel (Requirement 4)
                    ResultManager.getInstance().addResult(new TestResult(
                        currentCase.getTestCaseId(),
                        attempt,
                        msg.getIndex(),
                        inputs.getOrDefault(msg.getCustomKey(), ""),
                        ok ? "SENT" : "FAILED",
                        ok ? "SUCCESS" : "ERROR"
                    ));
                } catch (Exception ex) {
                    Logger.logCase(currentCase.getTestCaseId(), "ERROR",
                        "[MSG-" + msg.getIndex() + "] Exception: " + ex.getMessage());
                    allOk = false;
                    // Record exception result
                    ResultManager.getInstance().addResult(new TestResult(
                        currentCase.getTestCaseId(),
                        attempt,
                        msg.getIndex(),
                        "EXCEPTION",
                        "FAILED",
                        "ERROR"
                    ));
                }
            }
            Logger.logCaseEnd(currentCase.getTestCaseId());
            running.set(false);
        }, "BatchExec-" + currentCase.getTestCaseId());
        runThread.setDaemon(true);
        runThread.start();
    }

    private void doCancel() {
        if (running.get()) {
            int choice = JOptionPane.showConfirmDialog(this,
                "Execution is currently running for " +
                (currentCase != null ? currentCase.getTestCaseId() : "this case") + ".\n" +
                "Confirm cancel? This will STOP the current execution.",
                "Confirm Cancel",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;

            // Stop execution
            running.set(false);
            if (runThread != null) runThread.interrupt();
        }
        // Deregister listeners
        if (currentCase != null) {
            Logger.clearCaseLogListener(currentCase.getTestCaseId());
        }
        currentCase = null;
        clearState();
        showPlaceholder();
        if (onCancel != null) onCancel.run();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Log display
    // ─────────────────────────────────────────────────────────────────────────

    private void appendLog(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void appendLogBanner(BaseTestCase tc) {
        String sep = "═".repeat(60);
        String criteria = tc.getCriteria();
        String requirements = criteria;
        String ref = "";

        if (criteria != null && criteria.contains("Ref:")) {
            int idx = criteria.lastIndexOf("Ref:");
            requirements = criteria.substring(0, idx).trim();
            ref = criteria.substring(idx).replace("Ref:", "").trim();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(sep).append("\n");
        sb.append("CASE ").append(tc.getTestCaseId()).append("\n");
        if (requirements != null && !requirements.isEmpty()) {
            sb.append(requirements).append("\n");
        }

        String guide = tc.getManualGuide();
        if (guide != null && !guide.isEmpty()) {
            sb.append("Verification: ").append(guide).append("\n");
        }

        if (!ref.isEmpty()) {
            sb.append("Ref: ").append(ref).append("\n");
        }
        sb.append(sep).append("\n");

        logArea.append(sb.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void clearState() {
        running.set(false);
        checkboxes.clear();
        customFields.clear();
        counters.clear();
        attempts.clear();
        fileFields.clear();
        logArea.setText("");
    }

    private void showPlaceholder() {
        messagesPanel.removeAll();
        caseTitle.setText("Select a test case from the left panel");
        JLabel ph = new JLabel("  No test case selected. Click a case button on the left to begin.");
        ph.setForeground(clrFgDim);
        ph.setFont(new Font("Monospaced", Font.ITALIC, 12));
        ph.setBorder(new EmptyBorder(20, 16, 20, 16));
        messagesPanel.add(ph);
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    private JButton styledButton(String text, Color bg) {
        JButton btn = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isPressed() ? bg.darker() :
                    getModel().isRollover() ? bg.brighter() : bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setForeground(clrBtnText);
        btn.setFont(new Font("SansSerif", Font.BOLD, 11));
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(130, 28));
        return btn;
    }

    private void styleTextField(JTextField tf) {
        tf.setUI(new BasicTextFieldUI());
        tf.setOpaque(true);
        tf.setBackground(Color.WHITE);
        tf.setForeground(Color.BLACK);
        tf.setCaretColor(Color.BLACK);
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.BLACK),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        tf.setFont(new Font("Monospaced", Font.PLAIN, 11));
    }

    private void setupTheme() {
        Color base = UIManager.getColor("Panel.background");
        boolean isDark = false;
        if (base != null) {
            double l = (0.2126 * base.getRed() + 0.7152 * base.getGreen() + 0.0722 * base.getBlue()) / 255;
            isDark = l < 0.5;
        }

        clrAccent = new Color(0x3B, 0x82, 0xF6); // Blue 500
        clrSuccess = new Color(0x22, 0xC5, 0x5E); // Green 500
        clrWarn = new Color(0xE1, 0x1D, 0x48); // Rose 600
        clrRunBtn = new Color(0x3B, 0x82, 0xF6); // Blue 500

        if (isDark) {
            bgPanel = new Color(0x0F, 0x17, 0x2A); // Slate 900
            bgRowEven = new Color(0x1E, 0x29, 0x3B); // Slate 800
            bgRowOdd = new Color(0x0F, 0x17, 0x2A); // Slate 900
            bgHeader = new Color(0x11, 0x18, 0x27); // Gray 900
            bgLog = new Color(0x02, 0x06, 0x17); // Slate 950
            clrFg = new Color(0xF1, 0xF5, 0xF9); // Slate 100
            clrFgDim = new Color(0x94, 0xA3, 0xB8); // Slate 400
            clrSeparator = new Color(0x33, 0x41, 0x55); // Slate 700
            clrCancel = new Color(0x94, 0xA3, 0xB8); // Slate 400
            clrBatch = new Color(0xF1, 0xF5, 0xF9); // Slate 100
            clrBtnText = Color.BLACK;
        } else {
            bgPanel = new Color(0xF8, 0xFA, 0xFC); // Slate 50
            bgRowEven = Color.WHITE;
            bgRowOdd = Color.WHITE;
            bgHeader = new Color(0xFF, 0xFF, 0xFF); // White
            bgLog = new Color(0x1E, 0x29, 0x3B); // Slate 800
            clrFg = new Color(0x33, 0x41, 0x55); // Slate 700
            clrFgDim = new Color(0x64, 0x74, 0x8B); // Slate 500
            clrSeparator = new Color(0xE2, 0xE8, 0xF0); // Slate 200
            clrCancel = new Color(0x64, 0x74, 0x8B); // Slate 500
            clrBatch = new Color(0x0F, 0x17, 0x2A); // Slate 900
            clrBtnText = Color.WHITE;
        }
    }
}
