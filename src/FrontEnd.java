import javafx.util.Pair;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;

public class FrontEnd extends UnicastRemoteObject implements FrontEndInterface {
    // Constants
    private static final String DEFAULT_RMI_HOSTNAME = "localhost";
    private static final int DEFAULT_RMI_PORT = 1099;

    private static final int MAX_SERVERS = 3;
    private static final String FRONTEND_RMI_NAME = "FrontEnd";
    private static final String SERVER_RMI_NAME = "FileServer";

    // Object variables
    private Registry register;
    private List<ServerInterface> fileServers = new ArrayList<>();
    private Random random = new Random();

    public static void main(String[] args) {
        // Read arguments
        String hostname;
        if (args.length >= 1) {
            hostname = args[0];
        } else {
            hostname = DEFAULT_RMI_HOSTNAME;
        }

        int port = Shared.parseCommandLineInteger(args, 1, "Port number must be a positive integer", DEFAULT_RMI_PORT);

        // Initialise front end
        System.out.println("Initialising front end at " + hostname + ":" + port);

        try {
            FrontEnd obj = new FrontEnd(hostname, port);

            // System.out.println("Detected Local IP: " + InetAddress.getLocalHost().toString());
            // Bind the remote object's stub in the registry
            Registry register = LocateRegistry.getRegistry(1099);
            register.rebind(FRONTEND_RMI_NAME, obj);

            System.out.println("Server ready");
        } catch (Exception e) {
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
        }
    }

    private FrontEnd(String hostname, int port) throws RemoteException {
        log("Retrieving registry and file server stubs");

        // Initialize the registry
        register = LocateRegistry.getRegistry(hostname, port);

        // Attempt to initialize servers
        for (int i = 1; i <= MAX_SERVERS; i++) {
            fileServers.add(getServerStub(i));
        }

        log("Front End initialised");
    }

    // Attempts to retrieve a file server from the registry
    // Returns the stub if successful, otherwise null
    private ServerInterface getServerStub(int id) {
        log("Retrieving stub for server " + id);
        try {
            ServerInterface stub = (ServerInterface) register.lookup(SERVER_RMI_NAME + id);
            log("Retrieved stub for server " + id);
            return stub;
        } catch (RemoteException | NotBoundException e) {
            log("Could not retrieve file server " + id + " stub");
            return null;
        }
    }

    // If a server is null then attempts to reconnect to it
    private void checkServer(int id) {
        ServerInterface server = fileServers.get(id);

        if (server == null) {
            log("Server " + (id + 1) + " is not connected, attempting to reconnect");
            fileServers.set(id, getServerStub(id + 1));
        }
    }

    private void disconnectServer(int id, RemoteException e) {
        log(e.getMessage());
        log("Disconnected server " + (id + 1));

        // Remove entry from the register. Disabled for now, in case file server is still running succesfully and error was one that was not expected
        // try { register.unbind(SERVER_RMI_NAME + (id + 1)); } catch (RemoteException | NotBoundException ignored) {}
        fileServers.set(id, null);
    }

    // Output message to console, so that we can change logging method if needed without having to change all logging statements
    private void log(String msg) {
        System.out.println(msg);
    }

    @Override
    public String delete(String filename) {
        log("Received DELF request");

        // Iterate over servers. Keep track of the number of servers that we deleted from
        int numServers = 0;
        for (int i = 0; i < MAX_SERVERS; i++) {
            checkServer(i);
            ServerInterface server = fileServers.get(i);
            if (server == null) { continue; }

            // Upload
            try {
                if (server.delete(filename) == 1) {
                    numServers++;
                }
            } catch (RemoteException e) {
                disconnectServer(i, e);
            }
        }

        // Return status message
        if (numServers == 0) {
            log("Could not delete file from any servers");
            return "Could not delete file";
        }

        log(String.format("Deleted file from %,d/%,d servers", numServers, MAX_SERVERS));
        return "File deleted";
    }

    @Override
    public byte[] download(String filename) {
        // Implement basic load sharing by randomly selecting the server to download from
        // If this fails we then go to the next server, and then the next etc.
        // We stop when we get back to the starting server
        int startServer = random.nextInt(MAX_SERVERS);
        log("Received operation DWLD. Attempting to download file '" + filename + "' starting at server " + (startServer + 1));

        int curServer = startServer;
        do {
            // Retrieve stub and download
            checkServer(curServer);
            ServerInterface server = fileServers.get(curServer);

            if (server != null) {
                try {
                    log("Downloading file from server " + (curServer + 1));
                    byte[] data = server.download(filename);
                    if (data != null) {
                        return data;
                    } else {
                        log("Server did not contain the file (or an internal error occurred)");
                    }
                } catch (RemoteException e) {
                    disconnectServer(curServer, e);
                }
            }

            // Try next server
            curServer++;
            if (curServer >= MAX_SERVERS) {
                curServer = 0;
            }
        } while (curServer != startServer);

        log("No servers could be downloaded from");
        return null;
    }

