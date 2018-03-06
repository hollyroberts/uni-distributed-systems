import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class Client {
    public static void main(String[] args) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);

            FrontEndInterface stub = (FrontEndInterface) registry.lookup("FrontEnd");

            String[] response = stub.list();
            System.out.println(String.join("", response));
        } catch (Exception e) {
            System.out.println("Client exception: " + e.toString());
            e.printStackTrace();
        }
    }
}