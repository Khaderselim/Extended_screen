import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.Socket;

/**
 * Simple client that connects to the server and displays received JPEG frames in a JFrame.
 * Expects protocol: [4-byte big-endian length][JPEG bytes]...
 */
public class Client {
    private static volatile boolean running = true;

    public static void main(String[] args) {
        String host = "localhost";
        int port = 1234;


        JFrame frame = new JFrame("Screen Client");
        ImagePanel panel = new ImagePanel();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(panel);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        frame.setUndecorated(false); // Set to true if you want to remove title bar
        frame.setVisible(true);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> running = false));

        while (running) {
            try (Socket s = new Socket(host, port)) {
                System.out.println("Connected to server " + host + ":" + port);
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                while (running && !s.isClosed()) {
                    int len;
                    try {
                        len = in.readInt();
                    } catch (EOFException eof) {
                        System.out.println("Server closed connection");
                        break;
                    }
                    if (len <= 0 || len > 50_000_000) {
                        System.err.println("Invalid frame length: " + len);
                        break;
                    }
                    byte[] data = new byte[len];
                    in.readFully(data);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                    if (img != null) {
                        panel.setImage(img);
                    } else {
                        System.err.println("Failed to decode image frame");
                    }
                }
            } catch (IOException e) {
                System.err.println("Connection error: " + e.getMessage());
            }

            // Wait a bit before reconnecting
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        System.out.println("Client stopped");
    }

    // Simple panel that paints the latest image centered and scaled to fit
    private static class ImagePanel extends JPanel {
        private BufferedImage image;

        public synchronized void setImage(BufferedImage img) {
            this.image = img;
            SwingUtilities.invokeLater(() -> {
                repaint();
            });
        }

        @Override
        protected synchronized void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            // Use bicubic interpolation for clearer scaled images
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int iw = image.getWidth();
            int ih = image.getHeight();
            double scaleW = (double) getWidth() / iw;
            double scaleH = (double) getHeight() / ih;
            double scale = Math.min(scaleW, scaleH);
            int drawW = (int) (iw * scale);
            int drawH = (int) (ih * scale);
            int drawX = (getWidth() - drawW) / 2;
            int drawY = (getHeight() - drawH) / 2;
            g2.drawImage(image, drawX, drawY, drawW, drawH, null);
            g2.dispose();
        }
    }
}
