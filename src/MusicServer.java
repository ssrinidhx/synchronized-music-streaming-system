import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;

public class MusicServer {
    private static final int PORT = 5050;
    private static final String SONG_DIR = "songs";
    private static List<ClientHandler> clientHandlers = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        File songFolder = new File(SONG_DIR);
        if (!songFolder.exists()) songFolder.mkdirs();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Music Server started on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                synchronized (clientHandlers) {
                    clientHandlers.add(handler);
                }
                new Thread(handler).start();
            }
        }
    }

    public static void broadcast(String command, Object... data) {
        synchronized (clientHandlers) {
            Iterator<ClientHandler> it = clientHandlers.iterator();
            while (it.hasNext()) {
                ClientHandler h = it.next();
                try {
                    h.sendCommand(command, data);
                } catch (IOException e) {
                    it.remove();
                }
            }
        }
    }

    public static void updateSongList() throws IOException {
        File folder = new File(SONG_DIR);
        File[] files = folder.listFiles((d, n) -> n.toLowerCase().endsWith(".mp3"));
        List<String> names = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                names.add(f.getName().replace(".mp3", ""));
            }
            Collections.sort(names);
        }
        broadcast("SONG_LIST", names);
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        private ObjectOutputStream out;
        private ObjectInputStream in;

        ClientHandler(Socket s) throws IOException {
            this.socket = s;
            this.out = new ObjectOutputStream(s.getOutputStream());
            this.out.flush();
            this.in  = new ObjectInputStream(s.getInputStream());
            System.out.println("Client connected: " + s.getInetAddress());
        }

        public void run() {
            try {
                updateSongList();
                while (true) {
                    String command = (String) in.readObject();
                    switch (command) {
                        case "UPLOAD":
                            handleUpload();
                            break;
                        case "PLAY":
                            handlePlay();
                            break;
                        case "PAUSE":
                            handlePause();
                            break;
                        case "RESUME":
                            handleResume();
                            break;
                        case "STOP":
                            broadcast("STOP");
                            break;
                        case "NEXT":
                            handleNext();
                            break;
                        case "GET_SONGS":
                            updateSongList();
                            break;
                        default:
                            System.out.println("Unknown command: " + command);
                    }
                }
            } catch (Exception e) {
                System.out.println("Client disconnected: " + socket.getInetAddress());
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
                synchronized (clientHandlers) {
                    clientHandlers.remove(this);
                }
            }
        }

        private void handleUpload() throws IOException, ClassNotFoundException {
            String fileName = (String) in.readObject();
            byte[] fileData = (byte[]) in.readObject();

            File outFile = new File(SONG_DIR, fileName);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(fileData);
            }

            System.out.println("Uploaded: " + fileName);
            broadcast("UPLOAD_SUCCESS", fileName);
            updateSongList();
        }

        private void handlePlay() throws IOException, ClassNotFoundException {
            String songName = (String) in.readObject();
            File f = new File(SONG_DIR, songName);
            if (f.exists()) {
                byte[] bytes = Files.readAllBytes(f.toPath());
                broadcast("PLAY", songName, bytes);
            }
        }

        private void handlePause() throws IOException, ClassNotFoundException {
            String pos = (String) in.readObject();
            broadcast("PAUSE", pos);
        }

        private void handleResume() throws IOException, ClassNotFoundException {
            String pos = (String) in.readObject();
            broadcast("RESUME", pos);
        }

        private void handleNext() throws IOException, ClassNotFoundException {
            String current = (String) in.readObject();
            File[] files = new File(SONG_DIR).listFiles((d,n)->n.toLowerCase().endsWith(".mp3"));
            if (files != null && files.length>0) {
                List<File> list = Arrays.asList(files);
                Collections.sort(list);
                int idx = -1;
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).getName().equals(current)) {
                        idx = i; break;
                    }
                }
                if (idx>=0 && idx<list.size()-1) {
                    File nxt = list.get(idx+1);
                    byte[] bytes = Files.readAllBytes(nxt.toPath());
                    broadcast("PLAY", nxt.getName(), bytes);
                }
            }
        }

        public void sendCommand(String cmd, Object... data) throws IOException {
            out.writeObject(cmd);
            for (Object o : data) out.writeObject(o);
            out.flush();
        }
    }
}