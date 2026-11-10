package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Objects;

/** Owns one framed JSON connection; application operations are not implemented yet. */
public final class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final SessionManager sessionManager;
    private final Runnable onDisconnect;

    public ClientHandler(Socket clientSocket) {
        this(clientSocket, new SessionManager());
    }

    public ClientHandler(Socket clientSocket, SessionManager sessionManager) {
        this(clientSocket, sessionManager, () -> { });
    }

    ClientHandler(Socket clientSocket, Runnable onDisconnect) {
        this(clientSocket, new SessionManager(), onDisconnect);
    }

    ClientHandler(Socket clientSocket, SessionManager sessionManager, Runnable onDisconnect) {
        this.clientSocket = Objects.requireNonNull(clientSocket, "Client socket is required.");
        this.sessionManager = Objects.requireNonNull(sessionManager, "Session manager is required.");
        this.onDisconnect = Objects.requireNonNull(onDisconnect, "Disconnect callback is required.");
    }

    @Override
    public void run() {
        String address = clientSocket.getRemoteSocketAddress().toString();
        System.out.println("Client connected: " + address);
        try {
            var input = new DataInputStream(clientSocket.getInputStream());
            // Replies and routed events use this same manager-owned writer and lock.
            var writer = sessionManager.writerFor(clientSocket);
            Message message;
            while ((message = JsonUtil.readMessage(input)) != null) {
                System.out.println("Message received from " + address + ": " + message.type());
                var payload = JsonNodeFactory.instance.objectNode()
                        .put("code", "NOT_IMPLEMENTED")
                        .put("message", "Operation " + message.type() + " is not implemented.");
                Message response = new Message(MessageType.ERROR, message.correlationId(), null, payload);
                try {
                    // Preflight before writing: a large correlation ID may fill the reply.
                    JsonUtil.toJson(response);
                } catch (IOException exception) {
                    response = new Message(MessageType.ERROR, payload);
                }
                writer.send(response);
            }
        } catch (IOException exception) {
            if (!clientSocket.isClosed()) {
                System.err.println("Client connection error for " + address + ": " + exception.getMessage());
            }
        } finally {
            try {
                clientSocket.close();
            } catch (IOException exception) {
                System.err.println("Unable to close client socket for " + address + ": " + exception.getMessage());
            } finally {
                // Close first: a concurrent verified registration must not revive this socket.
                sessionManager.removeSession(clientSocket);
            }
            try {
                onDisconnect.run();
            } finally {
                System.out.println("Client disconnected: " + address);
            }
        }
    }
}
