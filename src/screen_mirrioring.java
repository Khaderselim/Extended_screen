// Source - https://stackoverflow.com/a/70920960
// Posted by MadProgrammer
// Retrieved 2026-03-13, License - CC BY-SA 4.0

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import java.io.File;
import java.awt.MouseInfo;
import java.awt.RenderingHints;

class screen_mirrioring {

    private static BufferedImage cursorImg = null;

    public static void main(String[] args) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice lstGDs[] = ge.getScreenDevices();
        for (GraphicsDevice gd : lstGDs) {
            System.out.println(gd.getDisplayMode());
        }

        // Load cursor image
        try {
            File cursorFile = new File("src/cursor.png");
            if (!cursorFile.exists()) {
                cursorFile = new File("cursor.png");
            }
            if (cursorFile.exists()) {
                cursorImg = ImageIO.read(cursorFile);
                System.out.println("Cursor image loaded successfully");
            } else {
                System.err.println("Warning: cursor.png not found");
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not load cursor image: " + e.getMessage());
        }

        new screen_mirrioring();
    }

    public screen_mirrioring() {
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                JFrame frame = new JFrame();
                frame.add(new TestPane());
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            }
        });
    }

    public class TestPane extends JPanel {

        private CaptureWorker worker;
        private BufferedImage snapshot;

        public TestPane() {
        }

        @Override
        public void addNotify() {
            super.addNotify();
            startCapture();
        }

        @Override
        public void removeNotify() {
            super.removeNotify();
            stopCapture();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(400, 400);
        }

        protected void startCapture() {
            try {
                stopCapture();
                GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                GraphicsDevice gd = ge.getScreenDevices()[2];
                worker = new CaptureWorker(gd, new CaptureWorker.Observer() {
                    @Override
                    public void imageAvaliable(CaptureWorker source, BufferedImage img) {
                        TestPane.this.snapshot = img;
                        repaint();
                    }
                });
                worker.execute();
            } catch (AWTException ex) {
                Logger.getLogger(screen_mirrioring.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        protected void stopCapture() {
            if (worker == null) {
                return;
            }
            worker.stop();
            worker = null;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (snapshot == null) return;
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int iw = snapshot.getWidth();
            int ih = snapshot.getHeight();
            double scaleW = (double) getWidth() / iw;
            double scaleH = (double) getHeight() / ih;
            double scale = Math.min(scaleW, scaleH);
            int drawW = (int) (iw * scale);
            int drawH = (int) (ih * scale);
            int drawX = (getWidth() - drawW) / 2;
            int drawY = (getHeight() - drawH) / 2;
            g2d.drawImage(snapshot, drawX, drawY, drawW, drawH, null);
            g2d.dispose();
        }

        public double getScaleFactor(int iMasterSize, int iTargetSize) {
            double dScale = 1;
            dScale = (double) iTargetSize / (double) iMasterSize;

            return dScale;
        }

        public double getScaleFactorToFit(Dimension original, Dimension toFit) {
            double dScale = 1d;

            if (original != null && toFit != null) {
                double dScaleWidth = getScaleFactor(original.width, toFit.width);
                double dScaleHeight = getScaleFactor(original.height, toFit.height);

                dScale = Math.min(dScaleHeight, dScaleWidth);
            }
            return dScale;
        }

        public double getScaleFactorToFill(Dimension masterSize, Dimension targetSize) {
            double dScaleWidth = getScaleFactor(masterSize.width, targetSize.width);
            double dScaleHeight = getScaleFactor(masterSize.height, targetSize.height);

            double dScale = Math.max(dScaleHeight, dScaleWidth);

            return dScale;
        }

    }

    public class CaptureWorker extends SwingWorker<Void, BufferedImage> {

        public interface Observer {

            public void imageAvaliable(CaptureWorker source, BufferedImage img);
        }

        private AtomicBoolean keepRunning = new AtomicBoolean(true);
        private Robot bot;
        private Rectangle captureBounds;

        private final Duration interval = Duration.ofMillis(33);

        private Observer observer;

        public CaptureWorker(GraphicsDevice device, Observer observer) throws AWTException {
            captureBounds = device.getDefaultConfiguration().getBounds();
            this.observer = observer;
            bot = new Robot();
        }

        public void stop() {
            keepRunning.set(false);
        }

        @Override
        protected void process(List<BufferedImage> chunks) {
            BufferedImage img = chunks.get(chunks.size() - 1);
            observer.imageAvaliable(this, img);
        }

        @Override
        protected Void doInBackground() throws Exception {
            try {
                while (keepRunning.get()) {
                    Instant anchor = Instant.now();
                    System.out.println("Snapshot");
                    BufferedImage image = bot.createScreenCapture(captureBounds);
                    // Overlay the cursor image if it was loaded successfully
                    if (cursorImg != null) {
                        try {
                            Point mouse = MouseInfo.getPointerInfo().getLocation();
                            int mx = mouse.x - captureBounds.x;
                            int my = mouse.y - captureBounds.y;

                            Graphics2D g = image.createGraphics();
                            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

                            // Scale cursor to 24x24 pixels for proper visibility
                            int cursorSize = 24;
                            g.drawImage(cursorImg, mx, my, cursorSize, cursorSize, null);
                            g.dispose();
                        } catch (Exception ex) {
                            // ignore mouse-draw errors and continue sending frames
                        }
                    }
                    System.out.println("Pubish");
                    publish(image);
                    Duration duration = Duration.between(anchor, Instant.now());
                    System.out.println("Took " + duration.toMillis());
                    long frameTime = Math.max(duration.toMillis(), interval.toMillis());
                    double fps = 1000.0 / frameTime;
                    System.out.println("FPS: " + fps);
                    duration = duration.minus(interval);
                    System.out.println("Time remaining " + duration.toMillis());
                    if (duration.isNegative()) {
                        long sleepTime = Math.abs(duration.toMillis());
                        System.out.println("Sleep for " + sleepTime);
                        Thread.sleep(sleepTime);
                    }
                }
            } catch (Exception exp) {
                exp.printStackTrace();
            }
            return null;
        }

    }
}
