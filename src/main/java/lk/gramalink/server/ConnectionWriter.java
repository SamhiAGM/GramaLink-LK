package lk.gramalink.server;

import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/** One manager-owned writer per socket, shared by replies and server events. */
final class ConnectionWriter {
    private final Socket socket;
    private DataOutputStream output;

    ConnectionWriter(Socket socket) {
        this.socket = socket;
    }

    synchronized void send(Message message) throws IOException {
        if (socket.isClosed() || socket.isOutputShutdown()) {
            throw new IOException("Connection is closed for writing.");
        }
        if (output == null) {
            output = new DataOutputStream(socket.getOutputStream());
        }
        JsonUtil.writeMessage(output, message);
    }
}
