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


public class Main {
    private static volatile boolean running = true;
    private static BufferedImage cursorImg = null;
    private static final int UDP_MAX_PAYLOAD = 65507;
    private static final int HEADER_SIZE = 12; // 4 bytes frame index + 4 bytes packet index + 4 bytes total packets
    private static final int UDP_DATA_SIZE = UDP_MAX_PAYLOAD - HEADER_SIZE;
    private static final int TILE_SIZE = 32; // 32x32 pixel tiles for delta encoding

    public static void main(String[] args) {
        int port = 1234;
        int screenIndex = 1;
        int fps = 15;
        float quality = 0.8f;


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
            GraphicsDevice gd = devices[2];
            Rectangle captureBounds = gd.getDefaultConfiguration().getBounds();

            Robot bot = new Robot(gd);

            Duration frameInterval = Duration.ofMillis(1000 / Math.max(1, fps));
            int frameIndex = 0;
            BufferedImage previousFrame = null;
            int tilesHorizontal = 0;
            int tilesVertical = 0;
            final int BOOTSTRAP_FRAMES = 10; // Number of full frames to send before enabling delta encoding
            boolean deltaEncodingEnabled = false;

            System.out.println("=== HANDSHAKE: Client will receive " + BOOTSTRAP_FRAMES + " full frames before delta encoding ===");

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

                // Initialize tile dimensions on first frame
                if (previousFrame == null) {
                    tilesHorizontal = (frame.getWidth() + TILE_SIZE - 1) / TILE_SIZE;
                    tilesVertical = (frame.getHeight() + TILE_SIZE - 1) / TILE_SIZE;
                    System.out.println("Screen resolution: " + frame.getWidth() + "x" + frame.getHeight());
                    System.out.println("Tile grid: " + tilesHorizontal + "x" + tilesVertical + " (" + (tilesHorizontal * tilesVertical) + " tiles)");
                }

                // Find changed tiles (for delta encoding phase)
                boolean[] changedTiles = new boolean[tilesHorizontal * tilesVertical];
                int changedCount = 0;
                if (previousFrame != null && previousFrame.getWidth() == frame.getWidth() && 
                    previousFrame.getHeight() == frame.getHeight()) {
                    changedCount = findChangedTiles(frame, previousFrame, changedTiles, tilesHorizontal, tilesVertical);
                } else {
                    // First frame or resolution changed: mark all tiles as changed
                    for (int i = 0; i < changedTiles.length; i++) changedTiles[i] = true;
                    changedCount = changedTiles.length;
                }

                // HANDSHAKE PHASE: Send first BOOTSTRAP_FRAMES as full frames
                if (frameIndex < BOOTSTRAP_FRAMES) {
                    byte[] jpeg = encodeJpeg(frame, quality);
                    sendFullFrame(socket, clientAddress, clientPort, jpeg, frameIndex);
                    System.out.println("Frame " + frameIndex + ": Full frame [BOOTSTRAP " + (frameIndex + 1) + "/" + BOOTSTRAP_FRAMES + "] (" + jpeg.length + " bytes)");
                    
                    if (frameIndex == BOOTSTRAP_FRAMES - 1) {
                        System.out.println("=== HANDSHAKE COMPLETE: Switching to delta encoding ===");
                        deltaEncodingEnabled = true;
                    }
                } 
                // DELTA ENCODING PHASE: Start after bootstrap frames
                else if (deltaEncodingEnabled) {
                    if (changedCount < tilesHorizontal * tilesVertical * 0.3) {
                        // Use delta encoding if <30% of tiles changed
                        sendDeltaFrame(socket, clientAddress, clientPort, frame, changedTiles, 
                                       tilesHorizontal, tilesVertical, frameIndex, quality);
                        System.out.println("Frame " + frameIndex + ": Delta (" + changedCount + "/" + 
                                         (tilesHorizontal * tilesVertical) + " tiles changed)");
                    } else {
                        // Too many changes: send as full frame
                        byte[] jpeg = encodeJpeg(frame, quality);
                        sendFullFrame(socket, clientAddress, clientPort, jpeg, frameIndex);
                        System.out.println("Frame " + frameIndex + ": Full frame (" + jpeg.length + " bytes)");
                    }
                }

                previousFrame = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = previousFrame.createGraphics();
                g2d.drawImage(frame, 0, 0, null);
                g2d.dispose();

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

    // Detect which tiles have changed between frames
    private static int findChangedTiles(BufferedImage current, BufferedImage previous, 
                                        boolean[] changedTiles, int tilesHorizontal, int tilesVertical) {
        int changedCount = 0;
        int width = current.getWidth();
        int height = current.getHeight();

        for (int ty = 0; ty < tilesVertical; ty++) {
            for (int tx = 0; tx < tilesHorizontal; tx++) {
                int tileIndex = ty * tilesHorizontal + tx;
                int x1 = tx * TILE_SIZE;
                int y1 = ty * TILE_SIZE;
                int x2 = Math.min(x1 + TILE_SIZE, width);
                int y2 = Math.min(y1 + TILE_SIZE, height);

                boolean changed = false;
                for (int y = y1; y < y2 && !changed; y++) {
                    for (int x = x1; x < x2 && !changed; x++) {
                        if (current.getRGB(x, y) != previous.getRGB(x, y)) {
                            changed = true;
                        }
                    }
                }

                changedTiles[tileIndex] = changed;
                if (changed) changedCount++;
            }
        }
        return changedCount;
    }

    // Send only the changed tiles as a delta frame
    private static void sendDeltaFrame(DatagramSocket socket, InetAddress clientAddress, int clientPort,
                                       BufferedImage frame, boolean[] changedTiles, int tilesHorizontal, 
                                       int tilesVertical, int frameIndex, float quality) throws IOException {
        ByteArrayOutputStream deltaData = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(deltaData);

        int width = frame.getWidth();
        int height = frame.getHeight();

        // Write header: frame type (1=delta), tile grid dimensions
        dos.writeByte(1); // Frame type: 1 = delta
        dos.writeShort(tilesHorizontal);
        dos.writeShort(tilesVertical);

        // Write which tiles changed and encode them
        int changedCount = 0;
        for (int i = 0; i < changedTiles.length; i++) {
            if (changedTiles[i]) changedCount++;
        }
        dos.writeShort(changedCount);

        // Encode changed tiles
        for (int ty = 0; ty < tilesVertical; ty++) {
            for (int tx = 0; tx < tilesHorizontal; tx++) {
                int tileIndex = ty * tilesHorizontal + tx;
                if (!changedTiles[tileIndex]) continue;

                // Extract and encode tile
                int x1 = tx * TILE_SIZE;
                int y1 = ty * TILE_SIZE;
                int x2 = Math.min(x1 + TILE_SIZE, width);
                int y2 = Math.min(y1 + TILE_SIZE, height);
                int tileW = x2 - x1;
                int tileH = y2 - y1;

                BufferedImage tileImg = frame.getSubimage(x1, y1, tileW, tileH);
                byte[] tileJpeg = encodeJpeg(tileImg, quality);

                // Write tile info: position and JPEG data
                dos.writeShort(tx);
                dos.writeShort(ty);
                dos.writeShort(tileJpeg.length);
                dos.write(tileJpeg);
            }
        }

        byte[] frameData = deltaData.toByteArray();
        
        // Fragment into UDP packets
        int totalPackets = (frameData.length + UDP_DATA_SIZE - 1) / UDP_DATA_SIZE;
        for (int packetIndex = 0; packetIndex < totalPackets; packetIndex++) {
            int startIdx = packetIndex * UDP_DATA_SIZE;
            int endIdx = Math.min(startIdx + UDP_DATA_SIZE, frameData.length);
            int dataLen = endIdx - startIdx;

            byte[] packet = new byte[HEADER_SIZE + dataLen];
            writeInt(packet, 0, frameIndex);
            writeInt(packet, 4, packetIndex);
            writeInt(packet, 8, totalPackets | 0x80000000); // Set high bit to indicate delta frame
            System.arraycopy(frameData, startIdx, packet, HEADER_SIZE, dataLen);

            DatagramPacket dgPacket = new DatagramPacket(packet, packet.length, clientAddress, clientPort);
            socket.send(dgPacket);
        }
    }

    // Send a full frame
    private static void sendFullFrame(DatagramSocket socket, InetAddress clientAddress, int clientPort,
                                      byte[] jpeg, int frameIndex) throws IOException {
        int totalPackets = (jpeg.length + UDP_DATA_SIZE - 1) / UDP_DATA_SIZE;
        for (int packetIndex = 0; packetIndex < totalPackets; packetIndex++) {
            int startIdx = packetIndex * UDP_DATA_SIZE;
            int endIdx = Math.min(startIdx + UDP_DATA_SIZE, jpeg.length);
            int dataLen = endIdx - startIdx;

            byte[] packet = new byte[HEADER_SIZE + dataLen];
            writeInt(packet, 0, frameIndex);
            writeInt(packet, 4, packetIndex);
            writeInt(packet, 8, totalPackets); // High bit NOT set = full frame
            System.arraycopy(jpeg, startIdx, packet, HEADER_SIZE, dataLen);

            DatagramPacket dgPacket = new DatagramPacket(packet, packet.length, clientAddress, clientPort);
            socket.send(dgPacket);
        }
    }

//    private static int readInt(byte[] array, int offset) {
//        return ((array[offset] & 0xFF) << 24) |
//               ((array[offset + 1] & 0xFF) << 16) |
//               ((array[offset + 2] & 0xFF) << 8) |
//               (array[offset + 3] & 0xFF);
//    }

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