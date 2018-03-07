import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;

public class Server extends UnicastRemoteObject implements ServerInterface {
    private static final String SERVER_RMI_NAME = "FileServer";
    private static final String DEFAULT_RMI_HOSTNAME = "localhost";
    private static final int DEFAULT_RMI_PORT = 1099;
    private static final String BASE_DIR = "server_files_";

    private String FILES_DIR;

    // Main entry functions
    public static void main(String[] args) {
        // Ensure argument length
        if (args.length == 0) {
            System.out.println("No arguments received");
            System.out.println("Must receive arguments in the form: ServerID [Hostname] [Port]");
        }

        // Read arguments
        int serverID = Shared.stringToPosInt(args[0], "Server ID must be a positive integer");
        if (serverID == -1) {
            return;
        }

        String hostname;
        if (args.length >= 2) {
            hostname = args[1];
        } else {
            hostname = DEFAULT_RMI_HOSTNAME;
        }

        int port;
        if (args.length >= 3) {
            port = Shared.stringToPosInt(args[2], "Port number must be a positive integer");

            if (port == -1) {
                System.out.println("Using default port of " + DEFAULT_RMI_PORT);
                port = DEFAULT_RMI_PORT;
            }
        } else {
            port = DEFAULT_RMI_PORT;
        }

        // Initialise server
        System.out.println("Initialising server with ID = " + serverID + " at " + hostname + ":" + port);

        try {
            Server obj = new Server(serverID);

            // System.out.println("Detected Local IP: " + InetAddress.getLocalHost().toString());
            // Bind the remote object's stub in the registry
            Registry register = LocateRegistry.getRegistry(hostname, port);
            register.rebind(SERVER_RMI_NAME + serverID, obj);

            System.out.println("Server ready");
        } catch (Exception e) {
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
        }
    }

    // Object functions
    private Server(int id) throws RemoteException {
        this.FILES_DIR = BASE_DIR + id + "/";
    }

    @Override
    public byte[] download(String filename) {
        // Check if file exists
        File file = new File(FILES_DIR + filename);
        if (!file.exists()) {
            log("The file \"" + filename + "\" does not exist on the server");
            return null;
        }

        // Read file from disk and return
        log("Reading file from disk");
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            log("Could not read '" + file.toString() + "' from disk. " + e.getMessage());
            return null;
        }
    }

    @Override
    public List<String> list() {
        log("Sending listings to client");
        List<String> listings = new ArrayList<>();

        // Get listings by traversing through source directory
        try {
            Files.walk(Paths.get(FILES_DIR))
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String listing = path.toString().substring(FILES_DIR.length());
                        listings.add(listing);
                    });
        } catch (IOException e) {
            log("Could not find file listings: " + e.getMessage());
            return new ArrayList<>();
        }

        return listings;
    }

    @Override
    public boolean upload(String filename, byte[] data) {
        // Convert filename to
        File outFile = new File(FILES_DIR + filename);
        //noinspection ResultOfMethodCallIgnored
        outFile.getParentFile().mkdirs();

        try (FileOutputStream stream = new FileOutputStream(outFile)) {
            stream.write(data);
            return true;
        } catch (IOException e) {
            log("Error writing file to disk");
            log(e.getMessage());
            return false;
        }
    }

    private void log(String msg) {
        System.out.println(msg);
    }
}
