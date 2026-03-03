import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Simple client that connects to the server and displays received JPEG frames via UDP.
 * Expects protocol: [4-byte frame_index][4-byte packet_index][4-byte total_packets][JPEG data]...
 */
public class Client {
    private static volatile boolean running = true;
    private static final int HEADER_SIZE = 12;
    private static final int RECV_BUFFER_SIZE = 65507;

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

        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress serverAddress = InetAddress.getByName(host);

            // Send initial packet to server to register ourselves
            byte[] initPacket = "CONNECT".getBytes();
            DatagramPacket initDp = new DatagramPacket(initPacket, initPacket.length, serverAddress, port);
            socket.send(initDp);
            System.out.println("Connected to server " + host + ":" + port);

            // Map to store frame packets as we receive them
            Map<Integer, Map<Integer, byte[]>> framePackets = new HashMap<>();
            int lastDisplayedFrame = -1;

            while (running) {
                byte[] buffer = new byte[RECV_BUFFER_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                // Parse packet header
                if (packet.getLength() < HEADER_SIZE) {
                    System.err.println("Received packet too small: " + packet.getLength());
                    continue;
                }

                int frameIndex = readInt(buffer, 0);
                int packetIndex = readInt(buffer, 4);
                int totalPackets = readInt(buffer, 8);
                int dataLen = packet.getLength() - HEADER_SIZE;

                // Store packet
                framePackets.computeIfAbsent(frameIndex, k -> new TreeMap<>())
                           .put(packetIndex, java.util.Arrays.copyOfRange(buffer, HEADER_SIZE, HEADER_SIZE + dataLen));

                // Check if frame is complete
                Map<Integer, byte[]> packets = framePackets.get(frameIndex);
                if (packets != null && packets.size() == totalPackets) {
                    // Reassemble frame
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    for (int i = 0; i < totalPackets; i++) {
                        baos.write(packets.get(i));
                    }
                    byte[] jpegData = baos.toByteArray();

                    try {
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpegData));
                        if (img != null) {
                            panel.setImage(img);
                            lastDisplayedFrame = frameIndex;
                        } else {
                            System.err.println("Failed to decode image frame " + frameIndex);
                        }
                    } catch (IOException e) {
                        System.err.println("Error decoding frame " + frameIndex + ": " + e.getMessage());
                    }

                    // Clean up old frames
                    framePackets.remove(frameIndex);
                }
            }
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        }

        System.out.println("Client stopped");
    }

    private static int readInt(byte[] array, int offset) {
        return ((array[offset] & 0xFF) << 24) |
               ((array[offset + 1] & 0xFF) << 16) |
               ((array[offset + 2] & 0xFF) << 8) |
               (array[offset + 3] & 0xFF);
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
