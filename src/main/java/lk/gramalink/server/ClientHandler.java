package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import lk.gramalink.shared.FileChunk;
import lk.gramalink.shared.TransportCodec;
import lk.gramalink.shared.TransportFrame;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.Objects;

/** Owns one mixed-frame connection; backend operations remain unavailable by default. */
public final class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final SessionManager sessionManager;
    private final FileTransferService fileTransferService;
    private final Runnable onDisconnect;

    public ClientHandler(Socket clientSocket) {
        this(clientSocket, new SessionManager());
    }

    public ClientHandler(Socket clientSocket, SessionManager sessionManager) {
        this(clientSocket, sessionManager, FileTransferService.unavailable());
    }

    public ClientHandler(Socket clientSocket, SessionManager sessionManager, FileTransferService fileTransferService) {
        this(clientSocket, sessionManager, fileTransferService, () -> { });
    }

    ClientHandler(Socket clientSocket, Runnable onDisconnect) {
        this(clientSocket, new SessionManager(), onDisconnect);
    }

    ClientHandler(Socket clientSocket, SessionManager sessionManager, Runnable onDisconnect) {
        this(clientSocket, sessionManager, FileTransferService.unavailable(), onDisconnect);
    }

    ClientHandler(Socket clientSocket, SessionManager sessionManager, FileTransferService fileTransferService,
                  Runnable onDisconnect) {
        this.clientSocket = Objects.requireNonNull(clientSocket, "Client socket is required.");
        this.sessionManager = Objects.requireNonNull(sessionManager, "Session manager is required.");
        this.fileTransferService = Objects.requireNonNull(fileTransferService, "File service is required.");
        this.onDisconnect = Objects.requireNonNull(onDisconnect, "Disconnect callback is required.");
    }

    @Override
    public void run() {
        String address = clientSocket.getRemoteSocketAddress().toString();
        System.out.println("Client connected: " + address);
        try (var transfers = new FileTransferTransport(clientSocket, sessionManager, fileTransferService)) {
            var input = new DataInputStream(clientSocket.getInputStream());
            // Replies and routed events use this same manager-owned writer and lock.
            var writer = sessionManager.writerFor(clientSocket);
            TransportFrame frame;
            while ((frame = TransportCodec.readFrame(input)) != null) {
                Message response;
                if (frame instanceof FileChunk chunk) {
                    System.out.println("Message received from " + address + ": FILE_CHUNK");
                    response = transfers.receiveChunk(chunk);
                } else {
                    Message message = ((TransportFrame.JsonFrame) frame).message();
                    System.out.println("Message received from " + address + ": " + message.type());
                    if (message.type() == MessageType.FILE_META) {
                        response = transfers.negotiate(message);
                    } else if (message.type() == MessageType.HEARTBEAT && sessionManager.recordHeartbeat(clientSocket)) {
                        var payload = JsonNodeFactory.instance.objectNode().put("acknowledgedType", "HEARTBEAT");
                        response = new Message(MessageType.ACK, message.correlationId(), null, payload);
                    } else {
                        var payload = JsonNodeFactory.instance.objectNode()
                                .put("code", "NOT_IMPLEMENTED")
                                .put("message", message.type() == MessageType.HEARTBEAT
                                        ? "Heartbeat requires an authenticated session; authentication is not available yet."
                                        : "Operation " + message.type() + " is not implemented.");
                        response = new Message(MessageType.ERROR, message.correlationId(), null, payload);
                    }
                }
                try {
                    // Preflight before writing: a large correlation ID may fill the reply.
                    JsonUtil.toJson(response);
                } catch (IOException exception) {
                    response = new Message(response.type(), response.payload());
                }
                writer.send(response);
            }
        } catch (EOFException exception) {
            if (!clientSocket.isClosed()) {
                System.err.println("Client connection ended during an incomplete frame: " + address);
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
