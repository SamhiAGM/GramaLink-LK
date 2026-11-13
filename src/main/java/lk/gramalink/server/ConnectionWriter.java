package lk.gramalink.server;

import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.FileChunk;
import lk.gramalink.shared.TransportCodec;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/** One manager-owned writer per socket, shared by replies and server events. */
final class ConnectionWriter {
    private final Socket socket;
    private DataOutputStream output;
    private volatile boolean closing;

    ConnectionWriter(Socket socket) {
        this.socket = socket;
    }

    synchronized void send(Message message) throws IOException {
        JsonUtil.writeMessage(output(), message);
    }

    synchronized void sendChunk(FileChunk chunk) throws IOException {
        TransportCodec.writeChunk(output(), chunk);
    }

    private DataOutputStream output() throws IOException {
        if (closing || socket.isClosed() || socket.isOutputShutdown()) {
            throw new IOException("Connection is closed for writing.");
        }
        if (output == null) {
            output = new DataOutputStream(socket.getOutputStream());
        }
        return output;
    }

    /** Timeout detection must be able to stop a blocked writer without taking its lock. */
    void markClosing() {
        closing = true;
    }

    boolean isClosing() {
        return closing;
    }
}
