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


public class Client {
    private static volatile boolean running = true;
    private static final int HEADER_SIZE = 12;
    private static final int RECV_BUFFER_SIZE = 65507;
    private static final int TILE_SIZE = 32;

    public static void main(String[] args) {
        String host = "localhost";
        int port = 1234;

        JFrame frame = new JFrame("Screen Client");
        ImagePanel panel = new ImagePanel();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(panel);

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
            BufferedImage lastDisplayedImage = null; // For delta frame reconstruction
            boolean receivedFirstFullFrame = false; // Track if we've gotten an initial full frame
            boolean deltaEncodingActive = false; // Track when server switches to delta encoding
            final int BOOTSTRAP_FRAMES = 10;

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
                int totalPacketsValue = readInt(buffer, 8);
                boolean isDeltaFrame = (totalPacketsValue & 0x80000000) != 0;
                int totalPackets = totalPacketsValue & 0x7FFFFFFF;
                int dataLen = packet.getLength() - HEADER_SIZE;

                // Skip delta frames if we haven't received the first full frame yet
                if (isDeltaFrame && !receivedFirstFullFrame) {
                    System.out.println("Skipping delta frame " + frameIndex + " (waiting for first full frame)");
                    continue;
                }

                // Also skip delta frames if they're for an earlier frame than what we've displayed
                // (this handles out-of-order packet delivery)
                if (isDeltaFrame && frameIndex <= lastDisplayedFrame) {
                    System.out.println("Skipping delta frame " + frameIndex + " (already past this frame)");
                    continue;
                }

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
                    byte[] frameData = baos.toByteArray();

                    try {
                        BufferedImage img;
                        if (isDeltaFrame) {
                            // Decode delta frame
                            img = decodeDeltaFrame(frameData, lastDisplayedImage);
                            if (img != null) {
                                System.out.println("Frame " + frameIndex + ": Delta frame decoded");
                            }
                        } else {
                            // Decode full frame
                            img = ImageIO.read(new ByteArrayInputStream(frameData));
                            if (img != null) {
                                System.out.println("Frame " + frameIndex + ": Full frame decoded [BOOTSTRAP " + (frameIndex + 1) + "/" + BOOTSTRAP_FRAMES + "]");
                                receivedFirstFullFrame = true; // Mark that we've received first full frame
                                
                                // Check if we've completed bootstrap phase
                                if (frameIndex == BOOTSTRAP_FRAMES - 1) {
                                    System.out.println("=== HANDSHAKE COMPLETE: Ready for delta encoding ===");
                                    deltaEncodingActive = true;
                                }
                            }
                        }

                        if (img != null) {
                            panel.setImage(img);
                            lastDisplayedFrame = frameIndex;
                            lastDisplayedImage = img;
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

    private static short readShort(byte[] array, int offset) {
        return (short) (((array[offset] & 0xFF) << 8) | (array[offset + 1] & 0xFF));
    }

    // Decode a delta frame by applying tile updates to the previous frame
    private static BufferedImage decodeDeltaFrame(byte[] frameData, BufferedImage previousFrame) throws IOException {
        if (previousFrame == null) {
            System.err.println("Error: Delta frame received but no previous frame available");
            return null;
        }

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(frameData));
        
        // Read header
        byte frameType = dis.readByte();
        if (frameType != 1) {
            System.err.println("Error: Invalid frame type for delta frame");
            return null;
        }

        int tilesHorizontal = dis.readShort();
        int tilesVertical = dis.readShort();
        int changedCount = dis.readShort();

        // Create output image as copy of previous frame
        BufferedImage result = new BufferedImage(
            previousFrame.getWidth(), 
            previousFrame.getHeight(), 
            BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = result.createGraphics();
        g.drawImage(previousFrame, 0, 0, null);
        g.dispose();

        // Apply tile updates
        for (int i = 0; i < changedCount; i++) {
            int tx = dis.readShort();
            int ty = dis.readShort();
            int tileJpegLen = dis.readShort();

            byte[] tileJpeg = new byte[tileJpegLen];
            dis.readFully(tileJpeg);

            // Decode tile JPEG
            BufferedImage tileImg = ImageIO.read(new ByteArrayInputStream(tileJpeg));
            if (tileImg != null) {
                // Draw tile onto result
                int x = tx * TILE_SIZE;
                int y = ty * TILE_SIZE;
                g = result.createGraphics();
                g.drawImage(tileImg, x, y, null);
                g.dispose();
            }
        }

        return result;
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
