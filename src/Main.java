// Server: captures a selected screen and streams frames as length-prefixed JPEGs to a single client.
// Protocol: [4-byte big-endian length][JPEG bytes]...
// Defaults: port=5000, screen=0, fps=10, quality=0.8

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.util.Iterator;

/**
 * Server: captures a selected screen and streams frames as length-prefixed JPEGs to a single client.
 * Protocol: [4-byte big-endian length][JPEG bytes]...
 * Defaults: port=5000, screen=0, fps=10, quality=0.8
 */
public class Main {
    private static volatile boolean running = true;
    private static BufferedImage cursorImg = null;

    public static void main(String[] args) {
        int port = 1234;
        int screenIndex = 1;
        int fps = 15;
        // Reduce JPEG quality for faster encoding and lower bandwidth (less lag)
        float quality = 0.7f;


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

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);

            while (running) {
                System.out.println("Waiting for client...");
                try (Socket client = serverSocket.accept()) {
                    System.out.println("Client connected from " + client.getRemoteSocketAddress());
                    client.setTcpNoDelay(true);
                    handleClient(client, screenIndex, fps, quality);
                } catch (Exception e) {
                    System.err.println("Client handler error: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("Server stopped");
    }

    private static void handleClient(Socket client, int screenIndex, int fps, float quality) {
        DataOutputStream out = null;
        try {
            // Use a smaller buffer size to reduce buffering delays
            out = new DataOutputStream(new BufferedOutputStream(client.getOutputStream(), 4096));

            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] devices = ge.getScreenDevices();
            screenIndex = 1;
            GraphicsDevice gd = devices[screenIndex];
            Rectangle captureBounds = gd.getDefaultConfiguration().getBounds();

            Robot bot = new Robot(gd);

            Duration frameInterval = Duration.ofMillis(1000 / Math.max(1, fps));

            while (!client.isClosed() && running) {
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

                // write length prefix and bytes
                out.writeInt(jpeg.length);
                out.write(jpeg);
                out.flush();

                long elapsed = Duration.between(start, Instant.now()).toMillis();
                long sleep = frameInterval.toMillis() - elapsed;
                if (sleep > 0) Thread.sleep(sleep);
            }
        } catch (AWTException awt) {
            System.err.println("AWT error: " + awt.getMessage());
            awt.printStackTrace();
        } catch (IOException io) {
            System.err.println("Client IO error (client likely disconnected): " + io.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
            try { client.close(); } catch (IOException ignored) {}
            System.out.println("Client connection closed");
        }
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