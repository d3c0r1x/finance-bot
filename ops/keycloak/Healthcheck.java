import java.net.HttpURLConnection;
import java.net.URI;

final class Healthcheck {
    public static void main(String[] args) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI
                .create("http://127.0.0.1:9000/health/ready")
                .toURL()
                .openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(1500);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                System.exit(1);
            }
        } finally {
            connection.disconnect();
        }
    }
}
