package meridian.worldmap;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * What the window says when it has nothing to show yet.
 *
 * <p>Opened by a double-click on the jar with no proxy behind it and no archive where one was
 * expected: rather than a message and an exit, it asks for the files. Drop the map archive on it -
 * or pick it with the button - and it opens; drop a markers file too and the markers are drawn on
 * top. Either order works, and a markers file dropped alone simply waits for a map.
 */
final class OpenPrompt extends JFrame {

    private final JLabel headline = new JLabel("Drop the map archive here", SwingConstants.CENTER);
    private final JLabel detail = new JLabel("", SwingConstants.CENTER);
    /** Markers dropped so far; they are drawn on whatever map is opened next. */
    private final List<Place> markers = new ArrayList<>();

    /**
     * @param looked where the archive was expected, so the window can say what it tried, or null
     *               when there was nowhere sensible to look
     */
    OpenPrompt(Path looked) {
        super("Meridian - World Map");
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, 16f));
        detail.setForeground(new Color(120, 120, 120));
        detail.setText(looked == null
                ? "worldmap.dat - and a markers .json with it, if you have one"
                : "Nothing at " + looked);

        JLabel hint = new JLabel("worldmap.dat, and a markers .json if you have one",
                SwingConstants.CENTER);
        hint.setForeground(new Color(120, 120, 120));

        JButton choose = new JButton("Choose a file...");
        choose.addActionListener(e -> pick());

        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.setBorder(BorderFactory.createEmptyBorder(40, 40, 40, 40));
        middle.add(headline, BorderLayout.NORTH);
        middle.add(hint, BorderLayout.CENTER);
        middle.add(detail, BorderLayout.SOUTH);

        JPanel buttons = new JPanel();
        buttons.add(choose);

        JPanel root = new JPanel(new BorderLayout());
        root.add(middle, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        root.setTransferHandler(dropped());
        setContentPane(root);
        setSize(520, 260);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    // ------------------------------------------------------------------

    private TransferHandler dropped() {
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    var files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    // Markers before maps, whatever order they were dropped in: a map opens a
                    // window, and the markers it is opened with have to be in hand by then.
                    files.stream().filter(f -> !isArchive(f)).forEach(f -> take(f.toPath()));
                    files.stream().filter(OpenPrompt::isArchive).forEach(f -> take(f.toPath()));
                    return true;
                } catch (Exception e) {
                    say("That could not be read: " + e);
                    return false;
                }
            }
        };
    }

    private void pick() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Map archive or markers (*.dat, *.json)", "dat", "json"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] chosen = chooser.getSelectedFiles();
        for (File file : chosen) {
            if (!isArchive(file)) {
                take(file.toPath());
            }
        }
        for (File file : chosen) {
            if (isArchive(file)) {
                take(file.toPath());
            }
        }
    }

    /** One file: a map opens the window, anything else is read for markers. */
    private void take(Path file) {
        if (isArchive(file.toFile())) {
            if (MapViewer.open(file, markers) != null) {
                dispose();
            }
            return;
        }
        try {
            List<Place> read = Places.read(file);
            if (read.isEmpty()) {
                say("No markers in " + file.getFileName());
                return;
            }
            markers.addAll(read);
            headline.setText("Now drop the map archive");
            say(markers.size() + " marker(s) ready, from " + file.getFileName());
        } catch (Exception e) {
            say("Could not read " + file.getFileName() + ": " + e);
        }
    }

    private void say(String message) {
        detail.setText(message);
    }

    private static boolean isArchive(File file) {
        return file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".dat");
    }
}
