// Server: captures a selected screen and streams frames via UDP to a single client.
// Protocol: UDP packets with frame sequencing and length prefix for frame reassembly
// Defaults: port=1234, screen=1, fps=15, quality=0.7

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.util.Iterator;

/**
 * Server: captures a selected screen and streams frames as UDP packets to a client.
 * Protocol: UDP packets with frame index (4 bytes), packet index (4 bytes), total packets (4 bytes), and JPEG data
 * Max packet size: 65507 bytes (UDP max payload)
 * Defaults: port=1234, screen=1, fps=15, quality=0.7
 */
public class Main {
    private static volatile boolean running = true;
    private static BufferedImage cursorImg = null;
    private static final int UDP_MAX_PAYLOAD = 65507;
    private static final int HEADER_SIZE = 12; // 4 bytes frame index + 4 bytes packet index + 4 bytes total packets
    private static final int UDP_DATA_SIZE = UDP_MAX_PAYLOAD - HEADER_SIZE;

    public static void main(String[] args) {
        int port = 1234;
        int screenIndex = 1;
        int fps = 15;
        // Reduce JPEG quality for faster encoding and lower bandwidth (less lag)
        float quality = 0.95f;


        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            running = false;
        }));

        // Load cursor image from file
        try {
            File cursorFile = new File("src/cursor.png");
            if (!cursorFile.exists()) {
                cursorFile = new File("cursor.png");
            }
            if (cursorFile.exists()) {
                cursorImg = ImageIO.read(cursorFile);
                System.out.println("Cursor image loaded successfully from " + cursorFile.getAbsolutePath());
            } else {
                System.err.println("Warning: cursor.png not found");
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not load cursor image: " + e.getMessage());
            e.printStackTrace();
        }

        try (DatagramSocket socket = new DatagramSocket(port)) {
            System.out.println("Server listening on UDP port " + port);
            socket.setReceiveBufferSize(1024 * 1024); // 1MB buffer

            // Wait for first packet from client to learn their address
            byte[] receiveBuffer = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            System.out.println("Waiting for client to send initial packet...");
            socket.receive(receivePacket);

            InetAddress clientAddress = receivePacket.getAddress();
            int clientPort = receivePacket.getPort();
            System.out.println("Client connected from " + clientAddress + ":" + clientPort);

            streamToClient(socket, clientAddress, clientPort, screenIndex, fps, quality);
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("Server stopped");
    }

    private static void streamToClient(DatagramSocket socket, InetAddress clientAddress, int clientPort,
                                      int screenIndex, int fps, float quality) {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] devices = ge.getScreenDevices();
            screenIndex = Math.min(screenIndex, devices.length - 1);
            GraphicsDevice gd = devices[screenIndex];
            Rectangle captureBounds = gd.getDefaultConfiguration().getBounds();

            Robot bot = new Robot(gd);

            Duration frameInterval = Duration.ofMillis(1000 / Math.max(1, fps));
            int frameIndex = 0;

            while (running) {
                Instant start = Instant.now();

                BufferedImage frame = bot.createScreenCapture(captureBounds);
                // Overlay the cursor image if it was loaded successfully
                if (cursorImg != null) {
                    try {
                        Point mouse = MouseInfo.getPointerInfo().getLocation();
                        int mx = mouse.x - captureBounds.x;
                        int my = mouse.y - captureBounds.y;

                        Graphics2D g = frame.createGraphics();
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

                        // Scale cursor to 24x24 pixels for proper visibility
                        int cursorSize = 24;
                        g.drawImage(cursorImg, mx, my, cursorSize, cursorSize, null);
                        g.dispose();
                    } catch (Exception ex) {
                        // ignore mouse-draw errors and continue sending frames
                    }
                }
                byte[] jpeg = encodeJpeg(frame, quality);

                // Fragment JPEG into UDP packets
                int totalPackets = (jpeg.length + UDP_DATA_SIZE - 1) / UDP_DATA_SIZE;
                for (int packetIndex = 0; packetIndex < totalPackets; packetIndex++) {
                    int startIdx = packetIndex * UDP_DATA_SIZE;
                    int endIdx = Math.min(startIdx + UDP_DATA_SIZE, jpeg.length);
                    int dataLen = endIdx - startIdx;

                    // Build packet: [frame_index(4)][packet_index(4)][total_packets(4)][data]
                    byte[] packet = new byte[HEADER_SIZE + dataLen];
                    writeInt(packet, 0, frameIndex);
                    writeInt(packet, 4, packetIndex);
                    writeInt(packet, 8, totalPackets);
                    System.arraycopy(jpeg, startIdx, packet, HEADER_SIZE, dataLen);

                    DatagramPacket dgPacket = new DatagramPacket(packet, packet.length, clientAddress, clientPort);
                    socket.send(dgPacket);
                }

                frameIndex++;
                long elapsed = Duration.between(start, Instant.now()).toMillis();
                long sleep = frameInterval.toMillis() - elapsed;
                if (sleep > 0) Thread.sleep(sleep);
            }
        } catch (AWTException awt) {
            System.err.println("AWT error: " + awt.getMessage());
            awt.printStackTrace();
        } catch (IOException io) {
            System.err.println("IO error: " + io.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeInt(byte[] array, int offset, int value) {
        array[offset] = (byte) ((value >> 24) & 0xFF);
        array[offset + 1] = (byte) ((value >> 16) & 0xFF);
        array[offset + 2] = (byte) ((value >> 8) & 0xFF);
        array[offset + 3] = (byte) (value & 0xFF);
    }

    private static int readInt(byte[] array, int offset) {
        return ((array[offset] & 0xFF) << 24) |
               ((array[offset + 1] & 0xFF) << 16) |
               ((array[offset + 2] & 0xFF) << 8) |
               (array[offset + 3] & 0xFF);
    }

    // Encode a BufferedImage to JPEG bytes with given quality.
    private static byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG writer found");
        ImageWriter writer = writers.next();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
        try (MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(mcios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                quality = Math.max(0.01f, Math.min(1.0f, quality));
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(img, null, null), param);
            writer.dispose();
            mcios.flush();
            return baos.toByteArray();
        }
    }
}