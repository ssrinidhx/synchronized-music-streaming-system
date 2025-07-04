import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.Timer;
import java.util.TimerTask;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.advanced.AdvancedPlayer;
import javazoom.jl.player.advanced.PlaybackEvent;
import javazoom.jl.player.advanced.PlaybackListener;
import com.mpatric.mp3agic.Mp3File;  

public class MusicClient {
    private static final String SERVER_IP = " "; //add the IPV4 address of the system here 
    private static final int SERVER_PORT = 5050;
    
    private static Socket socket;
    private static ObjectOutputStream objectOut;
    private static ObjectInputStream objectIn;
    
    private static AdvancedPlayer mp3Player;
    private static File currentSongFile;
    private static volatile boolean isPlaying = false;
    
    private static long songLengthMs = 0;
    private static long playbackStartTime = 0; 
    private static long pauseOffset = 0;      
    private static long currentPositionMs = 0;
    
    private static String currentSong = null;
    private static JList<String> songList;
    private static DefaultListModel<String> model;
    private static JLabel timeLabel;
    private static JSlider progressSlider;
    private static boolean isSliderAdjusting = false;
    private static Timer progressTimer;
    private static JButton pauseResumeButton;
    
    private static final PlaybackListener playbackListener = new PlaybackListener() {
        @Override
        public void playbackFinished(PlaybackEvent event) {
            isPlaying = false;
            SwingUtilities.invokeLater(() -> {
                if (currentPositionMs >= songLengthMs - 500) {
                    playNextSong();
                }
            });
        }
    };
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            createAndShowGUI();
            connectToServer();
            startListeningThread();
        });
    }
    
    private static void createAndShowGUI() {
        JFrame frame = new JFrame("🎵 MP3 Music Player 🎵");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        JPanel panel = new JPanel(new BorderLayout(15,15));
        panel.setBorder(BorderFactory.createEmptyBorder(15,15,15,15));
        panel.setBackground(new Color(25,25,30));
        
        model = new DefaultListModel<>();
        songList = new JList<>(model);
        songList.setFont(new Font("Calibri", Font.PLAIN, 16));
        songList.setBackground(new Color(40,40,50));
        songList.setForeground(Color.WHITE);
        songList.setSelectionBackground(new Color(70,130,180));
        songList.setSelectionForeground(Color.WHITE);
        songList.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
        JScrollPane scrollPane = new JScrollPane(songList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(0,0,0,0));
        
        JButton playButton = createStyledButton("Play");
        pauseResumeButton = createStyledButton("Pause");
        JButton stopButton = createStyledButton("Stop");
        JButton nextButton = createStyledButton("Next");
        JButton uploadButton = createStyledButton("Add Song");
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER,15,10));
        buttonPanel.setBackground(new Color(25,25,30));
        buttonPanel.add(playButton);
        buttonPanel.add(pauseResumeButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(nextButton);
        buttonPanel.add(uploadButton);
        
        timeLabel = new JLabel("00:00 / 00:00", SwingConstants.CENTER);
        timeLabel.setFont(new Font("Calibri", Font.BOLD, 16));
        timeLabel.setForeground(Color.WHITE);
        
        progressSlider = new JSlider();
        progressSlider.setEnabled(false);
        progressSlider.setBackground(new Color(40,40,50));
        progressSlider.setUI(new CustomSliderUI(progressSlider));
        
        JPanel bottomPanel = new JPanel(new BorderLayout(8,8));
        bottomPanel.setBackground(new Color(25,25,30));
        bottomPanel.add(timeLabel, BorderLayout.NORTH);
        bottomPanel.add(progressSlider, BorderLayout.CENTER);
        
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.NORTH);
        panel.add(bottomPanel, BorderLayout.SOUTH);
        
        frame.setContentPane(panel);
        frame.setSize(600,520);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        
        playButton.addActionListener(e -> playSelectedSong());
        pauseResumeButton.addActionListener(e -> togglePauseResume());
        stopButton.addActionListener(e -> stopCurrentSong());
        nextButton.addActionListener(e -> playNextSong());
        uploadButton.addActionListener(e -> uploadSong());
        
        progressSlider.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                isSliderAdjusting = true;
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (isSliderAdjusting) {
                    int desired = progressSlider.getValue();
                    seekToPosition(desired);
                    isSliderAdjusting = false;
                }
            }
        });
        
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                cleanupResources();
            }
        });
    }
    
    private static void connectToServer() {
        try {
            socket = new Socket(SERVER_IP, SERVER_PORT);
            objectOut = new ObjectOutputStream(socket.getOutputStream());
            objectOut.flush();
            objectIn = new ObjectInputStream(socket.getInputStream());
            System.out.println("Connected to server at " + SERVER_IP );
        } catch(IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Failed to connect to server: " + e.getMessage());
        }
    }
    
     private static void startListeningThread() {
        new Thread(() -> {
            try {
                while(true) {
                    String command = (String) objectIn.readObject();
                    if(command.equals("SONG_LIST")) {
                        @SuppressWarnings("unchecked")
                        java.util.List<String> songs = (java.util.List<String>) objectIn.readObject();
                        SwingUtilities.invokeLater(() -> {
                            model.clear();
                            for(String s : songs) model.addElement(s);
                        });
                    } else if (command.equals("PLAY")) {
                        String name = (String) objectIn.readObject();
                        byte[] data = (byte[]) objectIn.readObject();
                        SwingUtilities.invokeLater(() -> {
                            String bare = name.replace(".mp3", "");
                            int idx = model.indexOf(bare);
                            if (idx != -1) {
                                songList.setSelectedIndex(idx);
                                currentSong = bare;
                            }
                        });
                        playReceivedSong(name, data);
                    } else if(command.equals("PAUSE")) {
                        objectIn.readObject(); 
                        SwingUtilities.invokeLater(MusicClient::pausePlayback);
                    } else if(command.equals("RESUME")) {
                        objectIn.readObject();
                        SwingUtilities.invokeLater(MusicClient::resumePlayback);
                    } else if(command.equals("STOP")) {
                        SwingUtilities.invokeLater(MusicClient::stopCurrentSong);
                    } else if (command.equals("NEXT")) {
                        String nextSong = (String) objectIn.readObject();
                        SwingUtilities.invokeLater(() -> {
                            int index = model.indexOf(nextSong.replace(".mp3", ""));
                            if (index != -1) {
                                songList.setSelectedIndex(index);
                                currentSong = model.get(index);
                            }
                        });
                    } else if(command.equals("UPLOAD_SUCCESS")) {
                        String fn = (String) objectIn.readObject();
                        SwingUtilities.invokeLater(() -> {
                            model.addElement(fn.replace(".mp3",""));
                            JOptionPane.showMessageDialog(null, "Upload successful!");
                        });
                    }
                }
            } catch(Exception e) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(null, "Disconnected: "+ e.getMessage())
                );
            }
        }).start();
    }

    
    private static void playSelectedSong() {
        String selected = songList.getSelectedValue();
        if(selected != null) {
            currentSong = selected;
            sendPlayCommand(currentSong + ".mp3");
        } else {
            JOptionPane.showMessageDialog(null, "Please select a song first!");
        }
    }
    
    private static void sendPlayCommand(String songFileName) {
        try {
            objectOut.writeObject("PLAY");
            objectOut.writeObject(songFileName);
            objectOut.flush();
        } catch(IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Error sending play command: " + e.getMessage());
        }
    }
    
    private static void playReceivedSong(String songFileName, byte[] songData) {
        new Thread(() -> {
            try {
                stopCurrentSong();
                currentSongFile = File.createTempFile("music_", ".mp3");
                currentSongFile.deleteOnExit();
                try (FileOutputStream fos = new FileOutputStream(currentSongFile)) {
                    fos.write(songData);
                }
                songLengthMs = estimateSongLength(currentSongFile);
                SwingUtilities.invokeLater(() -> {
                    progressSlider.setEnabled(true);
                    progressSlider.setMaximum((int) songLengthMs);
                    progressSlider.setValue(0);
                    timeLabel.setText(formatTime(0) + " / " + formatTime((int) songLengthMs));
                });
                FileInputStream fis = new FileInputStream(currentSongFile);
                mp3Player = new AdvancedPlayer(fis);
                mp3Player.setPlayBackListener(playbackListener);
                playbackStartTime = System.currentTimeMillis();
                currentPositionMs = 0;
                isPlaying = true;
                SwingUtilities.invokeLater(() -> pauseResumeButton.setText("Pause"));
                startProgressTimer();
                new Thread(() -> {
                    try {
                        mp3Player.play();
                    } catch (JavaLayerException ex) {
                        ex.printStackTrace();
                    }
                }).start();
            } catch(Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(null, "Error playing song: " + ex.getMessage());
                });
            }
        }).start();
    }
    
    private static void togglePauseResume() {
        if(isPlaying) {
            pausePlayback();
            sendPauseCommand();
        } else {
            resumePlayback();
            sendResumeCommand();
        }
    }
    
    private static void pausePlayback() {
        if(mp3Player != null && isPlaying) {
            pauseOffset = System.currentTimeMillis() - playbackStartTime;
            try {
                try {
                    mp3Player.stop();
                } catch(Exception ex) {
                    System.err.println("Error stopping player in pause: " + ex.getMessage());
                }
            } catch(Exception ex) {
                ex.printStackTrace();
            }
            isPlaying = false;
            stopProgressTimer();
            SwingUtilities.invokeLater(() -> pauseResumeButton.setText("Resume"));
        }
    }
    
    private static void resumePlayback() {
        if(currentSongFile != null && !isPlaying) {
            new Thread(() -> {
                try {
                    FileInputStream fis = new FileInputStream(currentSongFile);
                    long bytesToSkip = (pauseOffset * currentSongFile.length()) / songLengthMs;
                    fis.skip(bytesToSkip);
                    mp3Player = new AdvancedPlayer(fis);
                    mp3Player.setPlayBackListener(playbackListener);
                    playbackStartTime = System.currentTimeMillis() - pauseOffset;
                    isPlaying = true;
                    SwingUtilities.invokeLater(() -> {
                        pauseResumeButton.setText("Pause");
                        progressSlider.setValue((int) pauseOffset);
                    });
                    startProgressTimer();
                    mp3Player.play();
                } catch(Exception ex) {
                    ex.printStackTrace();
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(null, "Error resuming playback: " + ex.getMessage());
                    });
                }
            }).start();
        }
    }
    
    private static void seekToPosition(int positionMs) {
        if(currentSongFile == null || positionMs < 0 || positionMs > songLengthMs) return;
        new Thread(() -> {
            try {
                if(mp3Player != null) {
                    try {
                        mp3Player.stop();
                    } catch(Exception ex) {
                        System.err.println("Error stopping player in seek: " + ex.getMessage());
                    }
                }
                FileInputStream fis = new FileInputStream(currentSongFile);
                long bytesToSkip = (positionMs * currentSongFile.length()) / songLengthMs;
                fis.skip(bytesToSkip);
                mp3Player = new AdvancedPlayer(fis);
                mp3Player.setPlayBackListener(playbackListener);
                playbackStartTime = System.currentTimeMillis() - positionMs;
                currentPositionMs = positionMs;
                isPlaying = true;
                startProgressTimer();
                new Thread(() -> {
                    try {
                        mp3Player.play();
                    } catch(JavaLayerException ex) {
                        ex.printStackTrace();
                    }
                }).start();
                SwingUtilities.invokeLater(() -> {
                    pauseResumeButton.setText("Pause");
                    progressSlider.setValue(positionMs);
                });
            } catch(Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(null, "Error seeking: " + ex.getMessage());
                });
            }
        }).start();
    }
    
    private static void stopCurrentSong() {
        if(mp3Player != null) {
            try {
                AdvancedPlayer temp = mp3Player;
                mp3Player = null;
                isPlaying = false;
                new Thread(() -> {
                    try {
                        if(temp != null) {
                            try {
                                temp.stop();
                            } catch(Exception ex) {
                                System.err.println("Error stopping player in stopCurrentSong: " + ex.getMessage());
                            }
                        }
                    } catch(Exception e) { e.printStackTrace(); }
                }).start();
            } catch(Exception e) { e.printStackTrace(); }
        }
        stopProgressTimer();
        SwingUtilities.invokeLater(() -> {
            pauseResumeButton.setText("Pause");
            progressSlider.setValue(0);
            timeLabel.setText("00:00 / 00:00");
        });
    }
    
    private static void startProgressTimer() {
        stopProgressTimer();
        progressTimer = new Timer();
        progressTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if(isPlaying && !isSliderAdjusting) {
                    currentPositionMs = System.currentTimeMillis() - playbackStartTime;
                    if(currentPositionMs > songLengthMs) {
                        currentPositionMs = songLengthMs;
                        stopCurrentSong();
                        playNextSong();
                    }
                    SwingUtilities.invokeLater(() -> {
                        progressSlider.setValue((int) currentPositionMs);
                        timeLabel.setText(formatTime((int) currentPositionMs) + " / " + formatTime((int) songLengthMs));
                    });
                }
            }
        }, 0, 500);
    }
    
    private static void stopProgressTimer() {
        if(progressTimer != null) {
            progressTimer.cancel();
            progressTimer = null;
        }
    }
    
    private static long estimateSongLength(File mp3File) {
        try {
            Mp3File mp3 = new Mp3File(mp3File);
            return mp3.getLengthInMilliseconds();
        } catch(Exception e) {
            e.printStackTrace();
            // Fallback estimation:
            long length = mp3File.length();
            return (long)(length * 8.0 / (128 * 1024) * 1000);
        }
    }
    
    private static void sendPauseCommand() {
        try {
            objectOut.writeObject("PAUSE");
            objectOut.writeObject(String.valueOf(currentPositionMs));
            objectOut.flush();
        } catch(IOException e) {
            e.printStackTrace();
        }
    }
    
    private static void sendResumeCommand() {
        try {
            objectOut.writeObject("RESUME");
            objectOut.writeObject(String.valueOf(pauseOffset));
            objectOut.flush();
        } catch(IOException e) {
            e.printStackTrace();
        }
    }
    
    private static void sendStopCommand() {
        try {
            objectOut.writeObject("STOP");
            objectOut.flush();
        } catch(IOException e) {
            e.printStackTrace();
        }
    }
    
    private static void playNextSong() {
        int index = songList.getSelectedIndex();
        if (index >= 0 && index < model.size() - 1) {
            currentSong = model.get(index); // don't pre-increment
            sendNextCommand(currentSong + ".mp3");
        }
    }

    private static void sendNextCommand(String songFileName) {
        try {
            objectOut.writeObject("NEXT");
            objectOut.writeObject(songFileName);
            objectOut.flush();
        } catch(IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Error sending next command: " + e.getMessage());
        }
    }
    
    private static void uploadSong() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("MP3 files","mp3"));
        if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            if(f.getName().toLowerCase().endsWith(".mp3")) {
                new Thread(() -> handleFileUpload(f)).start();
            } else {
                JOptionPane.showMessageDialog(null, "Only .mp3 files supported.");
            }
        }
    }

    private static void handleFileUpload(File songFile) {
        try {
            byte[] data = Files.readAllBytes(songFile.toPath());
            objectOut.writeObject("UPLOAD");
            objectOut.writeObject(songFile.getName());
            objectOut.writeObject(data);
            objectOut.flush();
        } catch(Exception e) {
            SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(null, "Upload error: " + e.getMessage())
            );
        }
    }
    
    private static void cleanupResources() {
        stopCurrentSong();
        try {
            if(socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch(IOException e) {
            e.printStackTrace();
        }
    }
    
    private static String formatTime(int ms) {
        int totalSec = ms / 1000;
        int minutes = totalSec / 60;
        int seconds = totalSec % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }
    
    private static JButton createStyledButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("Calibri", Font.BOLD, 14));
        button.setBackground(new Color(50,50,60));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(80,80,100), 1),
            BorderFactory.createEmptyBorder(5,15,5,15)
        ));
        button.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                button.setBackground(new Color(70,130,180));
            }
            public void mouseExited(MouseEvent e) {
                button.setBackground(new Color(50,50,60));
            }
        });
        return button;
    }
    
    static class CustomSliderUI extends javax.swing.plaf.basic.BasicSliderUI {
        public CustomSliderUI(JSlider slider) {
            super(slider);
        }
        
        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(new Color(0,200,255));
            g2d.fillOval(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height);
            g2d.dispose();
        }
        
        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(new Color(100,100,150));
            g2d.fillRoundRect(trackRect.x, trackRect.y + trackRect.height/3, trackRect.width, 5, 10, 10);
            g2d.dispose();
        }
    }
}