    @Override
    public boolean fileExists(String filename) {
        log("Processing request to see whether a file exists on the system");

        // Iterate over file servers, return true if one server returns true
        for (int i = 0; i < MAX_SERVERS; i++) {
            checkServer(i);
            ServerInterface server = fileServers.get(i);
            if (server == null) { continue; }

            try {
                if (server.fileExists(filename)) {
                    return true;
                }
            } catch (RemoteException e) {
                disconnectServer(i, e);
            }
        }

        // If no servers contain the file then return false
        return false;
    }

    @Override
    public String[] list() {
        log("Received operation LIST. Checking server statuses first");
        log("Retrieving listings from servers");

        // Store listings in a set to remove duplicates
        Set<String> listings = new HashSet<>();

        // Iterate over servers. Fetch listings and add to set
        int serversUsed = 0;
        for (int i = 0; i < MAX_SERVERS; i++) {
            checkServer(i);
            ServerInterface server = fileServers.get(i);
            if (server == null) { continue; }

            try {
                listings.addAll(server.list());
                serversUsed++;
            } catch (RemoteException e) {
                disconnectServer(i, e);
            }
        }

        // Convert set to sorted list
        List<String> sortedListings = new ArrayList<>(listings);
        Collections.sort(sortedListings);
        String[] returnArray = sortedListings.toArray(new String[sortedListings.size()]);

        log("Listings retrieved from " + serversUsed + " servers, sending to client");
        return returnArray;
    }

    @Override
    public String upload(String filename, byte[] data, boolean highReliability) {
        log("Received operation UPLD. Checking server statuses first");

        // Use a seperate method if we're uploading with high reliability
        if (highReliability) {
            return uploadAll(filename, data);
        }

        // start timer
        long startTime = System.currentTimeMillis();

        // Get the server with the smallest number of files, not file size! (as per spec)
        log("Retrieving listings from servers to determine order to attempt upload");

        // Store a list of pairs which store the number of files per file server (file server - number of files)
        // If an error occurs querying a server then they will not be added to this list
        List<Pair<Integer, Integer>> filesOnServers = new ArrayList<>();

        // Iterate over servers
        for (int i = 0; i < MAX_SERVERS; i++) {
            checkServer(i);
            ServerInterface server = fileServers.get(i);
            if (server == null) { continue; }

            try {
                // Find the number of files on the server and add it to the list
                filesOnServers.add(new Pair<>(i, server.list().size()));
            } catch (RemoteException e) {
                disconnectServer(i, e);
            }
        }

        // No servers found
        if (filesOnServers.size() == 0) {
            String msg = "File could not be uploaded. System is offline (no servers)";
            log(msg);
            return msg;
        }

        // Sort servers by number of files (ascending order)
        filesOnServers.sort(Comparator.comparingInt(Pair::getValue));

        // Try to upload to servers
        log(String.format("Found %,d servers available to upload to", filesOnServers.size()));

        // Log messages
        if (filesOnServers.size() > 0) {
            List<String> filesOnServersString = new ArrayList<>();
            for (Pair<Integer, Integer> pair : filesOnServers) {
                filesOnServersString.add((pair.getKey() + 1) + " (" + pair.getValue() + ")");
            }

            log("Uploading files");
            log("Server order (# of files): " + String.join(", ", filesOnServersString));
        }

        // Upload file to servers found until success
        int curIndex = 0;
        while (curIndex < filesOnServers.size()) {
            int curServer = filesOnServers.get(curIndex).getKey();
            log("Uploading file to server " + (curServer + 1));

            if (uploadToServer(curServer, filename, data)) {
                break;
            }

            curIndex++;
        }

        if (curIndex == filesOnServers.size()) {
            return "Could not upload file";
        }

        // Get stats and return message
        long endTime = System.currentTimeMillis();
        double timeTaken = (endTime - startTime);
        timeTaken /= 1000;

        return String.format("Uploaded file.%,d bytes uploaded in %,.2fs", data.length, timeTaken);
    }

    private String uploadAll(String filename, byte[] data) {
        // start timer
        long startTime = System.currentTimeMillis();

        // Iterate over servers. Keep track of the number of servers that were uploaded to
        int numServers = 0;
        for (int i = 0; i < MAX_SERVERS; i++) {
            if (uploadToServer(i, filename, data)) {
                numServers++;
            }
        }

        // Return status message
        if (numServers == 0) {
            return "Could not upload file to any servers";
        }

        // Get stats
        long endTime = System.currentTimeMillis();
        double timeTaken = (endTime - startTime);
        timeTaken /= 1000;

        return String.format("Uploaded file with high reliability\n%,d bytes processed in %,.2fs", data.length, timeTaken);
    }

    // Uploads data to an individual server
    // Returns true if file successfully uploaded
    private boolean uploadToServer(int id, String filename, byte[] data) {
        checkServer(id);
        ServerInterface server = fileServers.get(id);
        if (server == null) { return false; }

        // Upload
        try {
            return server.upload(filename, data);
        } catch (RemoteException e) {
            log("Error uploading file to server " + (id + 1));
            disconnectServer(id, e);
            return false;
        }
    }
}
