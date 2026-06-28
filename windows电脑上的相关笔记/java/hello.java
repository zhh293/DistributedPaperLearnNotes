import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

public class hello {
    public static void main(String[] args) throws IOException {
        int[] arr = new int[5];
        for (int i = 0; i < arr.length; i++) {

            System.out.println(arr[i]);

        }

        ByteArrayOutputStream baos=new ByteArrayOutputStream();
        ObjectOutputStream oos=new ObjectOutputStream(baos);
        oos.writeObject(arr);
        oos.flush();
        byte[] bytes = baos.toByteArray();
        System.out.println(bytes.length);
    }
}
